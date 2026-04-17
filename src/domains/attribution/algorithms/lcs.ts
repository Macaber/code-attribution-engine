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
    let prev = new Uint32Array(n + 1);
    let curr = new Uint32Array(n + 1);

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
      curr.fill(0);
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

    // Circuit breaker: truncate if N*M exceeds threshold
    if (refStr.length * tgtStr.length > this.maxCells) {
      const ratio = Math.sqrt(this.maxCells / (refStr.length * tgtStr.length));
      const newLenRef = Math.max(1, Math.floor(refStr.length * ratio));
      const newLenTgt = Math.max(1, Math.floor(tgtStr.length * ratio));
      refStr = refStr.substring(0, newLenRef);
      tgtStr = tgtStr.substring(0, newLenTgt);
    }

    const m = refStr.length;
    const n = tgtStr.length;

    // We keep a full DP table for backtracking.
    // 1D array representing a 2D matrix of (M+1) rows and (N+1) cols
    const dp = new Uint32Array((m + 1) * (n + 1));

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
