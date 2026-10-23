package com.readingnotes.service;

import com.readingnotes.entity.Book;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 书籍服务（F1 书籍管理，API 文档 §5）。
 * <p>
 * 两条上传入口共用 {@link #registerBook}：一次性上传（Controller 落临时文件后调用）与
 * 分片上传（合并后调用），保证校验、去重、建库、触发构建的副作用完全一致。
 */
public interface BookService {

    /** 书籍列表 */
    List<Book> list(String status, String keyword, int page, int pageSize);

    long count(String status, String keyword);

    /** 书籍详情 */
    Book detail(String bookId);

    /** 单本书的关联笔记数（软删笔记不计） */
    int noteCount(String bookId);

    /** 批量关联笔记数：bookId → count，供列表页一次聚合 */
    Map<String, Integer> noteCounts(List<String> bookIds);

    /**
     * 上传 PDF（F1.1）。
     *
     * @param file              PDF 文件
     * @param title            书名（缺省从 PDF 元数据/文件名提取）
     * @param author           作者（可选）
     * @param confirmDuplicate 重名时是否继续（false 且重名 → 抛 20003）
     * @return 新建的书籍（status=BUILDING）
     */
    Book upload(MultipartFile file, String title, String author, boolean confirmDuplicate);

    /**
     * 从已落盘的 PDF 登记书籍（分片上传合并后复用同一条入库链路）。
     *
     * @param tempPdf      已合并的临时 PDF，方法内部会移入 uploads/{book_id}.pdf
     * @param originalName 原始文件名（仅用于推导书名）
     * @param fileSize     声明大小，用于与实际字节数交叉校验（≤0 表示跳过校验）
     */
    Book registerBook(Path tempPdf, String originalName, long fileSize,
                      String title, String author, boolean confirmDuplicate);

    /** 编辑书名/作者（仅这两个字段可改） */
    Book updateMeta(String bookId, String title, String author);

    /** 软删除（F1.2 级联事务，进回收站） */
    void softDelete(String bookId, boolean keepNotes);

    /** 删除预检：影响面统计（笔记数/切片数/文件大小/是否构建中），供二次确认弹窗展示 */
    Map<String, Object> deletePreview(String bookId);

    /** 原书文件路径（供 F3 引用溯源"点击页码看原文"，软删书返回 404） */
    Path resolvePdf(String bookId);

    /** 恢复（回收站还原） */
    Book restore(String bookId);

    /** 永久删除（清 Chroma + 记录 + PDF） */
    void purge(String bookId);

    /** 回收站列表（含 remaining_hours） */
    List<Map<String, Object>> trash();

    /** 重建知识库（可指定切片粒度） */
    Book rebuild(String bookId, Integer chunkSize, Integer overlap);
}
