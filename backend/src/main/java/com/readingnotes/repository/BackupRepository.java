package com.readingnotes.repository;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 备份元数据访问层
 * 表：backups（见 Migration 0001 §15）
 */
@Slf4j
@Repository
public class BackupRepository {

    private final JdbcTemplate jdbc;

    public BackupRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 数据库实体 */
    public static class BackupRecord {
        public String backupId;
        public String backupType;
        public String trigger;
        public String filePath;
        public long fileSize;
        public String sha256;
        public String includedObjectsJson;
        public String startedAt;
        public String finishedAt;
        public String status;   // RUNNING / SUCCESS / FAILED
        public String errorMessage;
    }

    /** 查询备份列表（按 started_at DESC） */
    public List<BackupRecord> list() {
        return jdbc.query("SELECT * FROM backups ORDER BY started_at DESC", (rs, i) -> {
            BackupRecord r = new BackupRecord();
            r.backupId = rs.getString("backup_id");
            r.backupType = rs.getString("backup_type");
            r.trigger = rs.getString("trigger");
            r.filePath = rs.getString("file_path");
            r.fileSize = rs.getLong("file_size");
            r.sha256 = rs.getString("sha256");
            r.includedObjectsJson = rs.getString("included_objects_json");
            r.startedAt = rs.getString("started_at");
            r.finishedAt = rs.getString("finished_at");
            r.status = rs.getString("status");
            r.errorMessage = rs.getString("error_message");
            return r;
        });
    }

    /** 按 backup_id 查询 */
    public BackupRecord findById(String backupId) {
        List<BackupRecord> list = jdbc.query(
                "SELECT * FROM backups WHERE backup_id = ?",
                (rs, i) -> {
                    BackupRecord r = new BackupRecord();
                    r.backupId = rs.getString("backup_id");
                    r.backupType = rs.getString("backup_type");
                    r.trigger = rs.getString("trigger");
                    r.filePath = rs.getString("file_path");
                    r.fileSize = rs.getLong("file_size");
                    r.sha256 = rs.getString("sha256");
                    r.includedObjectsJson = rs.getString("included_objects_json");
                    r.startedAt = rs.getString("started_at");
                    r.finishedAt = rs.getString("finished_at");
                    r.status = rs.getString("status");
                    r.errorMessage = rs.getString("error_message");
                    return r;
                }, backupId);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 插入一条 RUNNING 状态的备份记录 */
    public void insert(BackupRecord r) {
        jdbc.update("""
                INSERT INTO backups (backup_id, backup_type, trigger, file_path, file_size, sha256,
                                     included_objects_json, started_at, finished_at, status, error_message)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                r.backupId, r.backupType, r.trigger, r.filePath, r.fileSize, r.sha256,
                r.includedObjectsJson, r.startedAt, r.finishedAt, r.status, r.errorMessage);
    }

    /** 更新备份状态为 SUCCESS/FAILED，填写 finished_at 和 sha256 */
    public void updateStatus(String backupId, String status, String finishedAt,
                              long fileSize, String sha256, String errorMessage) {
        jdbc.update("""
                UPDATE backups
                   SET status = ?, finished_at = ?, file_size = ?, sha256 = ?, error_message = ?
                 WHERE backup_id = ?
                """,
                status, finishedAt, fileSize, sha256, errorMessage, backupId);
    }
}
