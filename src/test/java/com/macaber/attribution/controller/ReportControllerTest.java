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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

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

    @Test
    void testGetReportByMergeId_NotFound() throws Exception {
        Mockito.when(resultService.getOne(any())).thenReturn(null);

        mockMvc.perform(get("/api/reports/MR-NONEXISTENT"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Report not found for mergeId: MR-NONEXISTENT"));
    }

    @Test
    void testGetReportByMergeId_Success() throws Exception {
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

        Mockito.when(resultService.getOne(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(report);
        Mockito.when(chunkDetailService.list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(List.of(detail));

        mockMvc.perform(get("/api/reports/MR-100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.report.mergeId").value("MR-100"))
                .andExpect(jsonPath("$.chunkDetails[0].filePath").value("Main.java"))
                .andExpect(jsonPath("$.messageBreakdown[0].messageId").value("msg1"))
                .andExpect(jsonPath("$.messageBreakdown[0].contributedLines").value(5.0)); // 10.0 / 2 messages = 5.0
    }

    @Test
    void testGetReportByMergeId_WithSysCode_Success() throws Exception {
        AttributionResult report = AttributionResult.builder()
                .id(1L)
                .mergeId("MR-100")
                .sysCode("SYS-A")
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

        Mockito.when(resultService.getOne(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(report);
        Mockito.when(chunkDetailService.list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(List.of(detail));

        mockMvc.perform(get("/api/reports/MR-100").param("sysCode", "SYS-A"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.report.mergeId").value("MR-100"))
                .andExpect(jsonPath("$.report.sysCode").value("SYS-A"));
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

        Mockito.when(resultService.getOne(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(report);
        Mockito.when(fileDetailService.list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(List.of(fileDetail));

        mockMvc.perform(get("/api/reports/MR-100/files").param("sysCode", "SYS-A"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].filePath").value("Main.java"))
                .andExpect(jsonPath("$[0].code").value("public class Main {}"))
                .andExpect(jsonPath("$[0].diff").value("@@ -1,1 +1,1 @@"));
    }
}
