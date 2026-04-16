import { SimilarityEngine } from '../similarity-engine';

describe('SimilarityEngine', () => {
  // ═══════════════════════════════════════════════
  // Legacy evaluate() — backward compatibility
  // ═══════════════════════════════════════════════
  describe('evaluate (legacy)', () => {
    const engine = new SimilarityEngine();

    it('should return ~1.0 for identical raw code', () => {
      const code = `
        function add(a: number, b: number): number {
          return a + b;
        }
      `;
      const result = engine.evaluate(code, code);
      expect(result.combinedScore).toBeGreaterThanOrEqual(0.99);
      expect(result.winnowingScore).toBeCloseTo(1.0, 1);
      expect(result.lcsScore).toBeCloseTo(1.0, 1);
    });

    it('should return ~1.0 for code differing only in formatting', () => {
      const aiCode = `function add(a: number, b: number): number {
  return a + b;
}`;
      const commitCode = `function  add( a: number,  b: number ):  number  {
        return  a  +  b ;
      }`;
      const result = engine.evaluate(aiCode, commitCode);
      expect(result.combinedScore).toBeGreaterThanOrEqual(0.99);
    });

    it('should return ~1.0 for code differing only in comments', () => {
      const aiCode = `// Calculate sum
function add(a: number, b: number): number {
  return a + b; // return result
}`;
      const commitCode = `/* My add function */
function add(a: number, b: number): number {
  // this adds two numbers
  return a + b;
}`;
      const result = engine.evaluate(aiCode, commitCode);
      expect(result.combinedScore).toBeGreaterThanOrEqual(0.99);
    });

    it('should return >= 0.40 for fuzzy match (AI code with moderate edits)', () => {
      const aiCode = `
async function fetchUsers() {
  const response = await fetch('/api/users');
  const data = await response.json();
  return data;
}`;
      const commitCode = `
async function fetchUsers() {
  try {
    const response = await fetch('/api/users');
    if (!response.ok) throw new Error('Failed');
    const data = await response.json();
    return data.users;
  } catch (error) {
    console.error(error);
    return [];
  }
}`;
      const result = engine.evaluate(aiCode, commitCode);
      expect(result.combinedScore).toBeGreaterThanOrEqual(0.40);
      expect(result.combinedScore).toBeLessThan(0.95);
    });

    it('should return < 0.60 for completely different code', () => {
      const aiCode = `
class UserService {
  private db: Database;
  constructor(db: Database) { this.db = db; }
  async getUser(id: string) { return this.db.findOne(id); }
}`;
      const commitCode = `
function validatePassword(pw: string): boolean {
  return pw.length >= 8 && /[A-Z]/.test(pw) && /[0-9]/.test(pw);
}`;
      const result = engine.evaluate(aiCode, commitCode);
      expect(result.combinedScore).toBeLessThan(0.60);
    });

    it('should handle empty inputs', () => {
      const result = engine.evaluate('', 'some code');
      expect(result.combinedScore).toBe(0);
    });
  });

  describe('classify (legacy)', () => {
    it('should classify >= 0.95 as strict', () => {
      expect(SimilarityEngine.classify(0.95)).toBe('strict');
      expect(SimilarityEngine.classify(1.0)).toBe('strict');
    });

    it('should classify 0.60-0.94 as fuzzy', () => {
      expect(SimilarityEngine.classify(0.60)).toBe('fuzzy');
      expect(SimilarityEngine.classify(0.75)).toBe('fuzzy');
    });

    it('should classify < 0.60 as none', () => {
      expect(SimilarityEngine.classify(0.59)).toBe('none');
      expect(SimilarityEngine.classify(0.0)).toBe('none');
    });
  });

  // ═══════════════════════════════════════════════
  // evaluateChunk() — Escalation Pipeline
  // ═══════════════════════════════════════════════
  describe('evaluateChunk (pipeline)', () => {
    const engine = new SimilarityEngine();

    it('should return STRICT/L1 for identical code (fast-pass)', async () => {
      const code = 'function add(a, b) { return a + b; }';
      const result = await engine.evaluateChunk(code, code);
      expect(result.matchType).toBe('STRICT');
      expect(result.level).toBe('L1');
      expect(result.score).toBeGreaterThanOrEqual(0.90);
      expect(result.details.l1WinnowingScore).toBeDefined();
    });

    it('should return NONE/L1 for completely different code (fast-fail)', async () => {
      const aiCode = 'aaaaaaaaaaaaaaaaaaaaaa';
      const userCode = 'zzzzzzzzzzzzzzzzzzzzzz';
      const result = await engine.evaluateChunk(aiCode, userCode);
      expect(result.matchType).toBe('NONE');
      expect(result.level).toBe('L1');
      expect(result.score).toBe(0);
    });

    it('should return NONE/FAILED_ALL for empty inputs', async () => {
      const result = await engine.evaluateChunk('', 'some code');
      expect(result.matchType).toBe('NONE');
      expect(result.level).toBe('FAILED_ALL');
      expect(result.score).toBe(0);
    });

    it('should escalate to L2 for moderately similar code', async () => {
      // Code similar enough to pass L1 fast-fail but not L1 fast-pass
      const aiCode = 'functioncalculatetotal(items){letsum=0;for(constitemofitems){sum+=item.price*item.quantity;}returnsum;}';
      const userCode = 'functioncalculatetotal(items){lettotal=0;for(constitemofitems){total+=item.price*item.quantity;}returntotal;}';
      const result = await engine.evaluateChunk(aiCode, userCode);

      // Should have reached at least L2 (L1 didn't short-circuit)
      expect(result.details.l1WinnowingScore).toBeDefined();
      expect(result.details.l2LcsScore).toBeDefined();
    });

    it('should skip L3 when addedLineCount exceeds maxLinesForL3', async () => {
      const aiCode = 'function test() { return 1; }';
      const userCode = 'function test() { return 2; }';
      const result = await engine.evaluateChunk(aiCode, userCode, {
        addedLineCount: 2000, // exceeds default 1000
        filePath: 'test.ts',
        fileContent: 'function test() { return 2; }',
      });

      // L3 should be skipped — no l3AstScore in details
      expect(result.details.l3AstScore).toBeUndefined();
    });

    it('should skip L3 for non-parseable file types', async () => {
      const aiCode = 'spring.datasource.url=jdbc:mysql://localhost:3306/db';
      const userCode = 'spring.datasource.url=jdbc:mysql://localhost:3306/db\nfeature.enabled=true';
      const result = await engine.evaluateChunk(aiCode, userCode, {
        filePath: 'application.properties', // non-parseable
        fileContent: userCode,
      });

      expect(result.details.l3AstScore).toBeUndefined();
    });

    it('should skip L3 when no fileContent provided', async () => {
      const aiCode = 'function test() { return 1; }';
      const userCode = 'function test() { return 2; }';
      const result = await engine.evaluateChunk(aiCode, userCode, {
        filePath: 'test.ts',
        // no fileContent
      });

      expect(result.details.l3AstScore).toBeUndefined();
    });
  });

  describe('matchTypeToAttribution', () => {
    it('should map STRICT to strict', () => {
      expect(SimilarityEngine.matchTypeToAttribution('STRICT')).toBe('strict');
    });

    it('should map FUZZY to fuzzy', () => {
      expect(SimilarityEngine.matchTypeToAttribution('FUZZY')).toBe('fuzzy');
    });

    it('should map DEEP_REFACTOR to deep_refactor', () => {
      expect(SimilarityEngine.matchTypeToAttribution('DEEP_REFACTOR')).toBe('deep_refactor');
    });

    it('should map NONE to none', () => {
      expect(SimilarityEngine.matchTypeToAttribution('NONE')).toBe('none');
    });
  });
});
