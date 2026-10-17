package com.readingnotes.build;

import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 章节分段器：把「行列表 + 标题列表」组装成「章节/段落」结构，供切片器消费。
 * <p>
 * 输出单元 {@link Section} 的粒度是<b>段落</b>而不是章节：
 * 切片器需要"段落聚合到 500~800 字"的原料（需求 F2.2），因此保留段落边界，
 * 同时每个段落带上 chapter_path / 起始页 / 字号，让切片器能精确回算页码。
 * <p>
 * 降级策略（需求 F2.1）：未识别到任何标题时，整本书作为一个逻辑 section，
 * chapter_path 置空 → 问答退化为纯向量检索。
 */
@Slf4j
@Component
public class ChapterFlowBuilder {

    /** 段落合并的硬上限：单段落超过此长度一定断开（防止 PDF 无标点时整章粘成一段） */
    private static final int MAX_PARAGRAPH_CHARS = 1200;

    /**
     * 段落起始缩进阈值（以全角空格计）。
     * PDFBox 会把中文排版的首行缩进还原成空格，这是最可靠的段落边界线索：
     * 首行缩进 2 字 ≈ 两个全角空格 / 四个半角空格。
     */
    private static final int PARAGRAPH_INDENT_SPACES = 2;

    /** 上一行是否以句末标点收尾（配合"本行顶格"判断段落收尾） */
    private boolean lastLineEndedSentence = false;

    /**
     * 构建结果
     */
    @Data
    public static class Flow {
        /** 全部段落（按阅读顺序） */
        private List<Section> paragraphs = new ArrayList<>();
        /** 章节目录（供 chapters 表落库、GET /chapters 展示） */
        private List<ChapterRecognizer.Heading> headings = new ArrayList<>();
        /** 章节识别率 0~1 */
        private double recognizedRate;
        /** 是否成功识别到章节结构 */
        private boolean hasStructure;
        private float bodyFontSize;
        private int totalPages;
    }

    /** 一个段落（切片最小输入单元） */
    @Data
    @Builder
    public static class Section {
        private String chapterPath;
        private String chapterTitle;
        private int level;
        /** 段落起始页 */
        private int startPage;
        /** 段落结束页 */
        private int endPage;
        private String text;
        private int wordCount;
        /** 正文字号（用于判断段落边界） */
        private float fontSize;
    }

    /**
     * 组装章节与段落。
     *
     * @param lines       文本行
     * @param recognized  章节识别结果
     * @param totalPages  PDF 总页数
     */
    public Flow build(List<TextLine> lines, ChapterRecognizer.Recognized recognized, int totalPages) {
        Flow flow = new Flow();
        flow.setRecognizedRate(recognized.getRecognizedRate());
        flow.setBodyFontSize(recognized.getBodyFontSize());
        flow.setTotalPages(totalPages);
        flow.setHeadings(recognized.getHeadings());
        flow.setHasStructure(!recognized.getHeadings().isEmpty());

        if (lines == null || lines.isEmpty()) {
            return flow;
        }

        // 标题行下标 → 标题，用于快速判断"这一行是不是标题"
        java.util.Map<Integer, ChapterRecognizer.Heading> headingAt = new java.util.HashMap<>();
        for (ChapterRecognizer.Heading h : recognized.getHeadings()) {
            headingAt.put(h.getLineIndex(), h);
        }

        float bodyFont = recognized.getBodyFontSize();
        List<Section> paragraphs = new ArrayList<>();

        // 当前段落累积状态
        StringBuilder buf = new StringBuilder();
        int paraStartPage = 0;
        int paraEndPage = 0;
        String chapterPath = null;
        String chapterTitle = null;
        int level = 0;
        float paraFont = bodyFont;

        int i = 0;
        while (i < lines.size()) {
            TextLine line = lines.get(i);
            String text = line.getText().trim();

            ChapterRecognizer.Heading heading = headingAt.get(i);
            if (heading != null) {
                // 标题前先把上一段收尾
                flush(paragraphs, buf, chapterPath, chapterTitle, level, paraStartPage, paraEndPage, paraFont);
                buf.setLength(0);
                lastLineEndedSentence = false;

                chapterPath = heading.getChapterPath();
                chapterTitle = heading.getTitle();
                level = heading.getLevel();

                // 标题行本身也拼进章节正文首段（便于向量检索命中章节名）
                paraStartPage = heading.getPage();
                paraEndPage = heading.getPage();
                paraFont = heading.getFontSize();
                buf.append(cleanLine(text));
                i++;
                // 紧跟在标题后的副标题/短行（同字号、无句末标点）合并进标题段
                while (i < lines.size() && headingAt.get(i) == null) {
                    TextLine next = lines.get(i);
                    String nt = next.getText().trim();
                    if (nt.isEmpty() || buf.length() > 200) {
                        break;
                    }
                    if (!isSubtitleContinuation(nt, next, heading.getFontSize())) {
                        break;
                    }
                    buf.append(cleanLine(nt));
                    paraEndPage = next.getPage();
                    i++;
                }
                buf.append('\n');
                continue;
            }

            if (text.isEmpty()) {
                flush(paragraphs, buf, chapterPath, chapterTitle, level, paraStartPage, paraEndPage, paraFont);
                buf.setLength(0);
                i++;
                continue;
            }

            // 段落边界判定：
            //  ① 字号变化 —— 标题、正文、代码块之间切换，最可靠；
            //  ② 首行缩进 —— 中文排版习惯（首行缩进 2 字）；
            //  ③ 上一行以句末标点收尾且未写满，本行重新顶格 —— 段落收尾的典型形态。
            // 反面案例：绝不能"只要行尾是句号就断段"。中文每句都以句号结尾，
            // 那样会把自然段切成几十个碎片（实测平均片长掉到 80 字，无法聚合 500~800 字的块）。
            int indent = leadingSpaces(text);
            boolean fontChange = buf.length() > 0
                    && Math.abs(line.getFontSize() - paraFont) > 0.8f;
            boolean tooLong = buf.length() >= MAX_PARAGRAPH_CHARS;
            boolean indented = buf.length() > 0 && indent >= PARAGRAPH_INDENT_SPACES;
            boolean sentenceEnded = buf.length() > 0 && lastLineEndedSentence && indent == 0;
            if (buf.length() > 0 && (fontChange || tooLong || indented || sentenceEnded)) {
                flush(paragraphs, buf, chapterPath, chapterTitle, level, paraStartPage, paraEndPage, paraFont);
                buf.setLength(0);
            }

            if (buf.length() == 0) {
                paraStartPage = line.getPage();
                paraFont = line.getFontSize();
            }
            paraEndPage = line.getPage();
            buf.append(cleanLine(text));
            lastLineEndedSentence = endsWithSentencePunct(text);
            i++;
        }
        flush(paragraphs, buf, chapterPath, chapterTitle, level, paraStartPage, paraEndPage, paraFont);

        flow.setParagraphs(paragraphs);
        log.info("chapter flow paragraphs={} headings={} hasStructure={}",
                paragraphs.size(), flow.getHeadings().size(), flow.isHasStructure());
        return flow;
    }

    /**
     * 章节标题后紧跟的副标题（同字号短行）合并进标题段，最多合并到 200 字。
     * <p>
     * 注意这里不因"行尾有句号"提前退出：正文首段本来就以句号结尾。
     */
    private boolean isSubtitleContinuation(String text, TextLine next, float headingFont) {
        return !text.isEmpty()
                && Math.abs(next.getFontSize() - headingFont) <= 0.8f;
    }

    private void flush(List<Section> out, StringBuilder buf, String path, String title, int level,
                       int startPage, int endPage, float font) {
        // 段落首行的缩进属于排版信息，入库前去掉，避免影响向量语义
        String text = buf.toString().replaceAll("^[ \\u3000\\n]+", "").trim();
        if (text.isEmpty()) {
            return;
        }
        out.add(Section.builder()
                .chapterPath(path)
                .chapterTitle(title)
                .level(level)
                .startPage(startPage)
                .endPage(Math.max(startPage, endPage))
                .text(text)
                .wordCount(countChars(text))
                .fontSize(font)
                .build());
    }

    /**
     * 去掉多余空白与软连字符，但<b>保留行首缩进</b>：
     * 缩进是段落边界的关键信号，被抹掉后一个自然段会被拆成十几个小标题碎片。
     * 行内连续空白压成一个空格，行尾空白去掉。
     */
    private static String cleanLine(String text) {
        String t = text.replace('\u00AD', ' ')
                .replaceAll("[\\t\\u00A0]+", " ")
                .replaceAll(" {2,}", "  ");   // 保留最多两个空格的缩进形态
        // 行尾空白去掉，行首保留（缩进）
        int end = t.length();
        while (end > 0 && t.charAt(end - 1) == ' ') {
            end--;
        }
        return t.substring(0, end);
    }

    /** 行尾是否为句末标点（不含逗号/顿号） */
    private static boolean endsWithSentencePunct(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        char c = text.charAt(text.length() - 1);
        return c == '。' || c == '！' || c == '？' || c == '!' || c == '?'
                || c == '…' || c == '；' || c == ';' || c == '.';
    }

    /** 行首缩进空格数（全角空格算 2，半角算 1） */
    private static int leadingSpaces(String text) {
        int n = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\u3000') {
                n += 2;
            } else if (c == ' ') {
                n += 1;
            } else {
                break;
            }
        }
        return n;
    }

    static int countChars(String s) {
        if (s == null) {
            return 0;
        }
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isWhitespace(s.charAt(i))) {
                n++;
            }
        }
        return n;
    }

    // =========================================================================
    // 调试用：预览章节树
    // =========================================================================

    /** 打印章节树（构建日志排障用） */
    public static String printTree(List<ChapterRecognizer.Heading> headings) {
        StringBuilder sb = new StringBuilder();
        for (ChapterRecognizer.Heading h : headings) {
            sb.append("  ".repeat(Math.max(0, h.getLevel() - 1)))
                    .append('[').append(h.getChapterPath()).append("] ")
                    .append(h.getTitle())
                    .append(" (p.").append(h.getPage()).append(")\n");
        }
        return sb.toString();
    }
}
