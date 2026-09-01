package com.macaber.attribution.controller;

import com.macaber.attribution.core.AttributionFilter;
import com.macaber.attribution.core.queue.QueueProducer;
import com.macaber.attribution.dto.AttributionJobData;
import com.macaber.attribution.dto.DoMergePayload;
import com.macaber.attribution.dto.MergeFileDetail;
import com.macaber.attribution.entity.AttributionReports;
import com.macaber.attribution.service.AttributionFileDetailService;
import com.macaber.attribution.service.AttributionReportsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WebhookController.class)
class WebhookControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AttributionReportsService resultService;

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

    @Autowired
    private ObjectMapper objectMapper;

    private MergeFileDetail createFileDetail(String path, String code, String diff) {
        MergeFileDetail detail = new MergeFileDetail();
        detail.setPath(path);
        detail.setCode(code);
        detail.setDiff(diff);
        return detail;
    }

    private DoMergePayload createPayload(String mergeId, String sysCode, String repoName, String oa, String title, String detail) {
        DoMergePayload payload = new DoMergePayload();
        payload.setMergeId(mergeId);
        payload.setSysCode(sysCode);
        payload.setRepoName(repoName);
        payload.setOa(oa);
        payload.setTitle(title);
        payload.setSource("feature");
        payload.setTarget("main");
        payload.setDetail(detail);
        return payload;
    }

    @Test
    void testDoMerge_NewReport_CreatesReportWithCreatedAt() throws Exception {
        when(resultService.getOne(any())).thenReturn(null);
        when(attributionFilter.shouldFilter(any())).thenReturn(false);

        List<MergeFileDetail> files = List.of(
                createFileDetail("src/App.java", "public class App {}", "@@ -0,0 +1 @@\n+public class App {}")
        );
        DoMergePayload payload = createPayload("mr-1001", "SYS1", "backend", "alice", "Add App",
                objectMapper.writeValueAsString(files));

        mockMvc.perform(post("/api/coding/doMerge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("accepted"))
                .andExpect(jsonPath("$.mergeId").value("mr-1001"));

        // Verify resultService.remove is never called
        verify(resultService, never()).remove(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));

        // Verify save is called for the new report
        verify(resultService, times(1)).save(any(AttributionReports.class));

        // Verify queue job added
        verify(queueProducer, times(1)).addJob(any(AttributionJobData.class));
    }

    @Test
    void testDoMerge_ExistingReport_PreservesCreatedAtAndDoesNotDelete() throws Exception {
        LocalDateTime originalCreatedAt = LocalDateTime.of(2026, 7, 1, 10, 0, 0);
        AttributionReports existingReport = AttributionReports.builder()
                .id(888L)
                .mergeId("mr-1002")
                .sysCode("SYS1")
                .repoName("backend")
                .userId("bob")
                .title("Original Title")
                .createdAt(originalCreatedAt)
                .build();

        when(resultService.getOne(any())).thenReturn(existingReport);
        when(attributionFilter.shouldFilter(any())).thenReturn(false);

        List<MergeFileDetail> files = List.of(
                createFileDetail("src/App.java", "public class App {}", "@@ -0,0 +1 @@\n+public class App {}")
        );
        DoMergePayload payload = createPayload("mr-1002", "SYS1", "backend", "bob", "Updated Title",
                objectMapper.writeValueAsString(files));

        mockMvc.perform(post("/api/coding/doMerge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("accepted"));

        // CRITICAL: resultService.remove MUST NOT be called!
        verify(resultService, never()).remove(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));

        // Verify updateById is called on existing report
        ArgumentCaptor<AttributionReports> captor = ArgumentCaptor.forClass(AttributionReports.class);
        verify(resultService, times(1)).updateById(captor.capture());

        AttributionReports updated = captor.getValue();
        assertEquals(888L, updated.getId());
        assertEquals("Updated Title", updated.getTitle());
        // Original createdAt is preserved!
        assertEquals(originalCreatedAt, updated.getCreatedAt());

        // Verify queue job has existing reportId
        ArgumentCaptor<AttributionJobData> jobCaptor = ArgumentCaptor.forClass(AttributionJobData.class);
        verify(queueProducer, times(1)).addJob(jobCaptor.capture());
        assertEquals(888L, jobCaptor.getValue().getReportId());
    }

    @Test
    void testDoMerge_RedisEnqueueFails_ReportNotDeleted() throws Exception {
        LocalDateTime originalCreatedAt = LocalDateTime.of(2026, 7, 1, 10, 0, 0);
        AttributionReports existingReport = AttributionReports.builder()
                .id(999L)
                .mergeId("mr-1003")
                .sysCode("SYS1")
                .repoName("backend")
                .userId("bob")
                .createdAt(originalCreatedAt)
                .build();

        when(resultService.getOne(any())).thenReturn(existingReport);
        when(attributionFilter.shouldFilter(any())).thenReturn(false);
        doThrow(new RuntimeException("Redis connection refused")).when(queueProducer).addJob(any());

        List<MergeFileDetail> files = List.of(
                createFileDetail("src/App.java", "public class App {}", "@@ -0,0 +1 @@\n+public class App {}")
        );
        DoMergePayload payload = createPayload("mr-1003", "SYS1", "backend", "bob", "Title",
                objectMapper.writeValueAsString(files));

        mockMvc.perform(post("/api/coding/doMerge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.error").value("Failed to queue attribution job"));

        // Report must NOT have been removed
        verify(resultService, never()).remove(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
    }
}
