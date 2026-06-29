package com.macaber.attribution.controller;

import com.macaber.attribution.entity.AttributionChunkDetail;
import com.macaber.attribution.entity.AttributionResult;
import com.macaber.attribution.entity.AttributionFileDetail;
import com.macaber.attribution.service.AttributionChunkDetailService;
import com.macaber.attribution.service.AttributionResultService;
import com.macaber.attribution.service.AttributionFileDetailService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.macaber.attribution.core.queue.QueueProducer;
import com.macaber.attribution.core.AttributionFilter;

@WebMvcTest(ReportController.class)
class ReportControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AttributionResultService resultService;

    @MockBean
    private AttributionChunkDetailService chunkDetailService;

    @MockBean
    private AttributionFileDetailService fileDetailService;

    @MockBean
    private QueueProducer queueProducer;

    @MockBean
    private AttributionFilter attributionFilter;

    @MockBean
    private com.macaber.attribution.core.SimilarityEngine similarityEngine;

    @MockBean
    private com.macaber.attribution.service.AiMessageService aiMessageService;



    @Test
    void testGetReportById_NotFound() throws Exception {
        Mockito.when(resultService.getById(999L)).thenReturn(null);

        mockMvc.perform(get("/api/reports/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Report not found for id: 999"));
    }

    @Test
    void testGetReportById_Success() throws Exception {
        AttributionResult report = AttributionResult.builder()
                .id(1L)
                .mergeId("MR-100")
                .repoName("my-repo")
                .userId("user1")
                .createdAt(LocalDateTime.now())
                .build();

        AttributionChunkDetail detail = AttributionChunkDetail.builder()
                .id(101L)
                .reportId(1L)
                .filePath("Main.java")
                .contributedLines(10.0)
                .matchedMessageIds("msg1,msg2")
                .attribution("fuzzy")
                .build();

        Mockito.when(resultService.getById(1L)).thenReturn(report);
        Mockito.when(chunkDetailService.list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(List.of(detail));

        mockMvc.perform(get("/api/reports/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.report.mergeId").value("MR-100"))
                .andExpect(jsonPath("$.chunkDetails[0].filePath").value("Main.java"))
                .andExpect(jsonPath("$.messageBreakdown[0].messageId").value("msg1"))
                .andExpect(jsonPath("$.messageBreakdown[0].contributedLines").value(5.0)); // 10.0 / 2 messages = 5.0
    }

    @Test
    void testGetReportById_WithSysCode_Success() throws Exception {
        AttributionResult report = AttributionResult.builder()
                .id(1L)
                .mergeId("MR-100")
                .sysCode("SYS-A")
                .repoName("my-repo")
                .userId("user1")
                .source("feature/branch-a")
                .target("master")
                .createdAt(LocalDateTime.now())
                .build();

        AttributionChunkDetail detail = AttributionChunkDetail.builder()
                .id(101L)
                .reportId(1L)
                .filePath("Main.java")
                .contributedLines(10.0)
                .matchedMessageIds("msg1,msg2")
                .attribution("fuzzy")
                .build();

        Mockito.when(resultService.getById(1L)).thenReturn(report);
        Mockito.when(chunkDetailService.list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(List.of(detail));

        mockMvc.perform(get("/api/reports/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.report.mergeId").value("MR-100"))
                .andExpect(jsonPath("$.report.sysCode").value("SYS-A"))
                .andExpect(jsonPath("$.report.source").value("feature/branch-a"))
                .andExpect(jsonPath("$.report.target").value("master"));
    }

    @Test
    void testGetReportFiles_Success() throws Exception {
        AttributionResult report = AttributionResult.builder()
                .id(1L)
                .mergeId("MR-100")
                .sysCode("SYS-A")
                .repoName("my-repo")
                .userId("user1")
                .createdAt(LocalDateTime.now())
                .build();

        AttributionFileDetail fileDetail = AttributionFileDetail.builder()
                .id(10L)
                .reportId(1L)
                .filePath("Main.java")
                .code("public class Main {}")
                .diff("@@ -1,1 +1,1 @@")
                .build();

        Mockito.when(resultService.getById(1L)).thenReturn(report);
        Mockito.when(fileDetailService.list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(List.of(fileDetail));

        mockMvc.perform(get("/api/reports/1/files"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].filePath").value("Main.java"))
                .andExpect(jsonPath("$[0].code").value("public class Main {}"))
                .andExpect(jsonPath("$[0].diff").value("@@ -1,1 +1,1 @@"));
    }

    @Test
    void testRecalculateReport_NotFound() throws Exception {
        Mockito.when(resultService.getById(999L)).thenReturn(null);

        mockMvc.perform(post("/api/reports/999/recalculate"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Report not found for id: 999"));
    }

    @Test
    void testRecalculateReport_Success() throws Exception {
        AttributionResult report = AttributionResult.builder()
                .id(1L)
                .mergeId("MR-100")
                .sysCode("SYS-A")
                .repoName("my-repo")
                .userId("user1")
                .createdAt(LocalDateTime.now())
                .build();

        AttributionFileDetail fileDetail = AttributionFileDetail.builder()
                .id(10L)
                .reportId(1L)
                .filePath("Main.java")
                .code("public class Main {}")
                .diff("@@ -1,1 +1,1 @@")
                .build();

        Mockito.when(resultService.getById(1L)).thenReturn(report);
        Mockito.when(fileDetailService.list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(List.of(fileDetail));
        Mockito.when(attributionFilter.shouldFilter(any())).thenReturn(false);

        mockMvc.perform(post("/api/reports/1/recalculate"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("accepted"))
                .andExpect(jsonPath("$.reportId").value(1))
                .andExpect(jsonPath("$.message").value("Recalculation job queued"));

        Mockito.verify(queueProducer, Mockito.times(1)).addJob(any());
    }

    @Test
    void testRecalculateReport_Skipped() throws Exception {
        AttributionResult report = AttributionResult.builder()
                .id(1L)
                .mergeId("MR-100")
                .sysCode("SYS-A")
                .repoName("my-repo")
                .userId("user1")
                .createdAt(LocalDateTime.now())
                .build();

        AttributionFileDetail fileDetail = AttributionFileDetail.builder()
                .id(10L)
                .reportId(1L)
                .filePath("Main.java")
                .code("public class Main {}")
                .diff("") // empty diff
                .build();

        Mockito.when(resultService.getById(1L)).thenReturn(report);
        Mockito.when(fileDetailService.list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(List.of(fileDetail));

        mockMvc.perform(post("/api/reports/1/recalculate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("skipped"))
                .andExpect(jsonPath("$.message").value("No file diffs to analyze"));
    }

    @Test
    void testRecalculateReport_WithTimeframeDays() throws Exception {
        AttributionResult report = AttributionResult.builder()
                .id(1L)
                .mergeId("MR-100")
                .sysCode("SYS-A")
                .repoName("my-repo")
                .userId("user1")
                .createdAt(LocalDateTime.now())
                .build();

        AttributionFileDetail fileDetail = AttributionFileDetail.builder()
                .id(10L)
                .reportId(1L)
                .filePath("Main.java")
                .code("public class Main {}")
                .diff("@@ -1,1 +1,1 @@")
                .build();

        Mockito.when(resultService.getById(1L)).thenReturn(report);
        Mockito.when(fileDetailService.list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(List.of(fileDetail));
        Mockito.when(attributionFilter.shouldFilter(any())).thenReturn(false);

        mockMvc.perform(post("/api/reports/1/recalculate").param("timeframeDays", "15"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("accepted"))
                .andExpect(jsonPath("$.reportId").value(1));

        Mockito.verify(queueProducer, Mockito.times(1)).addJob(org.mockito.ArgumentMatchers.argThat(job -> 
                job.getTimeframeDays() != null && job.getTimeframeDays() == 15
        ));
    }

    @Test
    void testGetReportVisualization_Success() throws Exception {
        AttributionResult report = AttributionResult.builder()
                .id(1L)
                .mergeId("MR-100")
                .sysCode("SYS-A")
                .repoName("my-repo")
                .userId("user1")
                .createdAt(LocalDateTime.now())
                .build();

        AttributionFileDetail fileDetail = AttributionFileDetail.builder()
                .id(10L)
                .reportId(1L)
                .filePath("src/Test.java")
                .code("public class Test {\n}")
                .diff("diff --git a/src/Test.java b/src/Test.java\n" +
                        "--- a/src/Test.java\n" +
                        "+++ b/src/Test.java\n" +
                        "@@ -1,2 +1,2 @@\n" +
                        "+(yfsun)+public class Test {\n" +
                        "+(yfsun)+}")
                .build();

        AttributionChunkDetail chunkDetail = AttributionChunkDetail.builder()
                .id(101L)
                .reportId(1L)
                .filePath("src/Test.java")
                .startLine(1)
                .endLine(2)
                .matchedMessageIds("2001")
                .attribution("fuzzy")
                .score(0.9)
                .matchType("FUZZY")
                .level("L2")
                .build();

        Mockito.when(resultService.getById(1L)).thenReturn(report);
        Mockito.when(fileDetailService.list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(List.of(fileDetail));
        Mockito.when(chunkDetailService.list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(List.of(chunkDetail));
        Mockito.when(aiMessageService.listByIds(any())).thenReturn(Collections.emptyList());

        mockMvc.perform(get("/api/reports/1/visualization"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].filePath").value("src/Test.java"))
                .andExpect(jsonPath("$[0].startLine").value(1))
                .andExpect(jsonPath("$[0].endLine").value(2))
                .andExpect(jsonPath("$[0].attribution").value("fuzzy"));
    }
}

