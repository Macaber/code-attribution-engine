package com.macaber.attribution.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChunkQueryResultDto {
    private Long id;
    private Long reportId;
    private String userId;
    private String filePath;
    private Integer startLine;
    private Integer endLine;
    private Integer totalLines;
    private Integer analyzedLines;
    private String attribution;
    private Double contributedLines;
    private String matchedMessageId;
    private String matchedMessageIds;
    private Double score;
    private String matchType;
    private String level;

    // Associated report attributes
    private String repoName;
    private String sysCode;
    private String source;
    private String target;
    private LocalDateTime reportCreatedAt;
}
