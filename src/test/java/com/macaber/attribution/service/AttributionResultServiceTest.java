package com.macaber.attribution.service;

import com.macaber.attribution.dao.AttributionResultMapper;
import com.macaber.attribution.entity.AttributionChunkDetail;
import com.macaber.attribution.entity.AttributionResult;
import com.macaber.attribution.service.impl.AttributionResultServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AttributionResultServiceTest {

    @Mock
    private AttributionResultMapper resultMapper;

    @Mock
    private AttributionChunkDetailService chunkDetailService;

    @InjectMocks
    private AttributionResultServiceImpl resultService;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        org.springframework.test.util.ReflectionTestUtils.setField(resultService, "baseMapper", resultMapper);
    }

    @Test
    void testSaveReportWithChunkDetails_ExistingReport() {
        AttributionResult report = AttributionResult.builder()
                .id(100L)
                .mergeId("mr-1")
                .sysCode("SYS")
                .totalCodeLines(50)
                .build();

        AttributionChunkDetail detail1 = AttributionChunkDetail.builder()
                .filePath("src/A.java")
                .startLine(1)
                .endLine(10)
                .build();

        AttributionChunkDetail detail2 = AttributionChunkDetail.builder()
                .filePath("src/B.java")
                .startLine(1)
                .endLine(5)
                .build();

        when(resultMapper.updateById(report)).thenReturn(1);

        resultService.saveReportWithChunkDetails(report, List.of(detail1, detail2));

        // Verify report update
        verify(resultMapper, times(1)).updateById(report);

        // Verify old chunk details removed
        verify(chunkDetailService, times(1)).remove(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));

        // Verify new chunk details batch saved with reportId set
        ArgumentCaptor<List<AttributionChunkDetail>> captor = ArgumentCaptor.forClass(List.class);
        verify(chunkDetailService, times(1)).saveBatch(captor.capture());

        List<AttributionChunkDetail> savedList = captor.getValue();
        assertEquals(2, savedList.size());
        assertEquals(100L, savedList.get(0).getReportId());
        assertEquals(100L, savedList.get(1).getReportId());
    }

    @Test
    void testSaveReportWithChunkDetails_NewReport() {
        AttributionResult report = AttributionResult.builder()
                .mergeId("mr-2")
                .sysCode("SYS")
                .totalCodeLines(20)
                .build();

        AttributionChunkDetail detail = AttributionChunkDetail.builder()
                .filePath("src/A.java")
                .build();

        // Simulate insert setting the generated ID
        doAnswer(invocation -> {
            AttributionResult entity = invocation.getArgument(0);
            entity.setId(200L);
            return 1;
        }).when(resultMapper).insert(report);

        resultService.saveReportWithChunkDetails(report, List.of(detail));

        verify(resultMapper, times(1)).insert(report);
        verify(chunkDetailService, times(1)).remove(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
        verify(chunkDetailService, times(1)).saveBatch(anyList());
        assertEquals(200L, detail.getReportId());
    }
}
