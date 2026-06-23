package com.macaber.attribution.core;

import lombok.Builder;
import lombok.Data;
import lombok.Getter;

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
    /**
     * -- GETTER --
     *  Expose normalizer for worker pre-normalization.
     */
    @Getter
    private final Normalizer normalizer;
    private final Winnowing winnowing;
    private final LCS lcs;
    private final PipelineConfig config;
    private final int winnowingMinLength;
    private final SimilarityWeights weights;

    private static final double THRESHOLD_STRICT = 0.95;
    private static final double THRESHOLD_FUZZY = 0.60;

    public SimilarityEngine(
            SimilarityWeights weights,
            WinnowingConfig winnowingConfig,
            LcsConfig lcsConfig,
            PipelineConfig pipelineConfig) {

        this.normalizer = new Normalizer();
        this.winnowing = new Winnowing(winnowingConfig);
        this.winnowingMinLength = winnowingConfig != null ? winnowingConfig.getKgramLength() : 5;
        this.lcs = new LCS(lcsConfig);
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
        List<LCS.LcsLineMatch> matches = lcs.calculateTraceableLcsLineMatches(
                aiLineMapping.getNormalizedLines(), chunkLineMapping.getNormalizedLines()
        );

        // Map matched normalizedLines indices back to original 0-indexed line numbers
        int exactContributedLines = matches.size();
        Set<Integer> contributedLineIndices = new HashSet<>();
        List<Map<String, Integer>> lineMatches = new java.util.ArrayList<>();
        for (LCS.LcsLineMatch match : matches) {
            int origTgt = chunkLineMapping.getOriginalLineIndices().get(match.getTgtIndex());
            int origRef = aiLineMapping.getOriginalLineIndices().get(match.getRefIndex());
            contributedLineIndices.add(origTgt);
            lineMatches.add(Map.of("chunkLineIdx", origTgt, "aiLineIdx", origRef));
        }

        // Trivial line filter: when chunk analyzed lines > 1 and match is exactly 1 line
        // and that line content is in the configured trivial list, classify as non-match (NONE).
        if (config.getL2().isFilterTrivialEnabled()
                && chunkLineMapping.getNormalizedLines().size() > 1
                && exactContributedLines == 1) {
            int matchedIdx = matches.get(0).getTgtIndex();
            String matchedLineContent = chunkLineMapping.getNormalizedLines().get(matchedIdx);
            if (config.getL2().getNormalizedTrivialLines().contains(matchedLineContent)) {
                Map<String, Double> details = new HashMap<>();
                details.put("l1WinnowingScore", 0.0);
                details.put("l2LcsScore", 0.0);
                details.put("trivialFiltered", 1.0);
                return EvaluationResult.builder()
                        .score(0)
                        .matchType(MatchType.NONE)
                        .level(PipelineLevel.L2)
                        .details(details)
                        .exactContributedLines(0)
                        .contributedLineIndices(new HashSet<>())
                        .build();
            }
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
                        .lineMatches(lineMatches)
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
                        .lineMatches(lineMatches)
                        .build();
            }
        }

        // ═════════════════════════════════════════════════════
        // L2: Line LCS — matched lines / total non-blank lines in the chunk
        // ═════════════════════════════════════════════════════
        double l2Score = chunkLineMapping.getNonBlankLineCount() > 0
                ? (double) exactContributedLines / chunkLineMapping.getNonBlankLineCount()
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
                    .lineMatches(lineMatches)
                    .build();
        }

        // We intentionally removed L2 fastFail here. Even if L2 score is < 0.30,
        // it might still contain exactContributedLines > 0. Since L3 AST is removed,
        // we directly return buildFallbackResult to secure the matched lines.
        return buildFallbackResult(l1Score, l2Score, exactContributedLines, contributedLineIndices, lineMatches, details);
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
            List<Map<String, Integer>> lineMatches,
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
                .lineMatches(lineMatches)
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

}
