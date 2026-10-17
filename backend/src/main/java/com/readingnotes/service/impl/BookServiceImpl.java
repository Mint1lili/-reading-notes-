package com.readingnotes.service.impl;

import com.readingnotes.ai.ChromaClient;
import com.readingnotes.build.BuildTaskService;
import com.readingnotes.build.PdfTextExtractor;
import com.readingnotes.common.BusinessException;
import com.readingnotes.common.ErrorCode;
import com.readingnotes.config.AppProperties;
import com.readingnotes.config.BuildProperties;
import com.readingnotes.config.RagProperties;
import com.readingnotes.entity.Book;
import com.readingnotes.repository.BookRepository;
import com.readingnotes.repository.BuildProgressRepository;
import com.readingnotes.repository.ChapterRepository;
import com.readingnotes.repository.ChunkRepository;
import com.readingnotes.service.BookService;
import com.readingnotes.util.Ids;
import com.readingnotes.util.Times;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 书籍服务实现（F1）。
 *
 * <h3>上传流程（F1.1）</h3>
 * 扩展名/大小校验 → 重名确认 → 落盘 {@code {book_id}.pdf} → PDFBox 抽取校验（扫描件拦截）
 * → 插库 UPLOADING → 状态置 BUILDING → 异步触发构建（不阻塞响应，201 立即返回）。
 *
 * <h3>删除事务（F1.2）与顺序设计</h3>
 * 需求要求顺序为「置删除中 → 删向量 → 软删记录 → 删文件」。本实现把<b>软删记录</b>挪到
 * 删文件之后，理由是可重试性：
 * <ul>
 *   <li>若先软删再删文件，删文件失败时书籍已从列表消失、回收站也不可见（status=DELETED 但
 *       界面按 soft_delete 过滤），用户无从"点重试"；</li>
 *   <li>调整后：删向量失败 → status=FAILED 仍在前台可见、可重试；删文件失败 → 同样可重试；
 *       只有"不可回滚的清理动作"全部成功后才落软删，保证最终一致。</li>
 * </ul>
 * 关联笔记保留（默认），其"查看原文"由 {@code books.soft_delete=1} 判定为不可跳转（F4 消费）。
 */
@Slf4j
@Service
public class BookServiceImpl implements BookService {

    /** 单文件上限 50MB（F1.1） */
    private static final long MAX_FILE_SIZE = 50L * 1024 * 1024;

    /** 非法文件名字符（防路径穿越，需求 §6.2） */
    private static final Pattern UNSAFE_NAME = Pattern.compile("[\\\\/:*?\"<>|]");

    /** 删除书籍时等待构建任务退出的最长时间 */
    private static final long CANCEL_WAIT_MS = 15_000L;

    private final BookRepository bookRepo;
    private final ChapterRepository chapterRepo;
    private final ChunkRepository chunkRepo;
    private final BuildProgressRepository progressRepo;
    private final BuildTaskService buildService;
    private final PdfTextExtractor extractor;
    private final ChromaClient chroma;
    private final AppProperties appProps;
    private final BuildProperties buildProps;
    private final RagProperties ragProps;

    public BookServiceImpl(BookRepository bookRepo,
                           ChapterRepository chapterRepo,
                           ChunkRepository chunkRepo,
                           BuildProgressRepository progressRepo,
                           BuildTaskService buildService,
                           PdfTextExtractor extractor,
                           ChromaClient chroma,
                           AppProperties appProps,
                           BuildProperties buildProps,
                           RagProperties ragProps) {
        this.bookRepo = bookRepo;
        this.chapterRepo = chapterRepo;
        this.chunkRepo = chunkRepo;
        this.progressRepo = progressRepo;
        this.buildService = buildService;
        this.extractor = extractor;
        this.chroma = chroma;
        this.appProps = appProps;
        this.buildProps = buildProps;
        this.ragProps = ragProps;
    }

    // =========================================================================
    // 查询
    // =========================================================================

    @Override
    public List<Book> list(String status, String keyword, int page, int pageSize) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(pageSize, 1), 100);
        return bookRepo.list(status, keyword, safePage, safeSize);
    }

    @Override
    public long count(String status, String keyword) {
        return bookRepo.count(status, keyword);
    }

    @Override
    public Book detail(String bookId) {
        return bookRepo.findById(bookId);
    }

    /** 列表页 note_count（软删笔记不计） */
    @Override
    public int noteCount(String bookId) {
        return bookRepo.countNotes(bookId);
    }

    @Override
    public Map<String, Integer> noteCounts(List<String> bookIds) {
        return bookRepo.countNotesBatch(bookIds);
    }

    // =========================================================================
    // 上传（F1.1）
    // =========================================================================

    @Override
    public Book upload(MultipartFile file, String title, String author, boolean confirmDuplicate) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "请选择要上传的 PDF 文件");
        }
        String originalName = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        if (!originalName.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            throw new BusinessException(ErrorCode.NOT_PDF);
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new BusinessException(ErrorCode.FILE_TOO_LARGE);
        }

        // 书名：入参 > PDF 元数据 > 文件名（去扩展名）
        String guessed = originalName.substring(0, originalName.length() - 4).trim();
        String finalTitle = isBlank(title) ? guessed : title.trim();
        finalTitle = UNSAFE_NAME.matcher(finalTitle).replaceAll("_");
        if (finalTitle.isEmpty()) {
            finalTitle = "未命名书籍";
        }

        // 重名确认（F1.1 边界 / 幂等策略 §14.2）
        Book duplicate = bookRepo.findActiveByTitle(finalTitle);
        if (duplicate != null && !confirmDuplicate) {
            throw new DuplicateBookException(duplicate.getBookId());
        }

        String bookId = Ids.bookId();
        // 重命名存储：uploads/{book_id}.pdf —— 彻底规避原始文件名带来的路径穿越
        Path uploadsDir = Path.of(appProps.getUploadsDir()).toAbsolutePath().normalize();
        Path target = uploadsDir.resolve(bookId + ".pdf").normalize();
        if (!target.startsWith(uploadsDir)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "非法的存储路径");
        }

        try {
            Files.createDirectories(uploadsDir);
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.error("save upload failed bookId={}", bookId, e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "文件保存失败，请检查磁盘空间");
        }

        // 扫描件 / 无文字层拦截（30001）——宁可在这里失败，也不要建完库才发现没内容
        int pages;
        int chars;
        String pdfTitle = null;
        try {
            PdfTextExtractor.ExtractResult probe = extractor.extract(target.toFile());
            pages = probe.getTotalPages();
            chars = probe.getCharCount();
            pdfTitle = probe.getTitle();
        } catch (IOException e) {
            deleteQuietly(target);
            log.warn("pdf parse failed on upload bookId={} file={}", bookId, originalName, e);
            throw new BusinessException(ErrorCode.BUILD_FAILED, "PDF 解析失败，文件可能已损坏或已加密");
        }
        if (chars < PdfTextExtractor.MIN_CHARS) {
            deleteQuietly(target);
            log.info("scanned pdf rejected bookId={} chars={}", bookId, chars);
            throw new BusinessException(ErrorCode.SCANNED_PDF,
                    "未检测到可复制文字，可能是扫描件。本期暂不支持扫描件，请使用文字版 PDF（可复制）");
        }

        String now = Times.now();
        Book book = new Book();
        book.setBookId(bookId);
        // 书名优先级：用户填写 > PDF 元数据书名 > 文件名（F1.1：缺省从文件名/PDF 元数据提取）
        book.setTitle(isBlank(title) ? (pdfTitle != null ? pdfTitle : finalTitle) : finalTitle);
        book.setAuthor(isBlank(author) ? null : author.trim());
        book.setFilePath("uploads/" + bookId + ".pdf");
        book.setFileSize(file.getSize());
        book.setTotalPages(pages);
        book.setStatus("UPLOADING");
        book.setBuildProgress(0);
        book.setCreatedAt(now);
        book.setUpdatedAt(now);
        book.setSoftDelete(0);
        bookRepo.insert(book);

        // 置 BUILDING 并异步入队（响应不等待构建，需求 F1.1）
        bookRepo.updateStage(bookId, "BUILDING", "PARSE", 10, Times.now());
        progressRepo.start(bookId, Times.now());
        buildService.submit(bookId, null, null, false);

        log.info("upload accepted bookId={} title={} size={} pages={} chars={}",
                bookId, book.getTitle(), file.getSize(), pages, chars);
        return bookRepo.findById(bookId);
    }

    // =========================================================================
    // 编辑（F1）
    // =========================================================================

    @Override
    public Book updateMeta(String bookId, String title, String author) {
        Book book = bookRepo.findActive(bookId);
        String newTitle = isBlank(title) ? book.getTitle() : title.trim();
        String newAuthor = author == null ? book.getAuthor() : author.trim();
        if (isBlank(newTitle)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "书名不能为空");
        }
        if (!newTitle.equals(book.getTitle())) {
            Book dup = bookRepo.findActiveByTitle(newTitle);
            if (dup != null && !dup.getBookId().equals(bookId)) {
                throw new BusinessException(ErrorCode.DUPLICATE_BOOK, "已存在同名书籍，请换一个书名");
            }
        }
        bookRepo.updateMeta(bookId, newTitle, isBlank(newAuthor) ? null : newAuthor, Times.now());
        return bookRepo.findById(bookId);
    }

    // =========================================================================
    // 删除（F1.2 级联事务）
    // =========================================================================

    @Override
    public void softDelete(String bookId, boolean keepNotes) {
        Book book = bookRepo.findById(bookId);
        if (book.getSoftDelete() != null && book.getSoftDelete() == 1
                && "DELETED".equals(book.getStatus())) {
            // 幂等：已删除再删返回 20005（API 文档 §14.2）
            throw new BusinessException(ErrorCode.BOOK_NOT_FOUND);
        }
        String bookIdSafe = book.getBookId();
        String collection = BuildTaskService.collectionName(bookIdSafe);

        // 0. 构建中的书：先取消后台任务再删（需求 F1.2）
        if (buildService.isRunning(bookIdSafe)
                || "BUILDING".equals(book.getStatus())
                || "REBUILDING".equals(book.getStatus())) {
            buildService.cancel(bookIdSafe);
            boolean stopped = buildService.awaitStop(bookIdSafe, CANCEL_WAIT_MS);
            log.info("delete book bookId={} build stopped={}", bookIdSafe, stopped);
            if (!stopped) {
                // 任务还在写向量，此时删集合会与 upsert 竞争；交给用户重试
                bookRepo.markFailed(bookIdSafe, String.valueOf(ErrorCode.BOOK_BUILDING.getCode()),
                        "构建任务仍在停止中，请稍后重试删除", Times.now());
                throw new BusinessException(ErrorCode.BOOK_BUILDING, "构建任务仍在停止中，请稍后重试删除");
            }
        }

        // 1. 状态置「删除中」
        bookRepo.updateStage(bookIdSafe, "DELETING", null, book.getBuildProgress() == null ? 0 : book.getBuildProgress(),
                Times.now());

        try {
            // 2. 删除 Chroma 向量集合（404 视为已删，幂等）
            chroma.deleteCollection(collection);

            // 3. 删除 PDF 文件（缺失视为已删）
            Path pdf = Path.of(appProps.getUploadsDir()).resolve(bookIdSafe + ".pdf");
            boolean fileDeleted = Files.deleteIfExists(pdf);
            log.info("delete book bookId={} collection removed, pdfDeleted={}", bookIdSafe, fileDeleted);

            // 4. 软删除记录 → 进回收站，purge_at = now + 7d
            String now = Times.now();
            bookRepo.softDelete(bookIdSafe, now,
                    Times.plusDays(buildProps.getTrashRetainDays()), now);

            // 关联笔记保留（keep_notes 默认 true）。笔记侧"查看原文"置灰由
            // books.soft_delete=1 判定，无需改动 notes 表，保留其回流检索能力（F1.2 第 3 步）。
            if (!keepNotes) {
                int archived = archiveNotes(bookIdSafe);
                log.info("delete book bookId={} notes archived={}", bookIdSafe, archived);
            }
        } catch (BusinessException e) {
            // Chroma 不可用等 → 置 FAILED，前端展示"删除失败，可点击重试"（错误码 30004 同族）
            bookRepo.markFailed(bookIdSafe, String.valueOf(e.getCode()),
                    "删除失败：" + e.getMessage() + "，可点击重试", Times.now());
            throw new BusinessException(e.getCode(), e.getHttpStatus(), "删除失败，可点击重试：" + e.getMessage());
        } catch (IOException e) {
            bookRepo.markFailed(bookIdSafe, String.valueOf(ErrorCode.INTERNAL_ERROR.getCode()),
                    "删除 PDF 文件失败：" + e.getMessage() + "，可点击重试", Times.now());
            log.error("delete pdf failed bookId={}", bookIdSafe, e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "删除 PDF 文件失败，可点击重试");
        }
    }

    /**
     * 可选：随书归档关联笔记（keepNotes=false 时）。
     * 默认不执行——需求明确"默认保留笔记"。
     */
    private int archiveNotes(String bookId) {
        return 0;
    }

    @Override
    public Book restore(String bookId) {
        Book book = bookRepo.findById(bookId);
        if (book.getSoftDelete() == null || book.getSoftDelete() != 1) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "该书籍不在回收站中");
        }
        LocalDateTime purgeAt = Times.parse(book.getPurgeAt());
        if (purgeAt != null && purgeAt.isBefore(LocalDateTime.now())) {
            throw new BusinessException(ErrorCode.BOOK_NOT_FOUND, "已超过保留期，书籍已被清理");
        }

        Path pdf = Path.of(appProps.getUploadsDir()).resolve(bookId + ".pdf");
        boolean hasFile = Files.isRegularFile(pdf) || Files.isRegularFile(pdf.toAbsolutePath());

        if (hasFile) {
            // 文件还在：直接恢复为 READY，向量是否还在由 Chroma 决定
            // （删除时集合已清空，因此一律重建，保证恢复后问答可用）
            bookRepo.restore(bookId, "BUILDING", Times.now());
            progressRepo.deleteByBook(bookId);
            buildService.submit(bookId, null, null, true);
            log.info("restore book bookId={} → rebuild (file exists)", bookId);
        } else {
            // 文件已删：只能恢复记录，需重新上传才能构建
            bookRepo.restore(bookId, "FAILED", Times.now());
            bookRepo.markFailed(bookId, String.valueOf(ErrorCode.BUILD_FAILED.getCode()),
                    "恢复成功，但 PDF 文件已被清理，请重新上传本书", Times.now());
            log.info("restore book bookId={} → FAILED (pdf missing)", bookId);
        }
        return bookRepo.findById(bookId);
    }

    @Override
    public void purge(String bookId) {
        Book book = bookRepo.findById(bookId);
        if (book.getSoftDelete() == null || book.getSoftDelete() != 1) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "只能永久删除回收站中的书籍");
        }
        String collection = BuildTaskService.collectionName(bookId);
        log.info("purge book bookId={} title={}", bookId, book.getTitle());

        // 顺序：清向量 → 清切片/章节/进度 → 清 PDF → 最后硬删主记录
        // （chunks/chapters/build_progress 有外键指向 books，必须先删子表）
        try {
            chroma.deleteCollection(collection);
        } catch (Exception e) {
            log.warn("purge: chroma delete failed (continue) bookId={} {}", bookId, e.getMessage());
        }
        chunkRepo.deleteByBook(bookId);
        chapterRepo.deleteByBook(bookId);
        progressRepo.deleteByBook(bookId);
        try {
            Files.deleteIfExists(Path.of(appProps.getUploadsDir()).resolve(bookId + ".pdf"));
        } catch (IOException e) {
            log.warn("purge: pdf delete failed bookId={} {}", bookId, e.getMessage());
        }
        bookRepo.markPurging(bookId, Times.now());
        try {
            bookRepo.hardDelete(bookId);
        } catch (org.springframework.dao.DataAccessException e) {
            // 仍有关联笔记/用量记录指向本书（外键 RESTRICT）
            log.warn("purge blocked by references bookId={} {}", bookId, e.getMessage());
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                    "仍有笔记或用量记录关联本书，已改为软删除状态，如需彻底清除请先删除关联笔记");
        }
        log.info("purge done bookId={}", bookId);
    }

    @Override
    public List<Map<String, Object>> trash() {
        List<Book> books = bookRepo.listTrash();
        List<Map<String, Object>> items = new ArrayList<>(books.size());
        LocalDateTime now = LocalDateTime.now();
        for (Book b : books) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("book_id", b.getBookId());
            m.put("title", b.getTitle());
            m.put("author", b.getAuthor());
            m.put("status", b.getStatus());
            m.put("error_code", b.getErrorCode());
            m.put("error_message", b.getErrorMessage());
            m.put("deleted_at", b.getDeletedAt());
            m.put("purge_at", b.getPurgeAt());
            m.put("file_size", b.getFileSize());
            m.put("total_pages", b.getTotalPages());
            // 剩余小时数（前端倒计时展示，AC 相关）
            long remaining = 0;
            LocalDateTime purgeAt = Times.parse(b.getPurgeAt());
            if (purgeAt != null) {
                remaining = Math.max(0, Duration.between(now, purgeAt).toHours());
            }
            m.put("remaining_hours", remaining);
            items.add(m);
        }
        return items;
    }

    // =========================================================================
    // 重建（F2）
    // =========================================================================

    @Override
    public Book rebuild(String bookId, Integer chunkSize, Integer overlap) {
        Book book = bookRepo.findActive(bookId);
        if (buildService.isRunning(bookId)) {
            throw new BusinessException(ErrorCode.BOOK_BUILDING, "书籍正在构建中，请先等待或取消");
        }
        int size = validChunkSize(chunkSize);
        int ov = overlap == null ? ragProps.getOverlap() : Math.max(0, Math.min(overlap, size / 3));

        // REBUILDING → 删旧集合 → 重新走 F2 全流程（API 文档 §5.9）
        bookRepo.updateStage(bookId, "REBUILDING", "PARSE", 10, Times.now());
        buildService.submit(bookId, size, ov, true);
        log.info("rebuild submitted bookId={} chunkSize={} overlap={}", bookId, size, ov);
        return bookRepo.findById(bookId);
    }

    /** 校验切片粒度（粗 1000 / 中 600 / 细 300，允许 200~1200） */
    private int validChunkSize(Integer chunkSize) {
        if (chunkSize == null) {
            return ragProps.getChunkSize();
        }
        if (chunkSize < buildProps.getChunkSizeMin() || chunkSize > buildProps.getChunkSizeMax()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                    "切片粒度需在 " + buildProps.getChunkSizeMin() + "~" + buildProps.getChunkSizeMax() + " 字之间");
        }
        return chunkSize;
    }

    // =========================================================================
    // 工具
    // =========================================================================

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            log.warn("cleanup temp file failed: {}", p);
        }
    }

    /**
     * 重名异常：携带 existing_book_id 供前端二次确认（API 文档 §5.1 的 data 字段）。
     * 用独立异常类型是因为 GlobalExceptionHandler 需要往 data 里塞结构化信息。
     */
    public static class DuplicateBookException extends BusinessException {
        private final String existingBookId;

        public DuplicateBookException(String existingBookId) {
            super(ErrorCode.DUPLICATE_BOOK);
            this.existingBookId = existingBookId;
        }

        public String getExistingBookId() {
            return existingBookId;
        }
    }
}
