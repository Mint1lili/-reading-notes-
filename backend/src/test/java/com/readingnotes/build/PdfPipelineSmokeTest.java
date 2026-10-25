package com.readingnotes.build;

import com.readingnotes.config.BuildProperties;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 解析 → 章节识别 → 分段 → 切片 的离线冒烟测试。
 * <p>
 * 不依赖 MySQL / Chroma / 豆包 API，只用 PDFBox，可在 CI 里跑。
 * 测试样本通过系统属性 {@code -Dtest.pdf=路径} 指定；未指定时用
 * {@code ../data/test/sample.pdf}（存在才跑），否则跳过。
 */
class PdfPipelineSmokeTest {

    private final PdfTextExtractor extractor = new PdfTextExtractor();
    private final ChapterRecognizer recognizer = new ChapterRecognizer();
    private final ChapterFlowBuilder flowBuilder = new ChapterFlowBuilder();

    private SemanticChunker chunker() {
        BuildProperties props = new BuildProperties();
        props.setEmbeddingBatchSize(16);
        props.setChunkSizeMin(200);
        props.setChunkSizeMax(1200);
        props.setChunkHardLimit(20000);
        return new SemanticChunker(props);
    }

    private File sample() {
        String path = System.getProperty("test.pdf");
        if (path == null || path.isBlank()) {
            return null;
        }
        File f = new File(path);
        return f.isFile() ? f : null;
    }

    @Test
    void fullPipelineOnRealPdf() throws Exception {
        File pdf = sample();
        if (pdf == null) {
            System.out.println("[skip] 未提供 -Dtest.pdf，跳过真实 PDF 冒烟测试");
            return;
        }

        // ---- 1. 解析 ----
        PdfTextExtractor.ExtractResult ex = extractor.extract(pdf);
        System.out.printf("[parse] pages=%d lines=%d chars=%d scanned=%s title=%s%n",
                ex.getTotalPages(), ex.getLines().size(), ex.getCharCount(), ex.isScanned(), ex.getTitle());
        assertFalse(ex.isScanned(), "样本 PDF 不应被判定为扫描件");
        assertTrue(ex.getCharCount() > 1000, "样本应提取到足够文本");
        assertFalse(ex.getLines().isEmpty());
        // 行页码必须单调不减
        int prevPage = 0;
        for (TextLine l : ex.getLines()) {
            assertTrue(l.getPage() >= prevPage, "行页码应单调不减");
            prevPage = l.getPage();
        }

        // ---- 2. 章节识别 ----
        ChapterRecognizer.Recognized rec = recognizer.recognize(ex.getLines());
        System.out.printf("[recognize] headings=%d bodyFont=%.1f rate=%.2f%n",
                rec.getHeadings().size(), rec.getBodyFontSize(), rec.getRecognizedRate());
        System.out.print(ChapterFlowBuilder.printTree(rec.getHeadings()));
        assertTrue(rec.getBodyFontSize() > 0, "正文主字号应大于 0");
        // chapter_path 不应出现 "0." 之类非法前缀
        for (ChapterRecognizer.Heading h : rec.getHeadings()) {
            assertNotNull(h.getChapterPath(), "标题应有 chapter_path");
            assertFalse(h.getChapterPath().startsWith("0."),
                    "chapter_path 不应以 0. 开头: " + h.getChapterPath());
            assertTrue(h.getLevel() >= 1 && h.getLevel() <= 3, "level 应在 1~3");
        }

        // ---- 3. 分段 ----
        ChapterFlowBuilder.Flow flow = flowBuilder.build(ex.getLines(), rec, ex.getTotalPages());
        System.out.printf("[flow] paragraphs=%d hasStructure=%s%n",
                flow.getParagraphs().size(), flow.isHasStructure());
        assertFalse(flow.getParagraphs().isEmpty());
        for (ChapterFlowBuilder.Section s : flow.getParagraphs()) {
            assertTrue(s.getEndPage() >= s.getStartPage());
            assertTrue(s.getWordCount() > 0);
        }

        // ---- 4. 切片 ----
        for (int size : new int[]{1000, 600, 300}) {
            SemanticChunker.ChunkPlan plan =
                    chunker().chunk("book_test_001", flow.getParagraphs(), size, 100);
            List<SemanticChunker.Chunk> chunks = plan.getChunks();
            System.out.printf("[chunk %d] count=%d batches=%d avgWords=%d truncated=%s%n",
                    size, chunks.size(), plan.getTotalBatches(),
                    chunks.stream().mapToInt(SemanticChunker.Chunk::getWordCount).sum()
                            / Math.max(1, chunks.size()), plan.isTruncated());

            assertFalse(chunks.isEmpty(), "切片不应为空");
            // 粒度粗 → 片数少
            assertEquals(plan.getTotalBatches(), (chunks.size() + 15) / 16, "批次划分应每 16 片一批");

            Set<String> ids = new HashSet<>();
            int prevPos = -1;
            int maxWords = 0;
            for (SemanticChunker.Chunk c : chunks) {
                assertTrue(ids.add(c.getChunkId()), "chunk_id 必须唯一: " + c.getChunkId());
                assertEquals(32, c.getChunkId().length(), "chunk_id 应为 32 位");
                assertTrue(c.getPosition() > prevPos, "position 应严格递增");
                prevPos = c.getPosition();
                assertTrue(c.getPage() >= 1, "页码应 >= 1");
                assertTrue(c.getEndPage() >= c.getPage(), "endPage 应 >= page");
                assertFalse(c.getContent().isBlank(), "切片内容不应为空");
                // 单片上限 = min(hardLimit, size*1.25 + overlap)，见 SemanticChunker.hardTarget
                int hardCap = Math.min(20000, size * 5 / 4 + Math.min(100, size / 3));
                assertTrue(c.getWordCount() <= hardCap + size / 2,
                        "切片过长 position=" + c.getPosition() + " words=" + c.getWordCount());
                maxWords = Math.max(maxWords, c.getWordCount());
            }
            System.out.printf("[chunk %d] maxWords=%d firstPage=%d lastPage=%d%n",
                    size, maxWords, chunks.get(0).getPage(),
                    chunks.get(chunks.size() - 1).getEndPage());
        }
    }

    /** 同一本书同一内容 → chunk_id 必须稳定（断点续传依赖） */
    @Test
    void chunkIdIsStable() {
        assertEquals(SemanticChunker.buildChunkId("book_A", 7),
                SemanticChunker.buildChunkId("book_A", 7));
        assertNotEquals(SemanticChunker.buildChunkId("book_A", 7),
                SemanticChunker.buildChunkId("book_B", 7));
        assertNotEquals(SemanticChunker.buildChunkId("book_A", 7),
                SemanticChunker.buildChunkId("book_A", 8));
    }
}
