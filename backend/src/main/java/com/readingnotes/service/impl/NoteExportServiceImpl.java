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
import org.springframework.stereotype.Service;

import java.io.IOException;
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
 * 笔记导出实现（API 文档 §9.11）
 *
 * <p>流程：解析 scope → 查笔记 → 逐个渲染 Markdown（可选 YAML front-matter）
 * → 打包 zip 落到 app.exports-dir → 返回下载地址与过期时间。
 * 只用 JDK 内置 zip（java.util.zip），不引第三方依赖。</p>
 */
@Slf4j
@Service
public class NoteExportServiceImpl implements NoteExportService {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(ZoneOffset.ofHours(8));

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

        // pdf 导出本期未实现（接口文档列出，但需 PDFBox 排版，优先级最低）
        if (!"markdown".equalsIgnoreCase(safeFormat)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "暂不支持的导出格式: " + safeFormat + "，请使用 markdown");
        }

        List<Note> notes = resolveNotes(noteIds, bookId, safeScope);
        if (notes.isEmpty()) {
            throw new BusinessException(ErrorCode.NOTE_NOT_FOUND, "没有可导出的笔记");
        }

        // 批量取标签，避免逐条 N+1
        Map<String, List<Tag>> tagsMap = tagRepository.findByNoteIds(
                notes.stream().map(Note::getNoteId).toList());

        Path zipPath = writeZip(notes, tagsMap, includeFrontMatter);

        Map<String, Object> data = new HashMap<>();
        data.put("download_url", "/api/v1/files/exports/" + zipPath.getFileName());
        data.put("file_name", "笔记导出_" + zipPath.getFileName().toString()
                .replace("export_", "").replace(".zip", "") + ".zip");
        data.put("file_size", sizeOf(zipPath));
        data.put("note_count", notes.size());
        data.put("expires_at", Instant.now().plusSeconds(props.getExportTtlHours() * 3600L)
                .truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString());
        log.info("notes exported: count={}, zip={}", notes.size(), zipPath.getFileName());
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

    /** 单篇 Markdown：可选 YAML front-matter + 正文 */
    private String render(Note n, List<Tag> tags, boolean includeFrontMatter) {
        StringBuilder sb = new StringBuilder();
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
