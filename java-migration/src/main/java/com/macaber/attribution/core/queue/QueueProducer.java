package com.macaber.attribution.core.queue;

import com.macaber.attribution.dto.AttributionJobData;

public interface QueueProducer {
    void addJob(AttributionJobData jobData);
}
