package com.macaber.attribution.core;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DiffParser — Extracts logical chunks of added lines from a unified Git diff.
 *
 * Parses standard unified diffs and groups contiguous added lines into DiffChunk objects.
 */
public class DiffParser {

    private final Normalizer normalizer;
    private static final Pattern CHUNK_HEADER_PATTERN = Pattern.compile("@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@.*");

    public DiffParser() {
        this.normalizer = new Normalizer();
    }

    public List<DiffChunk> parse(String rawDiff) {
        List<DiffChunk> chunks = new ArrayList<>();
        if (rawDiff == null || rawDiff.trim().isEmpty()) {
            return chunks;
        }

        String[] lines = rawDiff.split("\n");
        String currentFilePath = "unknown";
        int currentLineNumber = 0;

        List<String> currentChunkLines = new ArrayList<>();
        Integer startLine = null;

        for (String line : lines) {
            if (line.startsWith("+++ ")) {
                flushChunk(chunks, currentFilePath, startLine, currentLineNumber - 1, currentChunkLines);
                currentChunkLines.clear();
                startLine = null;

                String path = line.substring(4).trim();
                if (path.startsWith("b/")) {
                    currentFilePath = path.substring(2);
                } else if (path.startsWith("a/")) {
                    currentFilePath = path.substring(2);
                } else {
                    currentFilePath = path;
                }
            } else if (line.startsWith("@@ ")) {
                flushChunk(chunks, currentFilePath, startLine, currentLineNumber - 1, currentChunkLines);
                currentChunkLines.clear();
                startLine = null;

                Matcher m = CHUNK_HEADER_PATTERN.matcher(line);
                if (m.matches()) {
                    currentLineNumber = Integer.parseInt(m.group(1));
                }
            } else if (line.startsWith("+") && !line.startsWith("+++")) {
                if (startLine == null) {
                    startLine = currentLineNumber;
                }
                currentChunkLines.add(line.substring(1));
                currentLineNumber++;
            } else if (line.startsWith("-") && !line.startsWith("---")) {
                flushChunk(chunks, currentFilePath, startLine, currentLineNumber - 1, currentChunkLines);
                currentChunkLines.clear();
                startLine = null;
            } else if (line.startsWith(" ") || line.isEmpty() || line.startsWith("\\")) {
                flushChunk(chunks, currentFilePath, startLine, currentLineNumber - 1, currentChunkLines);
                currentChunkLines.clear();
                startLine = null;
                if (line.startsWith(" ") || line.isEmpty()) {
                    currentLineNumber++;
                }
            }
        }

        flushChunk(chunks, currentFilePath, startLine, currentLineNumber - 1, currentChunkLines);

        return chunks;
    }

    private void flushChunk(List<DiffChunk> chunks, String filePath, Integer startLine, int endLine, List<String> lines) {
        if (!lines.isEmpty() && startLine != null) {
            String content = String.join("\n", lines);
            DiffChunk chunk = DiffChunk.builder()
                    .filePath(filePath)
                    .startLine(startLine)
                    .endLine(endLine)
                    .content(content)
                    .normalizedContent(normalizer.normalizeText(content))
                    .build();
            chunks.add(chunk);
        }
    }
}
