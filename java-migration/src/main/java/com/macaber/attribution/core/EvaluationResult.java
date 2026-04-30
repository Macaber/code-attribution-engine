package com.macaber.attribution.core;

import lombok.Builder;
import lombok.Data;
import java.util.Map;

@Data
@Builder
public class EvaluationResult {
    private double score;
    private MatchType matchType;
    private PipelineLevel level;
    private Map<String, Double> details;
    private int exactContributedLines;
}
