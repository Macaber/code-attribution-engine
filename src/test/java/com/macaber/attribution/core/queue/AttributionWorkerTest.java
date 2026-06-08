package com.macaber.attribution.core.queue;

import org.junit.jupiter.api.Test;
import java.util.HashSet;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class AttributionWorkerTest {

    @Test
    void testSimplifyErrorMessage() {
        AttributionWorker worker = new AttributionWorker(null, null, null, null, null, null, null, null, null);

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
        AttributionWorker worker = new AttributionWorker(null, null, null, null, null, null, null, null, null);

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
}
