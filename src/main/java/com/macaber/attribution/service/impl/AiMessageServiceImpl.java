package com.macaber.attribution.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.macaber.attribution.service.AiMessageService;
import com.macaber.attribution.entity.AiMessage;
import com.macaber.attribution.dao.AiMessageMapper;
import org.springframework.stereotype.Service;

@Service
public class AiMessageServiceImpl extends ServiceImpl<AiMessageMapper, AiMessage> implements AiMessageService {
}
