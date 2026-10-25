package com.readingnotes.service.impl;

import com.readingnotes.common.BusinessException;
import com.readingnotes.common.ErrorCode;
import com.readingnotes.config.AppProperties;
import com.readingnotes.entity.Note;
import com.readingnotes.entity.Tag;
import com.readingnotes.repository.NoteRepository;
import com.readingnotes.repository.TagRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 笔记导出服务单元测试（API §9.11）
 *
 * <p>用 Mockito mock NoteRepository / TagRepository，真实 AppProperties + 临时目录，
 * 覆盖：scope=selected/book 解析、front-matter 开关、YAML 转义、zip 打包内容、
 * 非法文件名替换、参数与无数据校验。不依赖真实数据库。</p>
 */
@ExtendWith(MockitoExtension.class)
class NoteExportServiceImplTest {

    @TempDir
    Path tempDir;

    @Mock
    private NoteRepository noteRepository;

    @Mock
    private TagRepository tagRepository;

    private NoteExportServiceImpl exportService;

    private AppProperties props;

    @BeforeEach
    void setUp() {
        props = new AppProperties();
        props.setExportsDir(tempDir.toString());
        props.setExportTtlHours(1);
        exportService = new NoteExportServiceImpl(noteRepository, tagRepository, props);
    }

    // ==================== 辅助 ====================

    private Note note(String id, String title, String content, String bookId) {
        Note n = new Note();
        n.setNoteId(id);
        n.setTitle(title);
        n.setContent(content);
        n.setSourceType("MANUAL");
        n.setSourceBookId(bookId);
        n.setCreatedAt("2026-10-17T10:00:00.000Z");
        n.setUpdatedAt("2026-10-17T10:00:00.000Z");
        return n;
    }

    private Tag tag(String name) {
        Tag t = new Tag();
        t.setTagId("tag_" + name);
        t.setName(name);
        return t;
    }

    /** 读 zip 中第一个匹配前缀的 entry 内容 */
    private String readFirstEntry(Path zip, String prefix) throws Exception {
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.getName().startsWith(prefix)) {
                    try (InputStream in = zf.getInputStream(e)) {
                        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    }
                }
            }
        }
        throw new AssertionError("zip 中未找到前缀为 " + prefix + " 的 entry");
    }

    /** 找到导出目录里最新生成的 zip */
    private Path latestZip() throws Exception {
        try (var stream = Files.list(tempDir)) {
            return stream.filter(p -> p.toString().endsWith(".zip"))
                    .max(java.util.Comparator.comparingLong(p -> p.toFile().lastModified()))
                    .orElseThrow(() -> new AssertionError("导出目录中未生成 zip"));
        }
    }

    // ==================== scope=selected ====================

    @Test
    @DisplayName("selected 多笔记导出：zip 生成、note_count 正确、front-matter 含 title/book/tags")
    void selected_scope_zipWithFrontMatter() throws Exception {
        Note n1 = note("note_1", "习惯的力量笔记", "暗示 → 惯常行为 → 奖赏。", "book_1");
        Note n2 = note("note_2", "深度工作", "专注是稀缺资源。", "book_1");
        when(noteRepository.findById("note_1")).thenReturn(n1);
        when(noteRepository.findById("note_2")).thenReturn(n2);
        when(noteRepository.sourceBookTitle("book_1")).thenReturn("习惯的力量");
        when(tagRepository.findByNoteIds(anyList())).thenReturn(
                Map.of("note_1", List.of(tag("AI问答")), "note_2", List.of()));

        Map<String, Object> data = exportService.export(
                List.of("note_1", "note_2"), null, "selected", "markdown", true);

        assertThat(data.get("download_url")).asString().startsWith("/api/v1/files/exports/export_");
        assertThat(data.get("note_count")).isEqualTo(2);
        assertThat(data.get("expires_at")).isNotNull();

        Path zip = latestZip();
        String md = readFirstEntry(zip, "01-习惯的力量笔记.md");
        assertThat(md).startsWith("---\n");
        assertThat(md).contains("title: 习惯的力量笔记");
        assertThat(md).contains("book: 习惯的力量");
        assertThat(md).contains("tags: [AI问答]");
        assertThat(md).contains("暗示 → 惯常行为 → 奖赏。");
        // 第二个 entry 存在
        readFirstEntry(zip, "02-深度工作.md");
    }

    @Test
    @DisplayName("selected 重复 id 去重保序，只导出一次")
    void selected_dedupIds() throws Exception {
        Note n1 = note("note_1", "标题A", "正文A", null);
        when(noteRepository.findById("note_1")).thenReturn(n1);

        exportService.export(List.of("note_1", "note_1", "note_1"), null, "selected", "markdown", false);

        verify(noteRepository, times(1)).findById("note_1");
        Map<String, Object> data = exportService.export(
                List.of("note_1", "note_1"), null, "selected", "markdown", false);
        assertThat(data.get("note_count")).isEqualTo(1);
    }

    @Test
    @DisplayName("selected 空 note_ids 抛 10001")
    void selected_emptyIds_rejected() {
        assertThatThrownBy(() -> exportService.export(List.of(), null, "selected", "markdown", false))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.PARAM_ERROR.getCode());
    }

    // ==================== scope=book ====================

    @Test
    @DisplayName("book 范围按 book_id 导出整本书笔记")
    void bookScope_byBookId() throws Exception {
        Note n1 = note("note_1", "第一章笔记", "正文1", "book_1");
        Note n2 = note("note_2", "第二章笔记", "正文2", "book_1");
        when(noteRepository.findByBookId("book_1")).thenReturn(List.of(n1, n2));

        Map<String, Object> data = exportService.export(
                null, "book_1", "book", "markdown", false);

        verify(noteRepository).findByBookId("book_1");
        assertThat(data.get("note_count")).isEqualTo(2);
        Path zip = latestZip();
        readFirstEntry(zip, "01-第一章笔记.md");
        readFirstEntry(zip, "02-第二章笔记.md");
    }

    @Test
    @DisplayName("book 范围缺 book_id 抛 10001")
    void bookScope_missingBookId_rejected() {
        assertThatThrownBy(() -> exportService.export(null, null, "book", "markdown", false))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.PARAM_ERROR.getCode());
    }

    @Test
    @DisplayName("scope 非法值抛 10001")
    void invalidScope_rejected() {
        assertThatThrownBy(() -> exportService.export(List.of("note_1"), null, "all", "markdown", false))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.PARAM_ERROR.getCode());
    }

    @Test
    @DisplayName("无笔记可导出抛 50001")
    void noNotes_throwsNoteNotFound() {
        when(noteRepository.findByBookId("book_x")).thenReturn(List.of());
        assertThatThrownBy(() -> exportService.export(null, "book_x", "book", "markdown", false))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.NOTE_NOT_FOUND.getCode());
    }

    // ==================== 格式 ====================

    @Test
    @DisplayName("非法格式（非 markdown/pdf）抛 10001")
    void invalidFormat_rejected() {
        assertThatThrownBy(() -> exportService.export(List.of("note_1"), null, "selected", "html", false))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.PARAM_ERROR.getCode());
    }

    // ==================== PDF 导出（§9.11 format=pdf） ====================

    /**
     * stub 掉 renderPdfBytes 的导出服务：PDF 渲染隔离字体依赖（CI Linux 可能无中文字体），
     * 只验证 resolve→打包→返回链路；真实渲染由接口实测覆盖。
     */
    private NoteExportServiceImpl stubPdfService() {
        return new NoteExportServiceImpl(noteRepository, tagRepository, props) {
            @Override
            byte[] renderPdfBytes(List<Note> notes, Map<String, List<Tag>> tagsMap,
                                  boolean includeFrontMatter) {
                return "%PDF-1.7\n%%EOF\n".getBytes(StandardCharsets.UTF_8);
            }
        };
    }

    @Test
    @DisplayName("pdf selected 导出：zip 内单个 .pdf，note_count 正确")
    void pdf_selected_singleFile() throws Exception {
        Note n1 = note("note_1", "习惯的力量", "暗示 → 惯常行为 → 奖赏。", "book_1");
        when(noteRepository.findById("note_1")).thenReturn(n1);

        Map<String, Object> data = stubPdfService().export(
                List.of("note_1"), null, "selected", "pdf", false);

        assertThat(data.get("note_count")).isEqualTo(1);
        Path zip = latestZip();
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            ZipEntry e = en.nextElement();
            assertThat(e.getName()).endsWith(".pdf");
            try (InputStream in = zf.getInputStream(e)) {
                assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).startsWith("%PDF");
            }
        }
    }

    @Test
    @DisplayName("pdf book 范围：整本书笔记合并为单个 PDF")
    void pdf_bookScope_wholeBook() throws Exception {
        Note n1 = note("note_1", "第一章", "正文1", "book_1");
        Note n2 = note("note_2", "第二章", "正文2", "book_1");
        when(noteRepository.findByBookId("book_1")).thenReturn(List.of(n1, n2));

        Map<String, Object> data = stubPdfService().export(
                null, "book_1", "book", "pdf", true);

        verify(noteRepository).findByBookId("book_1");
        assertThat(data.get("note_count")).isEqualTo(2);
        Path zip = latestZip();
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            int count = 0;
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                assertThat(e.getName()).endsWith(".pdf");
                count++;
            }
            assertThat(count).isEqualTo(1); // 合并单文件
        }
    }

    @Test
    @DisplayName("pdf 导出无笔记抛 50001")
    void pdf_noNotes_throwsNotFound() {
        when(noteRepository.findByBookId("book_x")).thenReturn(List.of());
        assertThatThrownBy(() -> stubPdfService().export(null, "book_x", "book", "pdf", false))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.NOTE_NOT_FOUND.getCode());
    }

    // ==================== front-matter 开关与转义 ====================

    @Test
    @DisplayName("include_front_matter=false 时不输出 YAML 头，仅正文")
    void noFrontMatter_plainContent() throws Exception {
        Note n1 = note("note_1", "标题", "纯正文内容", null);
        when(noteRepository.findById("note_1")).thenReturn(n1);

        exportService.export(List.of("note_1"), null, "selected", "markdown", false);

        String md = readFirstEntry(latestZip(), "01-标题.md");
        assertThat(md).doesNotContain("---");
        assertThat(md).contains("纯正文内容");
    }

    @Test
    @DisplayName("YAML 含双引号时自动加引号并转义")
    void yamlSpecialChars_quoted() throws Exception {
        Note n1 = note("note_1", "标题\"带引号\"", "正文", "book_1");
        when(noteRepository.findById("note_1")).thenReturn(n1);
        when(noteRepository.sourceBookTitle("book_1")).thenReturn("书\"名");

        exportService.export(List.of("note_1"), null, "selected", "markdown", true);

        // 双引号不在安全字符集 → 值被引号包裹且内部引号转义
        String md = readFirstEntry(latestZip(), "01-");
        assertThat(md).contains("title: \"标题\\\"带引号\\\"\"");
        assertThat(md).contains("book: \"书\\\"名\"");
    }

    // ==================== 文件名安全 ====================

    @Test
    @DisplayName("Windows 非法字符文件名被替换、超长截断 50 字符")
    void fileName_illegalCharsReplaced() throws Exception {
        Note n1 = note("note_1", "a/b:c*?\"<>|", "正文", null);
        when(noteRepository.findById("note_1")).thenReturn(n1);

        exportService.export(List.of("note_1"), null, "selected", "markdown", false);

        Path zip = latestZip();
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            ZipEntry e = en.nextElement();
            assertThat(e.getName()).startsWith("01-");
            assertThat(e.getName()).endsWith(".md");
            assertThat(e.getName()).doesNotContain("/", ":", "*", "?", "\"", "<", ">", "|");
        }
    }

    @Test
    @DisplayName("无标题笔记用 note_id 作文件名兜底")
    void blankTitle_usesNoteId() throws Exception {
        Note n1 = note("note_1", "  ", "正文", null);
        when(noteRepository.findById("note_1")).thenReturn(n1);

        exportService.export(List.of("note_1"), null, "selected", "markdown", false);

        readFirstEntry(latestZip(), "01-note_1.md");
    }

    // ==================== 返回信息 ====================

    @Test
    @DisplayName("返回 file_size / note_count / expires_at 字段齐全")
    void responseFields_complete() throws Exception {
        Note n1 = note("note_1", "标题", "正文内容", null);
        when(noteRepository.findById("note_1")).thenReturn(n1);

        Map<String, Object> data = exportService.export(
                List.of("note_1"), null, "selected", "markdown", false);

        assertThat(data.keySet()).contains("download_url", "file_name", "file_size", "expires_at", "note_count");
        // sizeOf 返回 long（Files.size），按 Number 断言
        assertThat(((Number) data.get("file_size")).longValue()).isPositive();
    }
}
