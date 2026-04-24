export interface LineMapping {
  normalizedText: string;
  charToLineMap: number[]; // index = char index, value = 0-indexed line number
  lineCharCounts: Map<number, number>; // key = 0-indexed line number, value = number of valid characters
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
   */
  normalizeWithMapping(rawCode: string): LineMapping {
    if (!rawCode) {
      return { normalizedText: '', charToLineMap: [], lineCharCounts: new Map() };
    }

    let normalizedText = '';
    const charToLineMap: number[] = [];
    const lineCharCounts = new Map<number, number>();

    const lines = rawCode.split('\n');
    for (let lineIndex = 0; lineIndex < lines.length; lineIndex++) {
      // Step 3 & 4 equivalent for a single line
      const stripped = lines[lineIndex].replace(/\s+/g, '').toLowerCase();

      if (stripped.length > 0) {
        normalizedText += stripped;
        for (let i = 0; i < stripped.length; i++) {
          charToLineMap.push(lineIndex);
        }
        lineCharCounts.set(lineIndex, stripped.length);
      }
    }

    return { normalizedText, charToLineMap, lineCharCounts };
  }
}
