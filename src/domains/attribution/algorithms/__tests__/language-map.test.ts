import { getFileExtension, getGrammarName, isL3Eligible } from '../language-map';

describe('language-map', () => {
  describe('getFileExtension', () => {
    it('should extract extension from file path', () => {
      expect(getFileExtension('src/app.ts')).toBe('ts');
      expect(getFileExtension('backend/Main.java')).toBe('java');
      expect(getFileExtension('style.less')).toBe('less');
    });

    it('should handle nested paths', () => {
      expect(getFileExtension('a/b/c/d.py')).toBe('py');
    });

    it('should return empty for no extension', () => {
      expect(getFileExtension('Dockerfile')).toBe('');
      expect(getFileExtension('Makefile')).toBe('');
    });

    it('should lowercase the extension', () => {
      expect(getFileExtension('App.TSX')).toBe('tsx');
    });
  });

  describe('getGrammarName', () => {
    it('should map TypeScript extensions', () => {
      expect(getGrammarName('app.ts')).toBe('typescript');
      expect(getGrammarName('app.tsx')).toBe('tsx');
    });

    it('should map JavaScript extensions', () => {
      expect(getGrammarName('app.js')).toBe('javascript');
      expect(getGrammarName('app.mjs')).toBe('javascript');
    });

    it('should map Java', () => {
      expect(getGrammarName('Main.java')).toBe('java');
    });

    it('should map CSS/LESS/SCSS to css', () => {
      expect(getGrammarName('style.css')).toBe('css');
      expect(getGrammarName('style.less')).toBe('css');
      expect(getGrammarName('style.scss')).toBe('css');
    });

    it('should return null for non-parseable files', () => {
      expect(getGrammarName('app.properties')).toBeNull();
      expect(getGrammarName('data.json')).toBeNull();
      expect(getGrammarName('config.yaml')).toBeNull();
      expect(getGrammarName('README.md')).toBeNull();
    });

    it('should return null for unknown extensions', () => {
      expect(getGrammarName('file.xyz')).toBeNull();
    });

    it('should return null for no extension', () => {
      expect(getGrammarName('Dockerfile')).toBeNull();
    });
  });

  describe('isL3Eligible', () => {
    it('should return true for supported languages', () => {
      expect(isL3Eligible('app.ts')).toBe(true);
      expect(isL3Eligible('Main.java')).toBe(true);
      expect(isL3Eligible('script.py')).toBe(true);
    });

    it('should return false for non-parseable files', () => {
      expect(isL3Eligible('app.properties')).toBe(false);
      expect(isL3Eligible('data.json')).toBe(false);
    });

    it('should return false for unknown extensions', () => {
      expect(isL3Eligible('unknown.abc')).toBe(false);
    });
  });
});
