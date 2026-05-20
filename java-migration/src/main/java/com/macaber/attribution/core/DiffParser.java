package com.macaber.attribution.core;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DiffParser — Extracts logical chunks of added lines from a unified Git diff.
 *
 * Parses unified diffs where each added/removed line is prefixed with a user identifier:
 *   (username)+added line
 *   (username)-removed line
 *
 * Contiguous added lines from the SAME user are grouped into a single DiffChunk.
 * When the user changes between consecutive added lines, a new chunk is started.
 *
 * Aligned with TS: src/domains/attribution/diff-parser.ts
 */
public class DiffParser {

    private static final Pattern CHUNK_HEADER_PATTERN = Pattern.compile("@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@.*");
    /**
     * Matches lines like: (sun_yunfeng)+added code
     * Group 1 = username, Group 2 = +/- operator, Group 3 = line content
     */
    private static final Pattern USER_LINE_PATTERN = Pattern.compile("^\\(([^)]+)\\)([+-])(.*)$");

    public List<DiffChunk> parse(String rawDiff) {
        List<DiffChunk> chunks = new ArrayList<>();
        if (rawDiff == null || rawDiff.trim().isEmpty()) {
            return chunks;
        }

        rawDiff = rawDiff.replace("\r", "");
        String[] lines = rawDiff.split("\n");
        String currentFilePath = "unknown";
        int currentLineNumber = 0;
        boolean inHunk = false;

        List<String> currentChunkLines = new ArrayList<>();
        Integer startLine = null;
        String currentUserId = null;

        for (String line : lines) {
            if (line.startsWith("+++ ")) {
                flushChunk(chunks, currentFilePath, startLine, currentLineNumber - 1, currentChunkLines, currentUserId);
                currentChunkLines.clear();
                startLine = null;
                currentUserId = null;
                inHunk = false;

                String path = line.substring(4).trim();
                if (path.startsWith("b/")) {
                    currentFilePath = path.substring(2);
                } else if (path.startsWith("a/")) {
                    currentFilePath = path.substring(2);
                } else {
                    currentFilePath = path;
                }
            } else if (line.startsWith("@@ ")) {
                flushChunk(chunks, currentFilePath, startLine, currentLineNumber - 1, currentChunkLines, currentUserId);
                currentChunkLines.clear();
                startLine = null;
                currentUserId = null;
                inHunk = true;

                Matcher m = CHUNK_HEADER_PATTERN.matcher(line);
                if (m.matches()) {
                    currentLineNumber = Integer.parseInt(m.group(1));
                }
            } else if (inHunk) {
                Matcher userMatcher = USER_LINE_PATTERN.matcher(line);

                if (userMatcher.matches()) {
                    String userId = userMatcher.group(1);
                    String operator = userMatcher.group(2);
                    String content = userMatcher.group(3);

                    if ("+".equals(operator)) {
                        // User changed → flush previous chunk and start a new one
                        if (currentUserId != null && !currentUserId.equals(userId)) {
                            flushChunk(chunks, currentFilePath, startLine, currentLineNumber - 1, currentChunkLines, currentUserId);
                            currentChunkLines.clear();
                            startLine = null;
                        }
                        currentUserId = userId;
                        if (startLine == null) {
                            startLine = currentLineNumber;
                        }
                        currentChunkLines.add(content);
                        currentLineNumber++;
                    } else {
                        // Removed line: flush current chunk, don't advance line number
                        flushChunk(chunks, currentFilePath, startLine, currentLineNumber - 1, currentChunkLines, currentUserId);
                        currentChunkLines.clear();
                        startLine = null;
                        currentUserId = null;
                    }
                } else if (line.startsWith("+") && !line.startsWith("+++")) {
                    // Fallback: plain '+' line without user prefix (backward compat)
                    if (startLine == null) {
                        startLine = currentLineNumber;
                    }
                    currentChunkLines.add(line.substring(1));
                    currentLineNumber++;
                } else if (line.startsWith("-") && !line.startsWith("---")) {
                    // Fallback: plain '-' line without user prefix
                    flushChunk(chunks, currentFilePath, startLine, currentLineNumber - 1, currentChunkLines, currentUserId);
                    currentChunkLines.clear();
                    startLine = null;
                    currentUserId = null;
                } else if (line.startsWith(" ") || line.isEmpty() || line.startsWith("\\")) {
                    flushChunk(chunks, currentFilePath, startLine, currentLineNumber - 1, currentChunkLines, currentUserId);
                    currentChunkLines.clear();
                    startLine = null;
                    currentUserId = null;
                    if (line.startsWith(" ") || line.isEmpty()) {
                        currentLineNumber++;
                    }
                }
            }
        }

        flushChunk(chunks, currentFilePath, startLine, currentLineNumber - 1, currentChunkLines, currentUserId);

        return chunks;
    }

    /**
     * Build a DiffChunk from collected lines.
     * Now calculates nonBlankLineCount to match TS behavior.
     */
    private void flushChunk(List<DiffChunk> chunks, String filePath, Integer startLine, int endLine, List<String> lines, String userId) {
        if (!lines.isEmpty() && startLine != null) {
            String content = String.join("\n", lines);
            int nonBlankLineCount = 0;
            for (String line : lines) {
                if (!line.trim().isEmpty()) nonBlankLineCount++;
            }
            DiffChunk chunk = DiffChunk.builder()
                    .filePath(filePath)
                    .startLine(startLine)
                    .endLine(endLine)
                    .content(content)
                    .nonBlankLineCount(nonBlankLineCount)
                    .userId(userId)
                    .build();
            chunks.add(chunk);
        }
    }
}
