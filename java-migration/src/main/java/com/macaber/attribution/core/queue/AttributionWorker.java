package com.macaber.attribution.core.queue;

import com.macaber.attribution.core.*;
import com.macaber.attribution.entity.AttributionResult;
import com.macaber.attribution.service.AttributionResultService;
import com.macaber.attribution.dto.*;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBlockingQueue;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class AttributionWorker {

    private final RedissonClient redissonClient;
    private final SimilarityEngine similarityEngine;
    private final AttributionResultService resultService;
    private final DiffParser diffParser = new DiffParser();
    private final Normalizer normalizer = new Normalizer();

    private static final String QUEUE_NAME = "attribution-queue";
    private ExecutorService executorService;
    private volatile boolean isRunning = true;

    @PostConstruct
    public void init() {
        executorService = Executors.newSingleThreadExecutor();
        executorService.submit(this::processQueue);
        log.info("[Worker] AttributionWorker started, listening on queue: {}", QUEUE_NAME);
    }

    private void processQueue() {
        RBlockingQueue<AttributionJobData> queue = redissonClient.getBlockingQueue(QUEUE_NAME);
        while (isRunning) {
            try {
                // Blocks until a job is available
                AttributionJobData jobData = queue.poll(1, TimeUnit.SECONDS);
                if (jobData != null) {
                    processJob(jobData);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.info("[Worker] AttributionWorker interrupted");
                break;
            } catch (Exception e) {
                log.error("[Worker] Error processing job", e);
            }
        }
    }

    private void processJob(AttributionJobData jobData) {
        log.info("[Worker] Processing attribution job for mergeId: {}", jobData.getMergeId());
        
        List<EnrichedChunk> enrichedChunks = new ArrayList<>();
        
        // ── Step 1: Parse diffs and enrich chunks with file context ──
        for (var file : jobData.getFileDetails()) {
            if (file.getDiff() == null || file.getDiff().trim().isEmpty()) continue;
            
            List<DiffChunk> chunks = diffParser.parse(file.getDiff());
            int fileAddedLineCount = chunks.stream().mapToInt(c -> c.getEndLine() - c.getStartLine() + 1).sum();
            
            for (DiffChunk chunk : chunks) {
                enrichedChunks.add(new EnrichedChunk(chunk, file.getCode(), fileAddedLineCount, file.getPath()));
            }
        }

        if (enrichedChunks.isEmpty()) {
            log.info("[Worker] No valid diff chunks found for mergeId: {}", jobData.getMergeId());
            return;
        }

        // ── Step 2: Normalize all AI messages ──
        List<AiMessageDto> aiMessages = jobData.getAiMessages();
        // Since we don't store normalized content in DTO, we just normalize on the fly below

        // ── Step 3: Run pipeline for each chunk ──
        List<MatchResult> results = new ArrayList<>();
        for (EnrichedChunk chunk : enrichedChunks) {
            MatchResult bestResult = processChunk(chunk, aiMessages);
            results.add(bestResult);
        }

        // ── Step 4: Summarize and Save to DB ──
        double totalAiContributedLines = results.stream().mapToDouble(MatchResult::getContributedLines).sum();
        int totalAnalyzedLines = results.stream().mapToInt(r -> {
            String[] lines = r.getChunk().getContent().split("\n");
            int nonBlank = 0;
            for (String l : lines) if (!l.trim().isEmpty()) nonBlank++;
            return nonBlank;
        }).sum();

        int totalCodeLines = 0;
        if (jobData.getFileDetails() != null) {
            for (var f : jobData.getFileDetails()) {
                if (f.getCode() != null) {
                    totalCodeLines += f.getCode().split("\n").length;
                }
            }
        }

        double ratio = totalAnalyzedLines > 0 ? totalAiContributedLines / totalAnalyzedLines : 0;

        AttributionResult resultRecord = AttributionResult.builder()
                .mergeId(jobData.getMergeId())
                .repoName(jobData.getRepoName())
                .userOa(jobData.getUserId())
                .totalCodeLines(totalCodeLines)
                .analyzedLines(totalAnalyzedLines)
                .aiContributedLines(totalAiContributedLines)
                .aiContributionRatio(ratio)
                .createdAt(LocalDateTime.now())
                .build();

        resultService.save(resultRecord);
        
        log.info("[Worker] Finished processing job for mergeId: {}. AI Contributed Lines: {}, Ratio: {}", 
                jobData.getMergeId(), totalAiContributedLines, ratio);
    }

    private MatchResult processChunk(EnrichedChunk chunk, List<AiMessageDto> messages) {
        EvaluationResult bestResult = null;
        String bestMessageId = null;

        for (AiMessageDto msg : messages) {
            SimilarityEngine.EvaluationContext ctx = SimilarityEngine.EvaluationContext.builder()
                    .fileContent(chunk.getFileContent())
                    .filePath(chunk.getFilePath())
                    .addedLineCount(chunk.getFileAddedLineCount())
                    .chunkStartLine(chunk.getStartLine())
                    .chunkEndLine(chunk.getEndLine())
                    .build();

            EvaluationResult result = similarityEngine.evaluateChunk(msg.getRawContent(), chunk.getContent(), ctx);

            if (bestResult == null || result.getScore() > bestResult.getScore()) {
                bestResult = result;
                bestMessageId = msg.getMessageId();
            }

            if (result.getMatchType() == MatchType.STRICT) break;
        }

        String attribution = bestResult != null && bestResult.getMatchType() != MatchType.NONE ? bestResult.getMatchType().name() : "NONE";
        int totalLines = chunk.getEndLine() - chunk.getStartLine() + 1;
        double contributedLines = 0;

        if (bestResult != null) {
            switch (bestResult.getMatchType()) {
                case STRICT:
                    contributedLines = bestResult.getExactContributedLines() > 0 ? bestResult.getExactContributedLines() : totalLines;
                    break;
                case FUZZY:
                    contributedLines = bestResult.getExactContributedLines();
                    break;
                case DEEP_REFACTOR:
                    contributedLines = Math.max(bestResult.getExactContributedLines(), totalLines * bestResult.getScore());
                    break;
                case NONE:
                    contributedLines = 0;
                    break;
            }
        }

        MatchResult.BestMatch matchDetail = null;
        if (bestResult != null && bestMessageId != null) {
            matchDetail = MatchResult.BestMatch.builder()
                    .messageId(bestMessageId)
                    .score(bestResult.getScore())
                    .matchType(bestResult.getMatchType().name())
                    .level(bestResult.getLevel().name())
                    .details(bestResult.getDetails())
                    .build();
        }

        return MatchResult.builder()
                .chunk(chunk)
                .bestMatch(matchDetail)
                .attribution(attribution)
                .contributedLines(contributedLines)
                .build();
    }

    @PreDestroy
    public void destroy() {
        isRunning = false;
        if (executorService != null) {
            executorService.shutdownNow();
        }
    }
}
