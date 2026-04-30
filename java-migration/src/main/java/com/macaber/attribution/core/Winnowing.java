package com.macaber.attribution.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class Winnowing {
    private final int k;
    private final int w;

    public Winnowing() {
        this(new WinnowingConfig());
    }

    public Winnowing(WinnowingConfig config) {
        this.k = config != null ? config.getKgramLength() : 5;
        this.w = config != null ? config.getWindowSize() : 4;
    }

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

    public long hashKgram(String kgram) {
        long base = 31;
        long mod = 1_000_000_007;
        long hash = 0;
        for (int i = 0; i < kgram.length(); i++) {
            hash = (hash * base + kgram.charAt(i)) % mod;
        }
        return hash;
    }

    public Set<Long> selectFingerprints(List<Long> hashes, int w) {
        if (hashes == null || hashes.isEmpty()) return new HashSet<>();
        
        if (hashes.size() <= w) {
            long minHash = hashes.get(0);
            for (long hash : hashes) {
                if (hash < minHash) {
                    minHash = hash;
                }
            }
            Set<Long> res = new HashSet<>();
            res.add(minHash);
            return res;
        }

        Set<Long> fingerprints = new HashSet<>();
        for (int i = 0; i <= hashes.size() - w; i++) {
            long minHash = hashes.get(i);
            for (int j = i + 1; j < i + w; j++) {
                if (hashes.get(j) < minHash) {
                    minHash = hashes.get(j);
                }
            }
            fingerprints.add(minHash);
        }
        return fingerprints;
    }

    public Set<Long> getFingerprints(String text) {
        List<String> kgrams = generateKgrams(text, this.k);
        if (kgrams.isEmpty()) return new HashSet<>();

        List<Long> hashes = new ArrayList<>();
        for (String kg : kgrams) {
            hashes.add(hashKgram(kg));
        }
        
        return selectFingerprints(hashes, this.w);
    }

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
