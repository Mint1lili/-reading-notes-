package com.readingnotes.service;

import com.readingnotes.entity.Book;

import java.util.Map;

/**
 * 知识库构建服务（F2 查询与控制类接口，API 文档 §6）
 * <p>
 * 仅暴露"读状态 / 控制"能力；真正的构建执行在 {@link com.readingnotes.build.BuildTaskService}，
 * 本接口的返回值统一为可直接序列化的 Map，避免为每个视图单独建 DTO。
 */
public interface BuildService {

    /**
     * 构建状态（6.1）
     *
     * @param bookId 书籍 id
     * @return 包含 status / build_progress / build_sub_stage / last_completed_batch 等字段的视图
     */
    Map<String, Object> status(String bookId);

    /**
     * 重试构建（6.2，断点续传）
     *
     * @param bookId    书籍 id
     * @param fromBatch 从哪个批次开始重跑；为 null 时由服务端按已入库进度推算
     * @return 重试后的状态视图
     */
    Map<String, Object> retry(String bookId, Integer fromBatch);

    /**
     * 取消构建（6.3）
     *
     * @param bookId 书籍 id
     * @return 取消后的状态视图
     */
    Map<String, Object> cancel(String bookId);

    /** 切片预览（6.4） */
    Map<String, Object> chunks(String bookId, int page, int pageSize, String chapter);

    /** 章节结构（6.5） */
    Map<String, Object> chapters(String bookId);

    /** 切片预览统计（构建前展示：切片数/字数分布/章节识别率） */
    Map<String, Object> previewStats(Book book);
}
