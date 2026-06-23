package com.macaber.attribution.dto;

import lombok.Builder;
import lombok.Data;
import java.util.List;
import java.util.Set;

@Data
@Builder
public class ChunkVisualizationDto {
    private String filePath;
    private int startLine;
    private int endLine;
    private String userId;
    private String attribution;
    private double score;
    private String matchType;
    private String level;
    private String chunkContent;
    private Set<Integer> contributedLineIndices;
    private List<MatchedMessageVisualizationDto> matchedMessages;
}
