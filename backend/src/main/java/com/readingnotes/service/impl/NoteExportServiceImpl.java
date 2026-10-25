package com.readingnotes.service.impl;

import com.readingnotes.common.BusinessException;
import com.readingnotes.common.ErrorCode;
import com.readingnotes.config.AppProperties;
import com.readingnotes.entity.Note;
import com.readingnotes.entity.Tag;
import com.readingnotes.repository.NoteRepository;
import com.readingnotes.repository.TagRepository;
import com.readingnotes.service.NoteExportService;
import lombok.extern.slf4j.Slf4j;
import org.apache.fontbox.ttf.TrueTypeCollection;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 笔记导出实现（API 文档 §9.11，需求 §3.5）
 *
 * <p>流程：解析 scope → 查笔记 → 渲染（markdown 逐条 / pdf 合并单文件）
 * → 打包 zip 落到 app.exports-dir → 返回下载地址与过期时间。
 * zip 用 JDK 内置（java.util.zip）；PDF 用 Apache PDFBox 3（系统已含依赖）。
 * 中文渲染依赖系统中文字体（Windows 雅黑/宋体/黑体 → Linux Noto/文泉驿），
 * 找不到字体时抛 INTERNAL_ERROR 明确提示，不做乱码降级。</p>
 */
@Slf4j
@Service
public class NoteExportServiceImpl implements NoteExportService {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(ZoneOffset.ofHours(8));

    /** 中文字体候选路径（Windows → Linux CI/服务器） */
    private static final List<Path> FONT_CANDIDATES = List.of(
            Paths.get("C:\\Windows\\Fonts\\msyh.ttc"),      // 微软雅黑
            Paths.get("C:\\Windows\\Fonts\\simsun.ttc"),    // 宋体
            Paths.get("C:\\Windows\\Fonts\\simhei.ttf"),    // 黑体
            Paths.get("/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"),
            Paths.get("/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc"));

    private final NoteRepository noteRepository;
    private final TagRepository tagRepository;
    private final AppProperties props;

    public NoteExportServiceImpl(NoteRepository noteRepository, TagRepository tagRepository,
                                 AppProperties props) {
        this.noteRepository = noteRepository;
        this.tagRepository = tagRepository;
        this.props = props;
    }

    @Override
    public Map<String, Object> export(List<String> noteIds, String bookId, String scope,
                                      String format, boolean includeFrontMatter) {
        String safeScope = (scope == null || scope.isBlank()) ? "selected" : scope.trim();
        String safeFormat = (format == null || format.isBlank()) ? "markdown" : format.trim();
        boolean pdf = "pdf".equalsIgnoreCase(safeFormat);
        if (!pdf && !"markdown".equalsIgnoreCase(safeFormat)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "暂不支持的导出格式: " + safeFormat + "，请使用 markdown 或 pdf");
        }

        List<Note> notes = resolveNotes(noteIds, bookId, safeScope);
        if (notes.isEmpty()) {
            throw new BusinessException(ErrorCode.NOTE_NOT_FOUND, "没有可导出的笔记");
        }

        // 批量取标签，避免逐条 N+1
        Map<String, List<Tag>> tagsMap = tagRepository.findByNoteIds(
                notes.stream().map(Note::getNoteId).toList());

        Path zipPath = pdf ? writePdfZip(notes, tagsMap, includeFrontMatter)
                : writeZip(notes, tagsMap, includeFrontMatter);

        Map<String, Object> data = new HashMap<>();
        data.put("download_url", "/api/v1/files/exports/" + zipPath.getFileName());
        data.put("file_name", "笔记导出_" + zipPath.getFileName().toString()
                .replace("export_", "").replace(".zip", "") + ".zip");
        data.put("file_size", sizeOf(zipPath));
        data.put("note_count", notes.size());
        data.put("expires_at", Instant.now().plusSeconds(props.getExportTtlHours() * 3600L)
                .truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString());
        log.info("notes exported: format={}, count={}, zip={}", safeFormat, notes.size(),
                zipPath.getFileName());
        return data;
    }

    // ==================== 查询目标笔记 ====================

    private List<Note> resolveNotes(List<String> noteIds, String bookId, String scope) {
        if ("book".equalsIgnoreCase(scope)) {
            if (bookId == null || bookId.isBlank()) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "scope=book 时 book_id 必填");
            }
            return noteRepository.findByBookId(bookId.trim());
        }
        if (!"selected".equalsIgnoreCase(scope)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "scope 只能为 selected 或 book");
        }
        if (noteIds == null || noteIds.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "scope=selected 时 note_ids 不能为空");
        }
        // 去重保序；单个 id 不存在/已软删 → NOTE_NOT_FOUND
        List<Note> notes = new ArrayList<>();
        for (String id : new LinkedHashSet<>(noteIds)) {
            if (id == null || id.isBlank()) {
                continue;
            }
            notes.add(noteRepository.findById(id.trim()));
        }
        return notes;
    }

    // ==================== 渲染与打包 ====================

    private Path writeZip(List<Note> notes, Map<String, List<Tag>> tagsMap,
                          boolean includeFrontMatter) {
        try {
            Path dir = Paths.get(props.getExportsDir());
            Files.createDirectories(dir);
            String stamp = STAMP.format(Instant.now());
            Path zip = dir.resolve("export_" + stamp + ".zip");
            try (ZipOutputStream zos = new ZipOutputStream(
                    Files.newOutputStream(zip), StandardCharsets.UTF_8)) {
                for (int i = 0; i < notes.size(); i++) {
                    Note n = notes.get(i);
                    String md = render(n, tagsMap.getOrDefault(n.getNoteId(), List.of()),
                            includeFrontMatter);
                    zos.putNextEntry(new ZipEntry(fileName(i + 1, n)));
                    zos.write(md.getBytes(StandardCharsets.UTF_8));
                    zos.closeEntry();
                }
            }
            return zip;
        } catch (IOException e) {
            log.error("write export zip failed", e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "导出文件写入失败，请检查磁盘空间");
        }
    }

    // ==================== PDF 导出（§9.11 format=pdf） ====================

    /** 打包 PDF：单文件（整本书或选中笔记合并渲染），zip 内放 .pdf */
    private Path writePdfZip(List<Note> notes, Map<String, List<Tag>> tagsMap,
                             boolean includeFrontMatter) {
        try {
            Path dir = Paths.get(props.getExportsDir());
            Files.createDirectories(dir);
            String stamp = STAMP.format(Instant.now());
            Path zip = dir.resolve("export_" + stamp + ".zip");
            byte[] pdf = renderPdfBytes(notes, tagsMap, includeFrontMatter);
            try (ZipOutputStream zos = new ZipOutputStream(
                    Files.newOutputStream(zip), StandardCharsets.UTF_8)) {
                String pdfName = pdfFileName(notes);
                zos.putNextEntry(new ZipEntry(pdfName));
                zos.write(pdf);
                zos.closeEntry();
            }
            return zip;
        } catch (IOException e) {
            log.error("write export pdf zip failed", e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "导出文件写入失败，请检查磁盘空间");
        }
    }

    /**
     * 渲染 PDF 字节（package-private 供测试 override 隔离字体依赖）：
     * 每篇笔记从新页开始，标题 + 元数据 + 正文折行，页脚页码。
     */
    byte[] renderPdfBytes(List<Note> notes, Map<String, List<Tag>> tagsMap,
                          boolean includeFrontMatter) {
        PDDocument doc = new PDDocument();
        try {
            PDFont font = loadCjkFont(doc);
            final float margin = 56f;          // 页边距 pt（≈2cm）
            final float pageW = PDRectangle.A4.getWidth() - margin * 2;   // 595-112
            final float titleSize = 16f, metaSize = 10f, bodySize = 11f;
            final float lineHeight = 17f, bodyLine = 15f;
            final int bodyChars = Math.max(1, (int) (pageW / bodySize));  // 每行近似字符数
            final int metaChars = Math.max(1, (int) (pageW / metaSize));

            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            PDPageContentStream cs = new PDPageContentStream(doc, page);
            float y = PDRectangle.A4.getHeight() - margin;
            int pageNo = 1;

            for (int i = 0; i < notes.size(); i++) {
                Note n = notes.get(i);
                List<Tag> tags = tagsMap.getOrDefault(n.getNoteId(), List.of());
                // 每篇笔记从新页开始（第一篇已在首页）
                if (i > 0) {
                    cs.close();
                    page = new PDPage(PDRectangle.A4);
                    doc.addPage(page);
                    cs = new PDPageContentStream(doc, page);
                    y = PDRectangle.A4.getHeight() - margin;
                    pageNo++;
                }
                // 标题
                cs.beginText();
                cs.setFont(font, titleSize);
                cs.setNonStrokingColor(new Color(0x1F, 0x29, 0x37));
                cs.newLineAtOffset(margin, y - titleSize);
                cs.showText(truncate(titleOf(n), 40));
                cs.endText();
                y -= titleSize + 6;
                // 元数据行（书名 / 页码 / 标签 / 时间）
                cs.beginText();
                cs.setFont(font, metaSize);
                cs.setNonStrokingColor(new Color(0x8C, 0x8C, 0x8C));
                cs.newLineAtOffset(margin, y - metaSize);
                cs.showText(truncate(metaLine(n, tags), metaChars));
                cs.endText();
                y -= metaSize + 10;
                // 分隔线
                cs.setStrokingColor(new Color(0xDC, 0xDF, 0xE6));
                cs.moveTo(margin, y);
                cs.lineTo(margin + pageW, y);
                cs.stroke();
                y -= 12;
                // 正文折行（按换行分段 → 按字符数切行）
                String content = n.getContent() == null ? "" : n.getContent();
                cs.setFont(font, bodySize);
                cs.setNonStrokingColor(new Color(0x33, 0x33, 0x33));
                for (String para : content.split("\\r?\\n")) {
                    for (String line : wrap(para, bodyChars)) {
                        if (y - bodyLine < margin) {           // 换页
                            cs.close();
                            page = new PDPage(PDRectangle.A4);
                            doc.addPage(page);
                            cs = new PDPageContentStream(doc, page);
                            y = PDRectangle.A4.getHeight() - margin;
                            pageNo++;
                        }
                        cs.beginText();
                        cs.newLineAtOffset(margin, y - bodySize);
                        cs.showText(line);
                        cs.endText();
                        y -= bodyLine;
                    }
                }
                y -= 10;
            }
            // 页脚页码（简化：逐页补当前页号）
            cs.close();
            for (int p = 1; p <= doc.getNumberOfPages(); p++) {
                PDPageContentStream footer = new PDPageContentStream(
                        doc, doc.getPage(p - 1), PDPageContentStream.AppendMode.APPEND, true, true);
                footer.beginText();
                footer.setFont(font, 9f);
                footer.setNonStrokingColor(new Color(0x9E, 0x9E, 0x9E));
                footer.newLineAtOffset(margin, 30f);
                footer.showText("第 " + p + " 页");
                footer.endText();
                footer.close();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            log.error("render pdf failed", e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "PDF 渲染失败，请重试");
        } finally {
            try { doc.close(); } catch (IOException ignored) { }
        }
    }

    /** 加载系统中文字体：候选路径逐个尝试（TTC 取第 0 个字体），全失败抛 10004 */
    private static PDFont loadCjkFont(PDDocument doc) {
        for (Path p : FONT_CANDIDATES) {
            if (!Files.exists(p)) {
                continue;
            }
            try (InputStream in = Files.newInputStream(p)) {
                if (p.getFileName().toString().toLowerCase().endsWith(".ttc")) {
                    TrueTypeCollection ttc = new TrueTypeCollection(in);
                    TrueTypeFont[] holder = new TrueTypeFont[1];
                    ttc.processAllFonts(font -> { if (holder[0] == null) holder[0] = font; });
                    if (holder[0] == null) {
                        throw new IOException("empty ttc: " + p);
                    }
                    return PDType0Font.load(doc, holder[0], true);
                }
                return PDType0Font.load(doc, in, true);
            } catch (IOException e) {
                log.warn("font load failed: {} ({})", p, e.getMessage());
            }
        }
        throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                "系统缺少中文字体，无法导出 PDF（Windows 请确保 C:\\Windows\\Fonts 存在雅黑/宋体/黑体）");
    }

    /** 按字符宽度折行（中文按字符计，长英文可能断词，可接受） */
    private static List<String> wrap(String text, int maxChars) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            lines.add("");
            return lines;
        }
        for (int i = 0; i < text.length(); i += maxChars) {
            lines.add(text.substring(i, Math.min(i + maxChars, text.length())));
        }
        return lines;
    }

    /** 笔记标题，空则用 note_id */
    private static String titleOf(Note n) {
        return (n.getTitle() == null || n.getTitle().isBlank()) ? n.getNoteId() : n.getTitle();
    }

    /** 元数据行：书名｜页码｜标签｜更新时间 */
    private static String metaLine(Note n, List<Tag> tags) {
        StringBuilder sb = new StringBuilder();
        if (n.getSourceBookId() != null && !n.getSourceBookId().isBlank()) {
            sb.append("书: ").append(n.getSourceBookId());
        }
        if (n.getSourcePage() != null) {
            sb.append(" ｜ 页: ").append(n.getSourcePage());
        }
        if (!tags.isEmpty()) {
            sb.append(" ｜ 标签: ").append(tags.stream().map(Tag::getName)
                    .collect(java.util.stream.Collectors.joining(", ")));
        }
        if (n.getUpdatedAt() != null) {
            sb.append(" ｜ 更新: ").append(n.getUpdatedAt());
        }
        return sb.length() == 0 ? "（无来源信息）" : sb.toString();
    }

    /** PDF 文件名：整本书用书名，否则用时间戳 */
    private static String pdfFileName(List<Note> notes) {
        if (notes.size() == 1) {
            return titleOf(notes.get(0)) + ".pdf";
        }
        return "笔记导出_" + STAMP.format(Instant.now()) + ".pdf";
    }

    /** 超长截断（避免 PDF 单行越界） */
    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /** 单篇 Markdown：可选 YAML front-matter + 正文 */
    private String render(Note n, List<Tag> tags, boolean includeFrontMatter) {        StringBuilder sb = new StringBuilder();
        if (includeFrontMatter) {
            sb.append("---\n");
            sb.append("title: ").append(yaml(n.getTitle())).append('\n');
            sb.append("book: ").append(yaml(noteRepository.sourceBookTitle(n.getSourceBookId())))
                    .append('\n');
            if (n.getSourcePage() != null) {
                sb.append("page: ").append(n.getSourcePage()).append('\n');
            }
            sb.append("tags: [").append(String.join(", ",
                    tags.stream().map(t -> yaml(t.getName())).toList())).append("]\n");
            sb.append("source_type: ").append(yaml(n.getSourceType())).append('\n');
            sb.append("created_at: ").append(yaml(n.getCreatedAt())).append('\n');
            sb.append("updated_at: ").append(yaml(n.getUpdatedAt())).append('\n');
            sb.append("---\n\n");
        }
        sb.append(n.getContent() == null ? "" : n.getContent());
        if (!sb.toString().endsWith("\n")) {
            sb.append('\n');
        }
        return sb.toString();
    }

    /** zip 内文件名：序号-标题.md（过滤 Windows 非法字符，避免写盘失败） */
    private static String fileName(int index, Note n) {
        String title = (n.getTitle() == null || n.getTitle().isBlank())
                ? n.getNoteId() : n.getTitle();
        String safe = title.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (safe.length() > 50) {
            safe = safe.substring(0, 50);
        }
        return String.format("%02d-%s.md", index, safe);
    }

    /** YAML 标量：空值给空串，含特殊字符加引号 */
    private static String yaml(String v) {
        if (v == null || v.isBlank()) {
            return "\"\"";
        }
        String s = v.replace("\"", "\\\"");
        return s.matches("[A-Za-z0-9_\\-\\u4e00-\\u9fa5:+. ]+") ? s : "\"" + s + "\"";
    }

    private static long sizeOf(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return 0;
        }
    }
}
