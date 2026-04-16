import { Winnowing } from '../winnowing';

describe('Winnowing', () => {
  describe('with default config', () => {
    const winnowing = new Winnowing();

    it('should return 1.0 for identical strings', () => {
      const text = 'function add(a, b) { return a + b; }';
      const score = winnowing.calculateScore(text, text);
      expect(score).toBeCloseTo(1.0, 2);
    });

    it('should return 0.0 for completely different strings', () => {
      const textA = 'aaaaaaaaaaaaaaaa';
      const textB = 'zzzzzzzzzzzzzzzz';
      const score = winnowing.calculateScore(textA, textB);
      expect(score).toBeCloseTo(0.0, 1);
    });

    it('should return high score for structurally similar text (variable rename)', () => {
      // AI generated code
      const aiCode = 'functioncalculatetotal(items){letsum=0;for(constitemofitems){sum+=item.price*item.quantity;}returnsum;}';
      // User barely changed it (one variable rename: sum -> total)
      const userCode = 'functioncalculatetotal(items){lettotal=0;for(constitemofitems){total+=item.price*item.quantity;}returntotal;}';
      const score = winnowing.calculateScore(aiCode, userCode);
      // Winnowing uses Jaccard on fingerprint sets — variable renames mutate
      // multiple overlapping k-grams, so score drops more than char-level LCS.
      // This is expected: Winnowing is the macro detector, LCS handles precision.
      expect(score).toBeGreaterThanOrEqual(0.65);
      expect(score).toBeLessThan(0.95);
    });

    it('should return score in fuzzy range for 60% similar text (moderate edits)', () => {
      // AI generated a utility function
      const aiCode = 'functionvalidateemail(email){constregex=/^[a-za-z0-9]+@[a-za-z]+\\.[a-za-z]{2,}$/;returnregex.test(email);}';
      // User changed logic significantly: added domain check, changed regex, added error handling
      const userCode = 'functionvalidateemail(email){if(!email)returnfalse;constregex=/^[\\w]+@[\\w]+\\.[a-z]{2,4}$/i;constresult=regex.test(email);returnresult;}';
      const score = winnowing.calculateScore(aiCode, userCode);
      // Should be in a meaningful range - not too high, not zero
      expect(score).toBeGreaterThan(0.2);
      expect(score).toBeLessThan(0.95);
    });

    it('should handle empty strings gracefully', () => {
      expect(winnowing.calculateScore('', '')).toBe(0);
      expect(winnowing.calculateScore('hello', '')).toBe(0);
      expect(winnowing.calculateScore('', 'hello')).toBe(0);
    });

    it('should handle strings shorter than k-gram length', () => {
      const score = winnowing.calculateScore('ab', 'ab');
      // Should handle gracefully even if text is too short for k-grams
      expect(score).toBeGreaterThanOrEqual(0);
      expect(score).toBeLessThanOrEqual(1);
    });
  });

  describe('generateKgrams', () => {
    const winnowing = new Winnowing({ kgramLength: 3, windowSize: 2 });

    it('should generate correct k-grams', () => {
      const kgrams = winnowing.generateKgrams('abcde', 3);
      expect(kgrams).toEqual(['abc', 'bcd', 'cde']);
    });

    it('should return empty array for text shorter than k', () => {
      const kgrams = winnowing.generateKgrams('ab', 3);
      expect(kgrams).toEqual([]);
    });
  });

  describe('fingerprint consistency', () => {
    const winnowing = new Winnowing();

    it('should produce same fingerprints for same input', () => {
      const text = 'function hello() { return "world"; }';
      const fp1 = winnowing.getFingerprints(text);
      const fp2 = winnowing.getFingerprints(text);
      expect(fp1).toEqual(fp2);
    });

    it('should produce different fingerprints for different input', () => {
      const fp1 = winnowing.getFingerprints('function a() { return 1; }');
      const fp2 = winnowing.getFingerprints('class B { constructor() {} }');
      // Sets should differ
      const intersection = new Set([...fp1].filter(x => fp2.has(x)));
      expect(intersection.size).toBeLessThan(fp1.size);
    });
  });
});
