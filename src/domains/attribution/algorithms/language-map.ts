/**
 * Language Map — Maps file extensions to Tree-sitter grammar identifiers.
 *
 * Used for:
 * 1. Determining which Tree-sitter grammar to load for a file
 * 2. Determining whether L3 AST analysis is supported for a file type
 */

/** Mapping from file extension (without dot) to Tree-sitter grammar name */
const EXTENSION_TO_GRAMMAR: Record<string, string> = {
  // JavaScript / TypeScript
  ts: 'typescript',
  tsx: 'tsx',
  js: 'javascript',
  jsx: 'javascript',
  mjs: 'javascript',
  cjs: 'javascript',

  // Java
  java: 'java',

  // Python
  py: 'python',

  // CSS / Preprocessors
  css: 'css',
  less: 'css',   // LESS is close enough to CSS for structural features
  scss: 'css',

  // Go
  go: 'go',

  // C / C++
  c: 'c',
  h: 'c',
  cpp: 'cpp',
  cc: 'cpp',
  cxx: 'cpp',
  hpp: 'cpp',
};

/**
 * File extensions that should never enter L3 (non-code / config files).
 * These produce meaningless AST features.
 */
const NON_PARSEABLE_EXTENSIONS = new Set([
  'json',
  'yaml',
  'yml',
  'toml',
  'properties',
  'xml',
  'html',
  'md',
  'txt',
  'csv',
  'env',
  'gitignore',
  'dockerignore',
  'lock',
  'svg',
  'png',
  'jpg',
  'gif',
]);

/**
 * Extract file extension from a file path.
 */
export function getFileExtension(filePath: string): string {
  const lastDot = filePath.lastIndexOf('.');
  if (lastDot === -1 || lastDot === filePath.length - 1) return '';
  return filePath.substring(lastDot + 1).toLowerCase();
}

/**
 * Get the Tree-sitter grammar name for a file path.
 * Returns null if the file type is not supported for AST analysis.
 */
export function getGrammarName(filePath: string): string | null {
  const ext = getFileExtension(filePath);
  if (!ext) return null;
  if (NON_PARSEABLE_EXTENSIONS.has(ext)) return null;
  return EXTENSION_TO_GRAMMAR[ext] ?? null;
}

/**
 * Check if a file is eligible for L3 AST analysis.
 */
export function isL3Eligible(filePath: string): boolean {
  return getGrammarName(filePath) !== null;
}
