package com.readingnotes.build;

import com.readingnotes.config.BuildProperties;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 构建前切片试算（F2.3）的离线测试：不依赖 MySQL / Chroma / 豆包。
 * <p>
 * 用 {@code data/test/sample.pdf}（12 章 / 36 页）验证：
 * 三档粒度切片数单调递增、统计字段自洽、解析缓存生效、chunk_id 稳定。
 */
class BuildPreviewServiceTest {

    private BuildPreviewService service;
    private File sample;

    @BeforeEach
    void setUp() {
        BuildProperties props = new BuildProperties();
        props.setChunkSizeMin(200);
        props.setChunkSizeMax(1200);
        props.setChunkHardLimit(20000);
        props.setChunkSoftLimit(5000);
        props.setEmbeddingBatchSize(16);
        props.setMaxPages(2000);
        props.setChapterRateWarnThreshold(0.5);

        service = new BuildPreviewService(
                new PdfTextExtractor(),
                new ChapterRecognizer(),
                new ChapterFlowBuilder(),
                new SemanticChunker(props),
                props);

        String path = System.getProperty("test.pdf", "../data/test/sample.pdf");
        sample = new File(path);
        Assumptions.assumeTrue(sample.isFile(), "缺少测试 PDF：" + sample.getAbsolutePath());
    }

    @Test
    void previewReturnsSelfConsistentStats() throws Exception {
        Map<String, Object> data = service.preview("book_test_preview", sample, 600, 100);

        assertTrue((int) data.get("chunk_count") > 0, "切片数应大于 0");
        assertEquals(true, data.get("dry_run"));
        assertEquals(600, data.get("chunk_size"));
        assertNotNull(data.get("granularity_options"));

        Map<String, Object> stats = castMap(data.get("chunk_stats"));
        int min = (int) stats.get("min");
        int p50 = (int) stats.get("p50");
        int p90 = (int) stats.get("p90");
        int max = (int) stats.get("max");
        assertTrue(min <= p50 && p50 <= p90 && p90 <= max, "分位数应单调: " + stats);
        assertTrue(max <= 1200 * 2, "单片不应远超硬上限: " + max);

        Map<String, Object> chapter = castMap(data.get("chapter"));
        assertEquals(true, chapter.get("detected"), "样例 PDF 应识别到章节");

        List<?> samples = (List<?>) data.get("samples");
        assertFalse(samples.isEmpty(), "应给出抽样切片");
    }

    @Test
    void coarserGranularityProducesFewerChunks() throws Exception {
        int coarse = (int) service.preview("book_test_g", sample, 1000, 100).get("chunk_count");
        int medium = (int) service.preview("book_test_g", sample, 600, 100).get("chunk_count");
        int fine = (int) service.preview("book_test_g", sample, 300, 60).get("chunk_count");

        assertTrue(coarse < medium, "粗粒度切片数应少于中粒度: " + coarse + " vs " + medium);
        assertTrue(medium < fine, "中粒度切片数应少于细粒度: " + medium + " vs " + fine);
    }

    @Test
    void secondPreviewHitsParseCache() throws Exception {
        Map<String, Object> first = service.preview("book_test_cache", sample, 600, 100);
        Map<String, Object> second = service.preview("book_test_cache", sample, 1000, 100);

        assertEquals(false, first.get("cached_parse"), "首次应真实解析");
        assertEquals(true, second.get("cached_parse"), "换粒度应命中解析缓存");
        assertTrue((long) second.get("elapsed_ms") <= 4000, "命中缓存后重算应是毫秒级");
    }

    @Test
    void chunkIdsAreStableAcrossCalls() throws Exception {
        Map<String, Object> a = service.preview("book_test_stable", sample, 600, 100);
        Map<String, Object> b = service.preview("book_test_stable", sample, 600, 100);
        List<?> sa = (List<?>) a.get("samples");
        List<?> sb = (List<?>) b.get("samples");
        for (int i = 0; i < sa.size(); i++) {
            assertEquals(castMap(sa.get(i)).get("chunk_id"), castMap(sb.get(i)).get("chunk_id"),
                    "同书同粒度下 chunk_id 必须稳定（断点续传依赖）");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object o) {
        return (Map<String, Object>) o;
    }
}
