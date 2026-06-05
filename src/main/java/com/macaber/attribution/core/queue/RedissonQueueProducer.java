package com.macaber.attribution.core.queue;

import com.macaber.attribution.dto.AttributionJobData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBlockingQueue;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class RedissonQueueProducer implements QueueProducer {

    private final RedissonClient redissonClient;
    private static final String QUEUE_NAME = "attribution-queue";

    @Override
    public void addJob(AttributionJobData jobData) {
        RBlockingQueue<AttributionJobData> queue = redissonClient.getBlockingQueue(QUEUE_NAME);
        try {
            queue.put(jobData);
            log.info("[Queue] Successfully added job for mergeId: {}", jobData.getMergeId());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("[Queue] Failed to add job to queue", e);
        }
    }
}
