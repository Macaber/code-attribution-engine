package com.macaber.attribution.dto;

import com.macaber.attribution.core.DiffChunk;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class EnrichedChunk extends DiffChunk {
    private String fileContent;
    private int fileAddedLineCount;

    public EnrichedChunk(DiffChunk base, String fileContent, int fileAddedLineCount, String explicitPath) {
        this.setFilePath(explicitPath != null ? explicitPath : base.getFilePath());
        this.setStartLine(base.getStartLine());
        this.setEndLine(base.getEndLine());
        this.setContent(base.getContent());
        this.setNormalizedContent(base.getNormalizedContent());
        this.fileContent = fileContent;
        this.fileAddedLineCount = fileAddedLineCount;
    }
}
