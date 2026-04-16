import { DiffParser } from '../diff-parser';

describe('DiffParser', () => {
  const parser = new DiffParser();

  const mockDiff = `diff --git a/src/utils.ts b/src/utils.ts
new file mode 100644
index 0000000..1234567
--- /dev/null
+++ b/src/utils.ts
@@ -0,0 +1,10 @@
+function add(a: number, b: number): number {
+  return a + b;
+}
+
+function subtract(a: number, b: number): number {
+  return a - b;
+}`;

  it('should parse a unified diff and extract added chunks', () => {
    const chunks = parser.parse(mockDiff);
    expect(chunks.length).toBeGreaterThan(0);
  });

  it('should set filePath correctly', () => {
    const chunks = parser.parse(mockDiff);
    expect(chunks[0].filePath).toBe('src/utils.ts');
  });

  it('should set line numbers correctly', () => {
    const chunks = parser.parse(mockDiff);
    // First chunk should start at line 1
    expect(chunks[0].startLine).toBe(1);
  });

  it('should populate normalizedContent', () => {
    const chunks = parser.parse(mockDiff);
    expect(chunks[0].normalizedContent).toBeTruthy();
    // Normalized should be lowercase, no whitespace
    expect(chunks[0].normalizedContent).not.toContain(' ');
    expect(chunks[0].normalizedContent).toBe(
      chunks[0].normalizedContent.toLowerCase()
    );
  });

  it('should split chunks at non-added lines (context/deletion boundaries)', () => {
    const diffWithContext = `diff --git a/src/app.ts b/src/app.ts
index 1234567..abcdefg 100644
--- a/src/app.ts
+++ b/src/app.ts
@@ -1,5 +1,8 @@
 import express from 'express';
+import cors from 'cors';
+import helmet from 'helmet';
 
 const app = express();
+app.use(cors());
+app.use(helmet());
 app.listen(3000);`;

    const chunks = parser.parse(diffWithContext);
    // Should have 2 chunks: (cors+helmet imports) and (cors()+helmet() use)
    expect(chunks.length).toBe(2);
    expect(chunks[0].content).toContain('cors');
    expect(chunks[0].content).toContain('helmet');
    expect(chunks[1].content).toContain('app.use');
  });

  it('should handle empty diff', () => {
    expect(parser.parse('')).toEqual([]);
    expect(parser.parse('   ')).toEqual([]);
  });

  it('should handle diff with only deletions (no added lines)', () => {
    const deletionOnlyDiff = `diff --git a/src/old.ts b/src/old.ts
index abcdef..000000 100644
--- a/src/old.ts
+++ b/src/old.ts
@@ -1,3 +1,1 @@
-function deprecated() {
-  return null;
-}
 // keep this`;

    const chunks = parser.parse(deletionOnlyDiff);
    expect(chunks.length).toBe(0);
  });

  it('should handle multiple files in a single diff', () => {
    const multiFileDiff = `diff --git a/a.ts b/a.ts
new file mode 100644
--- /dev/null
+++ b/a.ts
@@ -0,0 +1,2 @@
+const a = 1;
+const b = 2;
diff --git a/b.ts b/b.ts
new file mode 100644
--- /dev/null
+++ b/b.ts
@@ -0,0 +1,2 @@
+const c = 3;
+const d = 4;`;

    const chunks = parser.parse(multiFileDiff);
    expect(chunks.length).toBe(2);
    expect(chunks[0].filePath).toBe('a.ts');
    expect(chunks[1].filePath).toBe('b.ts');
  });
});
