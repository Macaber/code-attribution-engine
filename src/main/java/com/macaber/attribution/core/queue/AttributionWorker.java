package com.macaber.attribution.core.queue;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.macaber.attribution.core.*;
import com.macaber.attribution.entity.AiMessage;
import com.macaber.attribution.entity.AttributionResult;
import com.macaber.attribution.service.AiMessageService;
import com.macaber.attribution.service.AttributionResultService;
import com.macaber.attribution.service.AttributionChunkDetailService;
import com.macaber.attribution.service.AttributionFailedJobService;
import com.macaber.attribution.service.AttributionFileDetailService;
import com.macaber.attribution.entity.AttributionChunkDetail;
import com.macaber.attribution.entity.AttributionFailedJob;
import com.macaber.attribution.dto.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBlockingDeque;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;

/**
 * AttributionWorker — Pipeline orchestrator for code attribution analysis.
 *
 * Takes a job containing file details (diff + full code) and AI message history,
 * then runs the 3-layer escalation pipeline for each chunk:
 *   L1 Winnowing → L2 LCS → L3 AST Features
 *
 * Key behaviors:
 * - Pre-normalizes all AI messages once per job (normalizeToLines)
 * - Passes full file content (from doMerge `code` field) to enable L3 AST parsing
 * - Counts added lines per file for L3 circuit breaker (>1000 lines skips AST)
 * - Each chunk is scored against all AI messages, best match wins
 * - Multi-message support: union-merges contributedLineIndices across qualifying messages
 *
 * Aligned with TS: src/domains/attribution/attribution.worker.ts
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AttributionWorker {

    private final RedissonClient redissonClient;
    private final SimilarityEngine similarityEngine;
    private final AiMessageService aiMessageService;
    private final AttributionResultService resultService;
    private final AttributionChunkDetailService chunkDetailService;
    private final AttributionFailedJobService failedJobService;
    private final ObjectMapper objectMapper;
    private final DiffParser diffParser = new DiffParser();
    private final Normalizer normalizer = new Normalizer();
    private final PipelineConfig pipelineConfig;

    private static final String QUEUE_NAME = "attribution-queue";
    private ExecutorService executorService;
    private volatile boolean isRunning = true;

    @Value("${attribution.worker.threads:2}")
    private int threadCount;

    @Value("${attribution.worker.ai-message.limit:1000}")
    private int aiMessageLimit;

    @Value("${attribution.worker.ai-message.timeframe-days:30}")
    private int aiMessageTimeframeDays;

    @PostConstruct
    public void init() {
        executorService = new java.util.concurrent.ThreadPoolExecutor(
                threadCount,
                threadCount,
                0L, java.util.concurrent.TimeUnit.MILLISECONDS,
                new java.util.concurrent.LinkedBlockingQueue<>(threadCount),
                new CustomizableThreadFactory("attribution-worker-"),
                new java.util.concurrent.ThreadPoolExecutor.AbortPolicy()
        );
        for (int i = 0; i < threadCount; i++) {
            executorService.submit(this::processQueue);
        }
        log.info("[Worker] AttributionWorker started with {} threads, listening on queue: {}", threadCount, QUEUE_NAME);
    }

    private void processQueue() {
        RBlockingDeque<AttributionJobData> queue = redissonClient.getBlockingDeque(QUEUE_NAME);
        while (isRunning) {
            AttributionJobData jobData = null;
            try {
                // Blocks until a job is available
                jobData = queue.poll(1, TimeUnit.SECONDS);
                if (jobData != null) {
                    try {
                        processJob(jobData);
                    } catch (Exception ex) {
                        if (ex.getCause() instanceof InterruptedException || Thread.currentThread().isInterrupted()) {
                            throw ex;
                        }
                        handleFailedJob(jobData, ex);
                    }
                    // Reset reference since job has been processed/handled
                    jobData = null;
                }
            } catch (InterruptedException e) {
                if (jobData != null) {
                    try {
                        // Put the in-flight job back to the front of the queue to prevent job loss
                        queue.addFirst(jobData);
                        log.info("[Worker] Rolled back in-flight job to Redis queue for mergeId: {}", jobData.getMergeId());
                    } catch (Exception ex) {
                        log.error("[Worker] Failed to roll back job to Redis queue for mergeId: {}", jobData.getMergeId(), ex);
                        handleFailedJob(jobData, new Exception("Job lost due to thread interrupt/shutdown", e));
                    }
                }
                Thread.currentThread().interrupt();
                log.info("[Worker] AttributionWorker thread interrupted");
                break;
            } catch (Exception e) {
                if (Thread.currentThread().isInterrupted() || e.getCause() instanceof InterruptedException) {
                    if (jobData != null) {
                        try {
                            queue.addFirst(jobData);
                            log.info("[Worker] Rolled back in-flight job to Redis queue for mergeId: {}", jobData.getMergeId());
                        } catch (Exception ex) {
                            log.error("[Worker] Failed to roll back job to Redis queue for mergeId: {}", jobData.getMergeId(), ex);
                            handleFailedJob(jobData, e);
                        }
                    }
                    log.info("[Worker] AttributionWorker thread interrupted during exception");
                    break;
                }
                log.error("[Worker] Error processing queue", e);
            }
        }
    }

    private void handleFailedJob(AttributionJobData jobData, Exception ex) {
        log.error("[Worker] Job failed for mergeId: {}", jobData.getMergeId(), ex);
        try {
            String jobDataJson = objectMapper.writeValueAsString(jobData);
            AttributionFailedJob failedJob = AttributionFailedJob.builder()
                    .mergeId(jobData.getMergeId())
                    .repoName(jobData.getRepoName())
                    .userId(jobData.getUserId())
                    .sysCode(jobData.getSysCode() != null ? jobData.getSysCode() : "")
                    .jobData(jobDataJson)
                    .errorMessage(simplifyErrorMessage(ex))
                    .errorStack(simplifyErrorStack(ex))
                    .attemptCount(1)
                    .status("pending")
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();
            failedJobService.save(failedJob);
        } catch (Exception e) {
            log.error("[Worker] Failed to save error record for mergeId: {}", jobData.getMergeId(), e);
        }
    }

    String simplifyErrorMessage(Throwable t) {
        if (t == null) {
            return "";
        }
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String mainMsg = t.getClass().getSimpleName() + ": " + t.getMessage();
        if (t != root) {
            return mainMsg + " [Root Cause: " + root.getClass().getSimpleName() + ": " + root.getMessage() + "]";
        }
        return mainMsg;
    }

    String simplifyErrorStack(Throwable t) {
        if (t == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        Set<Throwable> visited = new HashSet<>();
        buildSimplifiedStack(t, sb, false, visited);
        return sb.toString();
    }

    private void buildSimplifiedStack(Throwable t, StringBuilder sb, boolean isCause, Set<Throwable> visited) {
        if (!visited.add(t)) {
            sb.append("\n[Circular reference detected]");
            return;
        }
        if (isCause) {
            sb.append("\nCaused by: ");
        }
        sb.append(t.getClass().getName()).append(": ").append(t.getMessage()).append("\n");

        StackTraceElement[] trace = t.getStackTrace();
        int frameCount = 0;
        int maxTopFrames = 8;

        for (int i = 0; i < trace.length; i++) {
            StackTraceElement element = trace[i];
            String className = element.getClassName();
            boolean isProjectFrame = className.startsWith("com.macaber.");
            boolean isTopFrame = i < maxTopFrames;

            if (isProjectFrame || isTopFrame) {
                sb.append("\tat ").append(element.toString()).append("\n");
                frameCount++;
            }
        }

        if (trace.length > frameCount) {
            sb.append("\t... ").append(trace.length - frameCount).append(" more framework/internal frames truncated\n");
        }

        Throwable cause = t.getCause();
        if (cause != null) {
            buildSimplifiedStack(cause, sb, true, visited);
        }
    }


    private void processJob(AttributionJobData jobData) {
        long startTime = System.currentTimeMillis();
        log.info("[Worker] Processing attribution job for mergeId: {}", jobData.getMergeId());

        List<EnrichedChunk> enrichedChunks = new ArrayList<>();

        // ── Step 1: Parse diffs and enrich chunks with file context ──
        for (var file : jobData.getFileDetails()) {
            if (file.getDiff() == null || file.getDiff().trim().isEmpty()) continue;

            List<DiffChunk> chunks = diffParser.parse(file.getDiff());
            // Count total added lines in this file
            int fileAddedLineCount = chunks.stream()
                    .mapToInt(c -> c.getEndLine() - c.getStartLine() + 1)
                    .sum();

            for (DiffChunk chunk : chunks) {
                enrichedChunks.add(new EnrichedChunk(
                        chunk,
                        file.getCode(),
                        fileAddedLineCount,
                        file.getPath() != null ? file.getPath() : chunk.getFilePath()
                ));
            }
        }

        if (enrichedChunks.isEmpty()) {
            log.info("[Worker] No valid diff chunks found for mergeId: {}", jobData.getMergeId());
            return;
        }

        // ── Step 2: Collect involved userIds from parsed chunks and fetch AI messages from DB ──
        Set<String> involvedUserIds = new LinkedHashSet<>();
        for (EnrichedChunk chunk : enrichedChunks) {
            if (chunk.getUserId() != null) {
                involvedUserIds.add(chunk.getUserId());
            }
        }
        // Fallback: if no user prefixes found (old diff format), use the job submitter's userId
        if (involvedUserIds.isEmpty() && jobData.getUserId() != null) {
            involvedUserIds.add(jobData.getUserId());
        }

        List<AiMessageDto> aiMessages = fetchAiMessages(involvedUserIds, jobData.getTimeframeDays());
        log.info("[Worker] Fetched {} AI messages for users {} (mergeId: {})",
                aiMessages.size(), involvedUserIds, jobData.getMergeId());

        List<NormalizedAiMessage> normalizedMessages = normalizeMessages(aiMessages);

        // ── Step 3: Run pipeline for each chunk ──
        log.info("[Worker] Job {} — Starting pipeline processing for {} chunks", jobData.getMergeId(), enrichedChunks.size());
        List<MatchResult> results = new ArrayList<>();
        int processedCount = 0;
        for (EnrichedChunk chunk : enrichedChunks) {
            MatchResult result = processChunk(chunk, normalizedMessages);
            results.add(result);
            processedCount++;
            if (processedCount % 5 == 0 || processedCount == enrichedChunks.size()) {
                log.info("[Worker] Job {} — Processed {}/{} chunks", jobData.getMergeId(), processedCount, enrichedChunks.size());
            }
        }

        // ── Step 4: Summarize and Save to DB ──
        log.info("[Worker] Job {} — Saving summary and details to database...", jobData.getMergeId());
        long elapsedMs = System.currentTimeMillis() - startTime;
        saveSummary(results, jobData, elapsedMs);
    }

    /**
     * Fetch AI messages from DB for the given set of userIds.
     * Queries ai_messages table for edit/write function calls within the last month,
     * extracts the raw code content from function arguments.
     */
    private List<AiMessageDto> fetchAiMessages(Set<String> userIds, Integer timeframeDays) {
        if (userIds == null || userIds.isEmpty()) {
            return Collections.emptyList();
        }

        int days = timeframeDays != null ? timeframeDays : aiMessageTimeframeDays;
        LocalDateTime startTime = LocalDateTime.now().minusDays(days);

        LambdaQueryWrapper<AiMessage> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.in(AiMessage::getUserOa, userIds)
                .in(AiMessage::getFunctionName, Arrays.asList("edit", "write"))
                .ge(AiMessage::getCreatedAt, startTime)
                .orderByDesc(AiMessage::getCreatedAt)
                .last("LIMIT " + aiMessageLimit);

        List<AiMessage> rows = aiMessageService.list(queryWrapper);
        List<AiMessageDto> aiMessages = new ArrayList<>();

        for (AiMessage row : rows) {
            try {
                Map<String, Object> args = objectMapper.readValue(row.getFunctionArguments(),
                        new TypeReference<Map<String, Object>>() {});
                String rawContent = "";

                if ("edit".equals(row.getFunctionName()) && args.containsKey("newString")) {
                    rawContent = (String) args.get("newString");
                } else if ("write".equals(row.getFunctionName()) && args.containsKey("content")) {
                    rawContent = (String) args.get("content");
                }

                if (rawContent != null && !rawContent.trim().isEmpty()) {
                    aiMessages.add(AiMessageDto.builder()
                            .messageId(String.valueOf(row.getId()))
                            .userId(row.getUserOa())
                            .timestamp(row.getCreatedAt())
                            .rawContent(rawContent)
                            .fileName(row.getFileName())
                            .build());
                }
            } catch (Exception e) {
                log.warn("[Worker] Failed to parse ai_message arguments for id {}", row.getId());
            }
        }

        return aiMessages;
    }

    /**
     * Pre-normalize all AI messages for comparison.
     * Aligned with TS: normalizeMessages() in attribution.worker.ts
     */
    private List<NormalizedAiMessage> normalizeMessages(List<AiMessageDto> messages) {
        List<NormalizedAiMessage> normalized = new ArrayList<>();
        for (AiMessageDto msg : messages) {
            LineMapping lineMapping = normalizer.normalizeToLines(msg.getRawContent());
            String normalizedContent = msg.getNormalizedContent() != null
                    ? msg.getNormalizedContent()
                    : lineMapping.getNormalizedText();
            normalized.add(new NormalizedAiMessage(msg, normalizedContent, lineMapping));
        }
        return normalized;
    }

    /**
     * Run the escalation pipeline for a single chunk against all AI messages.
     *
     * Multi-message strategy (aligned with TS processChunk):
     *   1. Evaluate each AI message against this chunk
     *   2. Keep all messages whose L2 score >= threshold (meaningful contribution)
     *      OR exactContributedLines >= minLines
     *   3. Union all contributedLineIndices across qualifying messages (dedup)
     *   4. Final exactContributedLines = union set size
     *   5. Best match = highest-scoring message (drives attribution classification)
     */
    MatchResult processChunk(EnrichedChunk chunk, List<NormalizedAiMessage> messages) {
        // Collect all qualifying message evaluations
        List<CandidateMatch> candidates = new ArrayList<>();
        CandidateMatch bestCandidate = null;

        // PRE-CALCULATE chunk line mapping once per chunk to avoid repeating it for each AI message
        LineMapping chunkLineMapping = normalizer.normalizeToLines(chunk.getContent());

        // Get multi-message config
        double multiMsgThreshold = this.pipelineConfig.getMultiMessage().getThreshold();
        int multiMsgMinLines = this.pipelineConfig.getMultiMessage().getMinLines();

        for (NormalizedAiMessage msg : messages) {
            if (msg.normalizedContent == null || msg.normalizedContent.isEmpty()) continue;

            // Only compare against AI messages belonging to the same user as this chunk's author.
            // If chunk has no userId (backward compat with plain diff format), compare against all messages.
            if (chunk.getUserId() != null && msg.original.getUserId() != null
                    && !chunk.getUserId().equals(msg.original.getUserId())) {
                continue;
            }

            // Match by file name if the AI message has it populated
            if (msg.original.getFileName() != null && !msg.original.getFileName().trim().isEmpty()) {
                if (!isSameFileName(chunk.getFilePath(), msg.original.getFileName())) {
                    continue;
                }
            }

            SimilarityEngine.EvaluationContext ctx = SimilarityEngine.EvaluationContext.builder()
                    .fileContent(chunk.getFileContent())
                    .filePath(chunk.getFilePath())
                    .addedLineCount(chunk.getFileAddedLineCount())
                    .chunkStartLine(chunk.getStartLine())
                    .chunkEndLine(chunk.getEndLine())
                    .normalizedAi(msg.normalizedContent)
                    .aiLineMapping(msg.lineMapping)
                    .chunkLineMapping(chunkLineMapping)
                    .build();

            EvaluationResult result = similarityEngine.evaluateChunk(
                    msg.original.getRawContent(), chunk.getContent(), ctx);

            // Track best match (highest score)
            if (bestCandidate == null || result.getScore() > bestCandidate.result.getScore()) {
                bestCandidate = new CandidateMatch(msg.original.getMessageId(), result);
            }

            // Collect all messages with >= threshold contribution (L2 score basis) and >= minLines exact contribution
            Double l2Score = result.getDetails().get("l2LcsScore");
            double effectiveL2Score = l2Score != null ? l2Score : result.getScore();

            boolean isSingleLineMatch = false;
            if (chunkLineMapping.getNormalizedLines().size() == 1) {
                String normLine = chunkLineMapping.getNormalizedLines().get(0);
                if (normLine.length() >= pipelineConfig.getSingleLineThreshold()
                        && result.getExactContributedLines() == 1) {
                    isSingleLineMatch = true;
                }
            }

            if (result.getMatchType() != MatchType.NONE &&
                    (effectiveL2Score >= multiMsgThreshold ||
                            result.getExactContributedLines() >= multiMsgMinLines ||
                            isSingleLineMatch)) {
                candidates.add(new CandidateMatch(msg.original.getMessageId(), result));
            }

            // Early exit: if L1 STRICT match found, this single message explains the whole chunk
            if (result.getMatchType() == MatchType.STRICT) break;
        }

        // Ensure bestCandidate is ALWAYS in candidates if it's a valid match
        if (bestCandidate != null && bestCandidate.result.getMatchType() != MatchType.NONE) {
            final CandidateMatch bc = bestCandidate;
            boolean alreadyInCandidates = candidates.stream()
                    .anyMatch(c -> c.messageId.equals(bc.messageId));
            if (!alreadyInCandidates) {
                candidates.add(bestCandidate);
            }
        }

        // Sort candidates by the number of matched lines descending.
        // If the number of matched lines is the same, sort by score descending.
        candidates.sort((c1, c2) -> {
            int len1 = c1.result.getContributedLineIndices() != null ? c1.result.getContributedLineIndices().size() : 0;
            int len2 = c2.result.getContributedLineIndices() != null ? c2.result.getContributedLineIndices().size() : 0;
            if (len1 != len2) {
                return Integer.compare(len2, len1); // Descending order of size
            }
            return Double.compare(c2.result.getScore(), c1.result.getScore()); // Descending order of score
        });

        // ── Greedy Set Cover / Deduplication ──
        List<CandidateMatch> filteredCandidates = new ArrayList<>();
        Set<Integer> mergedLineIndices = new HashSet<>();
        for (CandidateMatch c : candidates) {
            Set<Integer> lineIndices = c.result.getContributedLineIndices();
            if (lineIndices == null || lineIndices.isEmpty()) {
                continue;
            }
            boolean contributesNewLines = false;
            for (int lineIdx : lineIndices) {
                if (!mergedLineIndices.contains(lineIdx)) {
                    contributesNewLines = true;
                    break;
                }
            }
            if (contributesNewLines) {
                filteredCandidates.add(c);
                mergedLineIndices.addAll(lineIndices);
            }
        }

        // Recalculate bestCandidate from the filtered candidates to keep it consistent
        bestCandidate = null;
        for (CandidateMatch c : filteredCandidates) {
            if (bestCandidate == null || c.result.getScore() > bestCandidate.result.getScore()) {
                bestCandidate = c;
            }
        }

        // ── Build attribution from best match ──
        String attribution = bestCandidate != null
                ? SimilarityEngine.matchTypeToAttribution(bestCandidate.result.getMatchType())
                : "none";

        // If no message met the threshold to claim this chunk, it's not attributed at all.
        if ("none".equals(attribution)) {
            return MatchResult.builder()
                    .chunk(chunk)
                    .bestMatch(null)
                    .matchedMessages(Collections.emptyList())
                    .matchedMessageIds("")
                    .attribution("none")
                    .contributedLines(0)
                    .build();
        }

        int totalLines = chunk.getEndLine() - chunk.getStartLine() + 1;
        double contributedLines;

        // Use union-merged line count instead of single-message count
        int unionContributedLines = mergedLineIndices.size();

        contributedLines = switch (attribution) {
            case "strict" ->
                // For strict match (L1 Winnowing fast-pass), we consider the entire chunk's valid lines as AI generated.
                // It bypassed L2 LCS line tracing, so unionContributedLines might undercount due to thresholding.
                    chunk.getNonBlankLineCount() > 0 ? chunk.getNonBlankLineCount() : totalLines;
            case "fuzzy" ->
                // Fuzzy relies purely on exact traced lines (now union-merged)
                    unionContributedLines;
            case "deep_refactor" ->
                // Deep refactor: max of union lines vs structural estimate
                    Math.max(
                            unionContributedLines,
                            totalLines * bestCandidate.result.getScore()
                    );
            default -> 0;
        };

        // ── Build matchedMessages array ──
        List<MatchResult.MessageContribution> matchedMessages = filteredCandidates.stream()
                .map(c -> MatchResult.MessageContribution.builder()
                        .messageId(c.messageId)
                        .score(c.result.getScore())
                        .matchType(c.result.getMatchType().name())
                        .level(c.result.getLevel().name())
                        .details(c.result.getDetails())
                        .build())
                .sorted((a, b) -> Double.compare(b.getScore(), a.getScore()))
                .collect(Collectors.toList());

        String matchedMessageIds = matchedMessages.stream()
                .map(MatchResult.MessageContribution::getMessageId)
                .collect(Collectors.joining(","));

        MatchResult.BestMatch matchDetail = null;
        matchDetail = MatchResult.BestMatch.builder()
                .messageId(bestCandidate.messageId)
                .score(bestCandidate.result.getScore())
                .matchType(bestCandidate.result.getMatchType().name())
                .level(bestCandidate.result.getLevel().name())
                .details(bestCandidate.result.getDetails())
                .build();

        return MatchResult.builder()
                .chunk(chunk)
                .bestMatch(matchDetail)
                .matchedMessages(matchedMessages)
                .matchedMessageIds(matchedMessageIds)
                .attribution(attribution)
                .contributedLines(Math.round(contributedLines * 100.0) / 100.0)
                .build();
    }

    /**
     * Summarize results and save to DB.
     * Aligned with TS: summarize() in attribution.worker.ts
     */
    private void saveSummary(List<MatchResult> results, AttributionJobData jobData, long elapsedMs) {
        double totalAiContributedLines = results.stream()
                .mapToDouble(MatchResult::getContributedLines).sum();

        // ── Analyzed lines = non-blank lines only (matches exactContributedLines counting basis) ──
        int totalAnalyzedLines = results.stream()
                .mapToInt(r -> r.getChunk().getNonBlankLineCount())
                .sum();

        int diffLines = 0;
        int strictMatches = 0;
        int fuzzyMatches = 0;
        int deepRefactorMatches = 0;
        int noMatches = 0;

        for (MatchResult r : results) {
            String attr = r.getAttribution();
            if ("strict".equals(attr)) strictMatches++;
            else if ("fuzzy".equals(attr)) fuzzyMatches++;
            else if ("deep_refactor".equals(attr)) deepRefactorMatches++;
            else noMatches++;

            diffLines += (r.getChunk().getEndLine() - r.getChunk().getStartLine() + 1);
        }

        // ── Total code lines (from fileDetails) ──
        int totalCodeLines = 0;
        int skippedFileCount = 0;
        if (jobData.getFileDetails() != null) {
            for (var f : jobData.getFileDetails()) {
                if (f.getDiff() == null || f.getDiff().trim().isEmpty()) {
                    skippedFileCount++;
                }
                if (f.getCode() != null && !f.getCode().isEmpty()) {
                    // Count newlines + 1 (matching TS: (code.match(/\n/g)?.length ?? 0) + 1)
                    int lineCount = 1;
                    for (int i = 0; i < f.getCode().length(); i++) {
                        if (f.getCode().charAt(i) == '\n') lineCount++;
                    }
                    totalCodeLines += lineCount;
                }
            }
        }

        double ratio = totalAnalyzedLines > 0
                ? Math.round((totalAiContributedLines / totalAnalyzedLines) * 10000.0) / 10000.0
                : 0;

        AttributionResult resultRecord = null;
        if (jobData.getReportId() != null) {
            resultRecord = resultService.getById(jobData.getReportId());
        }
        if (resultRecord == null) {
            resultRecord = AttributionResult.builder()
                    .mergeId(jobData.getMergeId())
                    .repoName(jobData.getRepoName())
                    .userId(jobData.getUserId())
                    .sysCode(jobData.getSysCode() != null ? jobData.getSysCode() : "")
                    .title(jobData.getTitle())
                    .source(jobData.getSource())
                    .target(jobData.getTarget())
                    .createdAt(LocalDateTime.now())
                    .build();
            resultService.save(resultRecord);
        }

        resultRecord.setTotalCodeLines(totalCodeLines);
        resultRecord.setDiffLines(diffLines);
        resultRecord.setAnalyzedLines(totalAnalyzedLines);
        resultRecord.setAiContributedLines(Math.round(totalAiContributedLines * 100.0) / 100.0);
        resultRecord.setAiContributionRatio(ratio);
        resultRecord.setSkippedLines(0);
        resultRecord.setSkippedFileCount(skippedFileCount);
        resultRecord.setStrictMatches(strictMatches);
        resultRecord.setFuzzyMatches(fuzzyMatches);
        resultRecord.setDeepRefactorMatches(deepRefactorMatches);
        resultRecord.setNoMatches(noMatches);
        resultRecord.setElapsedMs((int) elapsedMs);

        resultService.updateById(resultRecord);

        // Delete old chunk details if recalculating
        if (resultRecord.getId() != null) {
            chunkDetailService.remove(new LambdaQueryWrapper<AttributionChunkDetail>()
                    .eq(AttributionChunkDetail::getReportId, resultRecord.getId()));
        }

        List<AttributionChunkDetail> chunkDetails = new ArrayList<>();
        for (MatchResult r : results) {
            AttributionChunkDetail detail = AttributionChunkDetail.builder()
                    .reportId(resultRecord.getId())
                    .userId(r.getChunk().getUserId())
                    .filePath(r.getChunk().getFilePath())
                    .startLine(r.getChunk().getStartLine())
                    .endLine(r.getChunk().getEndLine())
                    .totalLines(r.getChunk().getEndLine() - r.getChunk().getStartLine() + 1)
                    .analyzedLines(r.getChunk().getNonBlankLineCount())
                    .attribution(r.getAttribution())
                    .contributedLines(r.getContributedLines())
                    .matchedMessageId(r.getBestMatch() != null ? r.getBestMatch().getMessageId() : null)
                    .matchedMessageIds(r.getMatchedMessageIds().isEmpty() ? null : r.getMatchedMessageIds())
                    .score(r.getBestMatch() != null ? r.getBestMatch().getScore() : 0.0)
                    .matchType(r.getBestMatch() != null ? r.getBestMatch().getMatchType() : "NONE")
                    .level(r.getBestMatch() != null ? r.getBestMatch().getLevel() : "FAILED_ALL")
                    .build();
            chunkDetails.add(detail);
        }
        
        if (!chunkDetails.isEmpty()) {
            chunkDetailService.saveBatch(chunkDetails);
        }

        log.info("[Worker] Finished processing job for mergeId: {}. AI Contributed Lines: {}, Ratio: {}",
                jobData.getMergeId(), totalAiContributedLines, ratio);
    }

    @PreDestroy
    public void destroy() {
        isRunning = false;
        log.info("[Worker] Shutting down AttributionWorker gracefully...");
        if (executorService != null) {
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(10, TimeUnit.SECONDS)) {
                    log.warn("[Worker] ExecutorService did not terminate gracefully in 10s, forcing shutdown...");
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                log.warn("[Worker] Graceful shutdown interrupted, forcing shutdown...");
                executorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        log.info("[Worker] AttributionWorker graceful shutdown completed.");
    }

    boolean isSameFileName(String pathA, String pathB) {
        if (pathA == null || pathB == null) {
            return false;
        }
        String pA = pathA.trim().replace('\\', '/');
        String pB = pathB.trim().replace('\\', '/');
        if (pA.equalsIgnoreCase(pB)) {
            return true;
        }
        String nameA = pA.substring(pA.lastIndexOf('/') + 1);
        String nameB = pB.substring(pB.lastIndexOf('/') + 1);
        return nameA.equalsIgnoreCase(nameB);
    }

    // ── Internal helper classes ──

    /**
     * Pre-normalized AI message with LineMapping for reuse across chunks.
     */
    static class NormalizedAiMessage {
        final AiMessageDto original;
        final String normalizedContent;
        final LineMapping lineMapping;

        NormalizedAiMessage(AiMessageDto original, String normalizedContent, LineMapping lineMapping) {
            this.original = original;
            this.normalizedContent = normalizedContent;
            this.lineMapping = lineMapping;
        }
    }

    /**
     * Internal candidate match for multi-message tracking.
     */
    private static class CandidateMatch {
        final String messageId;
        final EvaluationResult result;

        CandidateMatch(String messageId, EvaluationResult result) {
            this.messageId = messageId;
            this.result = result;
        }
    }
}
