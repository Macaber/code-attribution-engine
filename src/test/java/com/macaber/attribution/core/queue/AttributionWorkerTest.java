package com.macaber.attribution.core.queue;

import org.junit.jupiter.api.Test;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import com.macaber.attribution.core.*;
import com.macaber.attribution.dto.*;

class AttributionWorkerTest {

    @Test
    void testSimplifyErrorMessage() {
        AttributionWorker worker = new AttributionWorker(null, null, null, null, null, null, null, null);

        // Test null
        assertEquals("", worker.simplifyErrorMessage(null));

        // Test single exception
        Exception e1 = new IllegalArgumentException("Invalid input");
        assertEquals("IllegalArgumentException: Invalid input", worker.simplifyErrorMessage(e1));

        // Test nested exception
        Exception cause = new NullPointerException("Null reference");
        Exception e2 = new RuntimeException("Execution failed", cause);
        assertEquals("RuntimeException: Execution failed [Root Cause: NullPointerException: Null reference]", worker.simplifyErrorMessage(e2));
    }

    @Test
    void testSimplifyErrorStack() {
        AttributionWorker worker = new AttributionWorker(null, null, null, null, null, null, null, null);

        // Test null
        assertEquals("", worker.simplifyErrorStack(null));

        // Test stack trace formatting and simplification
        Exception e = new RuntimeException("Error occurred");
        String stackStr = worker.simplifyErrorStack(e);
        
        assertNotNull(stackStr);
        assertTrue(stackStr.contains("RuntimeException: Error occurred"));
        
        // Let's create an exception with a custom stack trace simulating different packages
        Exception customEx = new RuntimeException("Custom exception");
        StackTraceElement[] customTrace = new StackTraceElement[] {
            new StackTraceElement("java.util.ArrayList", "get", "ArrayList.java", 435),
            new StackTraceElement("com.macaber.attribution.core.queue.AttributionWorker", "processJob", "AttributionWorker.java", 165),
            new StackTraceElement("org.springframework.aop.support.AopUtils", "invokeJoinpointUsingReflection", "AopUtils.java", 344),
            new StackTraceElement("com.macaber.attribution.core.queue.AttributionWorker", "processQueue", "AttributionWorker.java", 98)
        };
        customEx.setStackTrace(customTrace);

        String simplifiedStack = worker.simplifyErrorStack(customEx);
        
        // Top frame should be present
        assertTrue(simplifiedStack.contains("java.util.ArrayList.get"));
        // Project frames should be present
        assertTrue(simplifiedStack.contains("com.macaber.attribution.core.queue.AttributionWorker.processJob"));
        assertTrue(simplifiedStack.contains("com.macaber.attribution.core.queue.AttributionWorker.processQueue"));
        // Framework frame is in the first 8 frames, so it should be present
        assertTrue(simplifiedStack.contains("org.springframework.aop.support.AopUtils.invokeJoinpointUsingReflection"));
        
        // Let's test truncation by creating many framework frames
        Exception deepEx = new RuntimeException("Deep exception");
        StackTraceElement[] deepTrace = new StackTraceElement[20];
        // 0-9: framework frames
        for (int i = 0; i < 10; i++) {
            deepTrace[i] = new StackTraceElement("org.springframework.Frame" + i, "method", "Frame.java", i);
        }
        // 10: project frame
        deepTrace[10] = new StackTraceElement("com.macaber.attribution.core.queue.AttributionWorker", "processJob", "AttributionWorker.java", 165);
        // 11-19: framework frames
        for (int i = 11; i < 20; i++) {
            deepTrace[i] = new StackTraceElement("org.springframework.Frame" + i, "method", "Frame.java", i);
        }
        deepEx.setStackTrace(deepTrace);
        
        String simplifiedDeepStack = worker.simplifyErrorStack(deepEx);
        // Top 8 frames (0-7) should be present
        for (int i = 0; i < 8; i++) {
            assertTrue(simplifiedDeepStack.contains("org.springframework.Frame" + i), "Frame " + i + " should be present");
        }
        // Frame 8-9 should be truncated (unless project frame)
        assertFalse(simplifiedDeepStack.contains("org.springframework.Frame8"));
        assertFalse(simplifiedDeepStack.contains("org.springframework.Frame9"));
        // Frame 10 (project frame) should be present
        assertTrue(simplifiedDeepStack.contains("com.macaber.attribution.core.queue.AttributionWorker.processJob"));
        // Frame 11-19 should be truncated
        assertFalse(simplifiedDeepStack.contains("org.springframework.Frame11"));
        
        // Should contain truncation note
        assertTrue(simplifiedDeepStack.contains("more framework/internal frames truncated"));
    }

    @Test
    void testIsSameFileName() {
        AttributionWorker worker = new AttributionWorker(null, null, null, null, null, null, null, null);

        // Test identical
        assertTrue(worker.isSameFileName("Normalizer.java", "Normalizer.java"));
        assertTrue(worker.isSameFileName("src/main/Normalizer.java", "src/main/Normalizer.java"));

        // Test case insensitive
        assertTrue(worker.isSameFileName("normalizer.java", "Normalizer.java"));

        // Test path separator mapping
        assertTrue(worker.isSameFileName("src\\main\\Normalizer.java", "src/main/Normalizer.java"));

        // Test base name matching
        assertTrue(worker.isSameFileName("src/main/java/com/macaber/attribution/core/Normalizer.java", "Normalizer.java"));
        assertTrue(worker.isSameFileName("Normalizer.java", "src/main/java/com/macaber/attribution/core/Normalizer.java"));

        // Test mismatch
        assertFalse(worker.isSameFileName("Normalizer.java", "SimilarityEngine.java"));
        assertFalse(worker.isSameFileName("src/Normalizer.java", "src/SimilarityEngine.java"));
        assertFalse(worker.isSameFileName(null, "Normalizer.java"));
        assertFalse(worker.isSameFileName("Normalizer.java", null));
    }

    @Test
    void testProcessChunk_SingleLineThresholdMatch() {
        // Setup mocks
        org.redisson.api.RedissonClient redissonClient = org.mockito.Mockito.mock(org.redisson.api.RedissonClient.class);
        SimilarityEngine similarityEngine = org.mockito.Mockito.mock(SimilarityEngine.class);
        com.macaber.attribution.service.AiMessageService aiMessageService = org.mockito.Mockito.mock(com.macaber.attribution.service.AiMessageService.class);
        com.macaber.attribution.service.AttributionResultService resultService = org.mockito.Mockito.mock(com.macaber.attribution.service.AttributionResultService.class);
        com.macaber.attribution.service.AttributionChunkDetailService chunkDetailService = org.mockito.Mockito.mock(com.macaber.attribution.service.AttributionChunkDetailService.class);
        com.macaber.attribution.service.AttributionFailedJobService failedJobService = org.mockito.Mockito.mock(com.macaber.attribution.service.AttributionFailedJobService.class);
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = org.mockito.Mockito.mock(com.fasterxml.jackson.databind.ObjectMapper.class);
        
        PipelineConfig pipelineConfig = org.mockito.Mockito.mock(PipelineConfig.class);
        PipelineConfig.MultiMessageConfig multiMessageConfig = org.mockito.Mockito.mock(PipelineConfig.MultiMessageConfig.class);
        org.mockito.Mockito.when(pipelineConfig.getMultiMessage()).thenReturn(multiMessageConfig);
        org.mockito.Mockito.when(multiMessageConfig.getThreshold()).thenReturn(0.80);
        org.mockito.Mockito.when(multiMessageConfig.getMinLines()).thenReturn(3);
        org.mockito.Mockito.when(pipelineConfig.getSingleLineThreshold()).thenReturn(20);

        AttributionWorker worker = new AttributionWorker(
                redissonClient, similarityEngine, aiMessageService, resultService,
                chunkDetailService, failedJobService, objectMapper, pipelineConfig
        );

        // Build 1-line chunk with length >= 20
        DiffChunk diffChunk = new DiffChunk();
        diffChunk.setContent("public class MyClassTest {\n");
        diffChunk.setStartLine(1);
        diffChunk.setEndLine(1);
        diffChunk.setFilePath("MyClassTest.java");
        diffChunk.setUserId("user1");
        diffChunk.setNonBlankLineCount(1);

        EnrichedChunk enrichedChunk = new EnrichedChunk(
                diffChunk, "public class MyClassTest {\n", 1, "MyClassTest.java"
        );

        // Build matching AI message
        AiMessageDto aiMessageDto = AiMessageDto.builder()
                .messageId("msg-abc")
                .userId("user1")
                .rawContent("public class MyClassTest {")
                .build();

        AttributionWorker.NormalizedAiMessage normalizedAiMessage = new AttributionWorker.NormalizedAiMessage(
                aiMessageDto, "publicclassmyclasstest{", 
                new Normalizer().normalizeToLines("public class MyClassTest {")
        );

        // Setup similarityEngine mock response
        EvaluationResult evaluationResult = EvaluationResult.builder()
                .score(1.0)
                .matchType(MatchType.FUZZY)
                .level(PipelineLevel.L2)
                .details(new java.util.HashMap<>())
                .exactContributedLines(1)
                .contributedLineIndices(java.util.Set.of(0))
                .build();
        org.mockito.Mockito.when(similarityEngine.evaluateChunk(
                org.mockito.Mockito.anyString(), org.mockito.Mockito.anyString(), org.mockito.Mockito.any()
        )).thenReturn(evaluationResult);

        // Run
        MatchResult matchResult = worker.processChunk(enrichedChunk, List.of(normalizedAiMessage));

        // Verify
        assertEquals("fuzzy", matchResult.getAttribution());
        assertEquals(1.0, matchResult.getContributedLines());
        assertEquals("msg-abc", matchResult.getMatchedMessageIds());
    }
}
