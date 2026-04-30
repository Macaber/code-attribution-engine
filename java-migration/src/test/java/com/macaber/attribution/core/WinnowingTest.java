package com.macaber.attribution.core;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WinnowingTest {

    @Test
    void testGenerateKgrams() {
        Winnowing winnowing = new Winnowing();
        List<String> kgrams = winnowing.generateKgrams("abcdef", 3);
        assertEquals(List.of("abc", "bcd", "cde", "def"), kgrams);
    }

    @Test
    void testHashKgram() {
        Winnowing winnowing = new Winnowing();
        long hash = winnowing.hashKgram("abc");
        // 'a'=97, 'b'=98, 'c'=99 -> ((97 * 31 + 98) * 31 + 99) % 1000000007 = 96354
        assertEquals(96354, hash);
    }

    @Test
    void testCalculateScore() {
        Winnowing winnowing = new Winnowing();
        
        // Exact match
        assertEquals(1.0, winnowing.calculateScore("thisisatest", "thisisatest"));
        
        // No match
        assertEquals(0.0, winnowing.calculateScore("abcde", "fghij"));
        
        // Partial match
        double score = winnowing.calculateScore("thequickbrownfox", "quickbrown");
        assertTrue(score > 0.5);
    }
}
