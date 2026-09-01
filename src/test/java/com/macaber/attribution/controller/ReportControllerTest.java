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

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void testGetStatsBreakdown_RepoName() throws Exception {
        java.util.Map<String, Object> record = new java.util.HashMap<>();
        record.put("sysCode", "euvd");
        record.put("repoName", "jxchat");
        record.put("totalAnalyzedLines", 100L);
        record.put("totalAiContributedLines", 20.5);

        Mockito.when(resultService.listMaps(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
                .thenReturn(List.of(record));

        mockMvc.perform(get("/api/reports/stats/breakdown")
                .param("groupBy", "repo-name"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].name").value("euvd/jxchat"))
                .andExpect(jsonPath("$[0].analyzedLines").value(100))
                .andExpect(jsonPath("$[0].aiContributedLines").value(20.5))
                .andExpect(jsonPath("$[0].aiRatio").value(0.205));
    }

    @Test
    void testGetChunks_Success() throws Exception {
        com.macaber.attribution.dto.ChunkQueryResultDto chunkDto = com.macaber.attribution.dto.ChunkQueryResultDto.builder()
                .id(10L)
                .reportId(1L)
                .userId("dev1")
                .filePath("src/Main.java")
                .startLine(1)
                .endLine(10)
                .attribution("strict")
                .score(1.0)
                .repoName("my-repo")
                .sysCode("SYS01")
                .source("feature")
                .target("main")
                .build();

        com.baomidou.mybatisplus.extension.plugins.pagination.Page<com.macaber.attribution.dto.ChunkQueryResultDto> pageResult =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 20);
        pageResult.setRecords(List.of(chunkDto));
        pageResult.setTotal(1);
        pageResult.setPages(1);

        Mockito.when(chunkDetailService.selectChunkWithReportPage(any(), any(), any(), any(), any(), any()))
                .thenReturn(pageResult);

        mockMvc.perform(get("/api/reports/chunks")
                .param("userId", "dev1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(10))
                .andExpect(jsonPath("$.data[0].userId").value("dev1"))
                .andExpect(jsonPath("$.data[0].repoName").value("my-repo"))
                .andExpect(jsonPath("$.data[0].sysCode").value("SYS01"))
                .andExpect(jsonPath("$.data[0].source").value("feature"))
                .andExpect(jsonPath("$.data[0].target").value("main"));
    }

    @Test
    void testGetChunkDetail_Success() throws Exception {
        AttributionChunkDetail chunkDetail = AttributionChunkDetail.builder()
                .id(10L)
                .reportId(1L)
                .userId("dev1")
                .filePath("src/Main.java")
                .startLine(1)
                .endLine(5)
                .attribution("strict")
                .score(0.9)
                .matchedMessageIds("101")
                .build();

        AttributionResult report = AttributionResult.builder()
                .id(1L)
                .repoName("my-repo")
                .sysCode("SYS01")
                .source("feature")
                .target("main")
                .build();

        Mockito.when(chunkDetailService.getById(10L)).thenReturn(chunkDetail);
        Mockito.when(resultService.getById(1L)).thenReturn(report);
        Mockito.when(fileDetailService.list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
                .thenReturn(Collections.emptyList());

        mockMvc.perform(get("/api/reports/chunks/10/detail"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chunkId").value(10))
                .andExpect(jsonPath("$.filePath").value("src/Main.java"))
                .andExpect(jsonPath("$.userId").value("dev1"))
                .andExpect(jsonPath("$.repoName").value("my-repo"))
                .andExpect(jsonPath("$.sysCode").value("SYS01"))
                .andExpect(jsonPath("$.source").value("feature"))
                .andExpect(jsonPath("$.target").value("main"));
    }

    @Test
    void testGetReports_NegativeOrZeroPageSize_NormalizedToDefault() throws Exception {
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<AttributionResult> pageResult =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 20);
        pageResult.setRecords(Collections.emptyList());
        pageResult.setTotal(0);
        pageResult.setPages(0);

        org.mockito.ArgumentCaptor<com.baomidou.mybatisplus.extension.plugins.pagination.Page<AttributionResult>> pageCaptor =
                org.mockito.ArgumentCaptor.forClass(com.baomidou.mybatisplus.extension.plugins.pagination.Page.class);

        Mockito.when(resultService.page(pageCaptor.capture(), any())).thenReturn(pageResult);

        // Test with negative pageSize and negative page
        mockMvc.perform(get("/api/reports")
                        .param("page", "-5")
                        .param("pageSize", "-1"))
                .andExpect(status().isOk());

        com.baomidou.mybatisplus.extension.plugins.pagination.Page<AttributionResult> capturedPage = pageCaptor.getValue();
        assertEquals(1, capturedPage.getCurrent(), "Negative page should be normalized to 1");
        assertEquals(20, capturedPage.getSize(), "Negative pageSize should be normalized to 20");
    }

    @Test
    void testGetReports_ExceedsMaxPageSize_NormalizedTo100() throws Exception {
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<AttributionResult> pageResult =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 100);
        pageResult.setRecords(Collections.emptyList());
        pageResult.setTotal(0);
        pageResult.setPages(0);

        org.mockito.ArgumentCaptor<com.baomidou.mybatisplus.extension.plugins.pagination.Page<AttributionResult>> pageCaptor =
                org.mockito.ArgumentCaptor.forClass(com.baomidou.mybatisplus.extension.plugins.pagination.Page.class);

        Mockito.when(resultService.page(pageCaptor.capture(), any())).thenReturn(pageResult);

        mockMvc.perform(get("/api/reports")
                        .param("pageSize", "500"))
                .andExpect(status().isOk());

        com.baomidou.mybatisplus.extension.plugins.pagination.Page<AttributionResult> capturedPage = pageCaptor.getValue();
        assertEquals(100, capturedPage.getSize(), "PageSize > 100 should be capped at 100");
    }
}

