package com.macaber.attribution.core;

import lombok.Data;

/**
 * Per-level threshold configuration for the escalation pipeline.
 * Aligned with TS: src/types/index.ts → PipelineConfig interface
 */
@Data
public class PipelineConfig {
    @Data
    public static class L1Config {
        private double fastPass = 0.90;
        private double fastFail = 0.15;
    }
    @Data
    public static class L2Config {
        private double fastPass = 0.80;
        private double fastFail = 0.30;
    }
    @Data
    public static class L3Config {
        private double pass = 0.60;
    }
    @Data
    public static class MultiMessageConfig {
        /** Minimum L2 score required for a message to be considered a multi-message contributor */
        private double threshold = 0.10;
        /** Minimum exact contributed lines required for a message to be considered a multi-message contributor */
        private int minLines = 3;
    }

    private L1Config l1 = new L1Config();
    private L2Config l2 = new L2Config();
    private L3Config l3 = new L3Config();
    private int maxLinesForL3 = 1000;
    /** Per-line match threshold to count a line as AI contributed */
    private double perLineMatchThreshold = 0.70;
    private MultiMessageConfig multiMessage = new MultiMessageConfig();
}
