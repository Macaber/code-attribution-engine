package com.macaber.attribution.core;

import lombok.Builder;
import lombok.Data;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * SimilarityEngine — 3-layer escalation pipeline for code attribution.
 *
 * Funnel architecture:
 *   L1 Winnowing (fast)  → fast-pass / fast-fail / continue →
 *   L2 Token LCS (medium) → fast-pass / continue →
 *   L3 AST Features (heavy, conditional)
 */
public class SimilarityEngine {
    private final Normalizer normalizer;
    private final Winnowing winnowing;
    private final LCS lcs;
    private final AstFeatureEngine astEngine;
    private final PipelineConfig config;
    private final int winnowingMinLength;
    private final SimilarityWeights weights;

    private static final double THRESHOLD_STRICT = 0.95;
    private static final double THRESHOLD_FUZZY = 0.60;

    public SimilarityEngine(
            SimilarityWeights weights,
            WinnowingConfig winnowingConfig,
            LcsConfig lcsConfig,
            PipelineConfig pipelineConfig,
            AstFeatureEngine astEngine) {
        
        this.normalizer = new Normalizer();
        this.winnowing = new Winnowing(winnowingConfig);
        this.winnowingMinLength = winnowingConfig != null ? winnowingConfig.getKgramLength() : 5;
        this.lcs = new LCS(lcsConfig);
        this.astEngine = astEngine; // Can be null if L3 is disabled
        this.weights = weights != null ? weights : new SimilarityWeights();
        this.config = pipelineConfig != null ? pipelineConfig : new PipelineConfig();
    }

    @Data
    @Builder
    public static class EvaluationContext {
        private String fileContent;
        private String filePath;
        private int addedLineCount;
        private Integer chunkStartLine;
        private Integer chunkEndLine;
    }

    /**
     * Evaluate a diff chunk against AI code using the escalation pipeline.
     */
    public EvaluationResult evaluateChunk(String aiCode, String diffChunkContent, EvaluationContext options) {
        String normalizedAi = normalizer.normalizeText(aiCode);
        LineMapping chunkMapping = normalizer.normalizeWithMapping(diffChunkContent);
        String normalizedChunk = chunkMapping.getNormalizedText();

        if (normalizedAi == null || normalizedAi.isEmpty() || normalizedChunk == null || normalizedChunk.isEmpty()) {
            return EvaluationResult.builder()
                    .score(0)
                    .matchType(MatchType.NONE)
                    .level(PipelineLevel.FAILED_ALL)
                    .details(new HashMap<>())
                    .exactContributedLines(0)
                    .build();
        }

        // ── Pre-calculate Exact Traceable Line Contributions via LCS ──
        List<Integer> matchedIndices = lcs.calculateTraceableLcs(normalizedAi, normalizedChunk);
        Map<Integer, Integer> matchedCharsPerLine = new HashMap<>();
        for (int charIndex : matchedIndices) {
            int lineIndex = chunkMapping.getCharToLineMap().get(charIndex);
            matchedCharsPerLine.put(lineIndex, matchedCharsPerLine.getOrDefault(lineIndex, 0) + 1);
        }

        int exactContributedLines = 0;
        double perLineMatchThreshold = 0.70;
        for (Map.Entry<Integer, Integer> entry : chunkMapping.getLineCharCounts().entrySet()) {
            int lineIndex = entry.getKey();
            int validTotalChars = entry.getValue();
            int matched = matchedCharsPerLine.getOrDefault(lineIndex, 0);
            if (validTotalChars > 0 && ((double) matched / validTotalChars) >= perLineMatchThreshold) {
                exactContributedLines++;
            }
        }

        // Short-text bypass
        boolean isShortText = normalizedChunk.length() < winnowingMinLength || normalizedAi.length() < winnowingMinLength;
        double l1Score = 0.0;
        Map<String, Double> details = new HashMap<>();

        // ── L1: Winnowing ──
        if (!isShortText) {
            Set<Long> fpAi = winnowing.getFingerprints(normalizedAi);
            Set<Long> fpDiff = winnowing.getFingerprints(normalizedChunk);

            if (!fpDiff.isEmpty()) {
                int contained = 0;
                for (long fp : fpDiff) {
                    if (fpAi.contains(fp)) contained++;
                }
                l1Score = (double) contained / fpDiff.size();
            }
            details.put("l1WinnowingScore", l1Score);

            if (l1Score >= config.getL1().getFastPass()) {
                return EvaluationResult.builder()
                        .score(l1Score)
                        .matchType(MatchType.STRICT)
                        .level(PipelineLevel.L1)
                        .details(details)
                        .exactContributedLines(exactContributedLines)
                        .build();
            }
            if (l1Score <= config.getL1().getFastFail()) {
                return EvaluationResult.builder()
                        .score(0)
                        .matchType(MatchType.NONE)
                        .level(PipelineLevel.L1)
                        .details(details)
                        .exactContributedLines(exactContributedLines)
                        .build();
            }
        }

        // ── L2: Token LCS ──
        double l2Score = normalizedChunk.isEmpty() ? 0 : (double) matchedIndices.size() / normalizedChunk.length();
        details.put("l2LcsScore", l2Score);

        if (l2Score >= config.getL2().getFastPass()) {
            return EvaluationResult.builder()
                    .score(l2Score)
                    .matchType(MatchType.FUZZY)
                    .level(PipelineLevel.L2)
                    .details(details)
                    .exactContributedLines(exactContributedLines)
                    .build();
        }

        // ── L3: AST Feature Matching ──
        if (options == null || astEngine == null) {
            return buildFallbackResult(l1Score, l2Score, exactContributedLines, details);
        }

        if (options.getAddedLineCount() > config.getMaxLinesForL3()) {
            return buildFallbackResult(l1Score, l2Score, exactContributedLines, details);
        }

        String filePath = options.getFilePath();
        if (filePath == null || !isL3Eligible(filePath)) {
            return buildFallbackResult(l1Score, l2Score, exactContributedLines, details);
        }

        String fileContent = options.getFileContent();
        if (fileContent == null || fileContent.isEmpty()) {
            return buildFallbackResult(l1Score, l2Score, exactContributedLines, details);
        }

        try {
            AstFeatureEngine.LineRange diffLineRange = null;
            if (options.getChunkStartLine() != null && options.getChunkEndLine() != null) {
                diffLineRange = new AstFeatureEngine.LineRange(options.getChunkStartLine() - 1, options.getChunkEndLine() - 1);
            }

            Double l3Score = astEngine.compareFeatures(aiCode, fileContent, filePath, diffLineRange);

            if (l3Score == null) {
                return buildFallbackResult(l1Score, l2Score, exactContributedLines, details);
            }

            details.put("l3AstScore", l3Score);

            if (l3Score >= config.getL3().getPass()) {
                return EvaluationResult.builder()
                        .score(l3Score)
                        .matchType(MatchType.DEEP_REFACTOR)
                        .level(PipelineLevel.L3)
                        .details(details)
                        .exactContributedLines(exactContributedLines)
                        .build();
            }

        } catch (Exception e) {
            System.err.println("[SimilarityEngine] L3 AST analysis failed, falling back to L1+L2: " + e.getMessage());
            return buildFallbackResult(l1Score, l2Score, exactContributedLines, details);
        }

        return buildFallbackResult(l1Score, l2Score, exactContributedLines, details);
    }

    private EvaluationResult buildFallbackResult(double l1Score, double l2Score, int exactContributedLines, Map<String, Double> details) {
        double combinedScore = weights.getWinnowing() * l1Score + weights.getLcs() * l2Score;
        MatchType matchType = MatchType.NONE;

        if (exactContributedLines > 0) {
            matchType = MatchType.FUZZY;
        } else {
            if (combinedScore >= THRESHOLD_STRICT) matchType = MatchType.STRICT;
            else if (combinedScore >= THRESHOLD_FUZZY) matchType = MatchType.FUZZY;
        }

        return EvaluationResult.builder()
                .score(matchType == MatchType.NONE ? 0 : Math.max(combinedScore, l2Score))
                .matchType(matchType)
                .level(PipelineLevel.L2)
                .details(details)
                .exactContributedLines(exactContributedLines)
                .build();
    }

    private boolean isL3Eligible(String filePath) {
        if (filePath == null) return false;
        return filePath.endsWith(".java") || 
               filePath.endsWith(".ts") || 
               filePath.endsWith(".tsx") || 
               filePath.endsWith(".js") || 
               filePath.endsWith(".jsx");
    }
}
