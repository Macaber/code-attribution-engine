package com.macaber.attribution.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.macaber.attribution.entity.AiMessage;
import com.macaber.attribution.entity.AttributionChunkDetail;
import com.macaber.attribution.entity.AttributionResult;
import com.macaber.attribution.entity.AttributionFileDetail;
import com.macaber.attribution.service.AttributionChunkDetailService;
import com.macaber.attribution.service.AttributionResultService;
import com.macaber.attribution.service.AttributionFileDetailService;
import com.macaber.attribution.core.queue.QueueProducer;
import com.macaber.attribution.core.AttributionFilter;
import com.macaber.attribution.dto.AttributionJobData;
import com.macaber.attribution.dto.MergeFileDetail;
import com.macaber.attribution.dto.ChunkVisualizationDto;
import com.macaber.attribution.dto.MatchedMessageVisualizationDto;
import com.macaber.attribution.core.DiffChunk;
import com.macaber.attribution.core.DiffParser;
import com.macaber.attribution.core.SimilarityEngine;
import com.macaber.attribution.core.EvaluationResult;
import com.macaber.attribution.service.AiMessageService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * ReportController — REST API for querying attribution reports, summaries, and details.
 *
 * Aligned with Node.js routes/api definition in api.md
 */
@Slf4j
@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
public class ReportController {

    private final AttributionResultService resultService;
    private final AttributionChunkDetailService chunkDetailService;
    private final AttributionFileDetailService fileDetailService;
    private final QueueProducer queueProducer;
    private final AttributionFilter attributionFilter;
    private final SimilarityEngine similarityEngine;
    private final AiMessageService aiMessageService;
    private final ObjectMapper objectMapper;


    /**
     * GET /api/reports
     * Query pagination report list with filters and sort.
     */
    @GetMapping
    public ResponseEntity<?> getReports(
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "pageSize", defaultValue = "20") int pageSize,
            @RequestParam(value = "userId", required = false) String userId,
            @RequestParam(value = "repoName", required = false) String repoName,
            @RequestParam(value = "sysCode", required = false) String sysCode,
            @RequestParam(value = "startDate", required = false) String startDate,
            @RequestParam(value = "endDate", required = false) String endDate,
            @RequestParam(value = "sortBy", defaultValue = "created_at") String sortBy,
            @RequestParam(value = "sortOrder", defaultValue = "desc") String sortOrder) {
        log.info("[ReportController] getReports — page: {}, pageSize: {}, userId: {}, repoName: {}, sysCode: {}, startDate: {}, endDate: {}, sortBy: {}, sortOrder: {}",
                page, pageSize, userId, repoName, sysCode, startDate, endDate, sortBy, sortOrder);

        // Validate max page size
        if (pageSize > 100) {
            pageSize = 100;
        }

        QueryWrapper<AttributionResult> queryWrapper = new QueryWrapper<>();

        // Add filter conditions
        if (userId != null && !userId.trim().isEmpty()) {
            queryWrapper.eq("user_id", userId.trim());
        }
        if (repoName != null && !repoName.trim().isEmpty()) {
            queryWrapper.like("repo_name", repoName.trim());
        }
        if (sysCode != null && !sysCode.trim().isEmpty()) {
            queryWrapper.eq("sys_code", sysCode.trim());
        }
        if (startDate != null && !startDate.trim().isEmpty()) {
            String start = startDate.trim();
            if (start.length() == 10) {
                start += " 00:00:00";
            }
            queryWrapper.ge("created_at", start);
        }
        if (endDate != null && !endDate.trim().isEmpty()) {
            String end = endDate.trim();
            if (end.length() == 10) {
                end += " 23:59:59";
            }
            queryWrapper.le("created_at", end);
        }

        // Map sortBy parameters
        String sortColumn = "created_at";
        if ("ai_contribution_ratio".equals(sortBy) || "aiContributionRatio".equals(sortBy)) {
            sortColumn = "ai_contribution_ratio";
        } else if ("analyzed_lines".equals(sortBy) || "analyzedLines".equals(sortBy)) {
            sortColumn = "analyzed_lines";
        }

        boolean isAsc = "asc".equalsIgnoreCase(sortOrder);
        queryWrapper.orderBy(true, isAsc, sortColumn);

        Page<AttributionResult> resultPage = resultService.page(new Page<>(page, pageSize), queryWrapper);

        Map<String, Object> response = new HashMap<>();
        response.put("data", resultPage.getRecords());
        response.put("pagination", Map.of(
                "page", resultPage.getCurrent(),
                "pageSize", resultPage.getSize(),
                "total", resultPage.getTotal(),
                "totalPages", resultPage.getPages()
        ));

        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/reports/stats/summary
     * Query overall statistical metrics.
     */
    @GetMapping("/stats/summary")
    public ResponseEntity<?> getStatsSummary(
            @RequestParam(value = "userId", required = false) String userId,
            @RequestParam(value = "repoName", required = false) String repoName,
            @RequestParam(value = "sysCode", required = false) String sysCode,
            @RequestParam(value = "startDate", required = false) String startDate,
            @RequestParam(value = "endDate", required = false) String endDate) {
        if (startDate == null || startDate.trim().isEmpty()) {
            startDate = java.time.LocalDate.now().minusMonths(1).toString();
        }

        QueryWrapper<AttributionResult> queryWrapper = new QueryWrapper<>();

        if (userId != null && !userId.trim().isEmpty()) {
            queryWrapper.eq("user_id", userId.trim());
        }
        if (repoName != null && !repoName.trim().isEmpty()) {
            queryWrapper.like("repo_name", repoName.trim());
        }
        if (sysCode != null && !sysCode.trim().isEmpty()) {
            queryWrapper.eq("sys_code", sysCode.trim());
        }
        if (startDate != null && !startDate.trim().isEmpty()) {
            String start = startDate.trim();
            if (start.length() == 10) {
                start += " 00:00:00";
            }
            queryWrapper.ge("created_at", start);
        }
        if (endDate != null && !endDate.trim().isEmpty()) {
            String end = endDate.trim();
            if (end.length() == 10) {
                end += " 23:59:59";
            }
            queryWrapper.le("created_at", end);
        }

        queryWrapper.select(
                "COUNT(*) as totalReports",
                "SUM(analyzed_lines) as totalAnalyzedLines",
                "SUM(ai_contributed_lines) as totalAiContributedLines",
                "AVG(ai_contribution_ratio) as avgAiContributionRatio",
                "SUM(strict_matches) as strictMatches",
                "SUM(fuzzy_matches) as fuzzyMatches",
                "SUM(deep_refactor_matches) as deepRefactorMatches",
                "SUM(no_matches) as noMatches"
        );

        Map<String, Object> map = resultService.getMap(queryWrapper);

        long totalReports = 0;
        long totalAnalyzedLines = 0;
        double totalAiContributedLines = 0.0;
        double avgAiContributionRatio = 0.0;
        long strict = 0;
        long fuzzy = 0;
        long deepRefactor = 0;
        long none = 0;

        if (map != null) {
            totalReports = map.get("totalReports") != null ? ((Number) map.get("totalReports")).longValue() : 0;
            totalAnalyzedLines = map.get("totalAnalyzedLines") != null ? ((Number) map.get("totalAnalyzedLines")).longValue() : 0;
            totalAiContributedLines = map.get("totalAiContributedLines") != null ? ((Number) map.get("totalAiContributedLines")).doubleValue() : 0.0;
            avgAiContributionRatio = map.get("avgAiContributionRatio") != null ? ((Number) map.get("avgAiContributionRatio")).doubleValue() : 0.0;
            strict = map.get("strictMatches") != null ? ((Number) map.get("strictMatches")).longValue() : 0;
            fuzzy = map.get("fuzzyMatches") != null ? ((Number) map.get("fuzzyMatches")).longValue() : 0;
            deepRefactor = map.get("deepRefactorMatches") != null ? ((Number) map.get("deepRefactorMatches")).longValue() : 0;
            none = map.get("noMatches") != null ? ((Number) map.get("noMatches")).longValue() : 0;
        }

        // Round values
        totalAiContributedLines = Math.round(totalAiContributedLines * 100.0) / 100.0;
        avgAiContributionRatio = Math.round(avgAiContributionRatio * 10000.0) / 10000.0;

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("totalReports", totalReports);
        response.put("totalAnalyzedLines", totalAnalyzedLines);
        response.put("totalAiContributedLines", totalAiContributedLines);
        response.put("avgAiContributionRatio", avgAiContributionRatio);
        response.put("matchDistribution", Map.of(
                "strict", strict,
                "fuzzy", fuzzy,
                "deepRefactor", deepRefactor,
                "none", none
        ));

        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/reports/{mergeId}
     * Query single report detail including chunk details and message breakdowns.
     */
    @GetMapping("/{id}")
    public ResponseEntity<?> getReportById(@PathVariable("id") Long id) {
        log.info("[ReportController] getReportById — id: {}", id);
        if (id == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "id is required"));
        }

        AttributionResult report = resultService.getById(id);

        if (report == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Report not found for id: " + id));
        }

        List<AttributionChunkDetail> chunkDetails = chunkDetailService.list(
                new LambdaQueryWrapper<AttributionChunkDetail>()
                        .eq(AttributionChunkDetail::getReportId, report.getId())
        );

        // Calculate message breakdown (distribute contribution lines equally among participating message IDs)
        Map<String, MessageBreakdownBuilder> breakdownMap = new LinkedHashMap<>();
        for (AttributionChunkDetail detail : chunkDetails) {
            String matchedIdsStr = detail.getMatchedMessageIds();
            if (matchedIdsStr == null || matchedIdsStr.trim().isEmpty()) {
                continue;
            }

            String[] messageIds = matchedIdsStr.split(",");
            int n = messageIds.length;
            if (n == 0) continue;

            double shareLines = detail.getContributedLines() != null ? detail.getContributedLines() / n : 0.0;

            for (String messageId : messageIds) {
                messageId = messageId.trim();
                if (messageId.isEmpty()) continue;

                MessageBreakdownBuilder builder = breakdownMap.computeIfAbsent(messageId, k -> new MessageBreakdownBuilder(k));
                builder.addContribution(shareLines, detail.getAttribution());
            }
        }

        List<Map<String, Object>> messageBreakdown = breakdownMap.values().stream()
                .map(MessageBreakdownBuilder::build)
                .collect(Collectors.toList());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("report", report);
        response.put("chunkDetails", chunkDetails);
        response.put("messageBreakdown", messageBreakdown);

        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/reports/{mergeId}/files
     * Query original files (path, code, diff) associated with a report.
     */
    @GetMapping("/{id}/files")
    public ResponseEntity<?> getReportFiles(@PathVariable("id") Long id) {
        log.info("[ReportController] getReportFiles — id: {}", id);
        if (id == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "id is required"));
        }

        AttributionResult report = resultService.getById(id);
        if (report == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Report not found for id: " + id));
        }

        List<AttributionFileDetail> files = fileDetailService.list(
                new LambdaQueryWrapper<AttributionFileDetail>()
                        .eq(AttributionFileDetail::getReportId, report.getId())
        );

        return ResponseEntity.ok(files);
    }

    /**
     * POST /api/reports/{id}/recalculate
     * Recalculate AI generated ratio based on reportId.
     */
    @PostMapping("/{id}/recalculate")
    public ResponseEntity<?> recalculateReport(
            @PathVariable("id") Long id,
            @RequestParam(value = "timeframeDays", required = false) Integer timeframeDays) {
        log.info("[ReportController] recalculateReport — id: {}, timeframeDays: {}", id, timeframeDays);
        AttributionResult report = resultService.getById(id);
        if (report == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Report not found for id: " + id));
        }

        List<AttributionFileDetail> fileDetails = fileDetailService.list(
                new LambdaQueryWrapper<AttributionFileDetail>()
                        .eq(AttributionFileDetail::getReportId, id)
        );

        List<MergeFileDetail> mergeFileDetails = fileDetails.stream()
                .map(f -> {
                    MergeFileDetail mergeDetail = new MergeFileDetail();
                    mergeDetail.setPath(f.getFilePath());
                    mergeDetail.setCode(f.getCode());
                    mergeDetail.setDiff(f.getDiff());
                    return mergeDetail;
                })
                .filter(f -> f.getDiff() != null && !f.getDiff().trim().isEmpty())
                .filter(f -> !attributionFilter.shouldFilter(f))
                .collect(Collectors.toList());

        if (mergeFileDetails.isEmpty()) {
            return ResponseEntity.ok(Map.of(
                    "status", "skipped",
                    "reportId", id,
                    "message", "No file diffs to analyze"));
        }

        AttributionJobData jobData = AttributionJobData.builder()
                .mergeId(report.getMergeId())
                .reportId(report.getId())
                .repoName(report.getRepoName())
                .userId(report.getUserId())
                .sysCode(report.getSysCode())
                .title(report.getTitle())
                .source(report.getSource())
                .target(report.getTarget())
                .fileDetails(mergeFileDetails)
                .timeframeDays(timeframeDays)
                .build();

        try {
            queueProducer.addJob(jobData);
        } catch (RuntimeException e) {
            log.error("[ReportController] Failed to enqueue recalculation for reportId: {}", id, e);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "status", "error",
                    "reportId", id,
                    "error", "Failed to queue recalculation job",
                    "message", e.getMessage() != null ? e.getMessage() : "queue unavailable"));
        }

        return ResponseEntity.accepted().body(Map.of(
                "status", "accepted",
                "reportId", id,
                "message", "Recalculation job queued"
        ));
    }

    /**
     * GET /api/reports/{mergeId}/visualization
     * Query detailed chunk-level trace for visualization.
     */
    @GetMapping("/{id}/visualization")
    public ResponseEntity<?> getReportVisualization(@PathVariable("id") Long id) {
        log.info("[ReportController] getReportVisualization — id: {}", id);
        if (id == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "id is required"));
        }

        AttributionResult report = resultService.getById(id);
        if (report == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Report not found for id: " + id));
        }

        List<AttributionChunkDetail> chunkDetails = chunkDetailService.list(
                new LambdaQueryWrapper<AttributionChunkDetail>()
                        .eq(AttributionChunkDetail::getReportId, report.getId())
        );

        List<AttributionFileDetail> fileDetails = fileDetailService.list(
                new LambdaQueryWrapper<AttributionFileDetail>()
                        .eq(AttributionFileDetail::getReportId, report.getId())
        );

        DiffParser diffParser = new DiffParser();
        List<ChunkVisualizationDto> vizDetails = new ArrayList<>();

        for (AttributionFileDetail file : fileDetails) {
            if (file.getDiff() == null || file.getDiff().trim().isEmpty()) {
                continue;
            }

            List<DiffChunk> chunks = diffParser.parse(file.getDiff());
            int fileAddedLineCount = chunks.stream()
                    .mapToInt(c -> c.getEndLine() - c.getStartLine() + 1)
                    .sum();

            for (DiffChunk chunk : chunks) {
                String explicitPath = file.getFilePath() != null ? file.getFilePath() : chunk.getFilePath();

                // Find corresponding AttributionChunkDetail
                AttributionChunkDetail detail = chunkDetails.stream()
                        .filter(d -> d.getFilePath().equals(explicitPath)
                                && d.getStartLine().equals(chunk.getStartLine())
                                && d.getEndLine().equals(chunk.getEndLine()))
                        .findFirst()
                        .orElse(null);

                List<MatchedMessageVisualizationDto> matchedMessages = new ArrayList<>();
                Set<Integer> overallContributedLines = new HashSet<>();

                String matchedIdsStr = detail != null ? detail.getMatchedMessageIds() : null;
                if (matchedIdsStr != null && !matchedIdsStr.trim().isEmpty()) {
                    String[] messageIds = matchedIdsStr.split(",");
                    List<Long> ids = new ArrayList<>();
                    for (String idStr : messageIds) {
                        try {
                            ids.add(Long.parseLong(idStr.trim()));
                        } catch (NumberFormatException e) {
                            // ignore
                        }
                    }

                    if (!ids.isEmpty()) {
                        List<AiMessage> aiMessages = aiMessageService.listByIds(ids);
                        for (AiMessage aiMsg : aiMessages) {
                            String rawContent = "";
                            try {
                                Map<String, Object> args = objectMapper.readValue(aiMsg.getFunctionArguments(),
                                        new TypeReference<Map<String, Object>>() {});
                                if ("edit".equals(aiMsg.getFunctionName()) && args.containsKey("newString")) {
                                    rawContent = (String) args.get("newString");
                                } else if ("write".equals(aiMsg.getFunctionName()) && args.containsKey("content")) {
                                    rawContent = (String) args.get("content");
                                }
                            } catch (Exception e) {
                                log.warn("[ReportController] Failed to parse arguments for ai message {}", aiMsg.getId());
                            }

                            if (rawContent != null && !rawContent.trim().isEmpty()) {
                                SimilarityEngine.EvaluationContext ctx = SimilarityEngine.EvaluationContext.builder()
                                        .fileContent(file.getCode())
                                        .filePath(explicitPath)
                                        .addedLineCount(fileAddedLineCount)
                                        .chunkStartLine(chunk.getStartLine())
                                        .chunkEndLine(chunk.getEndLine())
                                        .build();

                                EvaluationResult evalResult = similarityEngine.evaluateChunk(
                                        rawContent, chunk.getContent(), ctx
                                );

                                Set<Integer> indices = evalResult.getContributedLineIndices();
                                if (indices == null) {
                                    indices = new HashSet<>();
                                }

                                overallContributedLines.addAll(indices);

                                matchedMessages.add(MatchedMessageVisualizationDto.builder()
                                        .messageId(String.valueOf(aiMsg.getId()))
                                        .rawContent(rawContent)
                                        .fileName(aiMsg.getFileName())
                                        .timestamp(aiMsg.getCreatedAt())
                                        .score(evalResult.getScore())
                                        .matchType(evalResult.getMatchType().name())
                                        .contributedLineIndices(indices)
                                        .lineMatches(evalResult.getLineMatches())
                                        .build());
                            }
                        }
                    }
                }

                // Sort matched messages by score descending
                matchedMessages.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));

                vizDetails.add(ChunkVisualizationDto.builder()
                        .filePath(explicitPath)
                        .startLine(chunk.getStartLine())
                        .endLine(chunk.getEndLine())
                        .userId(chunk.getUserId())
                        .attribution(detail != null ? detail.getAttribution() : "none")
                        .score(detail != null && detail.getScore() != null ? detail.getScore() : 0.0)
                        .matchType(detail != null && detail.getMatchType() != null ? detail.getMatchType() : "NONE")
                        .level(detail != null && detail.getLevel() != null ? detail.getLevel() : "FAILED_ALL")
                        .chunkContent(chunk.getContent())
                        .contributedLineIndices(overallContributedLines)
                        .matchedMessages(matchedMessages)
                        .build());
            }
        }

        return ResponseEntity.ok(vizDetails);
    }

    /**
     * GET /api/reports/stats/breakdown
     * Query aggregated stats grouped by sys_code, repo_name, or developer.
     */
    @GetMapping("/stats/breakdown")
    public ResponseEntity<?> getStatsBreakdown(
            @RequestParam("groupBy") String groupBy,
            @RequestParam(value = "startDate", required = false) String startDate,
            @RequestParam(value = "endDate", required = false) String endDate) {
        log.info("[ReportController] getStatsBreakdown — groupBy: {}, startDate: {}, endDate: {}", groupBy, startDate, endDate);

        if (groupBy == null || groupBy.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "groupBy is required"));
        }

        if (startDate == null || startDate.trim().isEmpty()) {
            startDate = java.time.LocalDate.now().minusMonths(1).toString();
        }

        String target = groupBy.trim().toLowerCase();

        if ("sys-code".equals(target) || "syscode".equals(target)) {
            QueryWrapper<AttributionResult> sysWrapper = new QueryWrapper<>();
            sysWrapper.select("sys_code as sysCode", "SUM(analyzed_lines) as totalAnalyzedLines", "SUM(ai_contributed_lines) as totalAiContributedLines")
                    .groupBy("sys_code");
            applyDateFilters(sysWrapper, startDate, endDate);

            List<Map<String, Object>> sysList = resultService.listMaps(sysWrapper);
            List<Map<String, Object>> sysBreakdown = new ArrayList<>();
            for (Map<String, Object> map : sysList) {
                if (map == null) continue;
                String sysCode = map.get("sysCode") != null ? map.get("sysCode").toString() : "未知系统";
                long analyzed = map.get("totalAnalyzedLines") != null ? ((Number) map.get("totalAnalyzedLines")).longValue() : 0;
                double aiContributed = map.get("totalAiContributedLines") != null ? ((Number) map.get("totalAiContributedLines")).doubleValue() : 0.0;
                double ratio = analyzed > 0 ? aiContributed / analyzed : 0.0;
                sysBreakdown.add(Map.of(
                        "name", sysCode,
                        "analyzedLines", analyzed,
                        "aiContributedLines", Math.round(aiContributed * 100.0) / 100.0,
                        "aiRatio", Math.round(ratio * 10000.0) / 10000.0
                ));
            }
            sysBreakdown.sort((a, b) -> Double.compare((Double) b.get("aiContributedLines"), (Double) a.get("aiContributedLines")));
            List<Map<String, Object>> resultList = sysBreakdown.size() > 50 ? sysBreakdown.subList(0, 50) : sysBreakdown;
            return ResponseEntity.ok(resultList);

        } else if ("repo-name".equals(target) || "reponame".equals(target)) {
            QueryWrapper<AttributionResult> repoWrapper = new QueryWrapper<>();
            repoWrapper.select("sys_code as sysCode", "repo_name as repoName", "SUM(analyzed_lines) as totalAnalyzedLines", "SUM(ai_contributed_lines) as totalAiContributedLines")
                    .groupBy("sys_code", "repo_name");
            applyDateFilters(repoWrapper, startDate, endDate);
 
            List<Map<String, Object>> repoList = resultService.listMaps(repoWrapper);
            List<Map<String, Object>> repoBreakdown = new ArrayList<>();
            for (Map<String, Object> map : repoList) {
                if (map == null) continue;
                String sysCode = map.get("sysCode") != null ? map.get("sysCode").toString().trim() : "";
                String repoName = map.get("repoName") != null ? map.get("repoName").toString().trim() : "未知仓库";
                String displayName = sysCode.isEmpty() ? repoName : sysCode + "/" + repoName;

                long analyzed = map.get("totalAnalyzedLines") != null ? ((Number) map.get("totalAnalyzedLines")).longValue() : 0;
                double aiContributed = map.get("totalAiContributedLines") != null ? ((Number) map.get("totalAiContributedLines")).doubleValue() : 0.0;
                double ratio = analyzed > 0 ? aiContributed / analyzed : 0.0;
                repoBreakdown.add(Map.of(
                        "name", displayName,
                        "analyzedLines", analyzed,
                        "aiContributedLines", Math.round(aiContributed * 100.0) / 100.0,
                        "aiRatio", Math.round(ratio * 10000.0) / 10000.0
                ));
            }
            repoBreakdown.sort((a, b) -> Double.compare((Double) b.get("aiContributedLines"), (Double) a.get("aiContributedLines")));
            List<Map<String, Object>> resultList = repoBreakdown.size() > 50 ? repoBreakdown.subList(0, 50) : repoBreakdown;
            return ResponseEntity.ok(resultList);

        } else if ("developer".equals(target) || "user-id".equals(target) || "userid".equals(target)) {
            List<Map<String, Object>> authorBreakdown = new ArrayList<>();
            QueryWrapper<AttributionChunkDetail> chunkWrapper = new QueryWrapper<>();

            boolean hasDateFilter = (startDate != null && !startDate.trim().isEmpty())
                    || (endDate != null && !endDate.trim().isEmpty());

            if (hasDateFilter) {
                QueryWrapper<AttributionResult> reportWrapper = new QueryWrapper<>();
                reportWrapper.select("id");
                applyDateFilters(reportWrapper, startDate, endDate);
                List<Object> reportIds = resultService.listObjs(reportWrapper);
                if (reportIds.isEmpty()) {
                    return ResponseEntity.ok(authorBreakdown);
                }
                chunkWrapper.in("report_id", reportIds);
            }

            chunkWrapper.select("user_id as userId", "SUM(analyzed_lines) as totalAnalyzedLines", "SUM(contributed_lines) as totalAiContributedLines")
                    .groupBy("user_id");
            List<Map<String, Object>> chunkList = chunkDetailService.listMaps(chunkWrapper);
            for (Map<String, Object> map : chunkList) {
                if (map == null) continue;
                String userId = map.get("userId") != null ? map.get("userId").toString() : "未知作者";
                long analyzed = map.get("totalAnalyzedLines") != null ? ((Number) map.get("totalAnalyzedLines")).longValue() : 0;
                double aiContributed = map.get("totalAiContributedLines") != null ? ((Number) map.get("totalAiContributedLines")).doubleValue() : 0.0;
                double ratio = analyzed > 0 ? aiContributed / analyzed : 0.0;
                authorBreakdown.add(Map.of(
                        "name", userId,
                        "analyzedLines", analyzed,
                        "aiContributedLines", Math.round(aiContributed * 100.0) / 100.0,
                        "aiRatio", Math.round(ratio * 10000.0) / 10000.0
                ));
            }
            authorBreakdown.sort((a, b) -> Double.compare((Double) b.get("aiContributedLines"), (Double) a.get("aiContributedLines")));
            List<Map<String, Object>> resultList = authorBreakdown.size() > 50 ? authorBreakdown.subList(0, 50) : authorBreakdown;
            return ResponseEntity.ok(resultList);
        } else {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid groupBy parameter. Must be sys-code, repo-name, or developer."));
        }
    }

    private void applyDateFilters(QueryWrapper<?> queryWrapper, String startDate, String endDate) {
        if (startDate != null && !startDate.trim().isEmpty()) {
            String start = startDate.trim();
            if (start.length() == 10) {
                start += " 00:00:00";
            }
            queryWrapper.ge("created_at", start);
        }
        if (endDate != null && !endDate.trim().isEmpty()) {
            String end = endDate.trim();
            if (end.length() == 10) {
                end += " 23:59:59";
            }
            queryWrapper.le("created_at", end);
        }
    }

    /**
     * Inner helper class for calculating message breakdowns.
     */
    private static class MessageBreakdownBuilder {
        private final String messageId;
        private double contributedLines = 0.0;
        private int chunkCount = 0;
        private final Set<String> matchTypes = new LinkedHashSet<>();

        public MessageBreakdownBuilder(String messageId) {
            this.messageId = messageId;
        }

        public void addContribution(double lines, String type) {
            this.contributedLines += lines;
            this.chunkCount += 1;
            if (type != null && !type.trim().isEmpty()) {
                this.matchTypes.add(type.trim());
            }
        }

        public Map<String, Object> build() {
            return Map.of(
                    "messageId", messageId,
                    "contributedLines", Math.round(contributedLines * 100.0) / 100.0,
                    "chunkCount", chunkCount,
                    "matchTypes", matchTypes
            );
        }
    }
}
