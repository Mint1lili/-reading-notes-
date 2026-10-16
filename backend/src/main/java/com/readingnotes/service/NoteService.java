package com.readingnotes.service;

import com.readingnotes.entity.Note;

import java.util.List;

/**
 * 笔记服务（F4）——接口定义，实现见 NoteServiceImpl
 * 第一阶段（CRUD）：新建 / 列表 / 详情 / 编辑 / 软删 / 恢复 / 永久删除
 */
public interface NoteService {

    /** 新建笔记，返回已落库的完整实体（含生成的 note_id/时间/版本号） */
    Note create(Note note);

    /** 笔记列表（分页 + 可选过滤；tagName 按标签名过滤，阶段 2） */
    List<Note> list(String sourceBookId, String sourceType, String keyword,
                    String tagName, int page, int pageSize);

    /** 笔记总数（与 list 同过滤条件） */
    long count(String sourceBookId, String sourceType, String keyword, String tagName);

    /** 笔记详情（未软删；不存在抛 50001） */
    Note detail(String noteId);

    /**
     * 编辑笔记（乐观锁：expectedVersion 必须等于库中当前版本，否则抛 50004）
     *
     * @return 更新后的笔记（版本已 +1）
     */
    Note update(String noteId, String title, String content, int expectedVersion);

    /** 软删除（回收站），已删/不存在抛 50001 */
    void delete(String noteId);

    /** 恢复软删笔记；不存在抛 50001 */
    void restore(String noteId);

    /** 永久删除；不存在抛 50001 */
    void purge(String noteId);
}
