package com.macaber.attribution.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SimilarityEngineTest {

    private SimilarityEngine engine;

    @BeforeEach
    void setUp() {
        // null configurations to use defaults
        engine = new SimilarityEngine(null, null, null, null);
    }

    @Test
    void testEvaluateChunk_IdenticalCode_ReturnsStrictL1() {
        String code = "function add(a, b) {\n  return a + b;\n}";
        EvaluationResult result = engine.evaluateChunk(code, code, null);

        assertEquals(MatchType.STRICT, result.getMatchType());
        assertEquals(PipelineLevel.L1, result.getLevel());
        assertTrue(result.getScore() >= 0.90);
        assertTrue(result.getDetails().containsKey("l1WinnowingScore"));
    }

    @Test
    void testEvaluateChunk_CompletelyDifferent_ReturnsNoneL1() {
        String aiCode = "aaaaaaaaaaaaaaaaaaaaaa";
        String userCode = "zzzzzzzzzzzzzzzzzzzzzz";
        EvaluationResult result = engine.evaluateChunk(aiCode, userCode, null);

        assertEquals(MatchType.NONE, result.getMatchType());
        assertEquals(PipelineLevel.L1, result.getLevel());
        assertEquals(0, result.getScore());
    }

    @Test
    void testEvaluateChunk_EmptyInputs_ReturnsNoneFailedAll() {
        EvaluationResult result = engine.evaluateChunk("", "some code", null);

        assertEquals(MatchType.NONE, result.getMatchType());
        assertEquals(PipelineLevel.FAILED_ALL, result.getLevel());
        assertEquals(0, result.getScore());
    }

    @Test
    void testEvaluateChunk_ShortIdentical_BypassesL1ReturnsFuzzyL2() {
        // "x++" is 3 chars, below default k=5
        EvaluationResult result = engine.evaluateChunk("x++", "x++", null);

        assertTrue(result.getScore() >= 0.80);
        assertNotEquals(MatchType.NONE, result.getMatchType());
        assertTrue(result.getDetails().containsKey("l2LcsScore"));
    }

    @Test
    void testEvaluateChunk_ShortDifferent_ReturnsNone() {
        EvaluationResult result = engine.evaluateChunk("x++", "abc", null);

        assertEquals(MatchType.NONE, result.getMatchType());
    }

    @Test
    void testEvaluateChunk_LineLevel_TracksContributedLines() {
        String aiCode = "int a = 1;\nreturn a + b;\nconsole.log(a);";
        String userCode = "int a = 1;\nreturn x + y;\nconsole.log(a);";
        EvaluationResult result = engine.evaluateChunk(aiCode, userCode, null);

        // 2 lines match exactly: "int a = 1;" and "console.log(a);"
        assertEquals(2, result.getExactContributedLines());
        assertNotNull(result.getContributedLineIndices());
        assertTrue(result.getContributedLineIndices().size() >= 2);
    }

    @Test
    void testEvaluateChunk_ModeratelySimilar_EscalatesToL2() {
        // Multi-line code with moderate similarity
        String aiCode = "function calculateTotal(items) {\n  let sum = 0;\n  for (const item of items) {\n    sum += item.price * item.quantity;\n  }\n  return sum;\n}";
        String userCode = "function calculateTotal(items) {\n  let total = 0;\n  for (const item of items) {\n    total += item.price * item.quantity;\n  }\n  return total;\n}";

        EvaluationResult result = engine.evaluateChunk(aiCode, userCode, null);

        assertTrue(result.getDetails().containsKey("l1WinnowingScore") || result.getDetails().containsKey("l2LcsScore"));
    }

    @Test
    void testEvaluateChunk_ExceedsMaxLinesForL3_SkipsL3() {
        String aiCode = "function test() {\n  return 1;\n}";
        String userCode = "function test() {\n  return 2;\n}";

        SimilarityEngine.EvaluationContext context = SimilarityEngine.EvaluationContext.builder()
                .addedLineCount(2000)
                .filePath("test.ts")
                .fileContent("function test() { return 2; }")
                .build();

        EvaluationResult result = engine.evaluateChunk(aiCode, userCode, context);

        assertFalse(result.getDetails().containsKey("l3AstScore"));
    }

    @Test
    void testEvaluateChunk_NonParseableFile_SkipsL3() {
        String aiCode = "spring.datasource.url=jdbc:mysql://localhost:3306/db";
        String userCode = "spring.datasource.url=jdbc:mysql://localhost:3306/db\nfeature.enabled=true";

        SimilarityEngine.EvaluationContext context = SimilarityEngine.EvaluationContext.builder()
                .addedLineCount(1)
                .filePath("application.properties")
                .fileContent(userCode)
                .build();

        EvaluationResult result = engine.evaluateChunk(aiCode, userCode, context);

        assertFalse(result.getDetails().containsKey("l3AstScore"));
    }

    @Test
    void testMatchTypeToAttribution() {
        assertEquals("strict", SimilarityEngine.matchTypeToAttribution(MatchType.STRICT));
        assertEquals("fuzzy", SimilarityEngine.matchTypeToAttribution(MatchType.FUZZY));
        assertEquals("deep_refactor", SimilarityEngine.matchTypeToAttribution(MatchType.DEEP_REFACTOR));
        assertEquals("none", SimilarityEngine.matchTypeToAttribution(MatchType.NONE));
        assertEquals("none", SimilarityEngine.matchTypeToAttribution(null));
    }

    @Test
    void testEvaluateChunk_AcceptsPreCalculatedMappings() {
        Normalizer normalizer = new Normalizer();
        String aiCode = "int a = 1;\nreturn a;";
        String chunkCode = "int a = 1;\nreturn a;";

        LineMapping aiMapping = normalizer.normalizeToLines(aiCode);
        LineMapping chunkMapping = normalizer.normalizeToLines(chunkCode);

        SimilarityEngine.EvaluationContext ctx = SimilarityEngine.EvaluationContext.builder()
                .normalizedAi(aiMapping.getNormalizedText())
                .aiLineMapping(aiMapping)
                .chunkLineMapping(chunkMapping)
                .build();

        EvaluationResult result = engine.evaluateChunk(aiCode, chunkCode, ctx);

        assertNotEquals(MatchType.NONE, result.getMatchType());
        assertTrue(result.getScore() > 0);
    }

    @Test
    void testEvaluateChunk_TrivialLineFilter() {
        PipelineConfig config = new PipelineConfig();
        // Enable filter and configure trivial lines
        config.getL2().setFilterTrivialEnabled(true);
        config.getL2().setTrivialLines(java.util.Arrays.asList("{", "}", "<div>"));
        
        SimilarityEngine customEngine = new SimilarityEngine(null, null, null, config);

        // Scenario 1: Chunk analyzed lines > 1, matches exactly 1 line which is "{" (trivial)
        // Short texts bypass L1 to prevent L1 fast-fail.
        String aiCode = "{\n  y";
        String userCode = "{\n  x";
        
        EvaluationResult result1 = customEngine.evaluateChunk(aiCode, userCode, null);
        assertEquals(MatchType.NONE, result1.getMatchType());
        assertEquals(0, result1.getExactContributedLines());
        
        // Scenario 2: Same scenario, but filter is disabled
        config.getL2().setFilterTrivialEnabled(false);
        EvaluationResult result2 = customEngine.evaluateChunk(aiCode, userCode, null);
        assertNotEquals(MatchType.NONE, result2.getMatchType());
        assertEquals(1, result2.getExactContributedLines()); // matches "{"
        
        // Scenario 3: Filter enabled, chunk has only 1 line, matches "{"
        config.getL2().setFilterTrivialEnabled(true);
        EvaluationResult result3 = customEngine.evaluateChunk("{\n  y", "{", null);
        assertNotEquals(MatchType.NONE, result3.getMatchType());
        assertEquals(1, result3.getExactContributedLines());
        
        // Scenario 4: Filter enabled, chunk analyzed lines > 1, matches exactly 1 line which is NOT trivial (e.g. "a")
        String aiCode4 = "a\n  y";
        String userCode4 = "a\n  x";
        EvaluationResult result4 = customEngine.evaluateChunk(aiCode4, userCode4, null);
        assertNotEquals(MatchType.NONE, result4.getMatchType());
        assertEquals(1, result4.getExactContributedLines());
        
        // Scenario 5: Filter enabled, chunk analyzed lines > 1, matches 2 trivial lines ("{" and "}")
        String aiCode5 = "{\n  }\n  y";
        String userCode5 = "{\n  }\n  x";
        EvaluationResult result5 = customEngine.evaluateChunk(aiCode5, userCode5, null);
        assertNotEquals(MatchType.NONE, result5.getMatchType());
        assertEquals(2, result5.getExactContributedLines());
    }

    @Test
    void testEvaluateChunk_Concurrency_DoesNotDeadlockOrHang() throws Exception {
        PipelineConfig config = new PipelineConfig();
        SimilarityEngine customEngine = new SimilarityEngine(null, null, null, config);

        int threadCount = 10;
        int tasksPerThread = 50;
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(threadCount);
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(threadCount * tasksPerThread);

        String aiCode = "public class A {\n    public void test() {\n        System.out.println(\"test\");\n    }\n}";
        String userCode = "public class A {\n    public void test() {\n        System.out.println(\"test\");\n    }\n}";

        SimilarityEngine.EvaluationContext context = SimilarityEngine.EvaluationContext.builder()
                .addedLineCount(5)
                .filePath("A.java")
                .fileContent(userCode)
                .build();

        for (int i = 0; i < threadCount * tasksPerThread; i++) {
            executor.submit(() -> {
                try {
                    EvaluationResult result = customEngine.evaluateChunk(aiCode, userCode, context);
                    assertNotNull(result);
                } finally {
                    latch.countDown();
                }
            });
        }

        boolean finished = latch.await(5, java.util.concurrent.TimeUnit.SECONDS);
        executor.shutdownNow();

        assertTrue(finished, "Test timed out, concurrency deadlock or infinite loop detected!");
    }
}

