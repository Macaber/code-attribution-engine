package com.macaber.attribution.dto;

import lombok.Builder;
import lombok.Data;
import java.util.List;
import java.util.Map;

/**
 * Result of matching a single DiffChunk against all AiMessages.
 * Supports multi-message attribution: all AI messages with >= 10%
 * contribution are tracked, and their contributed lines are unioned
 * to avoid double-counting.
 *
 * Aligned with TS: src/types/index.ts → MatchResult interface
 */
@Data
@Builder
public class MatchResult {
    /** The diff chunk being evaluated */
    private EnrichedChunk chunk;
    /** The best matching AI message (highest score), or null if no match found */
    private BestMatch bestMatch;
    /** All AI messages that contributed >= threshold to this chunk */
    private List<MessageContribution> matchedMessages;
    /** Comma-separated messageIds of all contributing messages (for DB storage) */
    private String matchedMessageIds;
    /**
     * Attribution classification derived from the best matchType:
     * - 'strict':        STRICT match
     * - 'fuzzy':         FUZZY match
     * - 'deep_refactor': DEEP_REFACTOR
     * - 'none':          No significant match
     */
    private String attribution;
    /** Number of lines attributed to AI contribution (union-deduplicated across all messages) */
    private double contributedLines;

    @Data
    @Builder
    public static class BestMatch {
        private String messageId;
        private double score;
        private String matchType;
        private String level;
        private Map<String, Double> details;
    }

    @Data
    @Builder
    public static class MessageContribution {
        private String messageId;
        private double score;
        private String matchType;
        private String level;
        private Map<String, Double> details;
    }
}
