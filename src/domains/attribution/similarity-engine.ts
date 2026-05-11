import {
  SimilarityWeights,
  THRESHOLDS,
  EvaluationResult,
  PipelineConfig,
  DEFAULT_PIPELINE_CONFIG,
} from '../../types';
import { Normalizer, TokenMapping, LineMapping } from './normalizer';
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
  /** Minimum normalized text length for Winnowing to produce valid fingerprints */
  private readonly winnowingMinLength: number;

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
      perLineMatchThreshold?: number;
      multiMessage?: Partial<PipelineConfig['multiMessage']>;
    };
    astEngineOptions?: { grammarsDir?: string; cacheSize?: number; cacheTtlMs?: number };
  }) {
    this.normalizer = new Normalizer();
    const kgramLength = options?.winnowingConfig?.kgramLength ?? 5;
    this.winnowing = new Winnowing(options?.winnowingConfig);
    this.winnowingMinLength = kgramLength; // text shorter than k can't generate any k-grams
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
      perLineMatchThreshold: options?.pipelineConfig?.perLineMatchThreshold ?? DEFAULT_PIPELINE_CONFIG.perLineMatchThreshold,
      multiMessage: { ...DEFAULT_PIPELINE_CONFIG.multiMessage, ...options?.pipelineConfig?.multiMessage },
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
      fileContent?: string;      // Full merged file for L3 AST context
      filePath?: string;         // File path for language detection
      addedLineCount?: number;   // Number of added lines (for L3 circuit breaker)
      chunkStartLine?: number;   // Diff chunk start line in file (1-indexed)
      chunkEndLine?: number;     // Diff chunk end line in file (1-indexed)
      normalizedAi?: string;     // Pre-calculated normalized AI text
      chunkMapping?: TokenMapping;// Pre-calculated chunk mapping (legacy, unused)
      chunkLineMapping?: LineMapping; // Pre-calculated line mapping for line-level LCS
    },
  ): Promise<EvaluationResult> {
    const aiLineMapping = this.normalizer.normalizeToLines(aiCode);
    const normalizedAi = options?.normalizedAi ?? aiLineMapping.normalizedText;
    const chunkLineMapping = options?.chunkLineMapping ?? this.normalizer.normalizeToLines(diffChunkContent);
    const normalizedChunk = chunkLineMapping.normalizedText;

    if (!normalizedAi || !normalizedChunk) {
      return { score: 0, matchType: 'NONE', level: 'FAILED_ALL', details: {}, exactContributedLines: 0, contributedLineIndices: new Set() };
    }

    // ── Line-level LCS: each normalized line is an atomic comparison unit ──
    // This eliminates cross-line token leakage and the need for per-line thresholds.
    const matchedNormalizedIndices = this.lcs.calculateTraceableLcsLines(
      aiLineMapping.normalizedLines, chunkLineMapping.normalizedLines,
    );

    // Map matched normalizedLines indices back to original 0-indexed line numbers
    const exactContributedLines = matchedNormalizedIndices.length;
    const contributedLineIndices = new Set<number>();
    for (const idx of matchedNormalizedIndices) {
      contributedLineIndices.add(chunkLineMapping.originalLineIndices[idx]);
    }

    // ═════════════════════════════════════════════════════
    // Short-text bypass: 当任一文本短于 k-gram 最小长度时，
    // Winnowing 无法产生有效指纹，直接跳到 L2 LCS
    // 典型场景：AI 只生成了 1-2 行代码 (如 "return true;")
    // ═════════════════════════════════════════════════════
    const isShortText =
      normalizedChunk.length < this.winnowingMinLength ||
      normalizedAi.length < this.winnowingMinLength;

    // ═════════════════════════════════════════════════════
    // L1: Winnowing — Document fingerprint (极快)
    // 使用 Containment 而非 Jaccard: |fpDiff ∩ fpAI| / |fpDiff|
    // "Diff 的指纹有多少能在 AI 代码中找到?"
    // ═════════════════════════════════════════════════════
    let l1Score = 0;

    if (!isShortText) {
      const fpAi = this.winnowing.getFingerprints(normalizedAi);
      const fpDiff = this.winnowing.getFingerprints(normalizedChunk);

      if (fpDiff.size > 0) {
        let contained = 0;
        for (const fp of fpDiff) {
          if (fpAi.has(fp)) contained++;
        }
        l1Score = contained / fpDiff.size;
      }

      if (l1Score >= this.config.l1.fastPass) {
        return {
          score: l1Score,
          matchType: 'STRICT',
          level: 'L1',
          details: { l1WinnowingScore: l1Score },
          exactContributedLines,
          contributedLineIndices,
        };
      }
      if (l1Score <= this.config.l1.fastFail) {
        return {
          score: 0,
          matchType: 'NONE',
          level: 'L1',
          details: { l1WinnowingScore: l1Score },
          exactContributedLines,
          contributedLineIndices,
        };
      }
    }

    // ═════════════════════════════════════════════════════
    // L2: LCS — Token sequence matching (低耗)
    // 分母使用 Diff 侧长度: "用户提交的代码中有多少来自 AI?"
    // 而非 max(|AI|,|Diff|) 避免用户部分采纳时被大 AI 代码拉低
    // ═════════════════════════════════════════════════════
    // ═════════════════════════════════════════════════════
    // ═════════════════════════════════════════════════════
    // L2 Score: matched lines / total non-blank lines in the chunk
    const l2Score = chunkLineMapping.nonBlankLineCount > 0
      ? matchedNormalizedIndices.length / chunkLineMapping.nonBlankLineCount
      : 0;

    if (l2Score >= this.config.l2.fastPass) {
      return {
        score: l2Score,
        matchType: 'FUZZY',
        level: 'L2',
        details: { l1WinnowingScore: l1Score, l2LcsScore: l2Score },
        exactContributedLines,
        contributedLineIndices,
      };
    }

    // We intentionally removed L2 fastFail here. Even if L2 score is < 0.30, 
    // it might still contain `exactContributedLines > 0`. We let it escalate to L3, 
    // and if L3 fails, our buildFallbackResult will secure the matched lines.

    // ═════════════════════════════════════════════════════
    // L3: AST Feature Matching — 结构特征比对 (中高耗，仅模棱两可时触发)
    // ═════════════════════════════════════════════════════

    // Circuit breaker: skip L3 if too many added lines
    const addedLines = options?.addedLineCount ?? 0;
    if (addedLines > this.config.maxLinesForL3) {
      // Fall through to final scoring with L1+L2 only
      return this.buildFallbackResult(l1Score, l2Score, exactContributedLines, contributedLineIndices);
    }

    // Language check: skip L3 for non-parseable files
    const filePath = options?.filePath;
    if (!filePath || !isL3Eligible(filePath)) {
      return this.buildFallbackResult(l1Score, l2Score, exactContributedLines, contributedLineIndices);
    }

    // Need full file content for proper AST parsing
    const fileContent = options?.fileContent;
    if (!fileContent) {
      return this.buildFallbackResult(l1Score, l2Score, exactContributedLines, contributedLineIndices);
    }

    // Run L3 AST comparison
    try {
      // Scope L3 to diff region if line range is available
      const diffLineRange = (options?.chunkStartLine && options?.chunkEndLine)
        ? {
          startLine: options.chunkStartLine - 1, // Tree-sitter uses 0-indexed rows
          endLine: options.chunkEndLine - 1,
        }
        : undefined;

      const l3Score = await this.astEngine.compareFeatures(
        aiCode,
        fileContent,
        filePath,
        diffLineRange,
      );

      if (l3Score === null) {
        // Grammar not available — graceful language fallback
        return this.buildFallbackResult(l1Score, l2Score, exactContributedLines, contributedLineIndices);
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
          exactContributedLines,
          contributedLineIndices,
        };
      }
    } catch (error) {
      // L3 failed — graceful degradation to L1+L2
      console.warn('[SimilarityEngine] L3 AST analysis failed, falling back to L1+L2:', error);
      return this.buildFallbackResult(l1Score, l2Score, exactContributedLines, contributedLineIndices);
    }

    // All layers evaluated. Did we find any structural match?
    // If we've reached here, L3 didn't pass or skipped, so we fallback to L1+L2 evaluation.
    return this.buildFallbackResult(l1Score, l2Score, exactContributedLines, contributedLineIndices);
  }

  /**
   * Build a fallback result using L1+L2 scores and exact line tracking when L3 is skipped or fails.
   */
  private buildFallbackResult(
    l1Score: number,
    l2Score: number,
    exactContributedLines: number,
    contributedLineIndices: Set<number>,
  ): EvaluationResult {
    const combinedScore =
      this.weights.winnowing * l1Score + this.weights.lcs * l2Score;

    // Use legacy thresholds for L1+L2-only classification
    let matchType: EvaluationResult['matchType'] = 'NONE';

    // ── Ground Truth Floor ──
    // Any precise copied lines (verified at 70% per-line threshold) act as
    // definitive evidence of AI contribution, regardless of overall chunk score.
    if (exactContributedLines > 0) {
      matchType = 'FUZZY';
    } else {
      if (combinedScore >= THRESHOLDS.STRICT) matchType = 'STRICT';
      else if (combinedScore >= THRESHOLDS.FUZZY) matchType = 'FUZZY';
    }

    return {
      score: matchType === 'NONE' ? 0 : Math.max(combinedScore, l2Score),
      matchType,
      level: 'L2', // Resolved at L2 level
      details: { l1WinnowingScore: l1Score, l2LcsScore: l2Score },
      exactContributedLines,
      contributedLineIndices,
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
