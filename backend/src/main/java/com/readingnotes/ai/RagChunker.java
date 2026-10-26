package com.readingnotes.ai;

import lombok.Builder;
import lombok.Data;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 早期脚手架的语义切片器（需求 §4.1 的初版实现）。
 * <p>
 * <b>当前链路未使用</b>：正式构建走 {@link com.readingnotes.build.SemanticChunker}，
 * 后者解决了本类遗留的三个问题——chunk_id 跨书冲突（这里用全局自增 {@code chunk_000001}）、
 * 页码只记段落起始页、重叠硬截断。保留本类仅作为算法演进参考，新代码不要依赖。
 * <p>
 * 初版规则备忘：
 * - 默认 600 字/片，范围 400~800 触发二次切分
 * - 章节优先：每章一个逻辑单元；单章 &gt; 3000 字二次切分；&lt; 1500 字不切整章保留
 * - 相邻切片 100 字重叠，防止切断语义
 */
@Component
public class RagChunker {

    /**
     * @param sections 章节分组内容（每项含章节路径/标题/起始页/文本）
     * @param chunkSize 目标切片字数（默认 600）
     * @param overlap   重叠字数（默认 100）
     * @return 切片列表
     */
    public List<Chunk> chunk(List<Section> sections, int chunkSize, int overlap) {
        List<Chunk> out = new ArrayList<>();
        int seq = 0;
        for (Section s : sections) {
            if (s.getWordCount() == 0) {
                continue;
            }
            // 章节过短：< 1500 字不切，整章一片
            if (s.getWordCount() < 1500) {
                out.add(new Chunk(generateChunkId(++seq), s, s.getText(), 1, s.getStartPage()));
                continue;
            }
            // 过长章节按段落聚合二次切分
            List<String> paragraphs = splitParagraphs(s.getText());
            StringBuilder buf = new StringBuilder();
            int startPage = s.getStartPage();
            int pieceNo = 0;
            for (String p : paragraphs) {
                if (buf.length() > 0 && buf.length() + p.length() > chunkSize * 2) {
                    // 超过 2 倍目标长度强制切（800 上限）
                    flush(out, s, buf, startPage, ++seq, ++pieceNo);
                    buf = new StringBuilder();
                }
                if (buf.length() > 0 && buf.length() + p.length() > chunkSize) {
                    // 达到目标长度：留 overlap 尾巴进下一片
                    String tail = buf.substring(Math.max(0, buf.length() - overlap));
                    flush(out, s, buf, startPage, ++seq, ++pieceNo);
                    buf = new StringBuilder(tail);
                }
                buf.append(p);
            }
            if (buf.length() > 0) {
                flush(out, s, buf, startPage, ++seq, ++pieceNo);
            }
        }
        return out;
    }

    private void flush(List<Chunk> out, Section s, StringBuilder buf, int startPage,
                       int seq, int pieceNo) {
        String text = buf.toString().trim();
        if (text.isEmpty()) {
            return;
        }
        out.add(new Chunk(generateChunkId(seq), s, text, pieceNo, startPage));
    }

    /** 按换行与句号切段落（简单启发式，正式版可替换为 PDF 段落边界信息） */
    private List<String> splitParagraphs(String text) {
        String[] parts = text.split("(?<=[。！？!?\\n])");
        List<String> out = new ArrayList<>();
        for (String p : parts) {
            String t = p.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    private String generateChunkId(int seq) {
        // 正式实现：UUID v4 32 位无连字符（数据库设计 §2.3）；此处保持与 seq 对应便于调试
        return "chunk_" + String.format("%06d", seq);
    }

    /** 章节分组输入 */
    @Data
    @Builder
    public static class Section {
        private String bookId;
        private String chapterPath;
        private String chapterTitle;
        private String text;
        private int startPage;
        private int wordCount;
    }

    /** 切片结果 */
    @Data
    public static class Chunk {
        private final String chunkId;
        private final Section section;
        private final String content;
        private final int position;
        private final int page;

        public Chunk(String chunkId, Section section, String content, int position, int page) {
            this.chunkId = chunkId;
            this.section = section;
            this.content = content;
            this.position = position;
            this.page = page;
        }
    }
}
