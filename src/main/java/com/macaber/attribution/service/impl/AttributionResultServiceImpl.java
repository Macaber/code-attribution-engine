package com.macaber.attribution.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.macaber.attribution.service.AttributionResultService;
import com.macaber.attribution.entity.AttributionResult;
import com.macaber.attribution.dao.AttributionResultMapper;
import org.springframework.stereotype.Service;

@Service
public class AttributionResultServiceImpl extends ServiceImpl<AttributionResultMapper, AttributionResult> implements AttributionResultService {
}
