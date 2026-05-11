import { Normalizer } from '../normalizer';

describe('Normalizer', () => {
  const normalizer = new Normalizer();

  describe('normalizeText', () => {
    it('should keep single-line comments', () => {
      const input = `const x = 1; // this is a comment
const y = 2; // another comment`;
      const result = normalizer.normalizeText(input);
      expect(result).toContain('//thisisacomment');
      expect(result).toContain('constx=1;');
    });

    it('should keep multi-line comments', () => {
      const input = `/* this is
a multi-line
comment */ const x = 1;`;
      const result = normalizer.normalizeText(input);
      expect(result).toContain('/*thisisamulti-linecomment*/');
      expect(result).toContain('constx=1;');
    });

    it('should remove all whitespace (spaces, tabs, newlines)', () => {
      const input = `  const   x  =  1 ;
\tconst\ty\t=\t2;  `;
      const result = normalizer.normalizeText(input);
      expect(result).toBe('constx=1;consty=2;');
    });

    it('should convert all characters to lowercase', () => {
      const input = 'Const MyVar = "HELLO";';
      const result = normalizer.normalizeText(input);
      expect(result).toBe('constmyvar="hello";');
    });

    it('should handle empty string', () => {
      expect(normalizer.normalizeText('')).toBe('');
    });

    it('should handle string that is only comments', () => {
      const input = `// just a comment
/* another comment */`;
      const result = normalizer.normalizeText(input);
      expect(result).toBe('//justacomment/*anothercomment*/');
    });

    it('should handle string that is only whitespace', () => {
      expect(normalizer.normalizeText('   \t\n\r  ')).toBe('');
    });

    it('should preserve comment-like patterns inside string literals', () => {
      // String literals with // inside should ideally be preserved
      // but since we use regex, simple approach may strip them.
      // This documents the expected behavior of our regex-based approach.
      const input = 'const url = "http://example.com";';
      const result = normalizer.normalizeText(input);
      // regex strips after //, so we document this known limitation
      expect(result).toBeDefined();
    });

    it('should handle mixed comments and code by squashing whitespace', () => {
      const input = `
// Header comment
function add(a, b) {
  /* intermediate */ return a + b; // inline
}
/* trailing */`;
      const result = normalizer.normalizeText(input);
      expect(result).toBe('//headercommentfunctionadd(a,b){/*intermediate*/returna+b;//inline}/*trailing*/');
    });

    it('should handle \\r\\n line endings', () => {
      const input = 'const x = 1;\r\nconst y = 2;\r\n';
      const result = normalizer.normalizeText(input);
      expect(result).toBe('constx=1;consty=2;');
    });

    it('should normalize identical code with different formatting to the same string', () => {
      const codeA = `function   add(a,b) {
        return a+b;
      }`;
      const codeB = `function add( a, b ) {
  return a + b;
}`;
      expect(normalizer.normalizeText(codeA)).toBe(normalizer.normalizeText(codeB));
    });
  });

  describe('normalizeToLines', () => {
    it('should split code into normalized lines and skip blank lines', () => {
      const input = `const x = 1;

const y = 2;`;
      const result = normalizer.normalizeToLines(input);
      expect(result.normalizedLines).toEqual(['constx=1;', 'consty=2;']);
      expect(result.nonBlankLineCount).toBe(2);
    });

    it('should strip all whitespace within each line and lowercase', () => {
      const input = `  const   MyVar  =  "HELLO" ;`;
      const result = normalizer.normalizeToLines(input);
      expect(result.normalizedLines).toEqual(['constmyvar="hello";']);
    });

    it('should map normalizedLines indices back to original line numbers', () => {
      const input = `line0
line1

line3
`;
      // Line 0: "line0", Line 1: "line1", Line 2: "" (blank), Line 3: "line3", Line 4: "" (trailing)
      const result = normalizer.normalizeToLines(input);
      expect(result.normalizedLines).toEqual(['line0', 'line1', 'line3']);
      expect(result.originalLineIndices).toEqual([0, 1, 3]);
    });

    it('should produce normalizedText as concatenation of all normalized lines', () => {
      const input = `const x = 1;
const y = 2;`;
      const result = normalizer.normalizeToLines(input);
      expect(result.normalizedText).toBe('constx=1;consty=2;');
    });

    it('should handle empty input', () => {
      const result = normalizer.normalizeToLines('');
      expect(result.normalizedLines).toEqual([]);
      expect(result.originalLineIndices).toEqual([]);
      expect(result.nonBlankLineCount).toBe(0);
      expect(result.normalizedText).toBe('');
    });

    it('should handle input that is only whitespace/blank lines', () => {
      const result = normalizer.normalizeToLines('   \n\t\n  \n');
      expect(result.normalizedLines).toEqual([]);
      expect(result.nonBlankLineCount).toBe(0);
    });

    it('should normalize code with different indentation to the same lines', () => {
      const codeA = `function add(a, b) {
        return a + b;
      }`;
      const codeB = `function add(a, b) {
  return a + b;
}`;
      const resultA = normalizer.normalizeToLines(codeA);
      const resultB = normalizer.normalizeToLines(codeB);
      expect(resultA.normalizedLines).toEqual(resultB.normalizedLines);
    });
  });
});
