package com.macaber.attribution.core;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LineMapping {
    private String normalizedText;
    private List<Integer> charToLineMap;
    private Map<Integer, Integer> lineCharCounts;
}
