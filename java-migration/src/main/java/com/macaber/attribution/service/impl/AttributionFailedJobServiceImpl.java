package com.macaber.attribution.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.macaber.attribution.dao.AttributionFailedJobMapper;
import com.macaber.attribution.entity.AttributionFailedJob;
import com.macaber.attribution.service.AttributionFailedJobService;
import org.springframework.stereotype.Service;

@Service
public class AttributionFailedJobServiceImpl extends ServiceImpl<AttributionFailedJobMapper, AttributionFailedJob> implements AttributionFailedJobService {
}
