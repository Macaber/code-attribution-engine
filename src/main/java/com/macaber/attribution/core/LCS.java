package com.macaber.attribution.core;

import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * LCS (Longest Common Subsequence) Algorithm — Micro-level similarity measurement.
 *
 * Uses dynamic programming with two-row space optimization: O(min(M,N)) space.
 * Includes a circuit breaker for large inputs to prevent O(N²) memory/time explosion.
 *
 * Aligned with TS: src/domains/attribution/algorithms/lcs.ts
 */
public class LCS {
    private final int maxCells;

    public LCS() {
        this(new LcsConfig());
    }

    public LCS(LcsConfig config) {
        this.maxCells = config != null ? config.getMaxCells() : 10_000_000;
    }

    /**
     * Calculate the length of the Longest Common Subsequence of two strings.
     * Uses space-optimized DP (two rows only).
     *
     * If the inputs exceed maxCells, both strings are proportionally truncated.
     */
    public int calculateLcsLength(String a, String b) {
        if (a == null || a.isEmpty() || b == null || b.isEmpty()) return 0;

        String strA = a;
        String strB = b;
        long cellCount = (long) strA.length() * strB.length();
        if (cellCount > maxCells) {
            double ratio = Math.sqrt((double) maxCells / cellCount);
            int newLenA = Math.max(1, (int) Math.floor(strA.length() * ratio));
            int newLenB = Math.max(1, (int) Math.floor(strB.length() * ratio));
            strA = strA.substring(0, newLenA);
            strB = strB.substring(0, newLenB);
        }

        // Ensure strB is the shorter string for space optimization
        if (strA.length() < strB.length()) {
            String temp = strA;
            strA = strB;
            strB = temp;
        }

        int m = strA.length();
        int n = strB.length();

        // Two-row DP: O(min(m,n)) space
        int[] prev = new int[n + 1];
        int[] curr = new int[n + 1];

        for (int i = 1; i <= m; i++) {
            for (int j = 1; j <= n; j++) {
                if (strA.charAt(i - 1) == strB.charAt(j - 1)) {
                    curr[j] = prev[j - 1] + 1;
                } else {
                    curr[j] = Math.max(prev[j], curr[j - 1]);
                }
            }
            // Swap rows
            int[] temp = prev;
            prev = curr;
            curr = temp;
        }

        return prev[n];
    }

    /**
     * Calculates the LCS and returns the exact indices of `target` that match `reference`.
     * Uses a full 1D-backed 2D DP matrix to allow backtracking.
     *
     * If the inputs exceed maxCells, uses a chunked sliding-window approximation
     * to prevent truncating the end of large files.
     *
     * Aligned with TS: calculateTraceableLcs() in lcs.ts
     *
     * @param reference The base string
     * @param target    The string whose matched indices are desired
     * @return List of character indices in `target` that matched.
     */
    public List<Integer> calculateTraceableLcs(String reference, String target) {
        if (reference == null || reference.isEmpty() || target == null || target.isEmpty()) {
            return new ArrayList<>();
        }

        String refStr = reference;
        String tgtStr = target;

        // If input is small enough, use precise global LCS
        long cellCount = (long) refStr.length() * tgtStr.length();
        if (cellCount <= maxCells) {
            return calculateTraceableLcsInternal(refStr, tgtStr);
        }

        // ═════════════════════════════════════════════════════
        // Circuit breaker: Chunked sliding-window LCS approximation
        // To prevent truncating the end of large files (which drops
        // exact matched lines), we split the target into safe chunks
        // and match each against a sliding localized window of reference.
        // ═════════════════════════════════════════════════════

        int tgtChunkSize = (int) Math.floor(Math.sqrt((double) maxCells / 2));
        int refWindowSize = tgtChunkSize * 2;

        List<Integer> matchedTargetIndices = new ArrayList<>();

        for (int tgtOffset = 0; tgtOffset < tgtStr.length(); tgtOffset += tgtChunkSize) {
            int tgtEnd = Math.min(tgtOffset + tgtChunkSize, tgtStr.length());
            String tgtChunk = tgtStr.substring(tgtOffset, tgtEnd);

            // Estimate where in reference string this chunk should align
            int expectedCenter = (int) Math.floor(
                    (tgtOffset + tgtChunk.length() / 2.0) / tgtStr.length() * refStr.length());
            int refWindowStart = Math.max(0, expectedCenter - refWindowSize / 2);
            int refWindowEnd = Math.min(refStr.length(), expectedCenter + refWindowSize / 2);
            String refChunk = refStr.substring(refWindowStart, refWindowEnd);

            // Calculate localized LCS
            List<Integer> localMatches = calculateTraceableLcsInternal(refChunk, tgtChunk);

            // Map local target indices back to global target indices
            for (int localIdx : localMatches) {
                matchedTargetIndices.add(tgtOffset + localIdx);
            }
        }

        return matchedTargetIndices;
    }

    /**
     * Line-level LCS: treats each normalized line as an atomic comparison unit.
     * Returns the indices of `tgtLines` that were matched in the LCS.
     *
     * Since line counts are typically 1-2 orders of magnitude smaller than token
     * counts (hundreds of lines vs thousands of tokens), the DP matrix is very
     * compact and no circuit breaker is needed.
     *
     * Aligned with TS: calculateTraceableLcsLines() in lcs.ts
     *
     * @param refLines Normalized lines from the reference (AI) code
     * @param tgtLines Normalized lines from the target (diff chunk) code
     * @return List of indices into `tgtLines` that matched (0-indexed, ascending)
     */
    @Getter
    public static class LcsLineMatch {
        private final int refIndex;
        private final int tgtIndex;

        public LcsLineMatch(int refIndex, int tgtIndex) {
            this.refIndex = refIndex;
            this.tgtIndex = tgtIndex;
        }

    }

    public List<LcsLineMatch> calculateTraceableLcsLineMatches(List<String> refLines, List<String> tgtLines) {
        if (refLines == null || refLines.isEmpty() || tgtLines == null || tgtLines.isEmpty()) {
            return new ArrayList<>();
        }

        int m = refLines.size();
        int n = tgtLines.size();

        // DP matrix (m+1) × (n+1). Line counts are small, so no circuit breaker needed.
        int[] dp = new int[(m + 1) * (n + 1)];

        // Fill DP
        for (int i = 1; i <= m; i++) {
            int rowOffset = i * (n + 1);
            int prevRowOffset = (i - 1) * (n + 1);

            for (int j = 1; j <= n; j++) {
                if (refLines.get(i - 1).equals(tgtLines.get(j - 1))) {
                    dp[rowOffset + j] = dp[prevRowOffset + j - 1] + 1;
                } else {
                    dp[rowOffset + j] = Math.max(
                            dp[prevRowOffset + j],     // up
                            dp[rowOffset + j - 1]      // left
                    );
                }
            }
        }

        // Backtrack to find matched line index pairs
        int i = m;
        int j = n;
        List<LcsLineMatch> matches = new ArrayList<>();

        while (i > 0 && j > 0) {
            if (refLines.get(i - 1).equals(tgtLines.get(j - 1))) {
                matches.add(new LcsLineMatch(i - 1, j - 1));
                i--;
                j--;
            } else {
                int rowOffset = i * (n + 1);
                int prevRowOffset = (i - 1) * (n + 1);

                if (dp[prevRowOffset + j] > dp[rowOffset + j - 1]) {
                    i--;
                } else {
                    j--;
                }
            }
        }

        Collections.reverse(matches);
        return matches;
    }

    public List<Integer> calculateTraceableLcsLines(List<String> refLines, List<String> tgtLines) {
        List<LcsLineMatch> matches = calculateTraceableLcsLineMatches(refLines, tgtLines);
        List<Integer> matchedLineIndices = new ArrayList<>();
        for (LcsLineMatch match : matches) {
            matchedLineIndices.add(match.getTgtIndex());
        }
        return matchedLineIndices;
    }

    /**
     * Core implementation of traceable LCS.
     * Runs in O(M*N) time and space. Assumes inputs are already bounds-checked.
     *
     * Aligned with TS: _calculateTraceableLcsInternal() in lcs.ts
     */
    private List<Integer> calculateTraceableLcsInternal(String refStr, String tgtStr) {
        int m = refStr.length();
        int n = tgtStr.length();

        // Full DP table for backtracking: 1D array representing (M+1) × (N+1)
        int[] dp = new int[(m + 1) * (n + 1)];

        // Fill DP
        for (int i = 1; i <= m; i++) {
            int rowOffset = i * (n + 1);
            int prevRowOffset = (i - 1) * (n + 1);

            for (int j = 1; j <= n; j++) {
                if (refStr.charAt(i - 1) == tgtStr.charAt(j - 1)) {
                    dp[rowOffset + j] = dp[prevRowOffset + j - 1] + 1;
                } else {
                    dp[rowOffset + j] = Math.max(
                            dp[prevRowOffset + j],     // up
                            dp[rowOffset + j - 1]      // left
                    );
                }
            }
        }

        // Backtrack from bottom-right to find aligned target indices
        int i = m;
        int j = n;
        List<Integer> matchedTargetIndices = new ArrayList<>();

        while (i > 0 && j > 0) {
            if (refStr.charAt(i - 1) == tgtStr.charAt(j - 1)) {
                matchedTargetIndices.add(j - 1);
                i--;
                j--;
            } else {
                int rowOffset = i * (n + 1);
                int prevRowOffset = (i - 1) * (n + 1);

                if (dp[prevRowOffset + j] > dp[rowOffset + j - 1]) {
                    i--;
                } else {
                    j--;
                }
            }
        }

        // Since we backtrack from end to start, reverse to get chronological indices
        Collections.reverse(matchedTargetIndices);
        return matchedTargetIndices;
    }

    /**
     * Calculate LCS-based containment score.
     * Formula: LCS(reference, target) / |target|
     * Calculates what portion of the target string comes from the reference string.
     *
     * @param reference The base string (e.g., AI output history)
     * @param target    The tested string (e.g., User's submitted diff)
     * @return Score between 0.0 and 1.0
     */
    public double calculateScore(String reference, String target) {
        if (target == null || target.isEmpty()) return 0.0;
        if (reference == null || reference.isEmpty()) return 0.0;

        int lcsLen = calculateLcsLength(reference, target);
        return (double) lcsLen / target.length();
    }
}
