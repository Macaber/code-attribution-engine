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

import java.util.List;
import java.util.Map;
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

        // Filter out entries without diffs
        fileDetails = fileDetails.stream()
                .filter(f -> f.getDiff() != null && !f.getDiff().trim().isEmpty())
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
}
