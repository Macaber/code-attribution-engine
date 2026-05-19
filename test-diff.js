const parseDiff = require('parse-diff');

const rawDiff = `diff --git a/src/test.txt b/src/test.txt
index e69de29..d95f3ad 100644
--- a/src/test.txt
+++ b/src/test.txt
@@ -1,2 +1,4 @@
 line1
+line2
+line3
 line4`;

const files = parseDiff(rawDiff);
let tsLines = 0;
for (const file of files) {
  for (const chunk of file.chunks) {
    let currentLines = [];
    let startLine = null;
    let endLine = 0;
    for (const change of chunk.changes) {
      if (change.type === 'add') {
        if (startLine === null) startLine = change.ln;
        endLine = change.ln;
        currentLines.push(change.content.substring(1));
      } else {
        if (currentLines.length > 0 && startLine !== null) {
          tsLines += (endLine - startLine + 1);
          currentLines = [];
          startLine = null;
        }
      }
    }
    if (currentLines.length > 0 && startLine !== null) {
      tsLines += (endLine - startLine + 1);
    }
  }
}
console.log("TS diffLines:", tsLines);
