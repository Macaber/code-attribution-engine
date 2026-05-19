package com.macaber.attribution.core;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A chunk of added lines extracted from a Git diff.
 * Represents a contiguous block of new code in a commit.
 *
 * Aligned with TS: src/types/index.ts → DiffChunk interface
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DiffChunk {
    /** Path to the file within the repository */
    private String filePath;
    /** Starting line number in the new file */
    private int startLine;
    /** Ending line number in the new file */
    private int endLine;
    /** Raw added lines joined with newlines */
    private String content;
    /** Content after normalization (comments/whitespace removed, lowercased) */
    private String normalizedContent;
    /** Number of non-blank lines in this chunk */
    private int nonBlankLineCount;
    /** User who authored the added lines in this chunk (extracted from diff line prefix) */
    private String userId;
}
