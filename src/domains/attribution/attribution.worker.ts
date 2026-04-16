import {
  AiMessage,
  AttributionJobData,
  DiffChunk,
  MatchResult,
  EvaluationResult,
} from '../../types';
import { DiffParser } from './diff-parser';
import { SimilarityEngine } from './similarity-engine';
import { Normalizer } from './normalizer';

/**
 * Enriched DiffChunk with file-level context for pipeline evaluation.
 */
interface EnrichedChunk extends DiffChunk {
  /** Full merged file content (for L3 AST parsing) */
  fileContent?: string;
  /** Total added lines in this file (for L3 circuit breaker) */
  fileAddedLineCount: number;
}

/**
 * AttributionWorker — Pipeline orchestrator for code attribution analysis.
 *
 * Takes a job containing file details (diff + full code) and AI message history,
 * then runs the 3-layer escalation pipeline for each chunk:
 *   L1 Winnowing → L2 LCS → L3 AST Features
 *
 * Key behaviors:
 * - Passes full file content (from doMerge `code` field) to enable L3 AST parsing
 * - Counts added lines per file for L3 circuit breaker (>1000 lines skips AST)
 * - Each chunk is scored against all AI messages, best match wins
 */
export class AttributionWorker {
  private readonly diffParser: DiffParser;
  private readonly similarityEngine: SimilarityEngine;
  private readonly normalizer: Normalizer;

  constructor(options?: {
    weights?: { winnowing?: number; lcs?: number };
    winnowingConfig?: { kgramLength?: number; windowSize?: number };
    lcsConfig?: { maxCells?: number };
    pipelineConfig?: {
      l1?: { fastPass?: number; fastFail?: number };
      l2?: { fastPass?: number; fastFail?: number };
      l3?: { pass?: number };
      maxLinesForL3?: number;
    };
    astEngineOptions?: { grammarsDir?: string; cacheSize?: number; cacheTtlMs?: number };
  }) {
    this.diffParser = new DiffParser();
    this.similarityEngine = new SimilarityEngine(options);
    this.normalizer = new Normalizer();
  }

  /**
   * Process a complete attribution job (async for L3 AST support).
   *
   * Iterates over each file in fileDetails, parses its diff,
   * enriches chunks with file context, and runs the escalation pipeline.
   *
   * @param jobData - Contains file details (with diffs and full code) and AI message history
   * @returns Array of MatchResult for each diff chunk across all files
   */
  async process(jobData: AttributionJobData): Promise<MatchResult[]> {
    const { fileDetails, aiMessages } = jobData;

    // ── Step 1: Parse diffs and enrich chunks with file context ──
    const enrichedChunks: EnrichedChunk[] = [];

    for (const file of fileDetails) {
      if (!file.diff || file.diff.trim().length === 0) continue;

      const chunks = this.diffParser.parse(file.diff);
      // Count total added lines in this file
      const fileAddedLineCount = chunks.reduce(
        (sum, c) => sum + (c.endLine - c.startLine + 1),
        0,
      );

      for (const chunk of chunks) {
        enrichedChunks.push({
          ...chunk,
          filePath: file.path || chunk.filePath, // Prefer explicit path from doMerge
          fileContent: file.code,                // Full merged file for L3
          fileAddedLineCount,
        });
      }
    }

    if (enrichedChunks.length === 0) return [];

    // ── Step 2: Normalize all AI messages once ──
    const normalizedMessages = this.normalizeMessages(aiMessages);

    // ── Step 3: Run pipeline for each chunk ──
    const results: MatchResult[] = [];
    for (const chunk of enrichedChunks) {
      const result = await this.processChunk(chunk, normalizedMessages);
      results.push(result);
    }

    // Clean up AST cache after job completes
    this.similarityEngine.clearAstCache();

    return results;
  }

  /**
   * Pre-normalize all AI messages for comparison.
   */
  private normalizeMessages(
    messages: AiMessage[],
  ): Array<AiMessage & { normalizedContent: string }> {
    return messages.map(msg => ({
      ...msg,
      normalizedContent:
        msg.normalizedContent ?? this.normalizer.normalizeText(msg.rawContent),
    }));
  }

  /**
   * Run the escalation pipeline for a single chunk against all AI messages.
   * Takes the best match across all messages.
   */
  private async processChunk(
    chunk: EnrichedChunk,
    messages: Array<AiMessage & { normalizedContent: string }>,
  ): Promise<MatchResult> {
    let bestResult: EvaluationResult | null = null;
    let bestMessageId: string | null = null;

    for (const msg of messages) {
      if (!msg.normalizedContent) continue;

      const result = await this.similarityEngine.evaluateChunk(
        msg.rawContent,
        chunk.content,
        {
          fileContent: chunk.fileContent,
          filePath: chunk.filePath,
          addedLineCount: chunk.fileAddedLineCount,
        },
      );

      if (!bestResult || result.score > bestResult.score) {
        bestResult = result;
        bestMessageId = msg.messageId;
      }

      // Early exit: if L1 STRICT match found, no need to check other messages
      if (result.matchType === 'STRICT') break;
    }

    // ── Build MatchResult ──
    const attribution = bestResult
      ? SimilarityEngine.matchTypeToAttribution(bestResult.matchType)
      : 'none';

    const totalLines = chunk.endLine - chunk.startLine + 1;
    let contributedLines: number;

    switch (attribution) {
      case 'strict':
        contributedLines = totalLines * 1.0;
        break;
      case 'fuzzy':
      case 'deep_refactor':
        contributedLines = totalLines * (bestResult?.score ?? 0);
        break;
      case 'none':
      default:
        contributedLines = 0;
        break;
    }

    return {
      chunk,
      bestMatch: bestResult && bestMessageId
        ? {
            messageId: bestMessageId,
            score: bestResult.score,
            matchType: bestResult.matchType,
            level: bestResult.level,
            details: bestResult.details,
          }
        : null,
      attribution,
      contributedLines: Math.round(contributedLines * 100) / 100,
    };
  }

  /**
   * Generate a summary report from match results.
   */
  static summarize(results: MatchResult[]): {
    totalLines: number;
    aiContributedLines: number;
    aiContributionRatio: number;
    strictMatches: number;
    fuzzyMatches: number;
    deepRefactorMatches: number;
    noMatches: number;
  } {
    const totalLines = results.reduce(
      (sum, r) => sum + (r.chunk.endLine - r.chunk.startLine + 1),
      0,
    );
    const aiContributedLines = results.reduce(
      (sum, r) => sum + r.contributedLines,
      0,
    );

    return {
      totalLines,
      aiContributedLines: Math.round(aiContributedLines * 100) / 100,
      aiContributionRatio:
        totalLines > 0
          ? Math.round((aiContributedLines / totalLines) * 10000) / 10000
          : 0,
      strictMatches: results.filter(r => r.attribution === 'strict').length,
      fuzzyMatches: results.filter(r => r.attribution === 'fuzzy').length,
      deepRefactorMatches: results.filter(r => r.attribution === 'deep_refactor').length,
      noMatches: results.filter(r => r.attribution === 'none').length,
    };
  }
}
