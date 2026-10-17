package com.readingnotes.build;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 章节识别器（需求 F2.1）。
 * <p>
 * 判定"一行是标题"必须同时满足三个条件，避免把正文里的编号误判为章节：
 * <ol>
 *   <li><b>编号模式命中</b>：第X章 / 第X节 / Chapter X / 1.2 / 1.2.3 / 一、二、 / 序言、后记；</li>
 *   <li><b>排版显著性</b>：字号 ≥ 正文主字号 + 0.8pt，或是整行加粗，或比正文大 ≥ 15%；</li>
 *   <li><b>行长约束</b>：标题行字数不超过 40（正文首行常以编号开头但很长）。</li>
 * </ol>
 * 同时满足才升级为标题，并按编号深度 + 字号相对关系推导层级 level(1~3) 与 chapter_path。
 * <p>
 * 无章节结构的书（纯散文、无编号）会识别出 0 个标题，上层据此把 chapter_path 置空，
 * 问答退化为纯向量检索（需求 F2.1 降级策略）。
 */
@Slf4j
@Component
public class ChapterRecognizer {

    /** 命中标题的最大字数（超过视为正文段落） */
    private static final int MAX_TITLE_CHARS = 40;

    /** 标题行长相对正文行的最大宽度比例（标题通常短于正文满行） */
    private static final double MAX_TITLE_WIDTH_RATIO = 0.85;

    // ---- 编号模式（顺序敏感：先匹配更具体的） ----
    /** 第X章 / 第X节 / 第X部分 / 第X篇 */
    private static final Pattern P_CHAPTER_CN = Pattern.compile(
            "^第\\s*([0-9]{1,3}|[一二三四五六七八九十百零〇]{1,4})\\s*(章|篇|部分|卷|编)\\s*(.*)$");
    /** 第X节 */
    private static final Pattern P_SECTION_CN = Pattern.compile(
            "^第\\s*([0-9]{1,3}|[一二三四五六七八九十百零〇]{1,4})\\s*节\\s*(.*)$");
    /** Chapter 3 / CHAPTER III */
    private static final Pattern P_CHAPTER_EN = Pattern.compile(
            "^(?i:chapter|part|section)\\s+([0-9]{1,3}|[IVXLC]{1,6})\\b[.:：]?\\s*(.*)$");
    /** 1.2 / 1.2.3 / 1．2（阿拉伯数字层级，带标题文字） */
    private static final Pattern P_NUMBERED = Pattern.compile(
            "^([0-9]{1,2}(?:\\s*[.．]\\s*[0-9]{1,2}){1,2})\\s*[、.．]?\\s*(\\S.*)$");
    /** 1 标题（一级，单数字 + 空白 + 文字） */
    private static final Pattern P_NUMBERED_L1 = Pattern.compile(
            "^([0-9]{1,2})\\s+([^\\d\\s].{0,38})$");
    /** 一、二、三、 */
    private static final Pattern P_CN_ENUM = Pattern.compile(
            "^([一二三四五六七八九十]{1,3})\\s*[、.．]\\s*(.*)$");
    /** 序言 / 前言 / 引言 / 后记 / 附录 / 结语 / 致谢 */
    private static final Pattern P_SPECIAL = Pattern.compile(
            "^(序言|序|前言|引言|导言|绪论|后记|附录|结语|致谢|参考文献|目录|跋)(\\s*.*)$");

    /**
     * 识别结果：标题列表 + 正文主字号
     */
    @Data
    public static class Recognized {
        private List<Heading> headings = new ArrayList<>();
        /** 正文主字号（按字数加权的中位数），无正文时为 0 */
        private float bodyFontSize;

        /**
         * 章节识别率：被识别标题覆盖的字数 / 全文字数。
         * 需求 F2.3 用它在 < 50% 时提示"未识别到章节结构"。
         */
        private double recognizedRate;
    }

    /** 识别出的一个标题 */
    @Data
    public static class Heading {
        /** 行在 lines 中的下标 */
        private int lineIndex;
        /** 层级 1~3 */
        private int level;
        /** 章节路径，如 "1" / "1.2" / "1.2.3"；特殊章节用 "序言" 这类名字 */
        private String chapterPath;
        /** 章节标题（含编号原文） */
        private String title;
        private int page;
        private float fontSize;
        private boolean bold;
    }

    /**
     * 从文本行中识别章节标题。
     *
     * @param lines 已去除页眉页脚的文本行（按阅读顺序）
     */
    public Recognized recognize(List<TextLine> lines) {
        Recognized result = new Recognized();
        if (lines == null || lines.isEmpty()) {
            result.setBodyFontSize(0f);
            result.setRecognizedRate(0);
            return result;
        }

        float bodyFont = weightedMedianFontSize(lines);
        result.setBodyFontSize(bodyFont);

        List<Heading> headings = new ArrayList<>();
        // 一级编号计数器：用于给"第X章"生成 chapter_path，同时抵抗编号跳号/缺号
        int chapterSeq = 0;
        int sectionSeq = 0;
        int subSeq = 0;

        for (int i = 0; i < lines.size(); i++) {
            TextLine line = lines.get(i);
            String text = line.getText().trim();
            if (text.isEmpty() || text.length() > MAX_TITLE_CHARS) {
                continue;
            }

            ParsedNumber num = parseNumber(text);
            if (num == null) {
                continue;
            }
            // 排版显著性：字号较大 或 整行加粗（相对正文）
            boolean bigger = bodyFont > 0 && line.getFontSize() >= bodyFont + 0.8f;
            boolean muchBigger = bodyFont > 0 && line.getFontSize() >= bodyFont * 1.15f;
            boolean bold = line.isBold();
            if (!bigger && !muchBigger && !bold) {
                continue;
            }
            // 标题不应以句末标点结束（排除"1. 首先我们……。"这类正文）
            if (endsWithSentencePunct(text)) {
                continue;
            }
            // 下一行若紧接大写/正文字号，说明这是正文中的编号起始句，跳过
            if (num.depth == 1 && i + 1 < lines.size()
                    && bodyFont > 0 && lines.get(i + 1).getFontSize() <= bodyFont + 0.3f
                    && lines.get(i + 1).getText().length() > 30
                    && !line.isBold()) {
                continue;
            }

            Heading h = new Heading();
            h.setLineIndex(i);
            h.setPage(line.getPage());
            h.setFontSize(line.getFontSize());
            h.setBold(bold);
            h.setTitle(text);

            switch (num.kind) {
                case CHAPTER -> {
                    chapterSeq++;
                    sectionSeq = 0;
                    subSeq = 0;
                    h.setLevel(1);
                    h.setChapterPath(num.number != null ? num.number : String.valueOf(chapterSeq));
                }
                case SECTION -> {
                    if (chapterSeq == 0) {
                        chapterSeq = 1;
                    }
                    sectionSeq++;
                    subSeq = 0;
                    h.setLevel(2);
                    h.setChapterPath(num.number != null
                            ? chapterSeq + "." + num.number
                            : chapterSeq + "." + sectionSeq);
                }
                case SPECIAL -> {
                    h.setLevel(1);
                    // 特殊章节（序言/附录）不占编号，用名字做 path，便于元数据过滤
                    h.setChapterPath(text.replaceAll("[\\s:：]+$", ""));
                }
                default -> {
                    // 阿拉伯数字层级 / 中文序号：按点数决定层级
                    int depth = num.depth;
                    if (depth <= 1) {
                        chapterSeq++;
                        sectionSeq = 0;
                        subSeq = 0;
                        h.setLevel(1);
                        h.setChapterPath(num.number != null ? num.number : String.valueOf(chapterSeq));
                    } else if (depth == 2) {
                        sectionSeq++;
                        subSeq = 0;
                        h.setLevel(2);
                        h.setChapterPath(num.number != null
                                ? (chapterSeq == 0 ? "0." : chapterSeq + ".") + num.number
                                : chapterSeq + "." + sectionSeq);
                    } else {
                        subSeq++;
                        h.setLevel(3);
                        h.setChapterPath(num.number != null
                                ? (chapterSeq == 0 ? "0." : chapterSeq + ".") + num.number
                                : chapterSeq + "." + sectionSeq + "." + subSeq);
                    }
                }
            }
            // 字号极大（≥ 1.4 倍正文）的编号行，视为章级，即使编号深度 > 1
            if (h.getLevel() > 1 && muchBigger && line.getFontSize() >= bodyFont * 1.4f) {
                h.setLevel(1);
            }
            headings.add(h);
        }

        // 层级归一化：若全是 level 2/3（书从"1.1"直接开始），整体上提一级，避免 path 出现 "0."
        normalizeLevels(headings);
        // 字号修正层级：编号深度可能来自正文里的列表项，标题的真实层级以字号为主
        correctLevelByFontSize(headings, bodyFont);
        rebuildChapterPaths(headings);

        result.setHeadings(headings);
        result.setRecognizedRate(computeRate(lines, headings));
        log.info("chapter recognize lines={} headings={} bodyFont={} rate={}",
                lines.size(), headings.size(), bodyFont,
                String.format("%.2f", result.getRecognizedRate()));
        return result;
    }

    /**
     * 字号修正层级。
     * <p>
     * 编号深度不可靠：正文列表项（"1. 打开设置"）长得也像标题，会把章节序号搅乱。
     * 真正的章节层级由排版决定——标题字号越大层级越高。因此取"编号深度"与"字号排名"
     * 中更可靠的一个：字号明显大于正文（≥1.25 倍）的一律拉到 level 1。
     */
    private void correctLevelByFontSize(List<Heading> headings, float bodyFont) {
        if (headings.isEmpty() || bodyFont <= 0) {
            return;
        }
        for (Heading h : headings) {
            if (h.getFontSize() >= bodyFont * 1.25f) {
                h.setLevel(1);
            } else if (h.getFontSize() >= bodyFont * 1.08f && h.getLevel() > 2) {
                h.setLevel(2);
            }
        }
    }

    /**
     * 按最终层级重建 chapter_path（编号不可靠，路径必须自洽）。
     * <p>
     * 维护三个计数器：碰到 level1 重置，level2 累加，level3 累加。
     * 保留原始数值编号作为显示线索，但路径以计数器为准，保证 1 / 1.1 / 1.1.1 严格嵌套。
     */
    private void rebuildChapterPaths(List<Heading> headings) {
        int c1 = 0, c2 = 0, c3 = 0;
        for (Heading h : headings) {
            // 特殊章节（序言/附录）保留名字作为 path，不参与编号
            if (h.getLevel() == 1 && h.getChapterPath() != null
                    && !h.getChapterPath().matches("[0-9.]+")) {
                c1++;
                c2 = 0;
                c3 = 0;
                continue;
            }
            switch (h.getLevel()) {
                case 1 -> {
                    c1++;
                    c2 = 0;
                    c3 = 0;
                    h.setChapterPath(String.valueOf(c1));
                }
                case 2 -> {
                    if (c1 == 0) {
                        c1 = 1;
                    }
                    c2++;
                    c3 = 0;
                    h.setChapterPath(c1 + "." + c2);
                }
                default -> {
                    if (c1 == 0) {
                        c1 = 1;
                    }
                    if (c2 == 0) {
                        c2 = 1;
                    }
                    c3++;
                    h.setChapterPath(c1 + "." + c2 + "." + c3);
                }
            }
        }
    }

    /** 若最低层级缺少 level 1，则整体上提，保证 chapter_path 以章开头 */
    private void normalizeLevels(List<Heading> headings) {
        if (headings.isEmpty()) {
            return;
        }
        boolean hasLevel1 = headings.stream().anyMatch(h -> h.getLevel() == 1);
        if (hasLevel1) {
            return;
        }
        int min = headings.stream().mapToInt(Heading::getLevel).min().orElse(2);
        for (Heading h : headings) {
            h.setLevel(Math.max(1, h.getLevel() - min + 1));
            String path = h.getChapterPath();
            if (path != null && path.startsWith("0.")) {
                h.setChapterPath(path.substring(2));
            }
        }
    }

    /**
     * 章节识别率 = 被识别标题所"管辖"的字数 / 全文字数。
     * <p>
     * 若首个标题出现在第 50 行之后，则它之前的内容（封面/目录/前言）不计入已识别部分，
     * 这样"只识别到目录里几个词"不会伪造出高识别率。
     */
    private double computeRate(List<TextLine> lines, List<Heading> headings) {
        int total = lines.stream().mapToInt(l -> countChars(l.getText())).sum();
        if (total == 0 || headings.isEmpty()) {
            return 0;
        }
        int first = headings.get(0).getLineIndex();
        int covered = 0;
        for (int i = first; i < lines.size(); i++) {
            covered += countChars(lines.get(i).getText());
        }
        return Math.min(1.0, (double) covered / total);
    }

    private static int countChars(String s) {
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

    /**
     * 正文主字号：按字符数加权的加权中位数。
     * 用加权（而非行数）可避免书名页、目录页的少量大字把主字号拉高。
     */
    private float weightedMedianFontSize(List<TextLine> lines) {
        // 按 0.5pt 分桶累计字符数
        int[] buckets = new int[200];   // 0~100pt
        long total = 0;
        for (TextLine l : lines) {
            int chars = countChars(l.getText());
            if (chars < 5) {      // 极短行（页码残留、装饰符）不参与统计
                continue;
            }
            int idx = Math.min(199, Math.round(l.getFontSize() * 2));
            buckets[idx] += chars;
            total += chars;
        }
        if (total == 0) {
            return 0f;
        }
        long acc = 0;
        for (int i = 0; i < buckets.length; i++) {
            acc += buckets[i];
            if (acc * 2 >= total) {
                return i / 2f;
            }
        }
        return 10f;
    }

    /** 编号解析结果 */
    private static class ParsedNumber {
        enum Kind { CHAPTER, SECTION, SPECIAL, NUMBERED }

        Kind kind;
        /** 数字层级（1=章, 2=节, 3=小节）；CHAPTER/SECTION/SPECIAL 固定 1 */
        int depth;
        /** 原始编号（已归一化为点分形式），特殊章节为 null */
        String number;
    }

    private ParsedNumber parseNumber(String text) {
        Matcher m;

        if ((m = P_CHAPTER_CN.matcher(text)).find()) {
            ParsedNumber p = new ParsedNumber();
            p.kind = ParsedNumber.Kind.CHAPTER;
            p.depth = 1;
            p.number = String.valueOf(cnToInt(m.group(1)));
            return p;
        }
        if ((m = P_SECTION_CN.matcher(text)).find()) {
            ParsedNumber p = new ParsedNumber();
            p.kind = ParsedNumber.Kind.SECTION;
            p.depth = 2;
            p.number = String.valueOf(cnToInt(m.group(1)));
            return p;
        }
        if ((m = P_CHAPTER_EN.matcher(text)).find()) {
            ParsedNumber p = new ParsedNumber();
            p.kind = ParsedNumber.Kind.CHAPTER;
            p.depth = 1;
            String token = m.group(1);
            p.number = token.matches("\\d+") ? token : String.valueOf(romanToInt(token));
            return p;
        }
        if ((m = P_SPECIAL.matcher(text)).find()) {
            // "目录"页不当作章节
            if (!m.group(1).equals("目录")) {
                ParsedNumber p = new ParsedNumber();
                p.kind = ParsedNumber.Kind.SPECIAL;
                p.depth = 1;
                return p;
            }
            return null;
        }
        if ((m = P_NUMBERED.matcher(text)).find()) {
            String num = m.group(1).replaceAll("\\s+", "").replace('．', '.');
            String rest = m.group(2) == null ? "" : m.group(2).trim();
            if (rest.isEmpty()) {
                return null;
            }
            ParsedNumber p = new ParsedNumber();
            p.kind = ParsedNumber.Kind.NUMBERED;
            p.depth = (int) num.chars().filter(c -> c == '.').count() + 1;
            p.number = num;
            return p;
        }
        if ((m = P_NUMBERED_L1.matcher(text)).find()) {
            ParsedNumber p = new ParsedNumber();
            p.kind = ParsedNumber.Kind.NUMBERED;
            p.depth = 1;
            p.number = m.group(1);
            return p;
        }
        if ((m = P_CN_ENUM.matcher(text)).find()) {
            ParsedNumber p = new ParsedNumber();
            p.kind = ParsedNumber.Kind.NUMBERED;
            p.depth = 1;
            p.number = String.valueOf(cnToInt(m.group(1)));
            return p;
        }
        return null;
    }

    private static boolean endsWithSentencePunct(String text) {
        if (text.isEmpty()) {
            return false;
        }
        char c = text.charAt(text.length() - 1);
        return c == '。' || c == '；' || c == '，' || c == ',' || c == ';' || c == '！' || c == '?';
    }

    /** 中文数字转阿拉伯（1~99，足够覆盖章号） */
    static int cnToInt(String s) {
        if (s == null || s.isEmpty()) {
            return 0;
        }
        if (s.matches("\\d+")) {
            return Integer.parseInt(s);
        }
        String digits = "零一二三四五六七八九";
        if (s.equals("十")) {
            return 10;
        }
        int tenIdx = s.indexOf('十');
        if (tenIdx >= 0) {
            int tens = tenIdx == 0 ? 1 : digits.indexOf(s.charAt(0));
            int ones = tenIdx == s.length() - 1 ? 0 : digits.indexOf(s.charAt(tenIdx + 1));
            return Math.max(tens, 0) * 10 + Math.max(ones, 0);
        }
        int v = 0;
        for (char c : s.toCharArray()) {
            int d = digits.indexOf(c);
            if (d < 0) {
                return v;
            }
            v = v * 10 + d;
        }
        return v;
    }

    static int romanToInt(String s) {
        int[] vals = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
        String[] syms = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};
        String up = s.toUpperCase();
        int i = 0, res = 0;
        for (int k = 0; k < syms.length; k++) {
            while (up.startsWith(syms[k], i)) {
                res += vals[k];
                i += syms[k].length();
            }
        }
        return res;
    }
}
