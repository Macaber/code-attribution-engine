package com.macaber.attribution.core;

import com.macaber.attribution.dto.MergeFileDetail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class AttributionFilterTest {

    private PipelineConfig pipelineConfig;
    private AttributionFilter filter;

    @BeforeEach
    void setUp() {
        pipelineConfig = new PipelineConfig();
        // Set standard defaults manually since we are not running a full Spring boot context
        pipelineConfig.setFilterEnabled(true);
        pipelineConfig.setExcludeExtensions(Arrays.asList(
                "log", "txt", "png", "jpg", "jpeg", "gif", "pdf", "zip", "tar", "gz", "exe", "dll", "so",
                "bin", "woff", "ttf", "class", "jar", "lock", "md", "csv", "tsv", "xlsx"
        ));
        pipelineConfig.setMaxFileSizeKb(500);
        pipelineConfig.setMaxDiffSizeKb(100);
        pipelineConfig.setMaxFileLines(5000);
        pipelineConfig.setFilterBinary(true);
        pipelineConfig.setFilterLogs(true);

        filter = new AttributionFilter(pipelineConfig);
    }

    @Test
    void testShouldNotFilter_ValidJavaFile() {
        MergeFileDetail file = new MergeFileDetail();
        file.setPath("src/main/java/com/macaber/attribution/core/SimilarityEngine.java");
        file.setCode("package com.macaber.attribution.core;\n\npublic class SimilarityEngine {\n}");
        file.setDiff("@@ -1,3 +1,3 @@\n+public class SimilarityEngine {\n}");

        assertFalse(filter.shouldFilter(file));
    }

    @Test
    void testShouldFilter_ExcludedExtension() {
        MergeFileDetail file = new MergeFileDetail();
        file.setPath("logs/app.log");
        file.setCode("some log content");
        file.setDiff("some diff");

        assertTrue(filter.shouldFilter(file));

        MergeFileDetail imageFile = new MergeFileDetail();
        imageFile.setPath("assets/logo.png");
        assertTrue(filter.shouldFilter(imageFile));
    }

    @Test
    void testShouldFilter_BinaryIndicatorInDiff() {
        MergeFileDetail file = new MergeFileDetail();
        file.setPath("bin/program");
        file.setDiff("Binary files a/bin/program and b/bin/program differ\n");

        assertTrue(filter.shouldFilter(file));
    }

    @Test
    void testShouldFilter_NullBytesInContent() {
        MergeFileDetail file = new MergeFileDetail();
        file.setPath("data/cache.dat");
        file.setCode("some text \0 with null byte");
        file.setDiff("diff \0");

        assertTrue(filter.shouldFilter(file));
    }

    @Test
    void testShouldFilter_OverLimitFileSize() {
        MergeFileDetail file = new MergeFileDetail();
        file.setPath("src/LargeFile.java");

        // Build a string slightly over 500KB
        char[] chars = new char[501 * 1024];
        Arrays.fill(chars, 'a');
        file.setCode(new String(chars));
        file.setDiff("diff content");

        assertTrue(filter.shouldFilter(file));
    }

    @Test
    void testShouldFilter_OverLimitDiffSize() {
        MergeFileDetail file = new MergeFileDetail();
        file.setPath("src/LargeDiff.java");
        file.setCode("short code");

        // Build a diff slightly over 100KB
        char[] chars = new char[101 * 1024];
        Arrays.fill(chars, 'a');
        file.setDiff(new String(chars));

        assertTrue(filter.shouldFilter(file));
    }

    @Test
    void testShouldFilter_OverLimitLinesCount() {
        MergeFileDetail file = new MergeFileDetail();
        file.setPath("src/LongFile.java");

        // 5001 lines of short code
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i <= 5000; i++) {
            sb.append("line\n");
        }
        file.setCode(sb.toString());
        file.setDiff("diff content");

        assertTrue(filter.shouldFilter(file));
    }

    @Test
    void testShouldFilter_LogFileByContentDetection() {
        MergeFileDetail file = new MergeFileDetail();
        file.setPath("data/output.txt"); // txt is in excluded extensions by default, but let's test content matching

        // Content clearly looks like a log with timestamps and levels
        String content = "2026-05-22 14:46:03.123 [INFO] Application started\n" +
                "2026-05-22 14:46:04.223 [DEBUG] Initializing bean\n" +
                "2026-05-25 14:49:44.000 [WARN] Rate limit reached\n" +
                "2026-05-25 14:50:00.000 [ERROR] Connection failed\n";
        file.setCode(content);
        file.setDiff(content);

        assertTrue(filter.shouldFilter(file));
    }

    @Test
    void testShouldNotFilter_IfFilterDisabled() {
        pipelineConfig.setFilterEnabled(false);

        MergeFileDetail file = new MergeFileDetail();
        file.setPath("logs/app.log");
        file.setCode("some log content");

        assertFalse(filter.shouldFilter(file));
    }
}
