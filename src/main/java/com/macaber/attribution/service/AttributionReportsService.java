package com.macaber.attribution.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.macaber.attribution.entity.AttributionChunkDetail;
import com.macaber.attribution.entity.AttributionReports;

import java.util.List;

public interface AttributionReportsService extends IService<AttributionReports> {

    /**
     * Atomically save/update report summary, clear old chunk details and batch insert new chunk details.
     */
    void saveReportWithChunkDetails(AttributionReports report, List<AttributionChunkDetail> chunkDetails);
}
