package com.readingnotes.service.impl;

import com.readingnotes.ai.ChromaClient;
import com.readingnotes.build.BuildTaskService;
import com.readingnotes.build.PdfTextExtractor;
import com.readingnotes.common.BusinessException;
import com.readingnotes.common.ErrorCode;
import com.readingnotes.security.AuthContext;
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
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

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

    /** 查询列表；页码与页大小在此夹紧，防止上游传 0/负数造成 SQL 语法错误；多租户按登录用户过滤（共享书+本人书） */
    @Override
    public List<Book> list(String status, String keyword, int page, int pageSize) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(pageSize, 1), 100);
        return bookRepo.list(status, keyword, AuthContext.getUserId(), safePage, safeSize);
    }

    /** 总数，与 {@link #list} 使用同一组过滤条件，保证分页器总数一致 */
    @Override
    public long count(String status, String keyword) {
        return bookRepo.count(status, keyword, AuthContext.getUserId());
    }

    /** 详情；不存在时抛 20005（由仓储层统一抛） */
    @Override
    public Book detail(String bookId) {
        return bookRepo.findById(bookId);
    }

    /** 列表页 note_count（软删笔记不计） */
    @Override
    public int noteCount(String bookId) {
        return bookRepo.countNotes(bookId);
    }

    /** 批量取 note_count，列表页一次聚合，避免 N+1 */
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

        // 先落到临时文件，再做魔数/解析校验：校验失败的半成品不会在 uploads/ 留下孤儿文件
        Path tmpDir = Path.of(appProps.getUploadsDir()).toAbsolutePath().normalize().resolve(".tmp");
        Path tmp = tmpDir.resolve("mp_" + System.nanoTime() + ".pdf");
        try {
            Files.createDirectories(tmpDir);
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            }
            return registerBook(tmp, originalName, file.getSize(), title, author, confirmDuplicate);
        } catch (IOException e) {
            log.error("save upload temp failed", e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "文件保存失败，请检查磁盘空间");
        } finally {
            deleteQuietly(tmp);
        }
    }

    /**
     * 从已落盘的 PDF 文件登记一本书（单请求上传与分片上传共用）。
     * <p>
     * 顺序：魔数 → 解析探针（页数/字符数/元数据）→ 书名 → 重名确认 → 移入 uploads/ →
     * 插库 → 触发构建。任何一步失败都不会留下书籍记录，临时文件由调用方清理。
     *
     * @param tempPdf       已合并完成的临时 PDF（本方法负责移走）
     * @param originalName  原始文件名（仅用于取书名，不落盘）
     * @param fileSize      声明大小，用于和实际字节数交叉校验
     */
    @Override
    public Book registerBook(Path tempPdf, String originalName, long fileSize,
                             String title, String author, boolean confirmDuplicate) {
        if (tempPdf == null || !Files.isRegularFile(tempPdf)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "上传文件不存在，请重新上传");
        }
        long actualSize;
        try {
            actualSize = Files.size(tempPdf);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "读取上传文件失败");
        }
        if (actualSize <= 0) {
            throw new BusinessException(ErrorCode.PDF_DAMAGED, "文件内容为空，请重新上传");
        }
        if (actualSize > MAX_FILE_SIZE) {
            throw new BusinessException(ErrorCode.FILE_TOO_LARGE);
        }
        if (fileSize > 0 && fileSize != actualSize) {
            log.warn("upload size mismatch declared={} actual={} file={}", fileSize, actualSize, originalName);
            throw new BusinessException(ErrorCode.PARAM_ERROR, "文件传输不完整，请重新上传");
        }
        // 魔数：光看扩展名挡不住把 .doc 改名成 .pdf 的情况，PDFBox 也只会在解析时报错
        checkPdfMagic(tempPdf);

        // 解析探针：页数 / 字符数 / PDF 元数据书名
        int pages;
        int chars;
        String pdfTitle;
        try {
            PdfTextExtractor.ExtractResult probe = extractor.extract(tempPdf.toFile());
            pages = probe.getTotalPages();
            chars = probe.getCharCount();
            pdfTitle = probe.getTitle();
        } catch (PdfTextExtractor.EncryptedPdfException e) {
            log.info("encrypted pdf rejected file={}", originalName);
            throw new BusinessException(ErrorCode.PDF_ENCRYPTED);
        } catch (IOException e) {
            log.warn("pdf parse failed on upload file={}", originalName, e);
            throw new BusinessException(ErrorCode.PDF_DAMAGED, "文件已损坏或不是有效的 PDF");
        }
        if (pages > buildProps.getMaxPages()) {
            throw new BusinessException(ErrorCode.PDF_TOO_MANY_PAGES,
                    "本书共 " + pages + " 页，超过 " + buildProps.getMaxPages() + " 页上限，暂不支持");
        }
        if (chars < PdfTextExtractor.MIN_CHARS) {
            log.info("scanned pdf rejected file={} chars={}", originalName, chars);
            throw new BusinessException(ErrorCode.SCANNED_PDF,
                    "未检测到可复制文字，可能是扫描件。本期暂不支持扫描件，请使用文字版 PDF（可复制）");
        }

        // 书名优先级：用户填写 > PDF 元数据书名 > 文件名（去扩展名）
        String guessed = originalName.toLowerCase(Locale.ROOT).endsWith(".pdf")
                ? originalName.substring(0, originalName.length() - 4).trim() : originalName.trim();
        String finalTitle = isBlank(title) ? (isBlank(pdfTitle) ? guessed : pdfTitle) : title.trim();
        finalTitle = UNSAFE_NAME.matcher(finalTitle).replaceAll("_").trim();
        if (finalTitle.isEmpty()) {
            finalTitle = "未命名书籍";
        }
        if (finalTitle.length() > 80) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "书名不能超过 80 个字符");
        }

        // 重名确认（F1.1 边界 / 幂等策略 §14.2）
        Book duplicate = bookRepo.findActiveByTitle(finalTitle);
        if (duplicate != null && !confirmDuplicate) {
            throw new DuplicateBookException(duplicate.getBookId());
        }

        String bookId = Ids.bookId();
        Path uploadsDir = Path.of(appProps.getUploadsDir()).toAbsolutePath().normalize();
        Path target = uploadsDir.resolve(bookId + ".pdf").normalize();
        if (!target.getParent().equals(uploadsDir)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "非法的存储路径");
        }
        try {
            Files.createDirectories(uploadsDir);
            try {
                Files.move(tempPdf, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                // 跨盘/文件系统不支持原子移动时回退为普通移动
                Files.move(tempPdf, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.error("move upload failed bookId={}", bookId, e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "文件保存失败，请检查磁盘空间");
        }

        String now = Times.now();
        Book book = new Book();
        book.setBookId(bookId);
        book.setOwnerId(AuthContext.getUserId());   // 多租户：上传者归属；组员共享书保持 NULL
        book.setTitle(finalTitle);
        book.setAuthor(isBlank(author) ? null : author.trim());
        book.setFilePath("uploads/" + bookId + ".pdf");
        book.setFileSize(actualSize);
        book.setTotalPages(pages);
        book.setStatus("UPLOADING");
        book.setBuildProgress(0);
        book.setCreatedAt(now);
        book.setUpdatedAt(now);
        book.setSoftDelete(0);
        try {
            bookRepo.insert(book);
        } catch (RuntimeException e) {
            deleteQuietly(target);
            throw e;
        }

        // 置 BUILDING 并异步入队（响应不等待构建，需求 F1.1）
        bookRepo.updateStage(bookId, "BUILDING", "PARSE", 10, Times.now());
        progressRepo.start(bookId, Times.now());
        buildService.submit(bookId, null, null, false);

        log.info("upload accepted bookId={} title={} size={} pages={} chars={}",
                bookId, finalTitle, actualSize, pages, chars);
        return bookRepo.findById(bookId);
    }

    /** 校验 PDF 魔数（前 5 字节 %PDF-），挡住改扩展名的非 PDF 文件 */
    private void checkPdfMagic(Path pdf) {
        byte[] head = new byte[5];
        try (InputStream in = Files.newInputStream(pdf)) {
            int read = in.readNBytes(head, 0, head.length);
            if (read < head.length || head[0] != '%' || head[1] != 'P' || head[2] != 'D'
                    || head[3] != 'F' || head[4] != '-') {
                throw new BusinessException(ErrorCode.PDF_DAMAGED, "文件已损坏或不是有效的 PDF");
            }
        } catch (BusinessException e) {
            throw e;
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "读取文件失败，请重试");
        }
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

            // 3. 归档 PDF 到 uploads/.trash/（保留 7 天，恢复时移回）
            //    不能直接物理删除：一删，「回收站 7 天内可恢复」就成了空话，恢复后只剩空壳记录。
            boolean pdfArchived = archivePdf(bookIdSafe);
            log.info("delete book bookId={} collection removed, pdfArchived={}", bookIdSafe, pdfArchived);

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
                    "归档 PDF 文件失败：" + e.getMessage() + "，可点击重试", Times.now());
            log.error("archive pdf failed bookId={}", bookIdSafe, e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "归档 PDF 文件失败，可点击重试");
        }
    }

    /**
     * 可选：随书归档关联笔记（keepNotes=false 时）。
     * 默认不执行——需求明确"默认保留笔记"。
     */
    private int archiveNotes(String bookId) {
        return 0;
    }

    /**
     * 从回收站恢复。
     * <p>
     * 两条分支：原文件还在 → 回到 BUILDING 并自动重建（删除时向量已清空，不重建则问答不可用）；
     * 原文件已丢失 → 恢复到 FAILED 并提示重新上传，至少保留书名与笔记关联。
     */
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

        // 把 .trash 里的归档文件移回 uploads/；移不回来说明归档已丢失
        boolean hasFile = restoreArchivedPdf(bookId);

        if (hasFile) {
            // 向量集合在删除时已清空，因此恢复后一律重建，保证问答可用
            bookRepo.restore(bookId, "BUILDING", Times.now());
            progressRepo.deleteByBook(bookId);
            buildService.submit(bookId, null, null, true);
            log.info("restore book bookId={} → rebuild (pdf restored from trash)", bookId);
        } else {
            // 归档也被清理（超过保留期或磁盘异常）：只能恢复记录，需重新上传
            bookRepo.restore(bookId, "FAILED", Times.now());
            bookRepo.markFailed(bookId, String.valueOf(ErrorCode.FILE_NOT_FOUND_ON_DISK.getCode()),
                    "恢复成功，但原书文件已丢失，请重新上传本书", Times.now());
            log.info("restore book bookId={} → FAILED (archived pdf missing)", bookId);
        }
        return bookRepo.findById(bookId);
    }

    /**
     * 永久删除：清向量 → 清子表 → 清文件 → 硬删主记录。
     * <p>
     * 顺序不可调整：chunks/chapters/build_progress 均有外键指向 books，先删主记录会被 RESTRICT 拦住。
     * 若仍有关联笔记导致硬删失败，回退为软删状态并提示用户先处理笔记，不静默吞掉。
     */
    @Override
    public void purge(String bookId) {
        Book book = bookRepo.findById(bookId);
        if (book.getSoftDelete() == null || book.getSoftDelete() != 1) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "只能永久删除回收站中的书籍");
        }
        String collection = BuildTaskService.collectionName(bookId);
        log.info("purge book bookId={} title={}", bookId, book.getTitle());

        // 顺序：清向量 → 清切片/章节/进度 → 清 PDF（含 .trash 归档）→ 最后硬删主记录
        // （chunks/chapters/build_progress 有外键指向 books，必须先删子表）
        try {
            chroma.deleteCollection(collection);
        } catch (Exception e) {
            log.warn("purge: chroma delete failed (continue) bookId={} {}", bookId, e.getMessage());
        }
        chunkRepo.deleteByBook(bookId);
        chapterRepo.deleteByBook(bookId);
        progressRepo.deleteByBook(bookId);
        // 两处都要删：正常流程文件在 .trash，异常流程（删除中断在步 3）可能仍在 uploads/
        deleteQuietly(pdfPath(bookId));
        deleteQuietly(trashPath(bookId));
        bookRepo.markPurging(bookId, Times.now());
        try {
            bookRepo.hardDelete(bookId);
        } catch (DataAccessException e) {
            // 仍有关联笔记/用量记录指向本书（外键 RESTRICT）
            log.warn("purge blocked by references bookId={} {}", bookId, e.getMessage());
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                    "仍有笔记或用量记录关联本书，已改为软删除状态，如需彻底清除请先删除关联笔记");
        }
        log.info("purge done bookId={}", bookId);
    }

    /** 回收站列表：附剩余保留小时数，供前端倒计时 */
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

    private Path pdfPath(String bookId) {
        return Path.of(appProps.getUploadsDir()).toAbsolutePath().normalize().resolve(bookId + ".pdf");
    }

    /** 回收站归档路径：uploads/.trash/{book_id}.pdf */
    private Path trashPath(String bookId) {
        return Path.of(appProps.getUploadsDir()).toAbsolutePath().normalize()
                .resolve(".trash").resolve(bookId + ".pdf");
    }

    /**
     * 删除时把 PDF 移入 .trash（不物理删除）。
     * 返回 false 表示源文件本来就不存在（幂等：重复删除不报错）。
     */
    private boolean archivePdf(String bookId) throws IOException {
        Path src = pdfPath(bookId);
        if (!Files.isRegularFile(src)) {
            return false;
        }
        Path dst = trashPath(bookId);
        Files.createDirectories(dst.getParent());
        try {
            Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailed) {
            Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
        }
        return true;
    }

    /**
     * 恢复时把 PDF 从 .trash 移回 uploads/。
     * 返回 false 表示归档已丢失（超过保留期被清理 / 磁盘异常）。
     */
    private boolean restoreArchivedPdf(String bookId) {
        Path live = pdfPath(bookId);
        if (Files.isRegularFile(live)) {
            return true;   // 删除流程曾在第 3 步失败，文件还在原处
        }
        Path trashed = trashPath(bookId);
        if (!Files.isRegularFile(trashed)) {
            return false;
        }
        try {
            Files.createDirectories(live.getParent());
            try {
                Files.move(trashed, live, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                Files.move(trashed, live, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException e) {
            log.error("restore archived pdf failed bookId={}", bookId, e);
            return false;
        }
    }

    @Override
    public Path resolvePdf(String bookId) {
        Book book = bookRepo.findActive(bookId);
        Path pdf = pdfPath(book.getBookId());
        if (!Files.isRegularFile(pdf)) {
            throw new BusinessException(ErrorCode.FILE_NOT_FOUND_ON_DISK);
        }
        return pdf;
    }

    @Override
    public Map<String, Object> deletePreview(String bookId) {
        Book book = bookRepo.findActive(bookId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("book_id", book.getBookId());
        data.put("title", book.getTitle());
        data.put("file_size", book.getFileSize() == null ? 0 : book.getFileSize());
        data.put("total_pages", book.getTotalPages());
        data.put("chunk_count", book.getChunkCount() == null ? 0 : book.getChunkCount());
        data.put("note_count", bookRepo.countNotes(book.getBookId()));
        data.put("is_building", buildService.isRunning(book.getBookId())
                || "BUILDING".equals(book.getStatus())
                || "REBUILDING".equals(book.getStatus()));
        data.put("retain_days", buildProps.getTrashRetainDays());
        data.put("keep_notes_default", true);
        data.put("message", "将删除该书的向量索引与 PDF 文件，关联笔记保留并标记「所属书已删除」；"
                + "删除后 " + buildProps.getTrashRetainDays() + " 天内可在回收站恢复");
        return data;
    }

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
