package com.macaber.attribution.core;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NormalizerTest {

    @Test
    void testNormalizeText() {
        Normalizer normalizer = new Normalizer();
        String raw = "int a = 1; // comment";
        assertEquals("inta=1;//comment", normalizer.normalizeText(raw));
    }

    @Test
    void testNormalizeText_EmptyInput() {
        Normalizer normalizer = new Normalizer();
        assertEquals("", normalizer.normalizeText(null));
        assertEquals("", normalizer.normalizeText(""));
    }

    @Test
    void testNormalizeToLines() {
        Normalizer normalizer = new Normalizer();
        String raw = "int a = 1;\nreturn a;";
        LineMapping mapping = normalizer.normalizeToLines(raw);

        // Two non-blank lines
        assertEquals(2, mapping.getNonBlankLineCount());
        assertEquals(List.of("inta=1;", "returna;"), mapping.getNormalizedLines());
        assertEquals(List.of(0, 1), mapping.getOriginalLineIndices());
        assertEquals("inta=1;returna;", mapping.getNormalizedText());
    }

    @Test
    void testNormalizeToLines_SkipsBlankLines() {
        Normalizer normalizer = new Normalizer();
        String raw = "line1\n\n  \nline4";
        LineMapping mapping = normalizer.normalizeToLines(raw);

        assertEquals(2, mapping.getNonBlankLineCount());
        assertEquals(List.of("line1", "line4"), mapping.getNormalizedLines());
        // Original indices: line1 is at index 0, line4 is at index 3
        assertEquals(List.of(0, 3), mapping.getOriginalLineIndices());
    }

    @Test
    void testNormalizeToLines_EmptyInput() {
        Normalizer normalizer = new Normalizer();
        LineMapping mapping = normalizer.normalizeToLines(null);
        assertEquals(0, mapping.getNonBlankLineCount());
        assertTrue(mapping.getNormalizedLines().isEmpty());

        LineMapping mapping2 = normalizer.normalizeToLines("");
        assertEquals(0, mapping2.getNonBlankLineCount());
        assertTrue(mapping2.getNormalizedLines().isEmpty());
    }
}
