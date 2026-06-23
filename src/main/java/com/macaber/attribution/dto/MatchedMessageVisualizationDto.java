package com.macaber.attribution.dto;

import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.Set;

@Data
@Builder
public class MatchedMessageVisualizationDto {
    private String messageId;
    private String rawContent;
    private String fileName;
    private LocalDateTime timestamp;
    private double score;
    private String matchType;
    private Set<Integer> contributedLineIndices;
    private java.util.List<java.util.Map<String, Integer>> lineMatches;
}
