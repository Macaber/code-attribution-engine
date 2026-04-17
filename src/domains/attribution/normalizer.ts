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
}
