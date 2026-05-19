const parseDiff = require('parse-diff');
const diff = `
diff --git a/test.txt b/test.txt
index 83db48f..f3f6c86 100644
--- a/test.txt
+++ b/test.txt
@@ -1,3 +1,4 @@
 line1
-line2
+added2
+added3
 line3
`;
console.log(JSON.stringify(parseDiff(diff), null, 2));
