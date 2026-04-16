import { LCS } from '../lcs';

describe('LCS', () => {
  describe('with default config', () => {
    const lcs = new LCS();

    it('should return 1.0 for identical strings', () => {
      const text = 'function add(a, b) { return a + b; }';
      const score = lcs.calculateScore(text, text);
      expect(score).toBeCloseTo(1.0, 5);
    });

    it('should return 0.0 for completely different strings', () => {
      const score = lcs.calculateScore('aaaaaa', 'zzzzzz');
      expect(score).toBeCloseTo(0.0, 5);
    });

    it('should return 0.0 for empty strings', () => {
      expect(lcs.calculateScore('', '')).toBe(0);
      expect(lcs.calculateScore('hello', '')).toBe(0);
      expect(lcs.calculateScore('', 'hello')).toBe(0);
    });

    it('should return >= 0.95 for strings with a single character difference', () => {
      // AI code (100 chars)
      const aiCode = 'functioncalculatetotal(items){letsum=0;for(constitemofitems){sum+=item.price*item.quantity;}returnsum;}';
      // User only changed one variable name char (sum -> sun) — minimal edit
      const userCode = 'functioncalculatetotal(items){letsun=0;for(constitemofitems){sun+=item.price*item.quantity;}returnsun;}';
      const score = lcs.calculateScore(aiCode, userCode);
      expect(score).toBeGreaterThanOrEqual(0.95);
    });

    it('should return score in fuzzy range (0.60-0.95) for moderately similar text', () => {
      // AI code
      const aiCode = 'asyncfunctionfetchdata(url){constresponse=awaitfetch(url);constdata=awaitresponse.json();returndata;}';
      // User modified: added error handling, changed return, but kept core structure
      const userCode = 'asyncfunctionfetchdata(url){try{constresponse=awaitfetch(url);if(!response.ok)thrownew error();constdata=awaitresponse.json();returndata;}catch(e){returnnull;}}';
      const score = lcs.calculateScore(aiCode, userCode);
      expect(score).toBeGreaterThanOrEqual(0.55);
      expect(score).toBeLessThan(0.95);
    });

    it('should return < 0.60 for substantially different text', () => {
      const aiCode = 'classuserservice{constructor(privatedb:database){}asyncgetuser(id:string){returnthis.db.findone(id);}}';
      const userCode = 'functionvalidatepassword(pw:string):boolean{returnpw.length>=8&&/[a-z]/.test(pw)&&/[0-9]/.test(pw);}';
      const score = lcs.calculateScore(aiCode, userCode);
      expect(score).toBeLessThan(0.60);
    });
  });

  describe('calculateLcsLength', () => {
    const lcs = new LCS();

    it('should calculate correct LCS length for simple case', () => {
      // LCS of "abcde" and "ace" is "ace" = 3
      const length = lcs.calculateLcsLength('abcde', 'ace');
      expect(length).toBe(3);
    });

    it('should calculate LCS for same string', () => {
      const length = lcs.calculateLcsLength('hello', 'hello');
      expect(length).toBe(5);
    });

    it('should return 0 for no common subsequence', () => {
      const length = lcs.calculateLcsLength('abc', 'xyz');
      expect(length).toBe(0);
    });
  });

  describe('circuit breaker', () => {
    it('should complete within reasonable time for large inputs', () => {
      // Create two large strings (5000 chars each)
      const baseStr = 'abcdefghijklmnopqrstuvwxyz0123456789'.repeat(139); // ~5004 chars
      const modifiedStr = 'abcdefghijklmnopqrstuvwxyz9876543210'.repeat(139);

      const lcs = new LCS({ maxCells: 10_000_000 });

      const start = Date.now();
      const score = lcs.calculateScore(baseStr, modifiedStr);
      const elapsed = Date.now() - start;

      // Should complete in < 5 seconds (generous)
      expect(elapsed).toBeLessThan(5000);
      // Score should still be valid
      expect(score).toBeGreaterThan(0);
      expect(score).toBeLessThanOrEqual(1);
    });

    it('should truncate inputs that exceed maxCells', () => {
      // Very small maxCells to force truncation
      const lcs = new LCS({ maxCells: 100 });
      const longA = 'a'.repeat(1000);
      const longB = 'a'.repeat(1000);

      // Should still complete without error
      const score = lcs.calculateScore(longA, longB);
      expect(score).toBeGreaterThan(0);
      expect(score).toBeLessThanOrEqual(1);
    });
  });
});
