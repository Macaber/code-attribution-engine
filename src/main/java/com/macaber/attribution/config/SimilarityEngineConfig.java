package com.macaber.attribution.config;

import com.macaber.attribution.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.treesitter.TreeSitterJava;
import org.treesitter.TreeSitterJavascript;
import org.treesitter.TreeSitterTypescript;

@Configuration
public class SimilarityEngineConfig {

    @Bean
    public SimilarityEngine similarityEngine(PipelineConfig pipelineConfig) {
        // Initialize with default configurations
        SimilarityWeights weights = new SimilarityWeights();
        WinnowingConfig winnowingConfig = new WinnowingConfig();
        LcsConfig lcsConfig = new LcsConfig();
        
        // If we want to use AST, we need to inject or create AstFeatureEngine
        AstFeatureEngine astEngine = null;
        try {
            astEngine = new AstFeatureEngine(100, grammarName -> {
                if ("java".equalsIgnoreCase(grammarName)) {
                    return new TreeSitterJava();
                } else if ("javascript".equalsIgnoreCase(grammarName)) {
                    return new TreeSitterJavascript();
                } else if ("typescript".equalsIgnoreCase(grammarName)) {
                    return new TreeSitterTypescript();
                }
                return null;
            });
        } catch (Exception e) {
            // AST unavailable, proceed without it
            System.err.println("AST Engine initialization failed. Will continue without L3: " + e.getMessage());
        }

        return new SimilarityEngine(weights, winnowingConfig, lcsConfig, pipelineConfig, astEngine);
    }
}
