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

    // Step 1: Remove multi-line comments (non-greedy)
    result = result.replace(/\/\*[\s\S]*?\*\//g, '');

    // Step 2: Remove single-line comments
    result = result.replace(/\/\/.*$/gm, '');

    // Step 3: Remove all whitespace characters
    result = result.replace(/\s+/g, '');

    // Step 4: Convert to lowercase
    result = result.toLowerCase();

    return result;
  }
}
