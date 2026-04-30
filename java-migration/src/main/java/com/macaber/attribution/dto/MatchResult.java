package com.macaber.attribution.dto;

import lombok.Builder;
import lombok.Data;
import java.util.Map;

@Data
@Builder
public class MatchResult {
    private EnrichedChunk chunk;
    private BestMatch bestMatch;
    private String attribution;
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
}
