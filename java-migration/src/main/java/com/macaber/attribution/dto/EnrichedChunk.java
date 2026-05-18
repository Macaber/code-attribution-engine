package com.macaber.attribution.dto;

import com.macaber.attribution.core.DiffChunk;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Enriched DiffChunk with file-level context for pipeline evaluation.
 *
 * Aligned with TS: EnrichedChunk interface in attribution.worker.ts
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class EnrichedChunk extends DiffChunk {
    /** Full merged file content (for L3 AST parsing) */
    private String fileContent;
    /** Total added lines in this file (for L3 circuit breaker) */
    private int fileAddedLineCount;

    public EnrichedChunk(DiffChunk base, String fileContent, int fileAddedLineCount, String explicitPath) {
        this.setFilePath(explicitPath != null ? explicitPath : base.getFilePath());
        this.setStartLine(base.getStartLine());
        this.setEndLine(base.getEndLine());
        this.setContent(base.getContent());
        this.setNormalizedContent(base.getNormalizedContent());
        this.setNonBlankLineCount(base.getNonBlankLineCount());
        this.fileContent = fileContent;
        this.fileAddedLineCount = fileAddedLineCount;
    }
}
