import {
  AiMessage,
  AttributionJobData,
  DiffChunk,
  MatchResult,
  EvaluationResult,
  PipelineConfig,
  DEFAULT_PIPELINE_CONFIG,
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
  private readonly config: PipelineConfig;

  constructor(options?: {
    weights?: { winnowing?: number; lcs?: number };
    winnowingConfig?: { kgramLength?: number; windowSize?: number };
    lcsConfig?: { maxCells?: number };
    pipelineConfig?: {
      l1?: { fastPass?: number; fastFail?: number };
      l2?: { fastPass?: number; fastFail?: number };
      l3?: { pass?: number };
      maxLinesForL3?: number;
      perLineMatchThreshold?: number;
      multiMessage?: { threshold?: number; minLines?: number };
    };
    astEngineOptions?: { grammarsDir?: string; cacheSize?: number; cacheTtlMs?: number };
  }) {
    this.diffParser = new DiffParser();
    this.similarityEngine = new SimilarityEngine(options);
    this.normalizer = new Normalizer();
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
   *
   * Multi-message strategy:
   *   1. Evaluate each AI message against this chunk
   *   2. Keep all messages whose L2 score >= 10% (meaningful contribution)
   *   3. Union all contributedLineIndices across qualifying messages (dedup)
   *   4. Final exactContributedLines = union set size
   *   5. Best match = highest-scoring message (drives attribution classification)
   */

  private async processChunk(
    chunk: EnrichedChunk,
    messages: Array<AiMessage & { normalizedContent: string }>,
  ): Promise<MatchResult> {
    // Collect all qualifying message evaluations
    interface CandidateMatch {
      messageId: string;
      result: EvaluationResult;
    }

    const candidates: CandidateMatch[] = [];
    let bestCandidate: CandidateMatch | null = null;

    for (const msg of messages) {
      if (!msg.normalizedContent) continue;

      const result = await this.similarityEngine.evaluateChunk(
        msg.rawContent,
        chunk.content,
        {
          fileContent: chunk.fileContent,
          filePath: chunk.filePath,
          addedLineCount: chunk.fileAddedLineCount,
          chunkStartLine: chunk.startLine,
          chunkEndLine: chunk.endLine,
        },
      );

      // Track best match (highest score)
      if (!bestCandidate || result.score > bestCandidate.result.score) {
        bestCandidate = { messageId: msg.messageId, result };
      }

      // Collect all messages with >= threshold contribution (L2 score basis) and > minLines exact contribution
      const l2Score = result.details.l2LcsScore ?? result.score;
      if (
        l2Score >= this.config.multiMessage.threshold &&
        (result.exactContributedLines ?? 0) > this.config.multiMessage.minLines &&
        result.matchType !== 'NONE'
      ) {
        candidates.push({ messageId: msg.messageId, result });
      }

      // Early exit: if L1 STRICT match found, this single message explains the whole chunk
      if (result.matchType === 'STRICT') break;
    }

    // ── Union-merge contributedLineIndices across all qualifying messages ──
    const mergedLineIndices = new Set<number>();
    for (const c of candidates) {
      if (c.result.contributedLineIndices) {
        for (const idx of c.result.contributedLineIndices) {
          mergedLineIndices.add(idx);
        }
      }
    }

    // ── Build attribution from best match ──
    const attribution = bestCandidate
      ? SimilarityEngine.matchTypeToAttribution(bestCandidate.result.matchType)
      : 'none';

    const totalLines = chunk.endLine - chunk.startLine + 1;
    let contributedLines: number;

    // Use union-merged line count instead of single-message count
    const unionContributedLines = mergedLineIndices.size;

    switch (attribution) {
      case 'strict':
        // For strict match, use union lines if available, otherwise total
        contributedLines = unionContributedLines > 0 ? unionContributedLines : totalLines;
        break;
      case 'fuzzy':
        // Fuzzy relies purely on exact traced lines (now union-merged)
        contributedLines = unionContributedLines;
        break;
      case 'deep_refactor':
        // Deep refactor: max of union lines vs structural estimate
        contributedLines = Math.max(
          unionContributedLines,
          totalLines * (bestCandidate?.result.score ?? 0),
        );
        break;
      case 'none':
      default:
        contributedLines = 0;
        break;
    }

    // ── Build matchedMessages array ──
    const matchedMessages = candidates.map(c => ({
      messageId: c.messageId,
      score: c.result.score,
      matchType: c.result.matchType,
      level: c.result.level,
      details: c.result.details,
    }));

    // Sort by score descending for readability
    matchedMessages.sort((a, b) => b.score - a.score);

    const bestMatch = bestCandidate
      ? {
          messageId: bestCandidate.messageId,
          score: bestCandidate.result.score,
          matchType: bestCandidate.result.matchType,
          level: bestCandidate.result.level,
          details: bestCandidate.result.details,
        }
      : null;

    return {
      chunk,
      bestMatch,
      matchedMessages,
      matchedMessageIds: matchedMessages.map(m => m.messageId).join(','),
      attribution,
      contributedLines: Math.round(contributedLines * 100) / 100,
    };
  }

  /**
   * Generate a summary report from match results.
   *
   * @param results - Pipeline match results
   * @param jobData - Original job data (for computing totalCodeLines and skippedLines)
   */
  static summarize(
    results: MatchResult[],
    jobData?: AttributionJobData,
  ): {
    /** Total lines of code across ALL files (from merged file content) */
    totalCodeLines: number;
    /** Raw diff line count including blank lines */
    diffLines: number;
    /** Non-blank lines from diff chunks (used as denominator for AI ratio) */
    analyzedLines: number;
    /** AI contributed lines (weighted by match score) */
    aiContributedLines: number;
    /** AI contribution ratio = aiContributedLines / analyzedLines */
    aiContributionRatio: number;
    /** Lines in files that had no diff (skipped, not analyzed) */
    skippedLines: number;
    /** Number of files that had no diff and were skipped */
    skippedFileCount: number;
    /** Chunk match counts by attribution type */
    strictMatches: number;
    fuzzyMatches: number;
    deepRefactorMatches: number;
    noMatches: number;
    /** Per-message contribution breakdown (traceability) */
    messageBreakdown: Array<{
      messageId: string;
      contributedLines: number;
      chunkCount: number;
      matchTypes: string[];
    }>;
    /** Per-chunk attribution detail (full traceability) */
    chunkDetails: Array<{
      filePath: string;
      startLine: number;
      endLine: number;
      totalLines: number;
      attribution: string;
      contributedLines: number;
      matchedMessageId: string | null;
      matchedMessageIds: string;
      score: number;
      matchType: string;
      level: string;
    }>;
  } {
    // ── Diff lines = raw line count including blank lines ──
    const diffLines = results.reduce(
      (sum, r) => sum + (r.chunk.endLine - r.chunk.startLine + 1),
      0,
    );

    // ── Analyzed lines = non-blank lines only (matches exactContributedLines counting basis) ──
    const analyzedLines = results.reduce(
      (sum, r) => {
        const chunkContent = r.chunk.content;
        const nonBlankCount = chunkContent
          .split('\n')
          .filter(line => line.trim().length > 0).length;
        return sum + nonBlankCount;
      },
      0,
    );
    const aiContributedLines = results.reduce(
      (sum, r) => sum + r.contributedLines,
      0,
    );

    // ── Total code lines & skipped lines (from fileDetails) ──
    let totalCodeLines = 0;
    let skippedLines = 0;
    let skippedFileCount = 0;

    if (jobData?.fileDetails) {
      for (const file of jobData.fileDetails) {
        const fileLineCount = file.code
          ? file.code.split('\n').length
          : 0;
        totalCodeLines += fileLineCount;

        if (!file.diff || file.diff.trim().length === 0) {
          skippedLines += fileLineCount;
          skippedFileCount++;
        }
      }
    }

    // ── Message breakdown: aggregate contributions per AI messageId ──
    // Now iterates over all matchedMessages (not just bestMatch)
    const msgMap = new Map<string, { contributedLines: number; chunkCount: number; matchTypes: Set<string> }>();

    for (const r of results) {
      if (r.attribution !== 'none') {
        // Distribute contributed lines proportionally across all matched messages
        const msgCount = r.matchedMessages.length;
        const perMsgLines = msgCount > 0 ? r.contributedLines / msgCount : 0;

        for (const mm of r.matchedMessages) {
          const entry = msgMap.get(mm.messageId) ?? { contributedLines: 0, chunkCount: 0, matchTypes: new Set() };
          entry.contributedLines += perMsgLines;
          entry.chunkCount++;
          entry.matchTypes.add(r.attribution);
          msgMap.set(mm.messageId, entry);
        }
      }
    }

    const messageBreakdown = Array.from(msgMap.entries()).map(([messageId, v]) => ({
      messageId,
      contributedLines: Math.round(v.contributedLines * 100) / 100,
      chunkCount: v.chunkCount,
      matchTypes: Array.from(v.matchTypes),
    }));

    // ── Chunk details: per-chunk full traceability ──
    const chunkDetails = results.map(r => ({
      filePath: r.chunk.filePath,
      startLine: r.chunk.startLine,
      endLine: r.chunk.endLine,
      totalLines: r.chunk.endLine - r.chunk.startLine + 1,
      attribution: r.attribution,
      contributedLines: r.contributedLines,
      matchedMessageId: r.bestMatch?.messageId ?? null,
      matchedMessageIds: r.matchedMessageIds,
      score: r.bestMatch?.score ?? 0,
      matchType: r.bestMatch?.matchType ?? 'NONE',
      level: r.bestMatch?.level ?? 'FAILED_ALL',
    }));

    return {
      totalCodeLines,
      diffLines,
      analyzedLines,
      aiContributedLines: Math.round(aiContributedLines * 100) / 100,
      aiContributionRatio:
        analyzedLines > 0
          ? Math.round((aiContributedLines / analyzedLines) * 10000) / 10000
          : 0,
      skippedLines,
      skippedFileCount,
      strictMatches: results.filter(r => r.attribution === 'strict').length,
      fuzzyMatches: results.filter(r => r.attribution === 'fuzzy').length,
      deepRefactorMatches: results.filter(r => r.attribution === 'deep_refactor').length,
      noMatches: results.filter(r => r.attribution === 'none').length,
      messageBreakdown,
      chunkDetails,
    };
  }
}
