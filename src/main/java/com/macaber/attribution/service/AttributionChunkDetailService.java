package com.macaber.attribution.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.macaber.attribution.dto.ChunkQueryResultDto;
import com.macaber.attribution.entity.AttributionChunkDetail;

import java.util.Map;

public interface AttributionChunkDetailService extends IService<AttributionChunkDetail> {

    IPage<ChunkQueryResultDto> selectChunkWithReportPage(IPage<ChunkQueryResultDto> page,
                                                         String userId,
                                                         String repoName,
                                                         String sysCode,
                                                         String startDate,
                                                         String endDate);

    Map<String, Object> selectChunkSummary(String userId,
                                           String repoName,
                                           String sysCode,
                                           String startDate,
                                           String endDate);
}
