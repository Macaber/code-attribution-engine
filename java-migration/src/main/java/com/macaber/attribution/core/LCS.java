package com.macaber.attribution.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class LCS {
    private final int maxCells;

    public LCS() {
        this(new LcsConfig());
    }

    public LCS(LcsConfig config) {
        this.maxCells = config != null ? config.getMaxCells() : 10_000_000;
    }

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

        if (strA.length() < strB.length()) {
            String temp = strA;
            strA = strB;
            strB = temp;
        }

        int m = strA.length();
        int n = strB.length();

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
            int[] temp = prev;
            prev = curr;
            curr = temp;
            for (int k = 0; k <= n; k++) curr[k] = 0;
        }

        return prev[n];
    }

    public List<Integer> calculateTraceableLcs(String reference, String target) {
        if (reference == null || reference.isEmpty() || target == null || target.isEmpty()) {
            return new ArrayList<>();
        }

        String refStr = reference;
        String tgtStr = target;

        long cellCount = (long) refStr.length() * tgtStr.length();
        if (cellCount > maxCells) {
            double ratio = Math.sqrt((double) maxCells / cellCount);
            int newLenRef = Math.max(1, (int) Math.floor(refStr.length() * ratio));
            int newLenTgt = Math.max(1, (int) Math.floor(tgtStr.length() * ratio));
            refStr = refStr.substring(0, newLenRef);
            tgtStr = tgtStr.substring(0, newLenTgt);
        }

        int m = refStr.length();
        int n = tgtStr.length();

        int[] dp = new int[(m + 1) * (n + 1)];

        for (int i = 1; i <= m; i++) {
            int rowOffset = i * (n + 1);
            int prevRowOffset = (i - 1) * (n + 1);

            for (int j = 1; j <= n; j++) {
                if (refStr.charAt(i - 1) == tgtStr.charAt(j - 1)) {
                    dp[rowOffset + j] = dp[prevRowOffset + j - 1] + 1;
                } else {
                    dp[rowOffset + j] = Math.max(dp[prevRowOffset + j], dp[rowOffset + j - 1]);
                }
            }
        }

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

        Collections.reverse(matchedTargetIndices);
        return matchedTargetIndices;
    }

    public double calculateScore(String reference, String target) {
        if (target == null || target.isEmpty()) return 0.0;
        if (reference == null || reference.isEmpty()) return 0.0;

        int lcsLen = calculateLcsLength(reference, target);
        return (double) lcsLen / target.length();
    }
}
