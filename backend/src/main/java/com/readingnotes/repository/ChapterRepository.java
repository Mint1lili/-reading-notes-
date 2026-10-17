package com.readingnotes.repository;

import com.readingnotes.entity.Chapter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 章节数据访问（chapters 表）
 */
@Slf4j
@Repository
public class ChapterRepository {

    private final JdbcTemplate jdbc;

    public ChapterRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static Integer getInt(java.sql.ResultSet rs, String col) throws java.sql.SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }

    private static final RowMapper<Chapter> MAPPER = (rs, i) -> {
        Chapter c = new Chapter();
        c.setChapterId(rs.getString("chapter_id"));
        c.setBookId(rs.getString("book_id"));
        c.setChapterPath(rs.getString("chapter_path"));
        c.setTitle(rs.getString("title"));
        c.setLevel(rs.getInt("level"));
        c.setStartPage(rs.getInt("start_page"));
        c.setEndPage(getInt(rs, "end_page"));
        c.setTextOffset(rs.getInt("text_offset"));
        c.setWordCount(getInt(rs, "word_count"));
        return c;
    };

    /** 批量写入（重建前先 deleteByBook） */
    public void batchInsert(List<Chapter> chapters) {
        if (chapters == null || chapters.isEmpty()) {
            return;
        }
        List<Object[]> args = chapters.stream().map(c -> new Object[]{
                c.getChapterId(), c.getBookId(), c.getChapterPath(), c.getTitle(), c.getLevel(),
                c.getStartPage(), c.getEndPage(), c.getTextOffset() == null ? 0 : c.getTextOffset(),
                c.getWordCount()
        }).toList();
        jdbc.batchUpdate("""
                INSERT INTO chapters (chapter_id, book_id, chapter_path, title, level,
                                      start_page, end_page, text_offset, word_count)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, args);
    }

    public List<Chapter> listByBook(String bookId) {
        return jdbc.query(
                "SELECT * FROM chapters WHERE book_id = ? ORDER BY text_offset ASC", MAPPER, bookId);
    }

    public void deleteByBook(String bookId) {
        jdbc.update("DELETE FROM chapters WHERE book_id = ?", bookId);
    }
}
