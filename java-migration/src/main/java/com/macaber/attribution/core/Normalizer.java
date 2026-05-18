package com.macaber.attribution.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Normalizer — Regex-based code cleaning for similarity comparison.
 *
 * Removes formatting noise (whitespace, casing) so that
 * semantically identical code produces identical normalized strings.
 *
 * Aligned with TS: src/domains/attribution/normalizer.ts
 */
public class Normalizer {

    /**
     * Normalize raw code by:
     * 1. Removing all whitespace (spaces, tabs, newlines, carriage returns)
     * 2. Converting to lowercase
     *
     * Note: We deliberately KEEP comments.
     * If the AI generated comments and the user adopted them,
     * it should be counted as AI contribution.
     */
    public String normalizeText(String rawCode) {
        if (rawCode == null || rawCode.isEmpty()) return "";
        return rawCode.replaceAll("\\s+", "").toLowerCase();
    }

    /**
     * Normalizes code at line granularity for line-level LCS comparison.
     *
     * Each line is independently normalized (all whitespace stripped, lowercased).
     * Blank lines (empty after normalization) are excluded.
     * The result maps each normalized line back to its original 0-indexed line number.
     *
     * This replaces the token-level approach: instead of splitting into tokens and
     * tracking per-token line origin, each normalized line becomes the atomic
     * comparison unit for LCS, eliminating cross-line token leakage.
     *
     * Aligned with TS: normalizeToLines() in normalizer.ts
     */
    public LineMapping normalizeToLines(String rawCode) {
        if (rawCode == null || rawCode.isEmpty()) {
            return new LineMapping(new ArrayList<>(), new ArrayList<>(), 0, "");
        }

        List<String> normalizedLines = new ArrayList<>();
        List<Integer> originalLineIndices = new ArrayList<>();
        StringBuilder normalizedTextBuilder = new StringBuilder();

        String[] lines = rawCode.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            // Normalize each line: strip all whitespace + lowercase
            String normalized = lines[i].replaceAll("\\s+", "").toLowerCase();
            if (normalized.isEmpty()) continue; // Skip blank lines

            normalizedLines.add(normalized);
            originalLineIndices.add(i); // Map back to original 0-indexed line number
            normalizedTextBuilder.append(normalized); // For Winnowing compatibility
        }

        return new LineMapping(
                normalizedLines,
                originalLineIndices,
                normalizedLines.size(),
                normalizedTextBuilder.toString()
        );
    }
}
