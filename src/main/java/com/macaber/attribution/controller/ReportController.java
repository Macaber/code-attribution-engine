package com.macaber.attribution.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.macaber.attribution.entity.AttributionChunkDetail;
import com.macaber.attribution.entity.AttributionResult;
import com.macaber.attribution.service.AttributionChunkDetailService;
import com.macaber.attribution.service.AttributionResultService;
import lombok.RequiredArgsConstructor;
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
@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
public class ReportController {

    private final AttributionResultService resultService;
    private final AttributionChunkDetailService chunkDetailService;

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
    @GetMapping("/{mergeId}")
    public ResponseEntity<?> getReportByMergeId(@PathVariable("mergeId") String mergeId) {
        if (mergeId == null || mergeId.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "mergeId is required"));
        }

        AttributionResult report = resultService.getOne(
                new LambdaQueryWrapper<AttributionResult>()
                        .eq(AttributionResult::getMergeId, mergeId)
        );

        if (report == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Report not found for mergeId: " + mergeId));
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
