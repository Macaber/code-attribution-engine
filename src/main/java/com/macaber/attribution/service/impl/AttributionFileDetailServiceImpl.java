package com.macaber.attribution.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.macaber.attribution.dao.AttributionFileDetailMapper;
import com.macaber.attribution.entity.AttributionFileDetail;
import com.macaber.attribution.service.AttributionFileDetailService;
import org.springframework.stereotype.Service;

@Service
public class AttributionFileDetailServiceImpl extends ServiceImpl<AttributionFileDetailMapper, AttributionFileDetail> implements AttributionFileDetailService {
}
