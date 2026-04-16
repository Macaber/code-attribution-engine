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
}

/**
 * Result of matching a single DiffChunk against all AiMessages.
 * Contains the best match found and the attribution classification.
 */
export interface MatchResult {
  /** The diff chunk being evaluated */
  chunk: DiffChunk;
  /** The best matching AI message, or null if no match found */
  bestMatch: {
    messageId: string;
    score: number;           // 0.0 – 1.0
    matchType: MatchType;
    level: PipelineLevel;
    details: {
      l1WinnowingScore?: number;
      l2LcsScore?: number;
      l3AstScore?: number;
    };
  } | null;
  /**
   * Attribution classification derived from matchType:
   * - 'strict':        STRICT match (score >= 0.90 at L1)
   * - 'fuzzy':         FUZZY match (score >= 0.80 at L2)
   * - 'deep_refactor': DEEP_REFACTOR (score >= 0.60 at L3)
   * - 'none':          No significant match
   */
  attribution: 'strict' | 'fuzzy' | 'deep_refactor' | 'none';
  /** Number of lines attributed to AI contribution */
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
}

/** Default pipeline configuration */
export const DEFAULT_PIPELINE_CONFIG: PipelineConfig = {
  l1: { fastPass: 0.90, fastFail: 0.15 },
  l2: { fastPass: 0.80, fastFail: 0.30 },
  l3: { pass: 0.60 },
  maxLinesForL3: 1000,
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
