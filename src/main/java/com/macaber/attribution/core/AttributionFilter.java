package com.macaber.attribution.core;

import com.macaber.attribution.dto.MergeFileDetail;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * AttributionFilter — Filters out incoming diff files that are not code.
 * Detects logs, binary files, large files, and specific non-code extensions.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AttributionFilter {

    private final PipelineConfig pipelineConfig;

    // Pattern to detect timestamps common in log lines (e.g. 2026-05-22 14:46:03, [2026-05-22 14:46:03], 15:22:00.123)
    private static final Pattern LOG_TIMESTAMP_PATTERN = Pattern.compile(
            "\\d{4}[-/]\\d{2}[-/]\\d{2}[ T]\\d{2}:\\d{2}:\\d{2}|\\d{2}:\\d{2}:\\d{2}\\.\\d+"
    );

    // Pattern to detect log level indicators
    private static final Pattern LOG_LEVEL_PATTERN = Pattern.compile(
            "\\b(INFO|WARN|ERROR|DEBUG|TRACE|FATAL|SEVERE|WARNING)\\b", Pattern.CASE_INSENSITIVE
    );

    /**
     * Determine if the file should be skipped.
     *
     * @param file the MergeFileDetail containing path, code, and diff
     * @return true if the file should be filtered out/skipped
     */
    /**
     * Determine if the file should be skipped.
     *
     * @param file the MergeFileDetail containing path, code, and diff
     * @return true if the file should be filtered out/skipped
     */
    public boolean shouldFilter(MergeFileDetail file) {
        if (!pipelineConfig.isFilterEnabled()) {
            return false;
        }

        String path = file.getPath();
        if (path == null || path.isEmpty()) {
            return false;
        }

        // 1. Exclude extensions check
        String extension = getFileExtension(path).toLowerCase();
        if (pipelineConfig.getExcludeExtensions() != null && !extension.isEmpty()) {
            for (String ext : pipelineConfig.getExcludeExtensions()) {
                if (ext.trim().equalsIgnoreCase(extension)) {
                    log.info("[Filter] File skipped by extension: {} (matched excluded extension: {})", path, ext);
                    return true;
                }
            }
        }

        // 2. Binary detection
        if (pipelineConfig.isFilterBinary()) {
            if (isBinaryFile(file)) {
                log.info("[Filter] File skipped: {} is detected as binary", path);
                return true;
            }
        }

        // 3. Bypass size/line/log filters for legitimate code extensions
        if (pipelineConfig.getCodeExtensions() != null) {
            for (String ext : pipelineConfig.getCodeExtensions()) {
                if (ext.trim().equalsIgnoreCase(extension)) {
                    return false;
                }
            }
        }

        // 4. File size / Diff size limit
        if (file.getCode() != null) {
            long sizeBytes = file.getCode().length();
            if (sizeBytes > (long) pipelineConfig.getMaxFileSizeKb() * 1024) {
                log.info("[Filter] File skipped: {} code size ({} bytes) exceeds limit ({} KB)",
                        path, sizeBytes, pipelineConfig.getMaxFileSizeKb());
                return true;
            }
        }
        if (file.getDiff() != null) {
            long diffBytes = file.getDiff().length();
            if (diffBytes > (long) pipelineConfig.getMaxDiffSizeKb() * 1024) {
                log.info("[Filter] File skipped: {} diff size ({} bytes) exceeds limit ({} KB)",
                        path, diffBytes, pipelineConfig.getMaxDiffSizeKb());
                return true;
            }
        }

        // 5. File line count limit
        if (file.getCode() != null) {
            int lineCount = countLines(file.getCode());
            if (lineCount > pipelineConfig.getMaxFileLines()) {
                log.info("[Filter] File skipped: {} code lines ({}) exceed limit ({})",
                        path, lineCount, pipelineConfig.getMaxFileLines());
                return true;
            }
        }

        // 6. Log file detection
        if (pipelineConfig.isFilterLogs()) {
            if (isLogFile(file)) {
                log.info("[Filter] File skipped: {} is detected as log file/content", path);
                return true;
            }
        }

        return false;
    }

    private String getFileExtension(String path) {
        int lastIndex = path.lastIndexOf('.');
        if (lastIndex > 0 && lastIndex < path.length() - 1) {
            return path.substring(lastIndex + 1);
        }
        return "";
    }

    private boolean isBinaryFile(MergeFileDetail file) {
        // Git diff binary indicator
        String diff = file.getDiff();
        if (diff != null && (diff.contains("Binary files ") || diff.contains(" differ\n"))) {
            return true;
        }

        // Check for null bytes in code or diff
        String code = file.getCode();
        if (code != null && code.contains("\0")) {
            return true;
        }
        if (diff != null && diff.contains("\0")) {
            return true;
        }

        return false;
    }

    private boolean isLogFile(MergeFileDetail file) {
        String path = file.getPath();
        String extension = getFileExtension(path).toLowerCase();
        if ("log".equals(extension)) {
            return true;
        }

        // Check content patterns for logs
        String content = file.getCode();
        if (content == null || content.trim().isEmpty()) {
            content = file.getDiff();
        }
        if (content == null || content.trim().isEmpty()) {
            return false;
        }

        // Check first 20 lines
        String[] lines = content.split("\n", 20);
        int logPatternLinesCount = 0;
        int totalValidLines = 0;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            totalValidLines++;

            // Strip git diff headers/markers
            if (trimmed.startsWith("+") || trimmed.startsWith("-")) {
                trimmed = trimmed.substring(1).trim();
            }

            boolean hasTimestamp = LOG_TIMESTAMP_PATTERN.matcher(trimmed).find();
            boolean hasLogLevel = LOG_LEVEL_PATTERN.matcher(trimmed).find();

            if (hasTimestamp || hasLogLevel) {
                logPatternLinesCount++;
            }
        }

        // If >= 30% of valid lines contain log indicators (and there are at least 3 non-empty lines), classify as log
        return totalValidLines >= 3 && ((double) logPatternLinesCount / totalValidLines) >= 0.3;
    }

    private int countLines(String content) {
        if (content == null || content.isEmpty()) {
            return 0;
        }
        int lines = 1;
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                lines++;
            }
        }
        return lines;
    }
}
