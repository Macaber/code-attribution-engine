package com.macaber.attribution.core;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;

class DiffParserTest {

    @Test
    void testParseUnifiedDiff() {
        String rawDiff = 
            "diff --git a/src/app.ts b/src/app.ts\n" +
            "--- a/src/app.ts\n" +
            "+++ b/src/app.ts\n" +
            "@@ -10,3 +10,5 @@\n" +
            " context1\n" +
            "+added 1\n" +
            "+added 2\n" +
            " context2\n" +
            "@@ -20,2 +22,3 @@\n" +
            " context3\n" +
            "-removed\n" +
            "+added 3\n" +
            " context4\n";

        DiffParser parser = new DiffParser();
        List<DiffChunk> chunks = parser.parse(rawDiff);

        assertEquals(2, chunks.size());

        // Chunk 1
        assertEquals("src/app.ts", chunks.get(0).getFilePath());
        assertEquals(11, chunks.get(0).getStartLine());
        assertEquals(12, chunks.get(0).getEndLine());
        assertEquals("added 1\nadded 2", chunks.get(0).getContent());
        assertEquals("added1added2", chunks.get(0).getNormalizedContent());

        // Chunk 2
        assertEquals("src/app.ts", chunks.get(1).getFilePath());
        assertEquals(23, chunks.get(1).getStartLine());
        assertEquals(23, chunks.get(1).getEndLine());
        assertEquals("added 3", chunks.get(1).getContent());
    }
}
