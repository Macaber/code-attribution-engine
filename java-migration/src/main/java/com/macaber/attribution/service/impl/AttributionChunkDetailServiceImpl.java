package com.macaber.attribution.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.macaber.attribution.dao.AttributionChunkDetailMapper;
import com.macaber.attribution.entity.AttributionChunkDetail;
import com.macaber.attribution.service.AttributionChunkDetailService;
import org.springframework.stereotype.Service;

@Service
public class AttributionChunkDetailServiceImpl extends ServiceImpl<AttributionChunkDetailMapper, AttributionChunkDetail> implements AttributionChunkDetailService {
}
