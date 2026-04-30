package com.macaber.attribution.core;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;

class NormalizerTest {

    @Test
    void testNormalizeText() {
        Normalizer normalizer = new Normalizer();
        String raw = "int a = 1; // comment";
        assertEquals("inta=1;//comment", normalizer.normalizeText(raw));
    }

    @Test
    void testNormalizeWithMapping() {
        Normalizer normalizer = new Normalizer();
        String raw = "int a = 1;\nreturn a;";
        LineMapping mapping = normalizer.normalizeWithMapping(raw);
        
        assertEquals("inta=1;returna;", mapping.getNormalizedText());
        assertEquals(15, mapping.getCharToLineMap().size());
        
        // "inta=1;" -> 7 chars, line 0
        assertEquals(0, mapping.getCharToLineMap().get(0));
        assertEquals(0, mapping.getCharToLineMap().get(6));
        // "returna;" -> 8 chars, line 1
        assertEquals(1, mapping.getCharToLineMap().get(7));
        assertEquals(1, mapping.getCharToLineMap().get(14));
        
        assertEquals(7, mapping.getLineCharCounts().get(0));
        assertEquals(8, mapping.getLineCharCounts().get(1));
    }
}
