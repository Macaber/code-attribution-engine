package com.macaber.attribution.core;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

/**
 * Line-level normalization result for line-granularity LCS.
 * Each non-blank line is normalized (whitespace-stripped, lowercased) and
 * treated as an atomic unit for LCS comparison.
 *
 * Aligned with TS: src/domains/attribution/normalizer.ts → LineMapping interface
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LineMapping {
    /** Each non-blank line's normalized text (used as atomic LCS comparison unit) */
    private List<String> normalizedLines;
    /** Maps normalizedLines index → original 0-indexed line number */
    private List<Integer> originalLineIndices;
    /** Number of non-blank lines */
    private int nonBlankLineCount;
    /** Flattened normalized text for Winnowing backward compatibility */
    private String normalizedText;
}
