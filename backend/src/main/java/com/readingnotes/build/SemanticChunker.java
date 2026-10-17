package com.readingnotes.build;

import com.readingnotes.config.BuildProperties;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 语义感知切片器（需求 §4.1 / F2.2）
 * <p>
 * 相对脚手架版本的修正点（详见提交说明）：
 * <ol>
 *   <li><b>chunk_id 稳定且全局唯一</b>：由 {@code bookId + position} 派生（32 位 hex），
 *       重建/重试同一本书、或同一进程内处理多本书都不会撞号。脚手架用全局自增序号的
 *       {@code chunk_000001} 会跨书冲突，且无法在断点续传时复用。</li>
 *   <li><b>页码精确到字符</b>：段落只记住起止页，切片在段落内部切开时按"字符偏移比例"
 *       回算所在页，不再一律用段落起始页。</li>
 *   <li><b>重叠切在句界</b>：100 字重叠不再硬截断，而是从尾部向前找最近的句末标点，
 *       避免下一片以半句话开头。</li>
 *   <li><b>一次规划全部批次</b>：切片阶段就把 batchId 分配好（每 16 片一批），
 *       与 {@code chunks.embedding_batch_id} 对齐，断点续传按批次号即可续跑。</li>
 * </ol>
 */
@Slf4j
@Component
public class SemanticChunker {

    /** 句末标点（用于重叠对齐） */
    private static final String SENTENCE_END = "。！？!?…；;.．";

    private final BuildProperties props;

    public SemanticChunker(BuildProperties props) {
        this.props = props;
    }

    /**
     * 执行切片。
     *
     * @param bookId     书籍 id（参与 chunk_id 生成）
     * @param paragraphs 章节分段结果（已带 chapter_path / 页码）
     * @param chunkSize  目标切片字数（粗 1000 / 中 600 / 细 300）
     * @param overlap    重叠字数（默认 100）
     */
    public ChunkPlan chunk(String bookId, List<ChapterFlowBuilder.Section> paragraphs,
                           int chunkSize, int overlap) {
        int size = clamp(chunkSize, props.getChunkSizeMin(), props.getChunkSizeMax());
        int ov = Math.max(0, Math.min(overlap, size / 3));
        int batchSize = Math.max(1, props.getEmbeddingBatchSize());
        int hardLimit = props.getChunkHardLimit();

        List<Chunk> chunks = new ArrayList<>();
        int position = 0;
        // 软目标 / 硬目标：凑到 soft 就可以收片，最多不超过 hard
        int softTarget = size;
        int hardTarget = Math.min(hardLimit, size * 5 / 4 + ov);

        // 段落聚合缓冲：跨章节边界也继续累积（切片可以跨章节，但元数据记"起点章节"）。
        // 早先版本按 chapterPath 强制收片，导致 PDF 里每个"标题行 + 两行代码"的小节
        // 都单独成片（20~40 字），检索命中全是碎片。改为跨节聚合，只在超长时才切。
        List<ChapterFlowBuilder.Section> buffer = new ArrayList<>();
        int bufferChars = 0;

        for (ChapterFlowBuilder.Section para : paragraphs) {
            String text = para.getText() == null ? "" : para.getText().trim();
            int chars = para.getWordCount();
            if (chars == 0) {
                continue;
            }

            // 超长段落：先把缓冲收掉，再单独走滑动窗口切分
            if (chars > hardTarget) {
                if (!buffer.isEmpty()) {
                    position = flushBuffer(bookId, buffer, position, chunks,
                            softTarget, hardTarget, ov, batchSize);
                    buffer.clear();
                    bufferChars = 0;
                }
                position = splitLong(bookId, para, text, position, chunks,
                        softTarget, hardTarget, ov, batchSize);
            } else {
                // 若并入后会超过硬上限，先收片再放入
                if (bufferChars + chars > hardTarget && !buffer.isEmpty()) {
                    position = flushBuffer(bookId, buffer, position, chunks,
                            softTarget, hardTarget, ov, batchSize);
                    buffer.clear();
                    bufferChars = 0;
                }
                buffer.add(para);
                bufferChars += chars;
            }

            // 软上限保护：超过 5000 片不再继续累积，标记 truncated 由上层决定是否警告
            if (position + (buffer.isEmpty() ? 0 : 1) >= hardLimit) {
                log.warn("chunk hard limit reached bookId={} limit={} — truncated", bookId, hardLimit);
                break;
            }
        }
        if (!buffer.isEmpty()) {
            position = flushBuffer(bookId, buffer, position, chunks,
                    softTarget, hardTarget, ov, batchSize);
        }

        int totalBatches = (chunks.size() + batchSize - 1) / batchSize;
        ChunkPlan plan = new ChunkPlan();
        plan.setChunks(chunks);
        plan.setTotalBatches(totalBatches);
        plan.setBatchSize(batchSize);
        plan.setChunkSize(size);
        plan.setOverlap(ov);
        plan.setTruncated(position >= hardLimit && chunks.size() >= hardLimit);
        log.info("chunked bookId={} chunks={} batches={} size={} overlap={}",
                bookId, chunks.size(), totalBatches, size, ov);
        return plan;
    }

    // =========================================================================
    // 段落聚合
    // =========================================================================

    /**
     * 把缓冲区内若干段合并成尽量接近 {@code softTarget} 的切片。
     * <p>
     * 需求 F2.2 要求"段落聚合到 500~800 字为一片"。PDF 里大量段落只有几十字
     * （代码行、列表项、单句注释），若逐段成片会导致 top-k 检索命中大量碎片、
     * Embedding 成本翻十倍。这里按顺序累加，凑够 softTarget 就收片；
     * 只有最后剩一小段时才并入前一片（不超过 hardTarget）。
     * <p>
     * 切片允许多段拼接，元数据（chapter_path / chapter_title / 起始页）取<b>首段</b>的——
     * 引用定位落在切片开头所在章节，符合"引用来源"的语义预期；结束页取所有段的
     * 最大结束页，前端可显示 {@code p12-p15}。
     *
     * @return 收片后的下一个 position
     */
    private int flushBuffer(String bookId, List<ChapterFlowBuilder.Section> buffer,
                            int position, List<Chunk> out,
                            int softTarget, int hardTarget, int ov, int batchSize) {
        if (buffer.isEmpty()) {
            return position;
        }
        if (buffer.size() == 1) {
            ChapterFlowBuilder.Section only = buffer.get(0);
            return splitLong(bookId, only, only.getText().trim(), position, out,
                    softTarget, hardTarget, ov, batchSize);
        }

        int i = 0;
        while (i < buffer.size()) {
            StringBuilder sb = new StringBuilder();
            int startPage = buffer.get(i).getStartPage();
            int endPage = startPage;
            String path = buffer.get(i).getChapterPath();
            String title = buffer.get(i).getChapterTitle();
            int j = i;
            // 贪婪累加，直到够 softTarget 或再加就超 hardTarget
            while (j < buffer.size()) {
                int add = buffer.get(j).getWordCount() + (sb.length() > 0 ? 1 : 0);
                if (sb.length() > 0 && sb.length() + add > hardTarget) {
                    break;
                }
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(buffer.get(j).getText());
                endPage = Math.max(endPage, buffer.get(j).getEndPage());
                j++;
                if (sb.length() >= softTarget) {
                    break;
                }
            }
            // 剩余尾部过短（< 1/3 softTarget）：把尾巴一并吞掉，避免最后留一个十几字的碎片片。
            // 只在不超过 hardTarget + softTarget/2 时执行，防止单片过长。
            int remainChars = bufferCharsOf(buffer, j, buffer.size());
            boolean tailTiny = (buffer.size() - j) > 0
                    && remainChars < softTarget / 3
                    && sb.length() + remainChars <= hardTarget + softTarget / 2;
            if (tailTiny) {
                for (int k = j; k < buffer.size(); k++) {
                    if (sb.length() > 0) {
                        sb.append('\n');
                    }
                    sb.append(buffer.get(k).getText());
                    endPage = Math.max(endPage, buffer.get(k).getEndPage());
                }
                j = buffer.size();
            }

            String content = sb.toString().trim();
            if (!content.isEmpty()) {
                out.add(Chunk.builder()
                        .chunkId(buildChunkId(bookId, position))
                        .bookId(bookId)
                        .position(position)
                        .content(content)
                        .page(startPage)
                        .endPage(Math.max(startPage, endPage))
                        .chapterPath(path)
                        .chapterTitle(title)
                        .wordCount(ChapterFlowBuilder.countChars(content))
                        .batchId(position / batchSize)
                        .build());
                position++;
            }
            i = Math.max(j, i + 1);
        }
        return position;
    }

    private static int bufferCharsOf(List<ChapterFlowBuilder.Section> buffer, int from, int to) {
        int n = 0;
        for (int i = from; i < to; i++) {
            n += buffer.get(i).getWordCount() + 1;
        }
        return n;
    }

    /** 单个长段落：滑动窗口二次切分，窗口内按句界取断点 */
    private int splitLong(String bookId, ChapterFlowBuilder.Section para, String text,
                          int position, List<Chunk> out,
                          int softTarget, int hardTarget, int ov, int batchSize) {
        if (text.isEmpty()) {
            return position;
        }
        int cursor = 0;
        int guard = 0;
        while (cursor < text.length() && guard++ < 10_000) {
            int hardEnd = Math.min(text.length(), cursor + hardTarget);
            int cut = findCutPoint(text, cursor + softTarget, hardEnd);
            if (cut <= cursor) {
                cut = hardEnd;
            }
            String piece = text.substring(cursor, cut).trim();
            if (!piece.isEmpty()) {
                int startPage = pageAt(para, text, cursor);
                int endPage = pageAt(para, text, Math.max(cursor, cut - 1));
                out.add(newChunk(bookId, position, para, piece, startPage, endPage, batchSize));
                position++;
            }
            if (cut >= text.length()) {
                break;
            }
            // 下一片起点 = 本次终点回退 overlap 字，并向后对齐到句首
            int nextStart = Math.max(cursor + 1, cut - ov);
            nextStart = alignToSentenceStart(text, nextStart, cut);
            cursor = nextStart;
        }
        return position;
    }

    // =========================================================================
    // 断点与页码
    // =========================================================================

    /**
     * 在 [preferred, hardEnd] 区间内找最佳断点：
     * 优先最近的句末标点，其次逗号/分号，都没有则退到空白处，最后硬切。
     */
    private int findCutPoint(String text, int preferred, int hardEnd) {
        if (preferred >= text.length()) {
            return text.length();
        }
        int limit = Math.min(hardEnd, text.length());
        // 向后找句末（优先，保证句子完整）
        for (int i = preferred; i < limit; i++) {
            if (SENTENCE_END.indexOf(text.charAt(i)) >= 0) {
                return i + 1;
            }
        }
        // 向前找句末
        for (int i = preferred; i > Math.max(0, preferred - 200); i--) {
            if (SENTENCE_END.indexOf(text.charAt(i)) >= 0) {
                return i + 1;
            }
        }
        // 退而求其次：逗号、顿号、空白
        for (int i = preferred; i < limit; i++) {
            char c = text.charAt(i);
            if (c == '，' || c == ',' || c == '、' || c == ' ') {
                return i + 1;
            }
        }
        return limit;
    }

    /** 把重叠起点向前对齐到句首，避免下一片以半句开始 */
    private int alignToSentenceStart(String text, int start, int upperBound) {
        for (int i = start; i < Math.min(upperBound, start + 60); i++) {
            if (SENTENCE_END.indexOf(text.charAt(i - 1)) >= 0) {
                return i;
            }
        }
        return start;
    }

    /**
     * 按字符偏移回算页码：段落记录了 [startPage, endPage]，
     * 假设页内文本均匀分布，按偏移比例线性插值（误差 ≤ 1 页，足够引用定位）。
     */
    private int pageAt(ChapterFlowBuilder.Section para, String text, int offset) {
        int startPage = para.getStartPage();
        int endPage = Math.max(startPage, para.getEndPage());
        if (endPage == startPage || text.isEmpty()) {
            return startPage;
        }
        double ratio = (double) offset / Math.max(1, text.length());
        int page = startPage + (int) Math.floor(ratio * (endPage - startPage + 1));
        return Math.min(endPage, Math.max(startPage, page));
    }

    private Chunk newChunk(String bookId, int position, ChapterFlowBuilder.Section para,
                           String content, int startPage, int endPage, int batchSize) {
        return Chunk.builder()
                .chunkId(buildChunkId(bookId, position))
                .bookId(bookId)
                .position(position)
                .content(content)
                .page(startPage)
                .endPage(endPage)
                .chapterPath(para.getChapterPath())
                .chapterTitle(para.getChapterTitle())
                .wordCount(ChapterFlowBuilder.countChars(content))
                .batchId(position / batchSize)
                .build();
    }

    /**
     * chunk_id：{@code bookId 的哈希前 16 位} + {@code position 的 16 位 hex}，共 32 位。
     * 同一本书同一切片策略下结果稳定 → 断点续传/重试可覆盖旧向量；不同书不冲突。
     */
    public static String buildChunkId(String bookId, int position) {
        String bookPart = UUID.nameUUIDFromBytes(("book:" + bookId).getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .toString().replace("-", "").substring(0, 16);
        return bookPart + String.format("%016x", position);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    // =========================================================================
    // 结果模型
    // =========================================================================

    @Data
    public static class ChunkPlan {
        private List<Chunk> chunks = List.of();
        private int totalBatches;
        private int batchSize;
        private int chunkSize;
        private int overlap;
        /** 是否因超过硬上限被截断 */
        private boolean truncated;
    }

    @Data
    @Builder
    public static class Chunk {
        private String chunkId;
        private String bookId;
        private int position;
        private String content;
        /** 起始页（引用来源用这个） */
        private int page;
        private int endPage;
        private String chapterPath;
        private String chapterTitle;
        private int wordCount;
        /** 所属 embedding 批次（0-based） */
        private int batchId;
    }

    /** 批次分组（构建任务按批调用 Embedding） */
    public static Map<Integer, List<Chunk>> groupByBatch(List<Chunk> chunks) {
        Map<Integer, List<Chunk>> map = new LinkedHashMap<>();
        for (Chunk c : chunks) {
            map.computeIfAbsent(c.getBatchId(), k -> new ArrayList<>()).add(c);
        }
        return map;
    }
}
