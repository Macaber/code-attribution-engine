export interface TokenMapping {
  normalizedText: string; // Keep this for winnowing backward compatibility
  tokens: string[];
  tokenToLineMap: number[]; // index = token index, value = 0-indexed line number
  lineTokenCounts: Map<number, number>; // key = 0-indexed line number, value = number of valid tokens
}

/**
 * Line-level normalization result for line-granularity LCS.
 * Each non-blank line is normalized (whitespace-stripped, lowercased) and
 * treated as an atomic unit for LCS comparison.
 */
export interface LineMapping {
  /** Each non-blank line's normalized text (used as atomic LCS comparison unit) */
  normalizedLines: string[];
  /** Maps normalizedLines index → original 0-indexed line number */
  originalLineIndices: number[];
  /** Number of non-blank lines */
  nonBlankLineCount: number;
  /** Flattened normalized text for Winnowing backward compatibility */
  normalizedText: string;
}

/**
 * Normalizer — Regex-based code cleaning for similarity comparison.
 *
 * Removes formatting noise (comments, whitespace, casing) so that
 * semantically identical code produces identical normalized strings.
 */
export class Normalizer {
  /**
   * Normalize raw code by:
   * 1. Removing multi-line comments (/* ... *​/)
   * 2. Removing single-line comments (// ...)
   * 3. Removing all whitespace (spaces, tabs, newlines, carriage returns)
   * 4. Converting to lowercase
   */
  normalizeText(rawCode: string): string {
    if (!rawCode) return '';

    let result = rawCode;

    // Note: We deliberately KEEP comments.
    // If the AI generated comments and the user adopted them, 
    // it should be counted as AI contribution.

    // Step 3: Remove all whitespace characters
    result = result.replace(/\s+/g, '');

    // Step 4: Convert to lowercase
    result = result.toLowerCase();

    return result;
  }

  /**
   * Normalizes code and builds a mapping from each character in the
   * normalized string back to its original line number (0-indexed).
   * @deprecated Use normalizeToTokens instead for Token-based LCS.
   */
  normalizeWithMapping(rawCode: string): any {
    // Legacy support can be maintained if needed, but we recommend replacing its usage.
    throw new Error('Use normalizeToTokens instead');
  }

  /**
   * Normalizes code into an array of tokens (words and symbols), 
   * and maps each token back to its original line number.
   * Also generates normalizedText for Winnowing compatibility.
   * @deprecated Use normalizeToLines() for line-level LCS. Kept for backward compatibility.
   */
  normalizeToTokens(rawCode: string): TokenMapping {
    if (!rawCode) {
      return { normalizedText: '', tokens: [], tokenToLineMap: [], lineTokenCounts: new Map() };
    }

    const tokens: string[] = [];
    const tokenToLineMap: number[] = [];
    const lineTokenCounts = new Map<number, number>();
    const normalizedTextParts: string[] = [];

    const lines = rawCode.split('\n');
    for (let lineIndex = 0; lineIndex < lines.length; lineIndex++) {
      // Split by non-word characters, capturing the separators
      const parts = lines[lineIndex].split(/(\W)/);
      
      let tokensInLine = 0;
      for (const part of parts) {
        if (!part) continue;
        
        // Skip pure whitespace
        if (/^\s+$/.test(part)) continue;
        
        const token = part.toLowerCase();
        tokens.push(token);
        tokenToLineMap.push(lineIndex);
        normalizedTextParts.push(token); // Rebuild flattened text for winnowing
        tokensInLine++;
      }
      
      if (tokensInLine > 0) {
        lineTokenCounts.set(lineIndex, tokensInLine);
      }
    }

    return { 
      normalizedText: normalizedTextParts.join(''), 
      tokens, 
      tokenToLineMap, 
      lineTokenCounts 
    };
  }

  /**
   * Normalizes code at line granularity for line-level LCS comparison.
   *
   * Each line is independently normalized (all whitespace stripped, lowercased).
   * Blank lines (empty after normalization) are excluded.
   * The result maps each normalized line back to its original 0-indexed line number.
   *
   * This replaces the token-level approach: instead of splitting into tokens and
   * tracking per-token line origin, each normalized line becomes the atomic
   * comparison unit for LCS, eliminating cross-line token leakage.
   */
  normalizeToLines(rawCode: string): LineMapping {
    if (!rawCode) {
      return { normalizedLines: [], originalLineIndices: [], nonBlankLineCount: 0, normalizedText: '' };
    }

    const normalizedLines: string[] = [];
    const originalLineIndices: number[] = [];
    const normalizedTextParts: string[] = [];

    const lines = rawCode.split('\n');
    for (let i = 0; i < lines.length; i++) {
      // Normalize each line: strip all whitespace + lowercase
      const normalized = lines[i].replace(/\s+/g, '').toLowerCase();
      if (normalized.length === 0) continue; // Skip blank lines

      normalizedLines.push(normalized);
      originalLineIndices.push(i); // Map back to original 0-indexed line number
      normalizedTextParts.push(normalized); // For Winnowing compatibility
    }

    return {
      normalizedLines,
      originalLineIndices,
      nonBlankLineCount: normalizedLines.length,
      normalizedText: normalizedTextParts.join(''),
    };
  }
}
