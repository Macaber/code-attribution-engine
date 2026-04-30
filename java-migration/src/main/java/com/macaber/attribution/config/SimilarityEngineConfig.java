package com.macaber.attribution.config;

import com.macaber.attribution.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SimilarityEngineConfig {

    @Bean
    public SimilarityEngine similarityEngine() {
        // Initialize with default configurations
        SimilarityWeights weights = new SimilarityWeights();
        WinnowingConfig winnowingConfig = new WinnowingConfig();
        LcsConfig lcsConfig = new LcsConfig();
        PipelineConfig pipelineConfig = new PipelineConfig();
        
        // If we want to use AST, we need to inject or create AstFeatureEngine
        // For simplicity, we initialize it without grammar configs if native lib is present
        AstFeatureEngine astEngine = null;
        try {
            astEngine = new AstFeatureEngine();
        } catch (Exception e) {
            // AST unavailable, proceed without it
            System.err.println("AST Engine initialization failed. Will continue without L3: " + e.getMessage());
        }

        return new SimilarityEngine(weights, winnowingConfig, lcsConfig, pipelineConfig, astEngine);
    }
}
