import {
  Parser,
  Language,
  Node as SyntaxNode,
  Tree,
} from 'web-tree-sitter';
import * as path from 'path';
import * as fs from 'fs';
import { LRUCache } from '../../../core/cache/lru-cache';
import { getGrammarName } from './language-map';

/**
 * AstFeatureEngine — L3 layer: Tree-sitter AST feature extraction + Jaccard similarity.
 *
 * Instead of expensive tree edit distance, we flatten the AST into a set of
 * semantic features (function calls, control flow, operators, imports) and
 * compare via Jaccard similarity. This is robust against:
 * - Variable renaming
 * - Code reordering
 * - Extracting logic into sub-functions
 */
export class AstFeatureEngine {
  private parser: Parser | null = null;
  private loadedLanguages = new Map<string, Language>();
  private initPromise: Promise<void> | null = null;
  private readonly astCache: LRUCache<string, Tree>;
  private readonly grammarsDir: string;

  constructor(options?: { grammarsDir?: string; cacheSize?: number; cacheTtlMs?: number }) {
    const defaultGrammarsDir = path.join(__dirname, 'grammars');
    this.grammarsDir = options?.grammarsDir ?? defaultGrammarsDir;
    this.astCache = new LRUCache<string, Tree>({
      maxSize: options?.cacheSize ?? 50,
      ttlMs: options?.cacheTtlMs ?? 300_000,
    });
  }

  /**
   * Initialize Tree-sitter WASM runtime.
   * Safe to call multiple times — only initializes once.
   */
  async init(): Promise<void> {
    if (this.parser) return;
    if (this.initPromise) return this.initPromise;

    this.initPromise = (async () => {
      try {
        await Parser.init();
        this.parser = new Parser();
      } catch (error) {
        console.warn('[AstFeatureEngine] Failed to initialize Tree-sitter:', error);
        this.parser = null;
      }
    })();

    return this.initPromise;
  }

  /**
   * Load a language grammar by name.
   * Returns null if grammar file not found or loading fails.
   */
  private async loadLanguage(grammarName: string): Promise<Language | null> {
    if (this.loadedLanguages.has(grammarName)) {
      return this.loadedLanguages.get(grammarName)!;
    }

    const wasmPath = path.join(this.grammarsDir, `tree-sitter-${grammarName}.wasm`);

    if (!fs.existsSync(wasmPath)) {
      console.warn(
        `[AstFeatureEngine] Grammar not found: ${wasmPath} — L3 disabled for ${grammarName}`,
      );
      return null;
    }

    try {
      const language = await Language.load(wasmPath);
      this.loadedLanguages.set(grammarName, language);
      return language;
    } catch (error) {
      console.warn(
        `[AstFeatureEngine] Failed to load grammar ${grammarName}:`,
        error,
      );
      return null;
    }
  }

  /**
   * Parse code into a Tree-sitter AST.
   * Uses LRU cache to avoid re-parsing identical content.
   *
   * @returns Parse tree, or null if parsing fails/unavailable
   */
  async parseCode(code: string, grammarName: string): Promise<Tree | null> {
    if (!this.parser) {
      await this.init();
      if (!this.parser) return null;
    }

    // Check cache
    const cacheKey = `${grammarName}:${code}`;
    const cached = this.astCache.get(cacheKey);
    if (cached) return cached;

    const language = await this.loadLanguage(grammarName);
    if (!language) return null;

    try {
      this.parser.setLanguage(language);
      const tree = this.parser.parse(code);
      if (tree) {
        this.astCache.set(cacheKey, tree);
      }
      return tree;
    } catch (error) {
      console.warn('[AstFeatureEngine] Parse error:', error);
      return null;
    }
  }

  /**
   * Extract semantic features from a Tree-sitter AST node.
   * Returns a Set of feature strings like:
   *   'call:fetch', 'call:JSON.parse', 'control:if', 'control:try_catch',
   *   'op:===', 'import:express'
   */
  extractFeatures(node: SyntaxNode): Set<string> {
    const features = new Set<string>();
    this.walkNode(node, features);
    return features;
  }

  /**
   * Recursively walk the AST and extract features.
   */
  private walkNode(node: SyntaxNode, features: Set<string>): void {
    const type = node.type;

    // ── Function / Method calls ─────────────────────
    if (
      type === 'call_expression' ||
      type === 'method_invocation' ||   // Java
      type === 'member_expression'
    ) {
      const callName = this.extractCallName(node);
      if (callName) features.add(`call:${callName}`);
    }

    // ── Control flow ────────────────────────────────
    if (type === 'if_statement' || type === 'if_expression') {
      features.add('control:if');
    }
    if (type === 'for_statement' || type === 'for_in_statement' || type === 'enhanced_for_statement') {
      features.add('control:for');
    }
    if (type === 'while_statement') {
      features.add('control:while');
    }
    if (type === 'switch_statement' || type === 'switch_expression') {
      features.add('control:switch');
    }
    if (type === 'try_statement') {
      features.add('control:try_catch');
    }
    if (type === 'return_statement') {
      features.add('control:return');
    }
    if (type === 'throw_statement') {
      features.add('control:throw');
    }

    // ── Function / Class declarations ───────────────
    if (
      type === 'function_declaration' ||
      type === 'method_definition' ||
      type === 'method_declaration' ||   // Java
      type === 'arrow_function'
    ) {
      features.add('decl:function');
      const name = node.childForFieldName('name');
      if (name) features.add(`decl:fn:${name.text}`);
    }
    if (type === 'class_declaration') {
      features.add('decl:class');
      const name = node.childForFieldName('name');
      if (name) features.add(`decl:class:${name.text}`);
    }

    // ── Imports ─────────────────────────────────────
    if (type === 'import_statement' || type === 'import_declaration') {
      const source = node.childForFieldName('source') ?? node.descendantsOfType('string').pop();
      if (source) {
        const importPath = source.text.replace(/['"]/g, '');
        features.add(`import:${importPath}`);
      }
    }

    // ── Operators (binary) ──────────────────────────
    if (type === 'binary_expression') {
      const op = node.childForFieldName('operator');
      if (op) features.add(`op:${op.text}`);
    }
    if (type === 'assignment_expression' || type === 'augmented_assignment_expression') {
      features.add('op:assign');
    }

    // ── Recurse into children ───────────────────────
    for (let i = 0; i < node.childCount; i++) {
      const child = node.child(i);
      if (child) this.walkNode(child, features);
    }
  }

  /**
   * Extract a readable name from a call expression node.
   */
  private extractCallName(node: SyntaxNode): string | null {
    // Try function field
    const fn = node.childForFieldName('function');
    if (fn) {
      // member_expression: obj.method -> "obj.method"
      if (fn.type === 'member_expression') {
        const obj = fn.childForFieldName('object');
        const prop = fn.childForFieldName('property');
        if (obj && prop) return `${obj.text}.${prop.text}`;
      }
      // simple identifier
      if (fn.type === 'identifier') return fn.text;
    }

    // Java: method_invocation has 'name' and optionally 'object'
    const name = node.childForFieldName('name');
    const object = node.childForFieldName('object');
    if (name) {
      return object ? `${object.text}.${name.text}` : name.text;
    }

    return null;
  }

  /**
   * Compare two code snippets by extracting AST features and computing Jaccard similarity.
   *
   * @param aiCode - The AI-generated code
   * @param userCode - The user's full file content (for complete AST)
   * @param filePath - File path for language detection
   * @returns Jaccard similarity score (0.0 - 1.0), or null if AST parsing unavailable
   */
  async compareFeatures(
    aiCode: string,
    userCode: string,
    filePath: string,
  ): Promise<number | null> {
    const grammarName = getGrammarName(filePath);
    if (!grammarName) return null;

    const [treeAi, treeUser] = await Promise.all([
      this.parseCode(aiCode, grammarName),
      this.parseCode(userCode, grammarName),
    ]);

    if (!treeAi || !treeUser) return null;

    const featuresAi = this.extractFeatures(treeAi.rootNode);
    const featuresUser = this.extractFeatures(treeUser.rootNode);

    if (featuresAi.size === 0 && featuresUser.size === 0) return 0;
    if (featuresAi.size === 0 || featuresUser.size === 0) return 0;

    // Jaccard similarity: |A ∩ B| / |A ∪ B|
    let intersectionSize = 0;
    for (const f of featuresAi) {
      if (featuresUser.has(f)) intersectionSize++;
    }
    const unionSize = featuresAi.size + featuresUser.size - intersectionSize;

    return unionSize === 0 ? 0 : intersectionSize / unionSize;
  }

  /**
   * Clear the AST parse cache.
   */
  clearCache(): void {
    this.astCache.clear();
  }
}
