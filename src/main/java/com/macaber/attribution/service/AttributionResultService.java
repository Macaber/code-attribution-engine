package com.macaber.attribution.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.macaber.attribution.entity.AttributionChunkDetail;
import com.macaber.attribution.entity.AttributionResult;

import java.util.List;

public interface AttributionResultService extends IService<AttributionResult> {

    /**
     * Atomically save/update report summary, clear old chunk details and batch insert new chunk details.
     */
    void saveReportWithChunkDetails(AttributionResult report, List<AttributionChunkDetail> chunkDetails);
}

