package com.readingnotes.build;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * PDF 文本提取器（需求 F2.1）。
 * <p>
 * PDFBox 默认的 {@code getText()} 只给纯文本，丢掉字号/加粗信息，无法做章节识别。
 * 这里继承 {@link PDFTextStripper} 并在 {@code writeString} 里读取每个 {@link TextPosition}
 * 的字号与字体名，按行输出 {@link TextLine}，供后续章节识别与段落聚合使用。
 * <p>
 * 另外做两件清理：<br>
 * 1) 去除跨页重复的页眉页脚（同一文本在 ≥3 页且位于页面上下 12% 区域）；<br>
 * 2) 合并被 PDF 硬换行拆断的段落——交给 {@link ChapterFlowBuilder} 按启发式处理，
 * 此处只保证"一行一条"，不丢字符。
 */
@Slf4j
@Component
public class PdfTextExtractor {

    /** 扫描件判定阈值：全文可提取字符数低于此值视为扫描件（API 文档 30001） */
    public static final int MIN_CHARS = 50;

    /** 页眉页脚判定区域：页面高度的上下各 12% */
    private static final float HEADER_FOOTER_RATIO = 0.12f;

    /**
     * 噪点行正则：整行只有"像时间戳/纯数字序列"的内容时丢弃。
     * <p>
     * 典型来源：Office / WPS 导出 PDF 时插入的编辑时间戳（{@code 1749459313994}）、
     * 流水号、纯页码残留。这类行不含任何语义，若进入切片会污染 Embedding ——
     * 真实书稿里因此出现"每页一个 13 位数字独立成段"的碎片，把平均片长从 700 字拉到 76 字。
     * 限定 9 位以上、且整行仅由数字与分隔符组成，避免误删"2024""3.14"这类正常数字。
     */
    private static final Pattern NOISE_LINE = Pattern.compile(
            "^[\\s\\-_—–./:：]*\\d{9,}[\\s\\-_—–./:：]*$");

    /**
     * 提取结果：行列表 + 页数 + 文档元信息 + 字符数
     */
    @Data
    public static class ExtractResult {
        private List<TextLine> lines = List.of();
        private int totalPages;
        private int charCount;
        private String title;
        private String author;

        /** 扫描件 / 图片型 PDF：提取不到文字 */
        public boolean isScanned() {
            return charCount < MIN_CHARS;
        }
    }

    /**
     * 从 PDF 文件提取文本行。
     *
     * @param pdf 已落盘的 PDF 文件
     * @return 提取结果（文件损坏时抛 IOException，由上层转为构建失败）
     */
    public ExtractResult extract(File pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            ExtractResult result = new ExtractResult();
            int pages = doc.getNumberOfPages();
            result.setTotalPages(pages);

            PDDocumentInformation info = doc.getDocumentInformation();
            if (info != null) {
                result.setTitle(clean(info.getTitle()));
                result.setAuthor(clean(info.getAuthor()));
            }

            if (doc.isEncrypted()) {
                // 仅限制复制的加密 PDF，PDFBox 传空密码可解；若仍失败会抛异常
                log.warn("pdf is encrypted, trying empty password: {}", pdf.getName());
            }

            CollectingStripper stripper = new CollectingStripper();
            stripper.setSortByPosition(true);
            stripper.setLineSeparator("\n");
            stripper.setParagraphEnd("\n");
            stripper.getText(doc);   // 触发 writeString 回调

            List<TextLine> cleaned = stripRunningHeaders(stripper.lines, pages);
            result.setLines(cleaned);
            result.setCharCount(cleaned.stream()
                    .mapToInt(l -> countChars(l.getText()))
                    .sum());
            log.info("pdf parsed file={} pages={} lines={} chars={}",
                    pdf.getName(), pages, cleaned.size(), result.getCharCount());
            return result;
        } catch (InvalidPasswordException e) {
            // 需要打开密码的 PDF：不能笼统报"文件已损坏"，否则用户不知道该解除密码
            throw new EncryptedPdfException(pdf.getName());
        }
    }

    /** PDF 需要打开密码（需求 F1.1 异常：加密文件给明确中文提示） */
    public static class EncryptedPdfException extends IOException {
        public EncryptedPdfException(String fileName) {
            super("PDF 已加密，需要密码才能打开：" + fileName);
        }
    }

    /**
     * 去除跨页重复的页眉页脚。
     * <p>
     * 策略：统计全文出现 ≥3 次、且"出现在 ≥3 个不同页"的行文本；再看这些行是否集中在
     * 页面顶部/底部区域（PDFBox 的 TextPosition.y 距页顶 < 12% 或 > 88%）。
     * 满足条件则该文本在所有页中被剔除。页码行（"12"、"- 12 -"）单独正则兜底剔除。
     */
    private List<TextLine> stripRunningHeaders(List<CollectedLine> raw, int totalPages) {
        Map<String, Integer> pageCount = new HashMap<>();
        for (CollectedLine l : raw) {
            if (l.topBand || l.bottomBand) {
                pageCount.merge(normalize(l.line.getText()), 1, Integer::sum);
            }
        }
        int threshold = Math.max(3, totalPages / 10);

        List<TextLine> out = new ArrayList<>(raw.size());
        for (CollectedLine l : raw) {
            String key = normalize(l.line.getText());
            boolean repeatedHeader = pageCount.getOrDefault(key, 0) >= threshold;
            boolean pageNumberOnly = l.line.getText().matches("^[-—–\\s]*\\d{1,4}[-—–\\s]*$");
            if ((l.topBand || l.bottomBand) && (repeatedHeader || pageNumberOnly)) {
                continue;
            }
            // 全角空格/纯空行（PDF 里常见）直接丢弃
            if (key.isEmpty()) {
                continue;
            }
            // 时间戳 / 长数字串噪点：全篇无条件剔除（不限位置）
            if (NOISE_LINE.matcher(key).matches()) {
                continue;
            }
            out.add(l.line);
        }
        return out;
    }

    /** 归一化：去掉全部空白，用于"跨页重复文本"的等值比较（排版差异导致空格数不同） */
    private static String normalize(String s) {
        return s == null ? "" : s.replaceAll("\\s+", "").trim();
    }

    /** 清洗：连续空白压成一个空格，全空返回 null（便于调用方跳过空行） */
    private static String clean(String s) {
        if (s == null) {
            return null;
        }
        String t = s.replaceAll("\\s+", " ").trim();
        return t.isEmpty() ? null : t;
    }

    /** 统计有效字符（排除空白） */
    public static int countChars(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int n = 0;
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isWhitespace(text.charAt(i))) {
                n++;
            }
        }
        return n;
    }

    // =========================================================================
    // PDFTextStripper 子类：按行收集字号/加粗信息
    // =========================================================================

    /** 行 + 其在页面中的位置带（用于判断页眉页脚） */
    private static class CollectedLine {
        final TextLine line;
        final boolean topBand;
        final boolean bottomBand;

        CollectedLine(TextLine line, boolean topBand, boolean bottomBand) {
            this.line = line;
            this.topBand = topBand;
            this.bottomBand = bottomBand;
        }
    }

    private static class CollectingStripper extends PDFTextStripper {

        private final List<CollectedLine> lines = new ArrayList<>();

        private final StringBuilder lineText = new StringBuilder();
        private final List<TextPosition> linePositions = new ArrayList<>();

        private boolean lineBold;
        private int lineBoldChars;
        private int lineChars;
        private float pageHeight = 800f;

        CollectingStripper() throws IOException {
            super();
        }

        /** 记录页高，用于把 y 坐标换算成"页面上/下 12%"的位置带 */
        @Override
        protected void startPage(PDPage page) throws IOException {
            pageHeight = page.getMediaBox().getHeight();
            super.startPage(page);
        }

        /**
         * 每遇到一段文本回调一次：累加文本与字符数，同时统计加粗字符占比。
         * <p>
         * 一个"行"可能由多个 TextPosition 片段组成（混排字体），因此这里只累积，
         * 真正落盘交给 {@link #writeLineSeparator()} / {@link #endPage}。
         */
        @Override
        protected void writeString(String text, List<TextPosition> textPositions) {
            if (text == null || text.isBlank()) {
                return;
            }
            lineText.append(text);
            for (TextPosition tp : textPositions) {
                linePositions.add(tp);
                lineChars++;
                if (isBold(tp)) {
                    lineBoldChars++;
                }
            }
        }

        /** PDFBox 在行结束/分页时都会回调此方法：把缓冲的一行落盘 */
        @Override
        protected void writeWordSeparator() {
            lineText.append(' ');
        }

        /** PDFBox 在行结束时回调：把缓冲的一行落盘并清空状态 */
        @Override
        protected void writeLineSeparator() {
            flushLine();
        }

        /** 分页时也要落盘：最后一行的行结束符可能缺失，否则会丢行 */
        @Override
        protected void endPage(PDPage page) {
            flushLine();
        }

        @Override
        public String getText(PDDocument doc) throws IOException {
            String text = super.getText(doc);
            flushLine();
            return text;
        }

        private void flushLine() {
            String text = lineText.toString().replaceAll("[ \\t]+", " ").trim();
            if (text.isEmpty() || linePositions.isEmpty()) {
                lineText.setLength(0);
                linePositions.clear();
                lineBold = false;
                lineBoldChars = 0;
                lineChars = 0;
                return;
            }
            // 取该行出现频次最高的字号作为行字号（避免行内混排小字干扰）
            float fontSize = dominantFontSize(linePositions);
            boolean bold = lineChars > 0 && lineBoldChars * 2 >= lineChars;
            boolean vertical = isVertical(linePositions);

            float minY = Float.MAX_VALUE;
            float maxY = -Float.MAX_VALUE;
            for (TextPosition tp : linePositions) {
                minY = Math.min(minY, tp.getYDirAdj());
                maxY = Math.max(maxY, tp.getYDirAdj());
            }
            float top = pageHeight - maxY;    // 距页顶
            float bottom = minY;              // 距页底
            float band = pageHeight * HEADER_FOOTER_RATIO;
            boolean topBand = top < band;
            boolean bottomBand = bottom < band;

            int pageNo = getCurrentPageNo();
            lines.add(new CollectedLine(new TextLine(text, pageNo, fontSize, bold, vertical),
                    topBand, bottomBand));

            lineText.setLength(0);
            linePositions.clear();
            lineBold = false;
            lineBoldChars = 0;
            lineChars = 0;
        }

        private static float dominantFontSize(List<TextPosition> positions) {
            Map<Integer, Integer> counter = new LinkedHashMap<>();
            for (TextPosition tp : positions) {
                int key = Math.round(tp.getFontSizeInPt() * 2); // 0.5pt 精度
                counter.merge(key, 1, Integer::sum);
            }
            return counter.entrySet().stream()
                    .max(Comparator.comparingInt(Map.Entry::getValue))
                    .map(e -> e.getKey() / 2f)
                    .orElse(10f);
        }

        private static boolean isBold(TextPosition tp) {
            try {
                PDFont font = tp.getFont();
                String name = font == null ? "" : font.getName();
                if (name == null) {
                    return false;
                }
                String lower = name.toLowerCase();
                return lower.contains("bold")
                        || lower.contains("heavy")
                        || lower.contains("black")
                        || lower.contains("semibold")
                        || lower.contains("hei")          // SimHei / 黑体
                        || lower.contains("yahei");
            } catch (Exception e) {
                return false;
            }
        }

        private static boolean isVertical(List<TextPosition> positions) {
            if (positions.size() < 4) {
                return false;
            }
            float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
            float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
            for (TextPosition tp : positions) {
                minX = Math.min(minX, tp.getXDirAdj());
                maxX = Math.max(maxX, tp.getXDirAdj());
                minY = Math.min(minY, tp.getYDirAdj());
                maxY = Math.max(maxY, tp.getYDirAdj());
            }
            // 竖排：行内横向跨度小、纵向跨度大
            return (maxX - minX) < (maxY - minY) * 0.5f;
        }
    }
}
