package com.macaber.attribution.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.macaber.attribution.dao.AttributionResultMapper;
import com.macaber.attribution.entity.AttributionChunkDetail;
import com.macaber.attribution.entity.AttributionResult;
import com.macaber.attribution.service.AttributionChunkDetailService;
import com.macaber.attribution.service.AttributionResultService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AttributionResultServiceImpl extends ServiceImpl<AttributionResultMapper, AttributionResult> implements AttributionResultService {

    private final AttributionChunkDetailService chunkDetailService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveReportWithChunkDetails(AttributionResult report, List<AttributionChunkDetail> chunkDetails) {
        if (report.getId() == null) {
            save(report);
        } else {
            updateById(report);
        }

        // Delete old chunk details if report already existed
        if (report.getId() != null) {
            chunkDetailService.remove(new LambdaQueryWrapper<AttributionChunkDetail>()
                    .eq(AttributionChunkDetail::getReportId, report.getId()));
        }

        if (chunkDetails != null && !chunkDetails.isEmpty()) {
            for (AttributionChunkDetail detail : chunkDetails) {
                detail.setReportId(report.getId());
            }
            chunkDetailService.saveBatch(chunkDetails);
        }
    }
}
