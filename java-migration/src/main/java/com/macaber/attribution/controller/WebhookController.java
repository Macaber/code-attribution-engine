package com.macaber.attribution.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.macaber.attribution.service.AiMessageService;
import com.macaber.attribution.entity.AiMessage;
import com.macaber.attribution.dto.AiMessageDto;
import com.macaber.attribution.dto.AttributionJobData;
import com.macaber.attribution.dto.DoMergePayload;
import com.macaber.attribution.dto.MergeFileDetail;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.macaber.attribution.core.queue.QueueProducer;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * WebhookController — REST API for receiving CICD doMerge webhooks.
 *
 * POST /api/coding/doMerge
 */
@Slf4j
@RestController
@RequestMapping("/api/coding")
@RequiredArgsConstructor
public class WebhookController {

    private final AiMessageService aiMessageService;
    private final ObjectMapper objectMapper;
    private final QueueProducer queueProducer;

    @PostMapping("/doMerge")
    public ResponseEntity<?> doMerge(@RequestBody DoMergePayload payload) {
        if (payload.getMergeId() == null || payload.getRepoName() == null || payload.getDetail() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Missing required fields: mergeId, repoName, detail"));
        }

        List<MergeFileDetail> fileDetails;
        try {
            fileDetails = objectMapper.readValue(payload.getDetail(), new TypeReference<List<MergeFileDetail>>() {});
        } catch (JsonProcessingException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid detail field: expected JSON array of {path, code, diff}"));
        }

        // Filter out entries without diffs
        fileDetails = fileDetails.stream()
                .filter(f -> f.getDiff() != null && !f.getDiff().trim().isEmpty())
                .collect(Collectors.toList());

        if (fileDetails.isEmpty()) {
            return ResponseEntity.ok(Map.of(
                    "status", "skipped",
                    "mergeId", payload.getMergeId(),
                    "message", "No file diffs to analyze"
            ));
        }

        log.info("[Webhook] doMerge received — mergeId: {}, repo: {}, files: {}, operator: {}",
                payload.getMergeId(), payload.getRepoName(), fileDetails.size(), payload.getOa());

        LocalDateTime oneMonthAgo = LocalDateTime.now().minusMonths(1);

        // Fetch AI messages from database for this user
        LambdaQueryWrapper<AiMessage> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(AiMessage::getUserOa, payload.getOa())
                    .ge(AiMessage::getCreatedAt, oneMonthAgo);

        List<AiMessage> rows = aiMessageService.list(queryWrapper);
        List<AiMessageDto> aiMessages = new ArrayList<>();

        for (AiMessage row : rows) {
            try {
                // In Java, we typically just parse the JSON arguments string
                Map<String, Object> args = objectMapper.readValue(row.getFunctionArguments(), new TypeReference<Map<String, Object>>() {});
                String rawContent = "";

                if ("edit".equals(row.getFunctionName()) && args.containsKey("newString")) {
                    rawContent = (String) args.get("newString");
                } else if ("write".equals(row.getFunctionName()) && args.containsKey("content")) {
                    rawContent = (String) args.get("content");
                }

                if (rawContent != null && !rawContent.trim().isEmpty()) {
                    aiMessages.add(AiMessageDto.builder()
                            .messageId(String.valueOf(row.getId()))
                            .userId(payload.getOa())
                            .timestamp(row.getCreatedAt())
                            .rawContent(rawContent)
                            .build());
                }
            } catch (Exception e) {
                log.warn("[Webhook] Failed to parse ai_message arguments for id {}", row.getId());
            }
        }

        log.info("[Webhook] Fetched {} valid AI messages for user {}", aiMessages.size(), payload.getOa());

        AttributionJobData jobData = AttributionJobData.builder()
                .mergeId(payload.getMergeId())
                .repoName(payload.getRepoName())
                .userId(payload.getOa())
                .sysCode(payload.getSysCode())
                .title(payload.getTitle())
                .fileDetails(fileDetails)
                .aiMessages(aiMessages)
                .build();

        queueProducer.addJob(jobData);

        return ResponseEntity.accepted().body(Map.of(
                "status", "accepted",
                "mergeId", payload.getMergeId(),
                "repoName", payload.getRepoName(),
                "filesCount", fileDetails.size(),
                "message", "Attribution analysis job queued"
        ));
    }
}
