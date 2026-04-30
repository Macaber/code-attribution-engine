package com.macaber.attribution.core;

import lombok.Data;

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

    private L1Config l1 = new L1Config();
    private L2Config l2 = new L2Config();
    private L3Config l3 = new L3Config();
    private int maxLinesForL3 = 1000;
}
