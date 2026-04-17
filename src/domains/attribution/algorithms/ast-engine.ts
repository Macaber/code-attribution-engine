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
 * AstFeatureEngine — L3 layer: Tree-sitter AST feature extraction + Containment similarity.
 *
 * Instead of expensive tree edit distance, we flatten the AST into a set of
 * semantic features (function calls, control flow, operators, imports) and
 * compare via Containment. This is robust against:
 * - Variable renaming
 * - Code reordering
 * - Extracting logic into sub-functions
 *
 * Key optimization: when a diff line range is provided, only features from
 * AST nodes within that range are extracted from the user file, avoiding
 * "full-file noise" that dilutes the comparison.
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
   * Returns a Set of feature strings.
   *
   * @param node - Root node to extract from
   * @param lineRange - Optional: only extract from nodes within this line range (0-indexed)
   */
  extractFeatures(node: SyntaxNode, lineRange?: { startLine: number; endLine: number }): Set<string> {
    const features = new Set<string>();
    this.walkNode(node, features, lineRange);
    return features;
  }

  /**
   * Recursively walk the AST and extract enhanced semantic features.
   *
   * Enhanced over v1:
   * - call: includes argument count (call:fetch/2)
   * - control: distinguishes if vs if_else, for_in vs for
   * - decl: includes constructor, method, getter/setter
   * - literal: captures string/number/boolean literal presence
   * - type: captures type annotations (TypeScript)
   */
  private walkNode(
    node: SyntaxNode,
    features: Set<string>,
    lineRange?: { startLine: number; endLine: number },
  ): void {
    // ── Line-range filter: skip nodes entirely outside the diff range ──
    if (lineRange) {
      const nodeStart = node.startPosition.row; // 0-indexed
      const nodeEnd = node.endPosition.row;

      // If the node ends before our range or starts after it, skip entirely
      if (nodeEnd < lineRange.startLine || nodeStart > lineRange.endLine) {
        return;
      }
    }

    const type = node.type;

    // ── Function / Method calls ─────────────────────
    if (
      type === 'call_expression' ||
      type === 'method_invocation' ||   // Java
      type === 'new_expression'
    ) {
      const callName = this.extractCallName(node);
      if (callName) {
        features.add(`call:${callName}`);
        // Enhanced: include argument count for higher specificity
        const args = node.childForFieldName('arguments');
        if (args) {
          const argCount = args.namedChildCount;
          features.add(`call:${callName}/${argCount}`);
        }
      }
    }

    // ── Control flow (enhanced granularity) ──────────
    if (type === 'if_statement' || type === 'if_expression') {
      // Distinguish if vs if-else
      const hasElse = node.childForFieldName('alternative') !== null;
      features.add(hasElse ? 'control:if_else' : 'control:if');
    }
    if (type === 'for_statement') {
      features.add('control:for');
    }
    if (type === 'for_in_statement') {
      features.add('control:for_in');
    }
    if (type === 'enhanced_for_statement') { // Java
      features.add('control:for_each');
    }
    if (type === 'while_statement') {
      features.add('control:while');
    }
    if (type === 'do_statement') {
      features.add('control:do_while');
    }
    if (type === 'switch_statement' || type === 'switch_expression') {
      features.add('control:switch');
      // Count cases for specificity
      const caseCount = node.descendantsOfType('switch_case').length +
                         node.descendantsOfType('switch_default').length;
      if (caseCount > 0) features.add(`control:switch/${caseCount}`);
    }
    if (type === 'try_statement') {
      features.add('control:try_catch');
      const hasFinalizer = node.childForFieldName('finalizer') !== null;
      if (hasFinalizer) features.add('control:try_catch_finally');
    }
    if (type === 'return_statement') {
      features.add('control:return');
    }
    if (type === 'throw_statement') {
      features.add('control:throw');
    }
    if (type === 'ternary_expression' || type === 'conditional_expression') {
      features.add('control:ternary');
    }
    if (type === 'await_expression') {
      features.add('control:await');
    }
    if (type === 'yield_expression') {
      features.add('control:yield');
    }

    // ── Function / Class declarations (enhanced) ────
    if (type === 'function_declaration') {
      features.add('decl:function');
      const name = node.childForFieldName('name');
      if (name) features.add(`decl:fn:${name.text}`);
      // Count parameters
      const params = node.childForFieldName('parameters');
      if (params) features.add(`decl:fn_params/${params.namedChildCount}`);
    }
    if (type === 'arrow_function') {
      features.add('decl:arrow');
      const params = node.childForFieldName('parameters');
      if (params) features.add(`decl:arrow_params/${params.namedChildCount}`);
    }
    if (type === 'method_definition' || type === 'method_declaration') {
      const name = node.childForFieldName('name');
      const nameText = name?.text ?? '';
      if (nameText === 'constructor') {
        features.add('decl:constructor');
      } else if (nameText.startsWith('get')) {
        features.add('decl:getter');
        features.add(`decl:getter:${nameText}`);
      } else if (nameText.startsWith('set')) {
        features.add('decl:setter');
        features.add(`decl:setter:${nameText}`);
      } else {
        features.add('decl:method');
        if (name) features.add(`decl:method:${nameText}`);
      }
    }
    if (type === 'class_declaration') {
      features.add('decl:class');
      const name = node.childForFieldName('name');
      if (name) features.add(`decl:class:${name.text}`);
    }
    if (type === 'interface_declaration') {
      features.add('decl:interface');
      const name = node.childForFieldName('name');
      if (name) features.add(`decl:interface:${name.text}`);
    }
    if (type === 'enum_declaration') {
      features.add('decl:enum');
    }

    // ── Imports ─────────────────────────────────────
    if (type === 'import_statement' || type === 'import_declaration') {
      const source = node.childForFieldName('source') ?? node.descendantsOfType('string').pop();
      if (source) {
        const importPath = source.text.replace(/['"]/g, '');
        features.add(`import:${importPath}`);
      }
    }

    // ── Operators (enhanced) ────────────────────────
    if (type === 'binary_expression') {
      const op = node.childForFieldName('operator');
      if (op) features.add(`op:${op.text}`);
    }
    if (type === 'unary_expression') {
      const op = node.childForFieldName('operator');
      if (op) features.add(`op:unary:${op.text}`);
    }
    if (type === 'assignment_expression') {
      features.add('op:assign');
    }
    if (type === 'augmented_assignment_expression') {
      const op = node.childForFieldName('operator');
      features.add(`op:aug_assign:${op?.text ?? '?='}`);
    }
    if (type === 'instanceof_expression') {
      features.add('op:instanceof');
    }
    if (type === 'typeof_expression' || type === 'type_query') {
      features.add('op:typeof');
    }

    // ── Literals (type presence, not values) ────────
    if (type === 'string' || type === 'template_string') {
      features.add('literal:string');
    }
    if (type === 'number') {
      features.add('literal:number');
    }
    if (type === 'true' || type === 'false') {
      features.add('literal:boolean');
    }
    if (type === 'null' || type === 'undefined') {
      features.add('literal:null');
    }
    if (type === 'array') {
      features.add('literal:array');
    }
    if (type === 'object') {
      features.add('literal:object');
    }

    // ── Type annotations (TypeScript) ───────────────
    if (type === 'type_annotation') {
      features.add('type:annotation');
    }
    if (type === 'as_expression') {
      features.add('type:cast');
    }
    if (type === 'type_assertion') {
      features.add('type:assertion');
    }

    // ── Recurse into children ───────────────────────
    for (let i = 0; i < node.childCount; i++) {
      const child = node.child(i);
      if (child) this.walkNode(child, features, lineRange);
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

    // new_expression: class name
    const constructor = node.childForFieldName('constructor');
    if (constructor) {
      return `new:${constructor.text}`;
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
   * Compare AI code features against the user's file, scoped to the diff region.
   *
   * Optimization: parses the full file for valid AST, but only extracts features
   * from AST nodes within the diff chunk's line range. This prevents unrelated
   * code in the file from diluting the comparison.
   *
   * @param aiCode - The AI-generated code
   * @param userFileContent - Full file content (for complete, valid AST)
   * @param filePath - File path for language detection
   * @param diffLineRange - Optional: diff chunk line range to scope feature extraction
   * @returns Containment score (0.0 - 1.0), or null if AST parsing unavailable
   */
  async compareFeatures(
    aiCode: string,
    userFileContent: string,
    filePath: string,
    diffLineRange?: { startLine: number; endLine: number },
  ): Promise<number | null> {
    const grammarName = getGrammarName(filePath);
    if (!grammarName) return null;

    const [treeAi, treeUser] = await Promise.all([
      this.parseCode(aiCode, grammarName),
      this.parseCode(userFileContent, grammarName),
    ]);

    if (!treeAi || !treeUser) return null;

    // AI code: extract all features (it's the snippet we're looking for)
    const featuresAi = this.extractFeatures(treeAi.rootNode);

    // User file: only extract features from the diff region (if provided)
    // This avoids "full-file noise" diluting the comparison
    const featuresUser = this.extractFeatures(treeUser.rootNode, diffLineRange);

    if (featuresAi.size === 0 && featuresUser.size === 0) return 0;
    if (featuresAi.size === 0 || featuresUser.size === 0) return 0;

    // Containment: |featAI ∩ featUser| / |featAI|
    // "AI 的结构特征有多少出现在用户文件中?"
    let contained = 0;
    for (const f of featuresAi) {
      if (featuresUser.has(f)) contained++;
    }

    return contained / featuresAi.size;
  }

  /**
   * Clear the AST parse cache.
   */
  clearCache(): void {
    this.astCache.clear();
  }
}
