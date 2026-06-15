package com.macaber.attribution.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.macaber.attribution.dto.AttributionJobData;
import com.macaber.attribution.dto.DoMergePayload;
import com.macaber.attribution.dto.MergeFileDetail;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.macaber.attribution.core.queue.QueueProducer;
import com.macaber.attribution.core.AttributionFilter;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.macaber.attribution.entity.AttributionResult;
import com.macaber.attribution.entity.AttributionFileDetail;
import com.macaber.attribution.service.AttributionResultService;
import com.macaber.attribution.service.AttributionFileDetailService;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * WebhookController — REST API for receiving CICD doMerge webhooks.
 *
 * Thin controller: validates payload, filters empty diffs, and enqueues the job.
 * AI message fetching and diff parsing are handled by the consumer (AttributionWorker).
 *
 * POST /api/coding/doMerge
 */
@Slf4j
@RestController
@RequestMapping("/api/coding")
@RequiredArgsConstructor
public class WebhookController {

    private final ObjectMapper objectMapper;
    private final QueueProducer queueProducer;
    private final AttributionFilter attributionFilter;
    private final AttributionResultService resultService;
    private final AttributionFileDetailService fileDetailService;

    @PostMapping("/doMerge")
    public ResponseEntity<?> doMerge(@RequestBody DoMergePayload payload) {
        if (payload.getMergeId() == null || payload.getRepoName() == null || payload.getDetail() == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Missing required fields: mergeId, repoName, detail"));
        }

        List<MergeFileDetail> fileDetails;
        try {
            fileDetails = objectMapper.readValue(payload.getDetail(),
                    new TypeReference<List<MergeFileDetail>>() {});
        } catch (JsonProcessingException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Invalid detail field: expected JSON array of {path, code, diff}"));
        }

        // Clean up old report if it exists to avoid unique key conflict and keep the latest details
        String sysCode = payload.getSysCode() != null ? payload.getSysCode() : "";
        resultService.remove(new LambdaQueryWrapper<AttributionResult>()
                .eq(AttributionResult::getMergeId, payload.getMergeId())
                .eq(AttributionResult::getSysCode, sysCode));

        // Create an empty report record early to get an ID for file details mapping
        AttributionResult report = AttributionResult.builder()
                .mergeId(payload.getMergeId())
                .repoName(payload.getRepoName())
                .userId(payload.getOa())
                .sysCode(sysCode)
                .title(payload.getTitle())
                .createdAt(LocalDateTime.now())
                .build();
        resultService.save(report);

        // Save original request file details (path, code, diff) immediately
        if (!fileDetails.isEmpty()) {
            List<AttributionFileDetail> dbFileDetails = fileDetails.stream()
                    .map(f -> {
                        String filePath = f.getPath();
                        String extension = getFileExtension(filePath).toLowerCase();
                        boolean shouldExcludeContent = NO_CONTENT_EXTENSIONS.contains(extension) || isBinary(f);

                        String code = shouldExcludeContent ? null : truncate(f.getCode());
                        String diff = shouldExcludeContent ? null : truncate(f.getDiff());

                        return AttributionFileDetail.builder()
                                .reportId(report.getId())
                                .filePath(filePath)
                                .code(code)
                                .diff(diff)
                                .createdAt(LocalDateTime.now())
                                .build();
                    })
                    .collect(Collectors.toList());
            fileDetailService.saveBatch(dbFileDetails);
        }

        // Filter out entries without diffs and entries that should be ignored by filtering rules
        fileDetails = fileDetails.stream()
                .filter(f -> f.getDiff() != null && !f.getDiff().trim().isEmpty())
                .filter(f -> !attributionFilter.shouldFilter(f))
                .collect(Collectors.toList());

        if (fileDetails.isEmpty()) {
            return ResponseEntity.ok(Map.of(
                    "status", "skipped",
                    "mergeId", payload.getMergeId(),
                    "message", "No file diffs to analyze"));
        }

        log.info("[Webhook] doMerge received — mergeId: {}, repo: {}, files: {}, operator: {}",
                payload.getMergeId(), payload.getRepoName(), fileDetails.size(), payload.getOa());

        // Build lightweight job data — AI messages will be fetched by the worker
        AttributionJobData jobData = AttributionJobData.builder()
                .mergeId(payload.getMergeId())
                .reportId(report.getId())
                .repoName(payload.getRepoName())
                .userId(payload.getOa())
                .sysCode(payload.getSysCode())
                .title(payload.getTitle())
                .fileDetails(fileDetails)
                .build();

        queueProducer.addJob(jobData);

        return ResponseEntity.accepted().body(Map.of(
                "status", "accepted",
                "mergeId", payload.getMergeId(),
                "repoName", payload.getRepoName(),
                "filesCount", fileDetails.size(),
                "message", "Attribution analysis job queued"));
    }

    private static final Set<String> NO_CONTENT_EXTENSIONS = Set.of(
            // Images / media
            "png", "jpg", "jpeg", "gif", "bmp", "webp", "ico", "mp4", "mp3", "wav", "avi", "mov",
            // Documents
            "docx", "doc", "xlsx", "xls", "pptx", "ppt", "pdf",
            // Archives
            "zip", "tar", "gz", "rar", "7z",
            // Executables / build artifacts
            "exe", "dll", "so", "dylib", "bin", "class", "jar", "war", "ear"
    );

    private static final int MAX_CHAR_LIMIT = 1_000_000; // 1 million characters

    private String getFileExtension(String path) {
        if (path == null) return "";
        int lastIndex = path.lastIndexOf('.');
        if (lastIndex > 0 && lastIndex < path.length() - 1) {
            return path.substring(lastIndex + 1);
        }
        return "";
    }

    private boolean isBinary(MergeFileDetail file) {
        String diff = file.getDiff();
        if (diff != null && (diff.contains("Binary files ") || diff.contains(" differ\n"))) {
            return true;
        }
        String code = file.getCode();
        if (code != null && code.contains("\0")) {
            return true;
        }
        if (diff != null && diff.contains("\0")) {
            return true;
        }
        return false;
    }

    private String truncate(String text) {
        if (text == null || text.length() <= MAX_CHAR_LIMIT) {
            return text;
        }
        return text.substring(0, MAX_CHAR_LIMIT) + "\n[Content truncated due to size limit...]";
    }
}
