package com.macaber.attribution.core;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DiffChunk {
    private String filePath;
    private int startLine;
    private int endLine;
    private String content;
    private String normalizedContent;
    private String fileContent;
}
