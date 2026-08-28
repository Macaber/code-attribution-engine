package com.macaber.attribution.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.macaber.attribution.dao.AttributionChunkDetailMapper;
import com.macaber.attribution.dto.ChunkQueryResultDto;
import com.macaber.attribution.entity.AttributionChunkDetail;
import com.macaber.attribution.service.AttributionChunkDetailService;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class AttributionChunkDetailServiceImpl extends ServiceImpl<AttributionChunkDetailMapper, AttributionChunkDetail> implements AttributionChunkDetailService {

    @Override
    public IPage<ChunkQueryResultDto> selectChunkWithReportPage(IPage<ChunkQueryResultDto> page,
                                                                String userId,
                                                                String repoName,
                                                                String sysCode,
                                                                String startDate,
                                                                String endDate) {
        return baseMapper.selectChunkWithReportPage(page, userId, repoName, sysCode, startDate, endDate);
    }

    @Override
    public Map<String, Object> selectChunkSummary(String userId,
                                                  String repoName,
                                                  String sysCode,
                                                  String startDate,
                                                  String endDate) {
        return baseMapper.selectChunkSummary(userId, repoName, sysCode, startDate, endDate);
    }
}
