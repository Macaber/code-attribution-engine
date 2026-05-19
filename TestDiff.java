import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.List;
import java.util.ArrayList;

public class TestDiff {
    private static final Pattern CHUNK_HEADER_PATTERN = Pattern.compile("@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@.*");
    
    public static void main(String[] args) {
        String rawDiff = "diff --git a/src/test.txt b/src/test.txt\r\n" +
                         "index e69de29..d95f3ad 100644\r\n" +
                         "--- a/src/test.txt\r\n" +
                         "+++ b/src/test.txt\r\n" +
                         "@@ -1,2 +1,4 @@\r\n" +
                         " line1\r\n" +
                         "+line2\r\n" +
                         "+line3\r\n" +
                         " line4";
                         
        String[] lines = rawDiff.split("\n");
        int diffLines = 0;
        int currentLineNumber = 0;
        Integer startLine = null;
        
        for (String line : lines) {
            if (line.startsWith("+++ ")) {
                if (startLine != null) diffLines += (currentLineNumber - 1 - startLine + 1);
                startLine = null;
            } else if (line.startsWith("@@ ")) {
                if (startLine != null) diffLines += (currentLineNumber - 1 - startLine + 1);
                startLine = null;
                Matcher m = CHUNK_HEADER_PATTERN.matcher(line);
                if (m.matches()) {
                    currentLineNumber = Integer.parseInt(m.group(1));
                }
            } else if (line.startsWith("+") && !line.startsWith("+++")) {
                if (startLine == null) {
                    startLine = currentLineNumber;
                }
                currentLineNumber++;
            } else if (line.startsWith("-") && !line.startsWith("---")) {
                if (startLine != null) diffLines += (currentLineNumber - 1 - startLine + 1);
                startLine = null;
            } else if (line.startsWith(" ") || line.isEmpty() || line.startsWith("\\")) {
                if (startLine != null) diffLines += (currentLineNumber - 1 - startLine + 1);
                startLine = null;
                if (line.startsWith(" ") || line.isEmpty()) {
                    currentLineNumber++;
                }
            }
        }
        if (startLine != null) diffLines += (currentLineNumber - 1 - startLine + 1);
        System.out.println("Java diffLines: " + diffLines);
    }
}
