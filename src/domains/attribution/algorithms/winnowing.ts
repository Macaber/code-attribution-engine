import { WinnowingConfig } from '../../../types';

/**
 * Winnowing Algorithm — Document fingerprinting for macro-level plagiarism detection.
 *
 * Uses sliding-window k-grams with min-hash fingerprint selection.
 * Robust against insertions/deletions within code blocks.
 */
export class Winnowing {
  private readonly k: number; // k-gram length
  private readonly w: number; // window size

  constructor(config?: Partial<WinnowingConfig>) {
    this.k = config?.kgramLength ?? 5;
    this.w = config?.windowSize ?? 4;
  }

  /**
   * Generate k-grams from text using a sliding window.
   */
  generateKgrams(text: string, k: number): string[] {
    if (text.length < k) return [];

    const kgrams: string[] = [];
    for (let i = 0; i <= text.length - k; i++) {
      kgrams.push(text.substring(i, i + k));
    }
    return kgrams;
  }

  /**
   * Hash a k-gram using a simple polynomial rolling hash.
   * Uses a prime base and modulus to reduce collisions.
   */
  hashKgram(kgram: string): number {
    const BASE = 31;
    const MOD = 1_000_000_007;
    let hash = 0;
    for (let i = 0; i < kgram.length; i++) {
      hash = (hash * BASE + kgram.charCodeAt(i)) % MOD;
    }
    return hash;
  }

  /**
   * Select fingerprints using the Winnowing algorithm:
   * For each window of size w, select the minimum hash value.
   * Record the position to avoid duplicate fingerprints from overlapping windows.
   */
  selectFingerprints(hashes: number[], w: number): Set<number> {
    if (hashes.length === 0) return new Set();
    if (hashes.length <= w) {
      // Window is larger than hash array, just pick the minimum
      return new Set([Math.min(...hashes)]);
    }

    const fingerprints = new Set<number>();
    for (let i = 0; i <= hashes.length - w; i++) {
      let minHash = hashes[i];
      for (let j = i + 1; j < i + w; j++) {
        if (hashes[j] < minHash) {
          minHash = hashes[j];
        }
      }
      fingerprints.add(minHash);
    }
    return fingerprints;
  }

  /**
   * Get the full fingerprint set for a piece of text.
   */
  getFingerprints(text: string): Set<number> {
    const kgrams = this.generateKgrams(text, this.k);
    if (kgrams.length === 0) return new Set();

    const hashes = kgrams.map(kg => this.hashKgram(kg));
    return this.selectFingerprints(hashes, this.w);
  }

  /**
   * Calculate Winnowing Containment score between a reference text and a target text.
   * Uses Containment similarity: |Reference ∩ Target| / |Target|
   * Calculates what portion of the target string comes from the reference string.
   *
   * @param reference The base string (e.g., AI output history)
   * @param target The tested string (e.g., User's submitted diff)
   * @returns Containment score between 0.0 and 1.0
   */
  calculateScore(reference: string, target: string): number {
    if (!reference || !target) return 0;

    const fpRef = this.getFingerprints(reference);
    const fpTarget = this.getFingerprints(target);

    if (fpTarget.size === 0) return 0;
    if (fpRef.size === 0) return 0;

    // Calculate Containment similarity
    let contained = 0;
    for (const fp of fpTarget) {
      if (fpRef.has(fp)) {
        contained++;
      }
    }

    return contained / fpTarget.size;
  }
}
