// ============================================================
// Core Type Definitions for Code Attribution Engine
// ============================================================

/**
 * A chunk of added lines extracted from a Git diff.
 * Represents a contiguous block of new code in a commit.
 */
export interface DiffChunk {
  /** Path to the file within the repository */
  filePath: string;
  /** Starting line number in the new file */
  startLine: number;
  /** Ending line number in the new file */
  endLine: number;
  /** Raw added lines joined with newlines */
  content: string;
  /** Content after normalization (comments/whitespace removed, lowercased) */
  normalizedContent: string;
  /** Full merged file content (used for L3 AST parsing context) */
  fileContent?: string;
}

/**
 * An AI-generated code message from the interaction history.
 * Retrieved from the database for comparison against commit diffs.
 */
export interface AiMessage {
  /** Unique identifier for this message */
  messageId: string;
  /** User who triggered this AI generation */
  userId: string;
  /** When the AI code was generated */
  timestamp: Date;
  /** The raw AI-generated code content */
  rawContent: string;
  /** Content after normalization (populated during processing) */
  normalizedContent?: string;
}

// ============================================================
// Pipeline Evaluation Result Types
// ============================================================

/** Match type classification from the escalation pipeline */
export type MatchType = 'STRICT' | 'FUZZY' | 'DEEP_REFACTOR' | 'NONE';

/** Which pipeline level produced the result */
export type PipelineLevel = 'L1' | 'L2' | 'L3' | 'FAILED_ALL';

/**
 * Result from the escalation pipeline's evaluateChunk().
 * Includes which level short-circuited and why.
 */
export interface EvaluationResult {
  /** Final similarity score (0.0 – 1.0) */
  score: number;
  /** Classification of the match */
  matchType: MatchType;
  /** Which pipeline level produced the final decision */
  level: PipelineLevel;
  /** Individual level scores for debugging/logging */
  details: {
    l1WinnowingScore?: number;
    l2LcsScore?: number;
    l3AstScore?: number;
  };
  /**
   * The precise number of lines fully matching the AI source (Line-level Tracking).
   * Overrides score-based estimated line attribution.
   */
  exactContributedLines?: number;
  /**
   * The specific line indices (0-indexed, relative to chunk) that were
   * matched at >= 70% per-line threshold. Used for cross-message
   * union deduplication.
   */
  contributedLineIndices?: Set<number>;
}

/** A single AI message's contribution to a chunk */
export interface MessageContribution {
  messageId: string;
  score: number;           // 0.0 – 1.0
  matchType: MatchType;
  level: PipelineLevel;
  details: {
    l1WinnowingScore?: number;
    l2LcsScore?: number;
    l3AstScore?: number;
  };
}

/**
 * Result of matching a single DiffChunk against all AiMessages.
 * Supports multi-message attribution: all AI messages with >= 10%
 * contribution are tracked, and their contributed lines are unioned
 * to avoid double-counting.
 */
export interface MatchResult {
  /** The diff chunk being evaluated */
  chunk: DiffChunk;
  /** The best matching AI message (highest score), or null if no match found */
  bestMatch: MessageContribution | null;
  /** All AI messages that contributed >= 10% to this chunk */
  matchedMessages: MessageContribution[];
  /** Comma-separated messageIds of all contributing messages (for DB storage) */
  matchedMessageIds: string;
  /**
   * Attribution classification derived from the best matchType:
   * - 'strict':        STRICT match (score >= 0.90 at L1)
   * - 'fuzzy':         FUZZY match (score >= 0.80 at L2)
   * - 'deep_refactor': DEEP_REFACTOR (score >= 0.60 at L3)
   * - 'none':          No significant match
   */
  attribution: 'strict' | 'fuzzy' | 'deep_refactor' | 'none';
  /** Number of lines attributed to AI contribution (union-deduplicated across all messages) */
  contributedLines: number;
}

// ============================================================
// Webhook & Job Types
// ============================================================

/**
 * Incoming payload from the CICD system's doMerge webhook.
 * POST /api/coding/doMerge
 */
export interface DoMergePayload {
  /** Operator account (e.g. "codingadm") */
  oa: string;
  /** System code identifier */
  sysCode: string;
  /** System name (e.g. "cicd jenkinsFile") */
  sysName: string;
  /** Repository name */
  repoName: string;
  /** Merge request ID */
  mergeId: string;
  /** Merge title */
  title: string;
  /** Creation time (e.g. "2026-01-06 11:08:55") */
  createTime: string;
  /**
   * JSON-stringified array of file change details.
   * Each element: { path: string, code: string, diff: string }
   */
  detail: string;
}

/**
 * A single file change parsed from DoMergePayload.detail
 */
export interface MergeFileDetail {
  /** File path within the repository */
  path: string;
  /** Full merged file content */
  code: string;
  /** Unified diff string for this file */
  diff: string;
}

/**
 * Job data pushed to BullMQ for asynchronous processing.
 */
export interface AttributionJobData {
  /** Merge request ID as the unique job key */
  mergeId: string;
  /** Repository name */
  repoName: string;
  /** Operator account */
  userId: string;
  /** System code */
  sysCode: string;
  /** Merge title */
  title: string;
  /** Parsed file change details (each with its own diff) */
  fileDetails: MergeFileDetail[];
  /** AI message history to compare against */
  aiMessages: AiMessage[];
}

// ============================================================
// Pipeline Configuration
// ============================================================

/** Per-level threshold configuration for the escalation pipeline */
export interface PipelineConfig {
  l1: {
    /** Score >= this → STRICT fast-pass (default: 0.90) */
    fastPass: number;
    /** Score <= this → NONE fast-fail (default: 0.15) */
    fastFail: number;
  };
  l2: {
    /** Score >= this → FUZZY fast-pass (default: 0.80) */
    fastPass: number;
    /** Score <= this → NONE fast-fail (default: 0.30) */
    fastFail: number;
  };
  l3: {
    /** Score >= this → DEEP_REFACTOR pass (default: 0.60) */
    pass: number;
  };
  /** Max added lines before L3 is skipped (default: 1000) */
  maxLinesForL3: number;
  /** Per-line match threshold to count a line as AI contributed (default: 0.70) */
  perLineMatchThreshold: number;
  multiMessage: {
    /** Minimum L2 score required for a message to be considered a multi-message contributor (default: 0.10) */
    threshold: number;
    /** Minimum exact contributed lines required for a message to be considered a multi-message contributor (default: 3) */
    minLines: number;
  };
}

/** Default pipeline configuration */
export const DEFAULT_PIPELINE_CONFIG: PipelineConfig = {
  l1: {
    fastPass: process.env.PIPELINE_L1_FAST_PASS ? Number(process.env.PIPELINE_L1_FAST_PASS) : 0.90,
    fastFail: process.env.PIPELINE_L1_FAST_FAIL ? Number(process.env.PIPELINE_L1_FAST_FAIL) : 0.15,
  },
  l2: {
    fastPass: process.env.PIPELINE_L2_FAST_PASS ? Number(process.env.PIPELINE_L2_FAST_PASS) : 0.80,
    fastFail: process.env.PIPELINE_L2_FAST_FAIL ? Number(process.env.PIPELINE_L2_FAST_FAIL) : 0.30,
  },
  l3: {
    pass: process.env.PIPELINE_L3_PASS ? Number(process.env.PIPELINE_L3_PASS) : 0.60,
  },
  maxLinesForL3: process.env.PIPELINE_MAX_LINES_L3 ? Number(process.env.PIPELINE_MAX_LINES_L3) : 1000,
  perLineMatchThreshold: process.env.PER_LINE_MATCH_THRESHOLD ? Number(process.env.PER_LINE_MATCH_THRESHOLD) : 0.70,
  multiMessage: {
    threshold: process.env.MULTI_MSG_THRESHOLD ? Number(process.env.MULTI_MSG_THRESHOLD) : 0.10,
    minLines: process.env.MULTI_MSG_MIN_LINES ? Number(process.env.MULTI_MSG_MIN_LINES) : 3,
  },
};

// ============================================================
// Algorithm Configuration
// ============================================================

/** Configuration for the Winnowing algorithm */
export interface WinnowingConfig {
  /** Length of each k-gram (default: 5) */
  kgramLength: number;
  /** Window size for fingerprint selection (default: 4) */
  windowSize: number;
}

/** Configuration for the LCS algorithm */
export interface LcsConfig {
  /** Maximum number of DP cells before circuit breaker kicks in (default: 10_000_000) */
  maxCells: number;
}

/** Configuration for the SimilarityEngine score combination */
export interface SimilarityWeights {
  /** Weight for winnowing score (default: 0.4) */
  winnowing: number;
  /** Weight for LCS score (default: 0.6) */
  lcs: number;
}

/** Scoring thresholds for attribution classification (legacy, kept for backward compat) */
export const THRESHOLDS = {
  STRICT: 0.95,
  FUZZY: 0.60,
} as const;
