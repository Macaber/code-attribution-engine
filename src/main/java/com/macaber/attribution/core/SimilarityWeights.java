package com.macaber.attribution.core;

import lombok.Data;

@Data
public class SimilarityWeights {
    private double winnowing = 0.4;
    private double lcs = 0.6;
}
