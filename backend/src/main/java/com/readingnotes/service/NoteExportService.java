package com.readingnotes.service;

import java.util.List;
import java.util.Map;

/**
 * 笔记导出服务（API 文档 §9.11）
 *
 * <p>把笔记渲染为 Markdown 文件并打包成 zip 落盘，返回下载地址。
 * 支持两种范围：selected（按 note_ids）/ book（按 book_id 导出整本书笔记）。</p>
 */
public interface NoteExportService {

    /**
     * 导出笔记
     *
     * @param noteIds       scope=selected 时的目标笔记（可空）
     * @param bookId        scope=book 时的来源书（可空）
     * @param scope         selected / book
     * @param format        markdown / pdf（pdf 本期未实现）
     * @param includeFrontMatter 是否在每个 md 头部写 YAML front-matter
     * @return 下载信息：download_url / file_name / file_size / expires_at / note_count
     */
    Map<String, Object> export(List<String> noteIds, String bookId, String scope,
                               String format, boolean includeFrontMatter);
}
