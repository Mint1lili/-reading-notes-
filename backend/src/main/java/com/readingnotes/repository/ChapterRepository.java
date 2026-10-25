package com.readingnotes.repository;

import com.readingnotes.entity.Chapter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * 章节数据访问（chapters 表）。
 * <p>
 * 章节以扁平列表存储，查询按 {@code text_offset} 升序返回，调用方据此还原阅读顺序；
 * 重建时先 {@link #deleteByBook} 再批量写入，不做增量比对。
 */
@Slf4j
@Repository
public class ChapterRepository {

    private final JdbcTemplate jdbc;

    public ChapterRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 可空整数列的安全读取：getInt 返回 0 无法区分 NULL，需配合 wasNull */
    private static Integer getInt(ResultSet rs, String col) throws SQLException {
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

    /** 按阅读顺序取本书全部章节 */
    public List<Chapter> listByBook(String bookId) {
        return jdbc.query(
                "SELECT * FROM chapters WHERE book_id = ? ORDER BY text_offset ASC", MAPPER, bookId);
    }

    /** 删除本书全部章节（重建/删除书籍前调用） */
    public void deleteByBook(String bookId) {
        jdbc.update("DELETE FROM chapters WHERE book_id = ?", bookId);
    }
}
