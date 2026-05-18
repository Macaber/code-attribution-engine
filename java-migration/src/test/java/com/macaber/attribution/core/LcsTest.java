package com.macaber.attribution.core;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LcsTest {

    @Test
    void testCalculateLcsLength() {
        LCS lcs = new LCS();
        assertEquals(2, lcs.calculateLcsLength("abc", "acd"));
        assertEquals(3, lcs.calculateLcsLength("stone", "longest"));
        assertEquals(0, lcs.calculateLcsLength("", "abc"));
        assertEquals(0, lcs.calculateLcsLength(null, "abc"));
    }

    @Test
    void testCalculateTraceableLcs() {
        LCS lcs = new LCS();
        List<Integer> indices = lcs.calculateTraceableLcs("abc", "acd");
        assertEquals(List.of(0, 1), indices); // 'a' at 0, 'c' at 1 in "acd"
    }

    @Test
    void testCalculateTraceableLcs_EmptyInput() {
        LCS lcs = new LCS();
        assertEquals(List.of(), lcs.calculateTraceableLcs("", "abc"));
        assertEquals(List.of(), lcs.calculateTraceableLcs("abc", ""));
        assertEquals(List.of(), lcs.calculateTraceableLcs(null, "abc"));
    }

    @Test
    void testCalculateTraceableLcsLines() {
        LCS lcs = new LCS();

        // Exact line match
        List<String> refLines = Arrays.asList("inta=1;", "returnb;", "console.log();");
        List<String> tgtLines = Arrays.asList("inta=1;", "returnc;", "console.log();");

        List<Integer> matched = lcs.calculateTraceableLcsLines(refLines, tgtLines);
        // "inta=1;" matches at index 0, "console.log();" matches at index 2
        assertEquals(List.of(0, 2), matched);
    }

    @Test
    void testCalculateTraceableLcsLines_AllMatch() {
        LCS lcs = new LCS();

        List<String> refLines = Arrays.asList("line1", "line2", "line3");
        List<String> tgtLines = Arrays.asList("line1", "line2", "line3");

        List<Integer> matched = lcs.calculateTraceableLcsLines(refLines, tgtLines);
        assertEquals(List.of(0, 1, 2), matched);
    }

    @Test
    void testCalculateTraceableLcsLines_NoMatch() {
        LCS lcs = new LCS();

        List<String> refLines = Arrays.asList("aaa", "bbb", "ccc");
        List<String> tgtLines = Arrays.asList("xxx", "yyy", "zzz");

        List<Integer> matched = lcs.calculateTraceableLcsLines(refLines, tgtLines);
        assertEquals(List.of(), matched);
    }

    @Test
    void testCalculateTraceableLcsLines_EmptyInput() {
        LCS lcs = new LCS();
        assertEquals(List.of(), lcs.calculateTraceableLcsLines(List.of(), Arrays.asList("a")));
        assertEquals(List.of(), lcs.calculateTraceableLcsLines(Arrays.asList("a"), List.of()));
        assertEquals(List.of(), lcs.calculateTraceableLcsLines(null, Arrays.asList("a")));
    }

    @Test
    void testCalculateScore() {
        LCS lcs = new LCS();
        assertEquals(1.0, lcs.calculateScore("abc", "abc"));
        assertEquals(0.0, lcs.calculateScore("abc", ""));
        assertEquals(0.0, lcs.calculateScore("", "abc"));
        assertTrue(lcs.calculateScore("abcdef", "ace") > 0.5);
    }
}
