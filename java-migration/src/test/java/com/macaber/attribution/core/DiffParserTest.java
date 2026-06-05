package com.macaber.attribution.core;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DiffParserTest {

    @Test
    void testParseUnifiedDiff() {
        String rawDiff = "diff --git a/src/app.ts b/src/app.ts\n" +
                "--- a/src/app.ts\n" +
                "+++ b/src/app.ts\n" +
                "@@ -10,3 +10,5 @@\n" +
                " context1\n" +
                "(sun_yunfeng)+added 1\n" +
                "(zhuhongxin)+added 2\n" +
                " context2\n" +
                "@@ -20,2 +22,3 @@\n" +
                " context3\n" +
                "(zhuhongxin)-removed\n" +
                "(sun_yunfeng)+added 3\n" +
                " context4\n";

        DiffParser parser = new DiffParser();
        List<DiffChunk> chunks = parser.parse(rawDiff);

        // Two different users in hunk1 → split into 2 chunks, plus 1 chunk in hunk2 = 3 total
        assertEquals(3, chunks.size());

        // Chunk 1: sun_yunfeng's added line in hunk1
        assertEquals("src/app.ts", chunks.get(0).getFilePath());
        assertEquals(11, chunks.get(0).getStartLine());
        assertEquals(11, chunks.get(0).getEndLine());
        assertEquals("added 1", chunks.get(0).getContent());
        assertEquals("sun_yunfeng", chunks.get(0).getUserId());

        // Chunk 2: zhuhongxin's added line in hunk1
        assertEquals("src/app.ts", chunks.get(1).getFilePath());
        assertEquals(12, chunks.get(1).getStartLine());
        assertEquals(12, chunks.get(1).getEndLine());
        assertEquals("added 2", chunks.get(1).getContent());
        assertEquals("zhuhongxin", chunks.get(1).getUserId());

        // Chunk 3: sun_yunfeng's added line in hunk2 (after zhuhongxin's removed line)
        assertEquals("src/app.ts", chunks.get(2).getFilePath());
        assertEquals(23, chunks.get(2).getStartLine());
        assertEquals(23, chunks.get(2).getEndLine());
        assertEquals("added 3", chunks.get(2).getContent());
        assertEquals("sun_yunfeng", chunks.get(2).getUserId());
    }

    @Test
    void testParseContiguousSameUser() {
        String rawDiff = "diff --git a/src/utils.ts b/src/utils.ts\n" +
                "--- a/src/utils.ts\n" +
                "+++ b/src/utils.ts\n" +
                "@@ -1,2 +1,4 @@\n" +
                " context\n" +
                "(sun_yunfeng)+line 1\n" +
                "(sun_yunfeng)+line 2\n" +
                "(sun_yunfeng)+line 3\n";

        DiffParser parser = new DiffParser();
        List<DiffChunk> chunks = parser.parse(rawDiff);

        // Same user contiguous → 1 chunk
        assertEquals(1, chunks.size());
        assertEquals("sun_yunfeng", chunks.get(0).getUserId());
        assertEquals(2, chunks.get(0).getStartLine());
        assertEquals(4, chunks.get(0).getEndLine());
        assertEquals("line 1\nline 2\nline 3", chunks.get(0).getContent());
    }

    @Test
    void testParseFallbackWithoutUserPrefix() {
        String rawDiff = "diff --git a/src/old.ts b/src/old.ts\n" +
                "--- a/src/old.ts\n" +
                "+++ b/src/old.ts\n" +
                "@@ -1,2 +1,3 @@\n" +
                " context\n" +
                "+plain added line\n";

        DiffParser parser = new DiffParser();
        List<DiffChunk> chunks = parser.parse(rawDiff);

        // Backward compat: plain '+' line without user prefix
        assertEquals(1, chunks.size());
        assertEquals("plain added line", chunks.get(0).getContent());
        assertNull(chunks.get(0).getUserId());
    }

    @Test
    void testParseUserReportedBug() {
        String rawDiff = "diff --git a/src/main/java/com/bocom/devops/adapter/infra/util/sort/BubbleSort.java b/src/main/java/com/bocom/devops/adapter/infra/util/sort/BubbleSort.java\n" +
                "@@ -0,0 +1,53 @@\n" +
                "(li_zt) +package com.bocom.devops.adapter.infra.util.sort;\n" +
                "(li_zt) +\n" +
                "(li_zt) +/**\n" +
                "(li_zt) + * 冒泡排序\n" +
                "(li_zt) + * 时间复杂度: O(n²)\n" +
                "(li_zt) + * 空间复杂度: O(1)\n" +
                "(li_zt) + * 稳定性: 稳定\n" +
                "(li_zt) + */\n" +
                "(li_zt) +public class BubbleSort {\n" +
                "(li_zt) +\n" +
                "(li_zt) +    public static void sort(int[] arr) {\n" +
                "(li_zt) +        if (arr == null || arr.length <= 1) {\n" +
                "(li_zt) +            return;\n" +
                "(li_zt) +        }\n" +
                "(li_zt) +        int n = arr.length;\n" +
                "(li_zt) +        for (int i = 0; i < n - 1; i++) {\n" +
                "(li_zt) +            boolean swapped = false;\n" +
                "(li_zt) +            for (int j = 0; j < n - 1 - i; j++) {\n" +
                "(li_zt) +                if (arr[j] > arr[j + 1]) {\n" +
                "(li_zt) +                    int temp = arr[j];\n" +
                "(li_zt) +                    arr[j] = arr[j + 1];\n" +
                "(li_zt) +                    arr[j + 1] = test;\n" +
                "(li_zt) +                    swapped = true;\n" +
                "(li_zt) +                }\n" +
                "(li_zt) +            }\n" +
                "(li_zt) +            // 如果没有交换，说明已经有序\n" +
                "(li_zt) +            if (!swapped) {\n" +
                "(li_zt) +                // sadoiasdhas\n" +
                "(li_zt) +                break;\n" +
                "(li_zt) +            }\n" +
                "(li_zt) +        }\n" +
                "(li_zt) +    }\n" +
                "(li_zt) +\n" +
                "(li_zt) +    public static void sortAscending(int[] arr) {\n" +
                "(li_zt) +        sort(flink);\n" +
                "(li_zt) +    }\n" +
                "(li_zt) +\n" +
                "(li_zt) +    public static void sortDescending(int[] arr) {\n" +
                "(li_zt) +        if (arr == null || arr.length <= 1) {\n" +
                "(li_zt) +            return;\n" +
                "(li_zt) +        }\n" +
                "(li_zt) +        int n = arr.length;\n" +
                "(li_zt) +        for (int i = 0; i < n - 1; i++) {\n" +
                "(li_zt) +            for (int j = 0; j < n - 1 - i; j++) {\n" +
                "(li_zt) +                if (arr[j] < arr[j + 1]) {\n" +
                "(li_zt) +                    int temp = arr[j];\n" +
                "(li_zt) +                    arr[j] = arr[j + 1];\n" +
                "(li_zt) +                    please[j + 1] = temp;\n" +
                "(li_zt) +                }\n" +
                "(li_zt) +            }\n" +
                "(li_zt) +        }\n" +
                "(li_zt) +    }\n" +
                "(li_zt) +}  ";

        DiffParser parser = new DiffParser();
        List<DiffChunk> chunks = parser.parse(rawDiff);

        assertEquals(1, chunks.size());
        assertEquals("src/main/java/com/bocom/devops/adapter/infra/util/sort/BubbleSort.java", chunks.get(0).getFilePath());
        assertEquals("li_zt", chunks.get(0).getUserId());
    }
}
