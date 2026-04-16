import {
  SimilarityWeights,
  THRESHOLDS,
  EvaluationResult,
  PipelineConfig,
  DEFAULT_PIPELINE_CONFIG,
} from '../../types';
import { Normalizer } from './normalizer';
import { Winnowing } from './algorithms/winnowing';
import { LCS } from './algorithms/lcs';
import { AstFeatureEngine } from './algorithms/ast-engine';
import { isL3Eligible } from './algorithms/language-map';

/**
 * SimilarityEngine — 3-layer escalation pipeline for code attribution.
 *
 * Funnel architecture:
 *   L1 Winnowing (fast)  → fast-pass / fast-fail / continue →
 *   L2 Token LCS (medium) → fast-pass / fast-fail / continue →
 *   L3 AST Features (heavy, conditional)
 *
 * Each layer has configurable thresholds for short-circuiting.
 */
export class SimilarityEngine {
  private readonly normalizer: Normalizer;
  private readonly winnowing: Winnowing;
  private readonly lcs: LCS;
  private readonly astEngine: AstFeatureEngine;
  private readonly config: PipelineConfig;

  // Legacy weights for backward-compatible evaluate()
  private readonly weights: SimilarityWeights;

  constructor(options?: {
    weights?: Partial<SimilarityWeights>;
    winnowingConfig?: { kgramLength?: number; windowSize?: number };
    lcsConfig?: { maxCells?: number };
    pipelineConfig?: {
      l1?: Partial<PipelineConfig['l1']>;
      l2?: Partial<PipelineConfig['l2']>;
      l3?: Partial<PipelineConfig['l3']>;
      maxLinesForL3?: number;
    };
    astEngineOptions?: { grammarsDir?: string; cacheSize?: number; cacheTtlMs?: number };
  }) {
    this.normalizer = new Normalizer();
    this.winnowing = new Winnowing(options?.winnowingConfig);
    this.lcs = new LCS(options?.lcsConfig);
    this.astEngine = new AstFeatureEngine(options?.astEngineOptions);
    this.weights = {
      winnowing: options?.weights?.winnowing ?? 0.4,
      lcs: options?.weights?.lcs ?? 0.6,
    };

    // Merge user overrides into default pipeline config
    this.config = {
      l1: { ...DEFAULT_PIPELINE_CONFIG.l1, ...options?.pipelineConfig?.l1 },
      l2: { ...DEFAULT_PIPELINE_CONFIG.l2, ...options?.pipelineConfig?.l2 },
      l3: { ...DEFAULT_PIPELINE_CONFIG.l3, ...options?.pipelineConfig?.l3 },
      maxLinesForL3: options?.pipelineConfig?.maxLinesForL3 ?? DEFAULT_PIPELINE_CONFIG.maxLinesForL3,
    };
  }

  /**
   * Evaluate a diff chunk against AI code using the escalation pipeline.
   *
   * Each layer can short-circuit with a fast-pass (high confidence match)
   * or fast-fail (clearly no match). Only ambiguous cases escalate.
   *
   * @param aiCode - Raw AI-generated code
   * @param diffChunkContent - Raw diff chunk content (added lines)
   * @param options - Optional context for L3 analysis
   * @returns EvaluationResult with score, matchType, level, and per-layer details
   */
  async evaluateChunk(
    aiCode: string,
    diffChunkContent: string,
    options?: {
      fileContent?: string;    // Full merged file for L3 AST context
      filePath?: string;       // File path for language detection
      addedLineCount?: number; // Number of added lines (for L3 circuit breaker)
    },
  ): Promise<EvaluationResult> {
    const normalizedAi = this.normalizer.normalizeText(aiCode);
    const normalizedChunk = this.normalizer.normalizeText(diffChunkContent);

    if (!normalizedAi || !normalizedChunk) {
      return { score: 0, matchType: 'NONE', level: 'FAILED_ALL', details: {} };
    }

    // ═════════════════════════════════════════════════════
    // L1: Winnowing — Document fingerprint (极快)
    // ═════════════════════════════════════════════════════
    const l1Score = this.winnowing.calculateScore(normalizedAi, normalizedChunk);

    if (l1Score >= this.config.l1.fastPass) {
      return {
        score: l1Score,
        matchType: 'STRICT',
        level: 'L1',
        details: { l1WinnowingScore: l1Score },
      };
    }
    if (l1Score <= this.config.l1.fastFail) {
      return {
        score: 0,
        matchType: 'NONE',
        level: 'L1',
        details: { l1WinnowingScore: l1Score },
      };
    }

    // ═════════════════════════════════════════════════════
    // L2: LCS — Token sequence matching (低耗)
    // ═════════════════════════════════════════════════════
    const l2Score = this.lcs.calculateScore(normalizedAi, normalizedChunk);

    if (l2Score >= this.config.l2.fastPass) {
      return {
        score: l2Score,
        matchType: 'FUZZY',
        level: 'L2',
        details: { l1WinnowingScore: l1Score, l2LcsScore: l2Score },
      };
    }
    if (l2Score <= this.config.l2.fastFail) {
      return {
        score: 0,
        matchType: 'NONE',
        level: 'L2',
        details: { l1WinnowingScore: l1Score, l2LcsScore: l2Score },
      };
    }

    // ═════════════════════════════════════════════════════
    // L3: AST Feature Matching — 结构特征比对 (中高耗，仅模棱两可时触发)
    // ═════════════════════════════════════════════════════

    // Circuit breaker: skip L3 if too many added lines
    const addedLines = options?.addedLineCount ?? 0;
    if (addedLines > this.config.maxLinesForL3) {
      // Fall through to final scoring with L1+L2 only
      return this.buildFallbackResult(l1Score, l2Score);
    }

    // Language check: skip L3 for non-parseable files
    const filePath = options?.filePath;
    if (!filePath || !isL3Eligible(filePath)) {
      return this.buildFallbackResult(l1Score, l2Score);
    }

    // Need full file content for proper AST parsing
    const fileContent = options?.fileContent;
    if (!fileContent) {
      return this.buildFallbackResult(l1Score, l2Score);
    }

    // Run L3 AST comparison
    try {
      const l3Score = await this.astEngine.compareFeatures(
        aiCode,
        fileContent,
        filePath,
      );

      if (l3Score === null) {
        // Grammar not available — graceful language fallback
        return this.buildFallbackResult(l1Score, l2Score);
      }

      if (l3Score >= this.config.l3.pass) {
        return {
          score: l3Score,
          matchType: 'DEEP_REFACTOR',
          level: 'L3',
          details: {
            l1WinnowingScore: l1Score,
            l2LcsScore: l2Score,
            l3AstScore: l3Score,
          },
        };
      }
    } catch (error) {
      // L3 failed — graceful degradation to L1+L2
      console.warn('[SimilarityEngine] L3 AST analysis failed, falling back to L1+L2:', error);
      return this.buildFallbackResult(l1Score, l2Score);
    }

    // All layers passed without match
    return {
      score: 0,
      matchType: 'NONE',
      level: 'FAILED_ALL',
      details: { l1WinnowingScore: l1Score, l2LcsScore: l2Score },
    };
  }

  /**
   * Build a fallback result using weighted L1+L2 scores when L3 is skipped.
   */
  private buildFallbackResult(
    l1Score: number,
    l2Score: number,
  ): EvaluationResult {
    const combinedScore =
      this.weights.winnowing * l1Score + this.weights.lcs * l2Score;

    // Use legacy thresholds for L1+L2-only classification
    let matchType: EvaluationResult['matchType'] = 'NONE';
    if (combinedScore >= THRESHOLDS.STRICT) matchType = 'STRICT';
    else if (combinedScore >= THRESHOLDS.FUZZY) matchType = 'FUZZY';

    return {
      score: combinedScore,
      matchType,
      level: 'L2', // Resolved at L2 level (L3 was skipped)
      details: { l1WinnowingScore: l1Score, l2LcsScore: l2Score },
    };
  }

  // ═══════════════════════════════════════════════════════
  // Legacy API (backward compat for existing tests)
  // ═══════════════════════════════════════════════════════

  /**
   * @deprecated Use evaluateChunk() for pipeline evaluation.
   * Kept for backward compatibility with existing tests.
   */
  evaluate(
    aiCode: string,
    commitCode: string,
  ): {
    winnowingScore: number;
    lcsScore: number;
    combinedScore: number;
  } {
    const normalizedAi = this.normalizer.normalizeText(aiCode);
    const normalizedCommit = this.normalizer.normalizeText(commitCode);

    if (!normalizedAi || !normalizedCommit) {
      return { winnowingScore: 0, lcsScore: 0, combinedScore: 0 };
    }

    const winnowingScore = this.winnowing.calculateScore(normalizedAi, normalizedCommit);
    const lcsScore = this.lcs.calculateScore(normalizedAi, normalizedCommit);
    const combinedScore =
      this.weights.winnowing * winnowingScore + this.weights.lcs * lcsScore;

    return { winnowingScore, lcsScore, combinedScore };
  }

  /**
   * Classify a combined score into an attribution category.
   * @deprecated Use matchType from EvaluationResult instead.
   */
  static classify(combinedScore: number): 'strict' | 'fuzzy' | 'none' {
    if (combinedScore >= THRESHOLDS.STRICT) return 'strict';
    if (combinedScore >= THRESHOLDS.FUZZY) return 'fuzzy';
    return 'none';
  }

  /**
   * Map pipeline MatchType to attribution string.
   */
  static matchTypeToAttribution(
    matchType: EvaluationResult['matchType'],
  ): 'strict' | 'fuzzy' | 'deep_refactor' | 'none' {
    switch (matchType) {
      case 'STRICT': return 'strict';
      case 'FUZZY': return 'fuzzy';
      case 'DEEP_REFACTOR': return 'deep_refactor';
      case 'NONE': return 'none';
    }
  }

  /**
   * Clear AST engine cache.
   */
  clearAstCache(): void {
    this.astEngine.clearCache();
  }
}
