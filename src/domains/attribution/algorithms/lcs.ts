import { LcsConfig } from '../../../types';

/**
 * LCS (Longest Common Subsequence) Algorithm — Micro-level similarity measurement.
 *
 * Uses dynamic programming with two-row space optimization: O(min(M,N)) space.
 * Includes a circuit breaker for large inputs to prevent O(N²) memory/time explosion.
 */
export class LCS {
  private readonly maxCells: number;

  constructor(config?: Partial<LcsConfig>) {
    this.maxCells = config?.maxCells ?? 10_000_000;
  }

  /**
   * Calculate the length of the Longest Common Subsequence of two strings.
   * Uses space-optimized DP (two rows only).
   *
   * If the inputs exceed maxCells, both strings are proportionally truncated.
   */
  calculateLcsLength(a: string, b: string): number {
    if (a.length === 0 || b.length === 0) return 0;

    // Circuit breaker: truncate if N*M exceeds threshold
    let strA = a;
    let strB = b;
    if (strA.length * strB.length > this.maxCells) {
      const ratio = Math.sqrt(this.maxCells / (strA.length * strB.length));
      const newLenA = Math.max(1, Math.floor(strA.length * ratio));
      const newLenB = Math.max(1, Math.floor(strB.length * ratio));
      strA = strA.substring(0, newLenA);
      strB = strB.substring(0, newLenB);
    }

    // Ensure strB is the shorter string for space optimization
    if (strA.length < strB.length) {
      [strA, strB] = [strB, strA];
    }

    const m = strA.length;
    const n = strB.length;

    // Two-row DP: O(min(m,n)) space
    let prev = new Uint16Array(n + 1);
    let curr = new Uint16Array(n + 1);

    for (let i = 1; i <= m; i++) {
      for (let j = 1; j <= n; j++) {
        if (strA[i - 1] === strB[j - 1]) {
          curr[j] = prev[j - 1] + 1;
        } else {
          curr[j] = Math.max(prev[j], curr[j - 1]);
        }
      }
      // Swap rows
      [prev, curr] = [curr, prev];
    }

    return prev[n];
  }

  /**
   * Calculates the LCS and returns the exact indices of `target` that match `reference`.
   * Uses a full 1D-backed 2D DP matrix to allow backtracking.
   *
   * @param reference The base string
   * @param target The string whose matched indices are desired
   * @returns Array of character indices in `target` that matched.
   */
  calculateTraceableLcs(reference: string, target: string): number[] {
    if (reference.length === 0 || target.length === 0) return [];

    let refStr = reference;
    let tgtStr = target;

    // If input is small enough, use precise global LCS
    if (refStr.length * tgtStr.length <= this.maxCells) {
      return this._calculateTraceableLcsInternal(refStr, tgtStr);
    }

    // ═════════════════════════════════════════════════════
    // Circuit breaker: Chunked sliding-window LCS approximation
    // To prevent truncating the end of large files (which drops 
    // exact matched lines), we split the target into safe chunks
    // and match each against a sliding localized window of reference.
    // ═════════════════════════════════════════════════════
    
    // We want chunk_M * chunk_N <= maxCells.
    // Let's use a fixed target chunk size (e.g., 2000 chars)
    // and a reference window size (e.g., 4000 chars) to allow +/- 1000 shift.
    const tgtChunkSize = Math.floor(Math.sqrt(this.maxCells / 2)); // ~2236 for 10M cells
    const refWindowSize = tgtChunkSize * 2; // ~4472

    const matchedTargetIndices: number[] = [];

    for (let tgtOffset = 0; tgtOffset < tgtStr.length; tgtOffset += tgtChunkSize) {
      const tgtChunk = tgtStr.substring(tgtOffset, tgtOffset + tgtChunkSize);

      // Estimate where in reference string this chunk should align
      const expectedCenter = Math.floor((tgtOffset + tgtChunk.length / 2) / tgtStr.length * refStr.length);
      const refWindowStart = Math.max(0, expectedCenter - refWindowSize / 2);
      const refWindowEnd = Math.min(refStr.length, expectedCenter + refWindowSize / 2);
      const refChunk = refStr.substring(refWindowStart, refWindowEnd);

      // Calculate localized LCS
      const localMatches = this._calculateTraceableLcsInternal(refChunk, tgtChunk);

      // Map local target indices back to global target indices
      for (const localIdx of localMatches) {
        matchedTargetIndices.push(tgtOffset + localIdx);
      }
    }

    return matchedTargetIndices;
  }

  /**
   * Calculates the LCS for token arrays and returns the exact indices of `targetTokens`
   * that match `referenceTokens`.
   * Since token arrays are much smaller than character strings, this avoids OOM.
   *
   * @param refTokens The base token array
   * @param tgtTokens The token array whose matched indices are desired
   * @returns Array of token indices in `tgtTokens` that matched.
   */
  calculateTraceableLcsTokens(refTokens: string[], tgtTokens: string[]): number[] {
    if (refTokens.length === 0 || tgtTokens.length === 0) return [];

    const m = refTokens.length;
    const n = tgtTokens.length;

    // Safety circuit breaker (just in case the token count is still astronomically huge)
    // If it exceeds maxCells, we will truncate the arrays proportionally
    let safeRefTokens = refTokens;
    let safeTgtTokens = tgtTokens;

    if (m * n > this.maxCells) {
      const ratio = Math.sqrt(this.maxCells / (m * n));
      const newLenM = Math.max(1, Math.floor(m * ratio));
      const newLenN = Math.max(1, Math.floor(n * ratio));
      safeRefTokens = safeRefTokens.slice(0, newLenM);
      safeTgtTokens = safeTgtTokens.slice(0, newLenN);
    }

    const sm = safeRefTokens.length;
    const sn = safeTgtTokens.length;

    const dp = new Uint16Array((sm + 1) * (sn + 1));

    // Fill DP
    for (let i = 1; i <= sm; i++) {
      const rowOffset = i * (sn + 1);
      const prevRowOffset = (i - 1) * (sn + 1);

      for (let j = 1; j <= sn; j++) {
        if (safeRefTokens[i - 1] === safeTgtTokens[j - 1]) {
          dp[rowOffset + j] = dp[prevRowOffset + j - 1] + 1;
        } else {
          dp[rowOffset + j] = Math.max(
            dp[prevRowOffset + j], // up
            dp[rowOffset + j - 1], // left
          );
        }
      }
    }

    // Backtrack from bottom-right to find aligned target indices
    let i = sm;
    let j = sn;
    const matchedTargetIndices: number[] = [];

    while (i > 0 && j > 0) {
      if (safeRefTokens[i - 1] === safeTgtTokens[j - 1]) {
        matchedTargetIndices.push(j - 1);
        i--;
        j--;
      } else {
        const rowOffset = i * (sn + 1);
        const prevRowOffset = (i - 1) * (sn + 1);

        if (dp[prevRowOffset + j] > dp[rowOffset + j - 1]) {
          i--;
        } else {
          j--;
        }
      }
    }

    return matchedTargetIndices.reverse();
  }

  /**
   * Core implementation of traceable LCS.
   * Runs in O(M*N) time and space. Assumes inputs are already bounds-checked.
   */
  private _calculateTraceableLcsInternal(refStr: string, tgtStr: string): number[] {
    const m = refStr.length;
    const n = tgtStr.length;

    // We keep a full DP table for backtracking.
    // 1D array representing a 2D matrix of (M+1) rows and (N+1) cols.
    // Uint16Array is safe because max value is min(m,n) <= sqrt(maxCells), which is ~3162 for 10M cells.
    const dp = new Uint16Array((m + 1) * (n + 1));

    // Fill DP
    for (let i = 1; i <= m; i++) {
      const rowOffset = i * (n + 1);
      const prevRowOffset = (i - 1) * (n + 1);

      for (let j = 1; j <= n; j++) {
        if (refStr[i - 1] === tgtStr[j - 1]) {
          dp[rowOffset + j] = dp[prevRowOffset + j - 1] + 1;
        } else {
          dp[rowOffset + j] = Math.max(
            dp[prevRowOffset + j], // up
            dp[rowOffset + j - 1], // left
          );
        }
      }
    }

    // Backtrack from bottom-right to find aligned target indices
    let i = m;
    let j = n;
    const matchedTargetIndices: number[] = [];

    while (i > 0 && j > 0) {
      if (refStr[i - 1] === tgtStr[j - 1]) {
        // Match found, record the index in the target string (0-indexed)
        matchedTargetIndices.push(j - 1);
        i--;
        j--;
      } else {
        const rowOffset = i * (n + 1);
        const prevRowOffset = (i - 1) * (n + 1);

        if (dp[prevRowOffset + j] > dp[rowOffset + j - 1]) {
          i--;
        } else {
          j--;
        }
      }
    }

    // Since we backtrack from end to start, reverse to get chronological indices
    return matchedTargetIndices.reverse();
  }

  /**
   * Calculate LCS-based containment score.
   * Formula: LCS(reference, target) / |target|
   * Calculates what portion of the target string comes from the reference string.
   *
   * @param reference The base string (e.g., AI output history)
   * @param target The tested string (e.g., User's submitted diff)
   * @returns Score between 0.0 and 1.0
   */
  calculateScore(reference: string, target: string): number {
    if (target.length === 0) return 0;
    if (reference.length === 0) return 0;

    const lcsLen = this.calculateLcsLength(reference, target);

    return lcsLen / target.length;
  }
}
