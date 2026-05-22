package com.macaber.attribution.core;

import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.List;

/**
 * Per-level threshold configuration for the escalation pipeline.
 * Aligned with TS: src/types/index.ts → PipelineConfig interface
 */
@Component
@Data
public class PipelineConfig {
    @Data
    public static class L1Config {
        private double fastPass = 0.90;
        private double fastFail = 0.15;
    }
    @Data
    public class L2Config {
        private double fastPass = 0.80;
        private double fastFail = 0.30;

        public boolean isFilterTrivialEnabled() {
            return filterTrivialEnabled;
        }

        public void setFilterTrivialEnabled(boolean filterTrivialEnabled) {
            PipelineConfig.this.filterTrivialEnabled = filterTrivialEnabled;
        }

        public List<String> getTrivialLines() {
            return trivialLines;
        }

        public void setTrivialLines(List<String> trivialLines) {
            PipelineConfig.this.trivialLines = trivialLines;
        }

        public List<String> getNormalizedTrivialLines() {
            if (trivialLines == null) {
                return java.util.Collections.emptyList();
            }
            List<String> list = new java.util.ArrayList<>();
            for (String line : trivialLines) {
                if (line != null) {
                    String normalized = line.replaceAll("\\s+", "").toLowerCase();
                    if (!normalized.isEmpty()) {
                        list.add(normalized);
                    }
                }
            }
            return list;
        }
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

    @Value("${attribution.l2.filter-trivial.enabled:false}")
    private boolean filterTrivialEnabled = false;

    @Value("${attribution.l2.filter-trivial.lines:{,}}")
    private List<String> trivialLines = java.util.Arrays.asList("{", "}");

}

