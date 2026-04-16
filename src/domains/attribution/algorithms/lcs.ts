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
   * Calculate LCS-based similarity score.
   * Formula: LCS(a, b) / max(|a|, |b|)
   *
   * @returns Score between 0.0 and 1.0
   */
  calculateScore(a: string, b: string): number {
    if (a.length === 0 || b.length === 0) return 0;

    const lcsLen = this.calculateLcsLength(a, b);
    const maxLen = Math.max(a.length, b.length);

    return lcsLen / maxLen;
  }
}
