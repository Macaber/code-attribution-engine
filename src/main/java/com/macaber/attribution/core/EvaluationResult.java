package com.macaber.attribution.core;

import lombok.Builder;
import lombok.Data;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

/**
 * Result from the escalation pipeline's evaluateChunk().
 * Includes which level short-circuited and why.
 *
 * Aligned with TS: src/types/index.ts → EvaluationResult interface
 */
@Data
@Builder
public class EvaluationResult {
    /** Final similarity score (0.0 – 1.0) */
    private double score;
    /** Classification of the match */
    private MatchType matchType;
    /** Which pipeline level produced the final decision */
    private PipelineLevel level;
    /** Individual level scores for debugging/logging */
    private Map<String, Double> details;
    /**
     * The precise number of lines fully matching the AI source (Line-level Tracking).
     * Overrides score-based estimated line attribution.
     */
    private int exactContributedLines;
    /**
     * The specific line indices (0-indexed, relative to chunk) that were
     * matched. Used for cross-message union deduplication.
     */
    @Builder.Default
    private Set<Integer> contributedLineIndices = new HashSet<>();
}
