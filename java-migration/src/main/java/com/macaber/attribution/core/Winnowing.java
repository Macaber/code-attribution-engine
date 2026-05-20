package com.macaber.attribution.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Winnowing Algorithm — Document fingerprinting for macro-level plagiarism detection.
 *
 * Uses sliding-window k-grams with min-hash fingerprint selection.
 * Robust against insertions/deletions within code blocks.
 *
 * Aligned with TS: src/domains/attribution/algorithms/winnowing.ts
 */
public class Winnowing {
    private final int k; // k-gram length
    private final int w; // window size

    public Winnowing() {
        this(new WinnowingConfig());
    }

    public Winnowing(WinnowingConfig config) {
        this.k = config != null ? config.getKgramLength() : 5;
        this.w = config != null ? config.getWindowSize() : 4;
    }

    /**
     * Generate k-grams from text using a sliding window.
     */
    public List<String> generateKgrams(String text, int k) {
        if (text == null || text.length() < k) {
            return new ArrayList<>();
        }

        List<String> kgrams = new ArrayList<>();
        for (int i = 0; i <= text.length() - k; i++) {
            kgrams.add(text.substring(i, i + k));
        }
        return kgrams;
    }

    /**
     * Hash a k-gram using a simple polynomial rolling hash.
     * Uses a prime base and modulus to reduce collisions.
     */
    public long hashKgram(String kgram) {
        long base = 31;
        long mod = 1_000_000_007;
        long hash = 0;
        for (int i = 0; i < kgram.length(); i++) {
            hash = (hash * base + kgram.charAt(i)) % mod;
        }
        return hash;
    }

    /**
     * Select fingerprints using the Winnowing algorithm with monotonic deque.
     * For each window of size w, select the minimum hash value.
     *
     * Aligned with TS: selectFingerprints() using monotonic deque for O(N) performance.
     */
    public Set<Long> selectFingerprints(long[] hashes, int w) {
        if (hashes == null || hashes.length == 0) return new HashSet<>();

        if (hashes.length <= w) {
            long minHash = hashes[0];
            for (long hash : hashes) {
                if (hash < minHash) minHash = hash;
            }
            Set<Long> res = new HashSet<>();
            res.add(minHash);
            return res;
        }

        Set<Long> fingerprints = new HashSet<>();

        // Monotonic Deque (stores indices of hashes) for O(N) performance
        // Using a circular array of size w + 1 to avoid allocating hashes.length space
        int capacity = w + 1;
        int[] deque = new int[capacity];
        int head = 0;
        int tail = 0;
        int size = 0;

        for (int i = 0; i < hashes.length; i++) {
            // 1. Remove elements out of the current window
            if (size > 0 && deque[head] <= i - w) {
                head = (head + 1) % capacity;
                size--;
            }

            // 2. Maintain monotonic property: remove elements >= current hash
            while (size > 0 && hashes[deque[(tail - 1 + capacity) % capacity]] >= hashes[i]) {
                tail = (tail - 1 + capacity) % capacity;
                size--;
            }

            // 3. Add current element's index
            deque[tail] = i;
            tail = (tail + 1) % capacity;
            size++;

            // 4. Record the minimum once we've processed at least one full window
            if (i >= w - 1) {
                fingerprints.add(hashes[deque[head]]);
            }
        }

        return fingerprints;
    }

    /**
     * Get the full fingerprint set for a piece of text using O(N) Rolling Hash.
     * Bypasses generateKgrams to avoid string allocation overhead.
     *
     * Aligned with TS: getFingerprints() using rolling hash in winnowing.ts
     */
    public Set<Long> getFingerprints(String text) {
        if (text == null || text.length() < k) return new HashSet<>();

        long BASE = 31;
        long MOD = 1_000_000_007;

        // Precompute BASE^(k-1) % MOD
        long basePow = 1;
        for (int i = 0; i < k - 1; i++) {
            basePow = (basePow * BASE) % MOD;
        }

        long[] hashes = new long[text.length() - k + 1];

        // Compute hash for the first k-gram window
        long currentHash = 0;
        for (int i = 0; i < k; i++) {
            currentHash = (currentHash * BASE + text.charAt(i)) % MOD;
        }
        hashes[0] = currentHash;

        // Slide window for O(1) hash updates
        for (int i = 1; i <= text.length() - k; i++) {
            long leftChar = text.charAt(i - 1);
            long rightChar = text.charAt(i + k - 1);

            // Remove the outgoing character and add the incoming character
            long removeTerm = (leftChar * basePow) % MOD;
            currentHash = (currentHash - removeTerm + MOD) % MOD; // +MOD prevents negative values
            currentHash = (currentHash * BASE + rightChar) % MOD;

            hashes[i] = currentHash;
        }

        return selectFingerprints(hashes, this.w);
    }

    /**
     * Calculate Winnowing Containment score between a reference text and a target text.
     * Uses Containment similarity: |Reference ∩ Target| / |Target|
     * Calculates what portion of the target string comes from the reference string.
     *
     * @param reference The base string (e.g., AI output history)
     * @param target    The tested string (e.g., User's submitted diff)
     * @return Containment score between 0.0 and 1.0
     */
    public double calculateScore(String reference, String target) {
        if (reference == null || reference.isEmpty() || target == null || target.isEmpty()) {
            return 0.0;
        }

        Set<Long> fpRef = getFingerprints(reference);
        Set<Long> fpTarget = getFingerprints(target);

        if (fpTarget.isEmpty() || fpRef.isEmpty()) return 0.0;

        int contained = 0;
        for (Long fp : fpTarget) {
            if (fpRef.contains(fp)) {
                contained++;
            }
        }

        return (double) contained / fpTarget.size();
    }
}
