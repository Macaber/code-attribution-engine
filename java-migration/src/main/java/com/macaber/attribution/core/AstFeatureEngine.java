package com.macaber.attribution.core;

import com.macaber.attribution.util.LRUCache;
import org.treesitter.TSNode;
import org.treesitter.TSParser;
import org.treesitter.TSTree;
import org.treesitter.TSLanguage;
import org.treesitter.TSPoint;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AstFeatureEngine — L3 layer: Tree-sitter AST feature extraction + Containment similarity.
 *
 * Translated from the TypeScript implementation. Uses java-tree-sitter.
 */
public class AstFeatureEngine {

    private final Map<String, TSTree> astCache;
    private final Map<String, TSLanguage> loadedLanguages = new ConcurrentHashMap<>();
    private final LanguageProvider languageProvider;

    public interface LanguageProvider {
        TSLanguage getLanguage(String grammarName);
    }

    public static class LineRange {
        public final int startLine;
        public final int endLine;

        public LineRange(int startLine, int endLine) {
            this.startLine = startLine;
            this.endLine = endLine;
        }
    }

    public AstFeatureEngine(int cacheSize, LanguageProvider languageProvider) {
        this.astCache = Collections.synchronizedMap(new LRUCache<>(cacheSize));
        this.languageProvider = languageProvider;
    }

    public AstFeatureEngine() {
        this.astCache = Collections.synchronizedMap(new LRUCache<>(100));
        this.languageProvider = grammarName -> null;
    }

    public TSTree parseCode(String code, String grammarName) {
        if (code == null || grammarName == null) return null;

        String cacheKey = grammarName + ":" + code;
        TSTree cached = astCache.get(cacheKey);
        if (cached != null) return cached;

        TSLanguage language = loadedLanguages.computeIfAbsent(grammarName, languageProvider::getLanguage);
        if (language == null) return null;

        TSParser parser = new TSParser();
        parser.setLanguage(language);
        TSTree tree = parser.parseString(null, code);

        if (tree != null) {
            astCache.put(cacheKey, tree);
        }
        return tree;
    }

    public Set<String> extractFeatures(TSNode node, String sourceCode, LineRange lineRange) {
        Set<String> features = new HashSet<>();
        if (node != null && sourceCode != null) {
            walkNode(node, sourceCode, features, lineRange);
        }
        return features;
    }

    private void walkNode(TSNode node, String sourceCode, Set<String> features, LineRange lineRange) {
        if (lineRange != null) {
            TSPoint startPoint = node.getStartPoint();
            TSPoint endPoint = node.getEndPoint();
            
            if (startPoint == null || endPoint == null) return;
            
            int nodeStart = startPoint.getRow(); // 0-indexed
            int nodeEnd = endPoint.getRow();

            if (nodeEnd < lineRange.startLine || nodeStart > lineRange.endLine) {
                return;
            }
        }

        String type = node.getType();
        if (type == null) type = "";

        // Function / Method calls
        if (type.equals("call_expression") || type.equals("method_invocation") || type.equals("new_expression")) {
            String callName = extractCallName(node, sourceCode);
            if (callName != null) {
                features.add("call:" + callName);
                TSNode args = node.getChildByFieldName("arguments");
                if (args != null && !args.isNull()) {
                    int argCount = args.getNamedChildCount();
                    features.add("call:" + callName + "/" + argCount);
                }
            }
        }

        // Control flow
        if (type.equals("if_statement") || type.equals("if_expression")) {
            boolean hasElse = node.getChildByFieldName("alternative") != null && !node.getChildByFieldName("alternative").isNull();
            features.add(hasElse ? "control:if_else" : "control:if");
        }
        if (type.equals("for_statement")) features.add("control:for");
        if (type.equals("for_in_statement")) features.add("control:for_in");
        if (type.equals("enhanced_for_statement")) features.add("control:for_each");
        if (type.equals("while_statement")) features.add("control:while");
        if (type.equals("do_statement")) features.add("control:do_while");
        if (type.equals("switch_statement") || type.equals("switch_expression")) {
            features.add("control:switch");
            int caseCount = countDescendantsOfType(node, "switch_case") + countDescendantsOfType(node, "switch_default");
            if (caseCount > 0) features.add("control:switch/" + caseCount);
        }
        if (type.equals("try_statement")) {
            features.add("control:try_catch");
            boolean hasFinalizer = node.getChildByFieldName("finalizer") != null && !node.getChildByFieldName("finalizer").isNull();
            if (hasFinalizer) features.add("control:try_catch_finally");
        }
        if (type.equals("return_statement")) features.add("control:return");
        if (type.equals("throw_statement")) features.add("control:throw");
        if (type.equals("ternary_expression") || type.equals("conditional_expression")) features.add("control:ternary");
        if (type.equals("await_expression")) features.add("control:await");
        if (type.equals("yield_expression")) features.add("control:yield");

        // Declarations
        if (type.equals("function_declaration")) {
            features.add("decl:function");
            TSNode name = node.getChildByFieldName("name");
            if (name != null && !name.isNull()) {
                features.add("decl:fn:" + getText(name, sourceCode));
            }
            TSNode params = node.getChildByFieldName("parameters");
            if (params != null && !params.isNull()) {
                features.add("decl:fn_params/" + params.getNamedChildCount());
            }
        }
        if (type.equals("arrow_function")) {
            features.add("decl:arrow");
            TSNode params = node.getChildByFieldName("parameters");
            if (params != null && !params.isNull()) {
                features.add("decl:arrow_params/" + params.getNamedChildCount());
            }
        }
        if (type.equals("method_definition") || type.equals("method_declaration")) {
            TSNode name = node.getChildByFieldName("name");
            String nameText = name != null && !name.isNull() ? getText(name, sourceCode) : "";
            if (nameText.equals("constructor")) {
                features.add("decl:constructor");
            } else if (nameText.startsWith("get")) {
                features.add("decl:getter");
                features.add("decl:getter:" + nameText);
            } else if (nameText.startsWith("set")) {
                features.add("decl:setter");
                features.add("decl:setter:" + nameText);
            } else {
                features.add("decl:method");
                if (!nameText.isEmpty()) features.add("decl:method:" + nameText);
            }
        }
        if (type.equals("class_declaration")) {
            features.add("decl:class");
            TSNode name = node.getChildByFieldName("name");
            if (name != null && !name.isNull()) features.add("decl:class:" + getText(name, sourceCode));
        }
        if (type.equals("interface_declaration")) {
            features.add("decl:interface");
            TSNode name = node.getChildByFieldName("name");
            if (name != null && !name.isNull()) features.add("decl:interface:" + getText(name, sourceCode));
        }
        if (type.equals("enum_declaration")) features.add("decl:enum");

        // Imports
        if (type.equals("import_statement") || type.equals("import_declaration")) {
            TSNode source = node.getChildByFieldName("source");
            if (source == null || source.isNull()) {
                // fall back to last string
                for (int i = 0; i < node.getChildCount(); i++) {
                    TSNode child = node.getChild(i);
                    if (child != null && !child.isNull() && "string".equals(child.getType())) {
                        source = child;
                    }
                }
            }
            if (source != null && !source.isNull()) {
                String importPath = getText(source, sourceCode).replaceAll("['\"]", "");
                features.add("import:" + importPath);
            }
        }

        // Operators
        if (type.equals("binary_expression")) {
            TSNode op = node.getChildByFieldName("operator");
            if (op != null && !op.isNull()) features.add("op:" + getText(op, sourceCode));
        }
        if (type.equals("unary_expression")) {
            TSNode op = node.getChildByFieldName("operator");
            if (op != null && !op.isNull()) features.add("op:unary:" + getText(op, sourceCode));
        }
        if (type.equals("assignment_expression")) features.add("op:assign");
        if (type.equals("augmented_assignment_expression")) {
            TSNode op = node.getChildByFieldName("operator");
            features.add("op:aug_assign:" + (op != null && !op.isNull() ? getText(op, sourceCode) : "?="));
        }
        if (type.equals("instanceof_expression")) features.add("op:instanceof");
        if (type.equals("typeof_expression") || type.equals("type_query")) features.add("op:typeof");

        // Literals
        if (type.equals("string") || type.equals("template_string")) features.add("literal:string");
        if (type.equals("number")) features.add("literal:number");
        if (type.equals("true") || type.equals("false")) features.add("literal:boolean");
        if (type.equals("null") || type.equals("undefined")) features.add("literal:null");
        if (type.equals("array")) features.add("literal:array");
        if (type.equals("object")) features.add("literal:object");

        // Types
        if (type.equals("type_annotation")) features.add("type:annotation");
        if (type.equals("as_expression")) features.add("type:cast");
        if (type.equals("type_assertion")) features.add("type:assertion");

        // Recurse into children
        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            TSNode child = node.getChild(i);
            if (child != null && !child.isNull()) {
                walkNode(child, sourceCode, features, lineRange);
            }
        }
    }

    private String extractCallName(TSNode node, String sourceCode) {
        TSNode fn = node.getChildByFieldName("function");
        if (fn != null && !fn.isNull()) {
            if ("member_expression".equals(fn.getType())) {
                TSNode obj = fn.getChildByFieldName("object");
                TSNode prop = fn.getChildByFieldName("property");
                if (obj != null && !obj.isNull() && prop != null && !prop.isNull()) {
                    return getText(obj, sourceCode) + "." + getText(prop, sourceCode);
                }
            }
            if ("identifier".equals(fn.getType())) {
                return getText(fn, sourceCode);
            }
        }

        TSNode constructor = node.getChildByFieldName("constructor");
        if (constructor != null && !constructor.isNull()) {
            return "new:" + getText(constructor, sourceCode);
        }

        TSNode name = node.getChildByFieldName("name");
        TSNode object = node.getChildByFieldName("object");
        if (name != null && !name.isNull()) {
            if (object != null && !object.isNull()) {
                return getText(object, sourceCode) + "." + getText(name, sourceCode);
            }
            return getText(name, sourceCode);
        }

        return null;
    }

    private int countDescendantsOfType(TSNode node, String typeName) {
        int count = 0;
        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            TSNode child = node.getChild(i);
            if (child != null && !child.isNull()) {
                if (typeName.equals(child.getType())) {
                    count++;
                }
                count += countDescendantsOfType(child, typeName);
            }
        }
        return count;
    }

    private String getText(TSNode node, String sourceCode) {
        if (node == null || sourceCode == null) return "";
        int start = node.getStartByte();
        int end = node.getEndByte();
        if (start < 0 || end > sourceCode.length() || start >= end) {
            return "";
        }
        return sourceCode.substring(start, end);
    }

    public Double compareFeatures(String aiCode, String userFileContent, String filePath, LineRange diffLineRange) {
        String grammarName = getGrammarName(filePath);
        if (grammarName == null) return null;

        TSTree treeAi = parseCode(aiCode, grammarName);
        TSTree treeUser = parseCode(userFileContent, grammarName);

        if (treeAi == null || treeUser == null) return null;

        Set<String> featuresAi = extractFeatures(treeAi.getRootNode(), aiCode, null);
        Set<String> featuresUser = extractFeatures(treeUser.getRootNode(), userFileContent, diffLineRange);

        if (featuresAi.isEmpty() && featuresUser.isEmpty()) return 0.0;
        if (featuresAi.isEmpty() || featuresUser.isEmpty()) return 0.0;

        int contained = 0;
        for (String f : featuresAi) {
            if (featuresUser.contains(f)) {
                contained++;
            }
        }

        return (double) contained / featuresAi.size();
    }

    private String getGrammarName(String filePath) {
        if (filePath == null) return null;
        if (filePath.endsWith(".java")) return "java";
        if (filePath.endsWith(".ts") || filePath.endsWith(".tsx")) return "typescript";
        if (filePath.endsWith(".js") || filePath.endsWith(".jsx")) return "javascript";
        return null;
    }

    public void clearCache() {
        astCache.clear();
    }
}
