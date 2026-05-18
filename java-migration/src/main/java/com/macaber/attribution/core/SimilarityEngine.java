package com.macaber.attribution.core;

import lombok.Builder;
import lombok.Data;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * SimilarityEngine — 3-layer escalation pipeline for code attribution.
 *
 * Funnel architecture:
 *   L1 Winnowing (fast)  → fast-pass / fast-fail / continue →
 *   L2 Line LCS (medium) → fast-pass / continue →
 *   L3 AST Features (heavy, conditional)
 *
 * Aligned with TS: src/domains/attribution/similarity-engine.ts
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
        /** Pre-calculated normalized AI text (for Winnowing) */
        private String normalizedAi;
        /** Pre-calculated line mapping for AI code */
        private LineMapping aiLineMapping;
        /** Pre-calculated line mapping for diff chunk */
        private LineMapping chunkLineMapping;
    }

    /**
     * Evaluate a diff chunk against AI code using the escalation pipeline.
     *
     * Each layer can short-circuit with a fast-pass (high confidence match)
     * or fast-fail (clearly no match). Only ambiguous cases escalate.
     *
     * Aligned with TS: evaluateChunk() in similarity-engine.ts
     *
     * @param aiCode           Raw AI-generated code
     * @param diffChunkContent Raw diff chunk content (added lines)
     * @param options          Optional context for L3 analysis and pre-calculated mappings
     * @return EvaluationResult with score, matchType, level, and per-layer details
     */
    public EvaluationResult evaluateChunk(String aiCode, String diffChunkContent, EvaluationContext options) {
        // Use pre-calculated mappings if available, otherwise calculate
        LineMapping aiLineMapping = (options != null && options.getAiLineMapping() != null)
                ? options.getAiLineMapping()
                : normalizer.normalizeToLines(aiCode);
        String normalizedAi = (options != null && options.getNormalizedAi() != null)
                ? options.getNormalizedAi()
                : aiLineMapping.getNormalizedText();
        LineMapping chunkLineMapping = (options != null && options.getChunkLineMapping() != null)
                ? options.getChunkLineMapping()
                : normalizer.normalizeToLines(diffChunkContent);
        String normalizedChunk = chunkLineMapping.getNormalizedText();

        if (normalizedAi == null || normalizedAi.isEmpty() || normalizedChunk == null || normalizedChunk.isEmpty()) {
            return EvaluationResult.builder()
                    .score(0)
                    .matchType(MatchType.NONE)
                    .level(PipelineLevel.FAILED_ALL)
                    .details(new HashMap<>())
                    .exactContributedLines(0)
                    .contributedLineIndices(new HashSet<>())
                    .build();
        }

        // ── Line-level LCS: each normalized line is an atomic comparison unit ──
        // This eliminates cross-line token leakage and the need for per-line thresholds.
        List<Integer> matchedNormalizedIndices = lcs.calculateTraceableLcsLines(
                aiLineMapping.getNormalizedLines(), chunkLineMapping.getNormalizedLines()
        );

        // Map matched normalizedLines indices back to original 0-indexed line numbers
        int exactContributedLines = matchedNormalizedIndices.size();
        Set<Integer> contributedLineIndices = new HashSet<>();
        for (int idx : matchedNormalizedIndices) {
            contributedLineIndices.add(chunkLineMapping.getOriginalLineIndices().get(idx));
        }

        // ═════════════════════════════════════════════════════
        // Short-text bypass: when either text is shorter than k-gram minimum length,
        // Winnowing cannot produce valid fingerprints, skip directly to L2 LCS.
        // Typical scenario: AI only generated 1-2 lines of code (e.g., "return true;")
        // ═════════════════════════════════════════════════════
        boolean isShortText =
                normalizedChunk.length() < winnowingMinLength ||
                normalizedAi.length() < winnowingMinLength;

        // ═════════════════════════════════════════════════════
        // L1: Winnowing — Document fingerprint (ultra fast)
        // Uses Containment rather than Jaccard: |fpDiff ∩ fpAI| / |fpDiff|
        // "How many of Diff's fingerprints can be found in AI code?"
        // ═════════════════════════════════════════════════════
        double l1Score = 0.0;
        Map<String, Double> details = new HashMap<>();

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

            if (l1Score >= config.getL1().getFastPass()) {
                details.put("l1WinnowingScore", l1Score);
                return EvaluationResult.builder()
                        .score(l1Score)
                        .matchType(MatchType.STRICT)
                        .level(PipelineLevel.L1)
                        .details(details)
                        .exactContributedLines(exactContributedLines)
                        .contributedLineIndices(contributedLineIndices)
                        .build();
            }
            if (l1Score <= config.getL1().getFastFail()) {
                details.put("l1WinnowingScore", l1Score);
                return EvaluationResult.builder()
                        .score(0)
                        .matchType(MatchType.NONE)
                        .level(PipelineLevel.L1)
                        .details(details)
                        .exactContributedLines(exactContributedLines)
                        .contributedLineIndices(contributedLineIndices)
                        .build();
            }
        }

        // ═════════════════════════════════════════════════════
        // L2: Line LCS — matched lines / total non-blank lines in the chunk
        // ═════════════════════════════════════════════════════
        double l2Score = chunkLineMapping.getNonBlankLineCount() > 0
                ? (double) matchedNormalizedIndices.size() / chunkLineMapping.getNonBlankLineCount()
                : 0;
        details.put("l1WinnowingScore", l1Score);
        details.put("l2LcsScore", l2Score);

        if (l2Score >= config.getL2().getFastPass()) {
            return EvaluationResult.builder()
                    .score(l2Score)
                    .matchType(MatchType.FUZZY)
                    .level(PipelineLevel.L2)
                    .details(details)
                    .exactContributedLines(exactContributedLines)
                    .contributedLineIndices(contributedLineIndices)
                    .build();
        }

        // We intentionally removed L2 fastFail here. Even if L2 score is < 0.30,
        // it might still contain exactContributedLines > 0. We let it escalate to L3,
        // and if L3 fails, our buildFallbackResult will secure the matched lines.

        // ═════════════════════════════════════════════════════
        // L3: AST Feature Matching — structural feature comparison (heavy, only triggered for ambiguous cases)
        // ═════════════════════════════════════════════════════

        // Circuit breaker: skip L3 if too many added lines
        int addedLines = options != null ? options.getAddedLineCount() : 0;
        if (addedLines > config.getMaxLinesForL3()) {
            return buildFallbackResult(l1Score, l2Score, exactContributedLines, contributedLineIndices, details);
        }

        // Language check: skip L3 for non-parseable files
        String filePath = options != null ? options.getFilePath() : null;
        if (filePath == null || !isL3Eligible(filePath)) {
            return buildFallbackResult(l1Score, l2Score, exactContributedLines, contributedLineIndices, details);
        }

        // Need full file content for proper AST parsing
        String fileContent = options != null ? options.getFileContent() : null;
        if (fileContent == null || fileContent.isEmpty()) {
            return buildFallbackResult(l1Score, l2Score, exactContributedLines, contributedLineIndices, details);
        }

        // Guard: skip L3 if no AST engine available
        if (astEngine == null) {
            return buildFallbackResult(l1Score, l2Score, exactContributedLines, contributedLineIndices, details);
        }

        // Run L3 AST comparison
        try {
            AstFeatureEngine.LineRange diffLineRange = null;
            if (options.getChunkStartLine() != null && options.getChunkEndLine() != null) {
                diffLineRange = new AstFeatureEngine.LineRange(
                        options.getChunkStartLine() - 1,
                        options.getChunkEndLine() - 1
                );
            }

            Double l3Score = astEngine.compareFeatures(aiCode, fileContent, filePath, diffLineRange);

            if (l3Score == null) {
                return buildFallbackResult(l1Score, l2Score, exactContributedLines, contributedLineIndices, details);
            }

            details.put("l3AstScore", l3Score);

            if (l3Score >= config.getL3().getPass()) {
                return EvaluationResult.builder()
                        .score(l3Score)
                        .matchType(MatchType.DEEP_REFACTOR)
                        .level(PipelineLevel.L3)
                        .details(details)
                        .exactContributedLines(exactContributedLines)
                        .contributedLineIndices(contributedLineIndices)
                        .build();
            }

        } catch (Exception e) {
            System.err.println("[SimilarityEngine] L3 AST analysis failed, falling back to L1+L2: " + e.getMessage());
            return buildFallbackResult(l1Score, l2Score, exactContributedLines, contributedLineIndices, details);
        }

        // All layers evaluated. Did we find any structural match?
        return buildFallbackResult(l1Score, l2Score, exactContributedLines, contributedLineIndices, details);
    }

    /**
     * Build a fallback result using L1+L2 scores and exact line tracking when L3 is skipped or fails.
     *
     * Aligned with TS: buildFallbackResult() in similarity-engine.ts
     */
    private EvaluationResult buildFallbackResult(
            double l1Score,
            double l2Score,
            int exactContributedLines,
            Set<Integer> contributedLineIndices,
            Map<String, Double> details) {

        double combinedScore = weights.getWinnowing() * l1Score + weights.getLcs() * l2Score;
        MatchType matchType = MatchType.NONE;

        // ── Ground Truth Floor ──
        // Any precise copied lines (verified at line-level LCS) act as
        // definitive evidence of AI contribution, regardless of overall chunk score.
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
                .contributedLineIndices(contributedLineIndices)
                .build();
    }

    /**
     * Map pipeline MatchType to attribution string.
     * Aligned with TS: matchTypeToAttribution() in similarity-engine.ts
     */
    public static String matchTypeToAttribution(MatchType matchType) {
        if (matchType == null) return "none";
        switch (matchType) {
            case STRICT: return "strict";
            case FUZZY: return "fuzzy";
            case DEEP_REFACTOR: return "deep_refactor";
            case NONE: return "none";
            default: return "none";
        }
    }

    /**
     * Legacy evaluate method for backward compatibility with existing tests.
     * @deprecated Use evaluateChunk() for pipeline evaluation.
     */
    @Deprecated
    public LegacyResult evaluate(String aiCode, String commitCode) {
        String normalizedAi = normalizer.normalizeText(aiCode);
        String normalizedCommit = normalizer.normalizeText(commitCode);

        if (normalizedAi == null || normalizedAi.isEmpty() || normalizedCommit == null || normalizedCommit.isEmpty()) {
            return new LegacyResult(0, 0, 0);
        }

        double winnowingScore = winnowing.calculateScore(normalizedAi, normalizedCommit);
        double lcsScore = lcs.calculateScore(normalizedAi, normalizedCommit);
        double combinedScore = weights.getWinnowing() * winnowingScore + weights.getLcs() * lcsScore;

        return new LegacyResult(winnowingScore, lcsScore, combinedScore);
    }

    @Data
    public static class LegacyResult {
        private final double winnowingScore;
        private final double lcsScore;
        private final double combinedScore;

        public LegacyResult(double winnowingScore, double lcsScore, double combinedScore) {
            this.winnowingScore = winnowingScore;
            this.lcsScore = lcsScore;
            this.combinedScore = combinedScore;
        }
    }

    /**
     * Classify a combined score into an attribution category.
     * @deprecated Use matchType from EvaluationResult instead.
     */
    @Deprecated
    public static String classify(double combinedScore) {
        if (combinedScore >= THRESHOLD_STRICT) return "strict";
        if (combinedScore >= THRESHOLD_FUZZY) return "fuzzy";
        return "none";
    }

    private boolean isL3Eligible(String filePath) {
        if (filePath == null) return false;
        return filePath.endsWith(".java") ||
               filePath.endsWith(".ts") ||
               filePath.endsWith(".tsx") ||
               filePath.endsWith(".js") ||
               filePath.endsWith(".jsx");
    }

    /**
     * Expose normalizer for worker pre-normalization.
     */
    public Normalizer getNormalizer() {
        return normalizer;
    }
}
