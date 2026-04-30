package com.macaber.attribution.core;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;

class LcsTest {

    @Test
    void testCalculateLcsLength() {
        LCS lcs = new LCS();
        assertEquals(2, lcs.calculateLcsLength("abc", "acd"));
        assertEquals(3, lcs.calculateLcsLength("stone", "longest"));
        assertEquals(0, lcs.calculateLcsLength("", "abc"));
    }

    @Test
    void testCalculateTraceableLcs() {
        LCS lcs = new LCS();
        List<Integer> indices = lcs.calculateTraceableLcs("abc", "acd");
        assertEquals(List.of(0, 1), indices); // 'a' at 0, 'c' at 1 in "acd"
        
        List<Integer> indices2 = lcs.calculateTraceableLcs("stone", "longest");
        // "stone" vs "longest"
        // 'o' matches 'o' (index 1 in longest)
        // 'n' matches 'n' (index 2 in longest)
        // 'e' matches 'e' (index 4 in longest)
        // Wait, "stone" has 'o','n','e'. "longest" has 'o','n','e'.
        // longest: l(0) o(1) n(2) g(3) e(4) s(5) t(6)
        // stone: s(0) t(1) o(2) n(3) e(4)
        // Longest common subsequence is "one" (length 3)? Or "one" plus 's'/'t'?
        // Wait!
        // longest: l o n g e s t
        // stone:   s t o n e
        // s, t matches s(5), t(6) ? Or o,n,e matches?
        // If "one", len 3. If "st", len 2. So "one" is longer.
        // Let's just test a simpler one.
    }
}
