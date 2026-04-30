package com.macaber.attribution.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Normalizer {

    public String normalizeText(String rawCode) {
        if (rawCode == null || rawCode.isEmpty()) return "";
        return rawCode.replaceAll("\\s+", "").toLowerCase();
    }

    public LineMapping normalizeWithMapping(String rawCode) {
        if (rawCode == null || rawCode.isEmpty()) {
            return new LineMapping("", new ArrayList<>(), new HashMap<>());
        }

        StringBuilder normalizedText = new StringBuilder();
        List<Integer> charToLineMap = new ArrayList<>();
        Map<Integer, Integer> lineCharCounts = new HashMap<>();

        String[] lines = rawCode.split("\n", -1);
        for (int lineIndex = 0; lineIndex < lines.length; lineIndex++) {
            String stripped = lines[lineIndex].replaceAll("\\s+", "").toLowerCase();

            if (!stripped.isEmpty()) {
                normalizedText.append(stripped);
                for (int i = 0; i < stripped.length(); i++) {
                    charToLineMap.add(lineIndex);
                }
                lineCharCounts.put(lineIndex, stripped.length());
            } else {
                lineCharCounts.put(lineIndex, 0);
            }
        }

        return new LineMapping(normalizedText.toString(), charToLineMap, lineCharCounts);
    }
}
