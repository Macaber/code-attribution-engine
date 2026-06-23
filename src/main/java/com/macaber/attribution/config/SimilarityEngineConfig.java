package com.macaber.attribution.config;

import com.macaber.attribution.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SimilarityEngineConfig {

    @Bean
    public SimilarityEngine similarityEngine(PipelineConfig pipelineConfig) {
        // Initialize with default configurations
        SimilarityWeights weights = new SimilarityWeights();
        WinnowingConfig winnowingConfig = new WinnowingConfig();
        LcsConfig lcsConfig = new LcsConfig();
        
        return new SimilarityEngine(weights, winnowingConfig, lcsConfig, pipelineConfig);
    }
}
