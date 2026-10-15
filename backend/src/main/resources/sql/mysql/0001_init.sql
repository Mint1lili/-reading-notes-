-- ============================================================================
-- Migration 0001 (MySQL 8.0+): Initial Schema
-- System : 阅读笔记系统 / Personal Reading Notes
-- Version: V1.0  (对应《数据库设计说明书 V1.0》)
-- Date   : 2026-10-15
-- Target : MySQL 8.0+ / InnoDB / utf8mb4
-- 包含   : 18 张业务表 + schema_migrations + 全部索引 + CHECK 约束
-- 说明   : 由 SQLite 版迁移而来；TEXT 主键/外键/索引列改为 VARCHAR(64)，
--          部分索引(WITH WHERE)改为普通索引，REAL 改为 DOUBLE，
--          INSERT OR IGNORE 改为 INSERT IGNORE
-- ============================================================================

-- ---------- 迁移版本跟踪表 ---------
-- 建表期间临时关闭外键检查：chunks→notes、notes→ai_versions 存在前向引用/循环依赖，
-- MySQL 要求被引用表已存在，故统一先关检查、建完再开（mysqldump 同款做法）
SET FOREIGN_KEY_CHECKS = 0;

CREATE TABLE IF NOT EXISTS schema_migrations (
    version    INT     PRIMARY KEY,
    name       VARCHAR(128) NOT NULL,
    applied_at VARCHAR(32)  NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================================
-- 1. books  书籍主表
-- ============================================================================
CREATE TABLE IF NOT EXISTS books (
    book_id                  VARCHAR(64) PRIMARY KEY,
    title                    VARCHAR(512) NOT NULL,
    author                   VARCHAR(128),
    file_path                VARCHAR(1024) NOT NULL,
    file_size                BIGINT NOT NULL,
    total_pages              INT,
    status                   VARCHAR(16) NOT NULL DEFAULT 'UPLOADING',
    build_sub_stage          VARCHAR(16),
    build_progress           INT NOT NULL DEFAULT 0,
    error_code               VARCHAR(64),
    error_message            VARCHAR(1024),
    chunk_count              INT,
    chapter_recognized_rate  DOUBLE,
    embedding_model          VARCHAR(128),
    embedding_dim            INT,
    created_at               VARCHAR(32) NOT NULL,
    updated_at               VARCHAR(32) NOT NULL,
    last_ready_at            VARCHAR(32),
    soft_delete              INT NOT NULL DEFAULT 0,
    deleted_at               VARCHAR(32),
    purge_at                 VARCHAR(32),
    CHECK (status IN ('UPLOADING','BUILDING','READY','REBUILDING',
                      'DELETING','DELETED','PURGED','CANCELLED','FAILED')),
    CHECK (build_sub_stage IN ('PARSE','RECOGNIZE','CHUNK','EMBED','INGEST')
           OR build_sub_stage IS NULL),
    CHECK (build_progress >= 0 AND build_progress <= 100),
    CHECK (soft_delete IN (0,1)),
    CHECK (chunk_count IS NULL OR chunk_count >= 0),
    CHECK (chapter_recognized_rate IS NULL
           OR (chapter_recognized_rate >= 0.0 AND chapter_recognized_rate <= 1.0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_books_status
    ON books(status);
CREATE INDEX idx_books_purge_at
    ON books(purge_at);
CREATE INDEX idx_books_created_at
    ON books(created_at DESC);

-- ============================================================================
-- 2. chapters  章节结构
-- ============================================================================
CREATE TABLE IF NOT EXISTS chapters (
    chapter_id    VARCHAR(64) PRIMARY KEY,
    book_id       VARCHAR(64) NOT NULL,
    chapter_path  VARCHAR(512) NOT NULL,
    title         VARCHAR(512) NOT NULL,
    level         INT NOT NULL,
    start_page    INT NOT NULL,
    end_page      INT,
    text_offset   INT NOT NULL,
    word_count    INT,
    FOREIGN KEY (book_id) REFERENCES books(book_id) ON DELETE RESTRICT,
    CHECK (level IN (1, 2, 3))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_chapters_book_path
    ON chapters(book_id, chapter_path);
CREATE INDEX idx_chapters_book_level
    ON chapters(book_id, level);

-- ============================================================================
-- 3. chunks  通用切片（原书/note 统一表，靠 chunk_type 区分）
-- ============================================================================
CREATE TABLE IF NOT EXISTS chunks (
    chunk_id           VARCHAR(64) PRIMARY KEY,
    book_id            VARCHAR(64),
    note_id            VARCHAR(64),
    chunk_type         VARCHAR(16) NOT NULL,
    content            TEXT NOT NULL,
    page               INT,
    chapter_path       VARCHAR(512),
    chapter_title      VARCHAR(512),
    position           INT NOT NULL,
    word_count         INT,
    embedding_status   VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    embedding_batch_id INT,
    vector_id          VARCHAR(64),
    created_at         VARCHAR(32) NOT NULL,
    FOREIGN KEY (book_id) REFERENCES books(book_id) ON DELETE RESTRICT,
    FOREIGN KEY (note_id) REFERENCES notes(note_id) ON DELETE RESTRICT,
    CHECK (chunk_type IN ('book','note')),
    CHECK (embedding_status IN ('PENDING','DONE','FAILED')),
    CHECK (position >= 0),
    CHECK (
        (chunk_type='book'  AND book_id IS NOT NULL AND note_id IS NULL)
        OR
        (chunk_type='note' AND note_id IS NOT NULL AND book_id IS NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_chunks_book
    ON chunks(book_id, position);
CREATE INDEX idx_chunks_note
    ON chunks(note_id, position);
CREATE INDEX idx_chunks_batch
    ON chunks(embedding_batch_id);
CREATE INDEX idx_chunks_pending
    ON chunks(embedding_status);
CREATE INDEX idx_chunks_chapter
    ON chunks(book_id, chapter_path);

-- ============================================================================
-- 4. notes  笔记主表
-- ============================================================================
CREATE TABLE IF NOT EXISTS notes (
    note_id              VARCHAR(64) PRIMARY KEY,
    title                VARCHAR(512),
    content              TEXT NOT NULL,
    source_type          VARCHAR(24) NOT NULL DEFAULT 'MANUAL',
    source_book_id       VARCHAR(64),
    source_page          INT,
    source_chunk_id      VARCHAR(64),
    source_ai_version_id VARCHAR(64),
    book_owner_id        VARCHAR(64),
    is_indexed           INT NOT NULL DEFAULT 0,
    index_status         VARCHAR(24) NOT NULL DEFAULT 'NOT_INDEXED',
    last_index_version   VARCHAR(64),
    word_count           INT,
    editor_version       INT NOT NULL DEFAULT 1,
    soft_delete          INT NOT NULL DEFAULT 0,
    deleted_at           VARCHAR(32),
    created_at           VARCHAR(32) NOT NULL,
    updated_at           VARCHAR(32) NOT NULL,
    FOREIGN KEY (source_book_id)       REFERENCES books(book_id)       ON DELETE RESTRICT,
    FOREIGN KEY (source_chunk_id)      REFERENCES chunks(chunk_id)     ON DELETE RESTRICT,
    FOREIGN KEY (source_ai_version_id) REFERENCES ai_versions(version_id) ON DELETE RESTRICT,
    CHECK (source_type IN ('MANUAL','AI_SUMMARY','AI_QA','AI_MINDMAP','BOOK_EXCERPT')),
    CHECK (index_status IN ('NOT_INDEXED','INDEXING','INDEXED','INDEX_FAILED')),
    CHECK (is_indexed IN (0,1)),
    CHECK (soft_delete IN (0,1)),
    CHECK (editor_version >= 1),
    CHECK (
        (is_indexed = 1 AND index_status = 'INDEXED')
        OR (is_indexed = 0)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_notes_book
    ON notes(source_book_id);
CREATE INDEX idx_notes_indexed
    ON notes(is_indexed);
CREATE INDEX idx_notes_created
    ON notes(created_at DESC);
CREATE INDEX idx_notes_source_type
    ON notes(source_type);
CREATE INDEX idx_notes_deleted_at
    ON notes(deleted_at);

-- ============================================================================
-- 5. tags  标签字典
-- ============================================================================
CREATE TABLE IF NOT EXISTS tags (
    tag_id     VARCHAR(64) PRIMARY KEY,
    name       VARCHAR(64) NOT NULL,
    color      VARCHAR(16),
    created_at VARCHAR(32) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE UNIQUE INDEX idx_tags_name ON tags(name);

-- ============================================================================
-- 6. note_tag_map 笔记-标签多对多
-- ============================================================================
CREATE TABLE IF NOT EXISTS note_tag_map (
    note_id    VARCHAR(64) NOT NULL,
    tag_id     VARCHAR(64) NOT NULL,
    created_at VARCHAR(32) NOT NULL,
    PRIMARY KEY (note_id, tag_id),
    FOREIGN KEY (note_id) REFERENCES notes(note_id) ON DELETE RESTRICT,
    FOREIGN KEY (tag_id)  REFERENCES tags(tag_id)   ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_ntm_tag ON note_tag_map(tag_id);

-- ============================================================================
-- 7. note_links  笔记双向链接
-- ============================================================================
CREATE TABLE IF NOT EXISTS note_links (
    link_id           VARCHAR(64) PRIMARY KEY,
    source_note_id    VARCHAR(64) NOT NULL,
    target_note_id    VARCHAR(64) NOT NULL,
    context           VARCHAR(512),
    target_jumpable   INT NOT NULL DEFAULT 1,
    created_at        VARCHAR(32) NOT NULL,
    FOREIGN KEY (source_note_id) REFERENCES notes(note_id) ON DELETE RESTRICT,
    FOREIGN KEY (target_note_id) REFERENCES notes(note_id) ON DELETE RESTRICT,
    CHECK (target_jumpable IN (0,1)),
    UNIQUE (source_note_id, target_note_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_links_source ON note_links(source_note_id);
CREATE INDEX idx_links_target ON note_links(target_note_id);

-- ============================================================================
-- 8. note_feedback  点踩/反馈
-- ============================================================================
CREATE TABLE IF NOT EXISTS note_feedback (
    feedback_id  VARCHAR(64) PRIMARY KEY,
    target_type  VARCHAR(16) NOT NULL,
    target_id    VARCHAR(64) NOT NULL,
    vote         INT NOT NULL,
    reason       VARCHAR(512),
    comment      VARCHAR(1024),
    created_at   VARCHAR(32) NOT NULL,
    CHECK (target_type IN ('ai_version','note')),
    CHECK (vote IN (1, -1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_feedback_target
    ON note_feedback(target_type, target_id);

-- ============================================================================
-- 9. ai_tasks  AI 异步任务
-- ============================================================================
CREATE TABLE IF NOT EXISTS ai_tasks (
    task_id        VARCHAR(64) PRIMARY KEY,
    task_type      VARCHAR(16) NOT NULL,
    target_type    VARCHAR(16),
    target_id      VARCHAR(64),
    parent_task_id VARCHAR(64),
    status         VARCHAR(16) NOT NULL DEFAULT 'QUEUED',
    params_json    TEXT,
    result_json    TEXT,
    error_message  VARCHAR(1024),
    retry_count    INT NOT NULL DEFAULT 0,
    created_at     VARCHAR(32) NOT NULL,
    started_at     VARCHAR(32),
    finished_at    VARCHAR(32),
    trace_id       VARCHAR(64),
    FOREIGN KEY (parent_task_id) REFERENCES ai_tasks(task_id) ON DELETE RESTRICT,
    CHECK (task_type IN ('SUMMARY','QA','MINDMAP','EMBED_BOOK','EMBED_NOTE','EVAL')),
    CHECK (target_type IN ('book','note','session','dataset')
           OR target_type IS NULL),
    CHECK (status IN ('QUEUED','RUNNING','STREAMING','COMPLETED','FAILED','CANCELLED')),
    CHECK (retry_count >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_ai_tasks_status
    ON ai_tasks(status);
CREATE INDEX idx_ai_tasks_target
    ON ai_tasks(target_type, target_id);
CREATE INDEX idx_ai_tasks_parent
    ON ai_tasks(parent_task_id);
CREATE INDEX idx_ai_tasks_trace
    ON ai_tasks(trace_id);
CREATE INDEX idx_ai_tasks_created
    ON ai_tasks(created_at DESC);

-- ============================================================================
-- 10. ai_versions  AI 产物多版本
-- ============================================================================
CREATE TABLE IF NOT EXISTS ai_versions (
    version_id            VARCHAR(64) PRIMARY KEY,
    task_id               VARCHAR(64) NOT NULL,
    target_type           VARCHAR(16) NOT NULL,
    target_id             VARCHAR(64) NOT NULL,
    artifact_type         VARCHAR(16) NOT NULL,
    version_number        INT NOT NULL,
    is_active             INT NOT NULL DEFAULT 0,
    content               TEXT NOT NULL,
    citations_json        TEXT,
    params_snapshot_json  TEXT,
    prompt_version_id     VARCHAR(64),
    created_at            VARCHAR(32) NOT NULL,
    FOREIGN KEY (task_id) REFERENCES ai_tasks(task_id) ON DELETE RESTRICT,
    CHECK (target_type IN ('book','note','session')),
    CHECK (artifact_type IN ('SUMMARY','ANSWER','MINDMAP')),
    CHECK (is_active IN (0,1)),
    CHECK (version_number >= 1),
    UNIQUE (target_type, target_id, artifact_type, version_number)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE UNIQUE INDEX idx_aiver_target_active
    ON ai_versions(target_type, target_id, artifact_type);
CREATE INDEX idx_aiver_task
    ON ai_versions(task_id);

-- ============================================================================
-- 11. chat_sessions  多轮对话会话
-- ============================================================================
CREATE TABLE IF NOT EXISTS chat_sessions (
    session_id  VARCHAR(64) PRIMARY KEY,
    scope       VARCHAR(64) NOT NULL,
    book_id     VARCHAR(64),
    title       VARCHAR(512),
    created_at  VARCHAR(32) NOT NULL,
    updated_at  VARCHAR(32) NOT NULL,
    FOREIGN KEY (book_id) REFERENCES books(book_id) ON DELETE RESTRICT,
    CHECK (scope LIKE 'book:%' OR scope = 'cross' OR scope LIKE 'note:%')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_chat_scope_book
    ON chat_sessions(scope, book_id, updated_at DESC);
CREATE INDEX idx_chat_updated
    ON chat_sessions(updated_at DESC);

-- ============================================================================
-- 12. chat_messages  多轮对话消息
-- ============================================================================
CREATE TABLE IF NOT EXISTS chat_messages (
    message_id          VARCHAR(64) PRIMARY KEY,
    session_id          VARCHAR(64) NOT NULL,
    role                VARCHAR(16) NOT NULL,
    content             TEXT NOT NULL,
    cited_chunks_json   TEXT,
    token_usage_json    TEXT,
    feedback_id         VARCHAR(64),
    created_at          VARCHAR(32) NOT NULL,
    FOREIGN KEY (session_id)  REFERENCES chat_sessions(session_id)    ON DELETE RESTRICT,
    FOREIGN KEY (feedback_id) REFERENCES note_feedback(feedback_id)   ON DELETE RESTRICT,
    CHECK (role IN ('user','assistant','system'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_msg_session_seq
    ON chat_messages(session_id, created_at);
CREATE INDEX idx_msg_feedback
    ON chat_messages(feedback_id);

-- ============================================================================
-- 13. build_progress  构建进度与断点
-- ============================================================================
CREATE TABLE IF NOT EXISTS build_progress (
    book_id                  VARCHAR(64) PRIMARY KEY,
    last_completed_batch     INT NOT NULL DEFAULT 0,
    total_batches            INT,
    chunk_count_planned      INT,
    chunk_count_actual       INT,
    failed_batches_json      TEXT,
    chapter_recognized_rate  DOUBLE,
    build_started_at         VARCHAR(32),
    last_updated_at          VARCHAR(32) NOT NULL,
    FOREIGN KEY (book_id) REFERENCES books(book_id) ON DELETE RESTRICT,
    CHECK (last_completed_batch >= 0),
    CHECK (chapter_recognized_rate IS NULL
           OR (chapter_recognized_rate >= 0.0 AND chapter_recognized_rate <= 1.0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================================
-- 14. prompt_versions  Prompt 模板多版本
-- ============================================================================
CREATE TABLE IF NOT EXISTS prompt_versions (
    name        VARCHAR(64) NOT NULL,
    version     INT NOT NULL,
    content     TEXT NOT NULL,
    is_active   INT NOT NULL DEFAULT 0,
    author      VARCHAR(128),
    description VARCHAR(512),
    created_at  VARCHAR(32) NOT NULL,
    PRIMARY KEY (name, version),
    CHECK (is_active IN (0,1)),
    CHECK (version >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE UNIQUE INDEX idx_prompt_active
    ON prompt_versions(name);
CREATE INDEX idx_prompt_name_created
    ON prompt_versions(name, version DESC);

-- ============================================================================
-- 15. backups  备份元信息
-- ============================================================================
CREATE TABLE IF NOT EXISTS backups (
    backup_id              VARCHAR(64) PRIMARY KEY,
    backup_type            VARCHAR(16) NOT NULL,
    trigger_source         VARCHAR(24) NOT NULL,
    file_path              VARCHAR(1024) NOT NULL,
    file_size              BIGINT NOT NULL,
    sha256                 VARCHAR(64) NOT NULL,
    included_objects_json  TEXT NOT NULL,
    started_at             VARCHAR(32) NOT NULL,
    finished_at            VARCHAR(32),
    status                 VARCHAR(16) NOT NULL DEFAULT 'RUNNING',
    error_message          VARCHAR(1024),
    CHECK (backup_type IN ('auto','manual')),
    CHECK (trigger_source IN ('scheduled','manual_click','pre_upgrade')),
    CHECK (status IN ('RUNNING','SUCCESS','FAILED')),
    CHECK (file_size >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_backup_created
    ON backups(started_at DESC);
CREATE INDEX idx_backup_type_created
    ON backups(backup_type, started_at DESC);

-- ============================================================================
-- 16. usage_logs  API 用量统计
-- ============================================================================
CREATE TABLE IF NOT EXISTS usage_logs (
    usage_id       VARCHAR(64) PRIMARY KEY,
    log_date       VARCHAR(16) NOT NULL,
    api_type       VARCHAR(16) NOT NULL,
    feature        VARCHAR(32) NOT NULL,
    book_id        VARCHAR(64),
    note_id        VARCHAR(64),
    input_tokens   INT NOT NULL DEFAULT 0,
    output_tokens  INT NOT NULL DEFAULT 0,
    total_tokens   INT NOT NULL DEFAULT 0,
    cost_cny       DOUBLE NOT NULL DEFAULT 0.0,
    latency_ms     INT NOT NULL,
    status         VARCHAR(16) NOT NULL DEFAULT 'SUCCESS',
    created_at     VARCHAR(32) NOT NULL,
    FOREIGN KEY (book_id) REFERENCES books(book_id) ON DELETE RESTRICT,
    FOREIGN KEY (note_id) REFERENCES notes(note_id) ON DELETE RESTRICT,
    CHECK (api_type IN ('embedding','llm_chat','llm_stream','rerank')),
    CHECK (status IN ('SUCCESS','FAILURE')),
    CHECK (input_tokens >= 0 AND output_tokens >= 0 AND total_tokens >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_usage_date_feature
    ON usage_logs(log_date, feature);
CREATE INDEX idx_usage_created
    ON usage_logs(created_at);
CREATE INDEX idx_usage_feature
    ON usage_logs(feature);

-- ============================================================================
-- 17. eval_datasets  评测数据集
-- ============================================================================
CREATE TABLE IF NOT EXISTS eval_datasets (
    item_id                       VARCHAR(64) PRIMARY KEY,
    category                      VARCHAR(24) NOT NULL,
    book_id                       VARCHAR(64),
    question                      TEXT NOT NULL,
    ground_truth_answer           TEXT NOT NULL,
    expected_chunk_ids_json       TEXT,
    expected_citation_pages_json  TEXT,
    difficulty                    VARCHAR(16) NOT NULL DEFAULT 'EASY',
    created_at                    VARCHAR(32) NOT NULL,
    FOREIGN KEY (book_id) REFERENCES books(book_id) ON DELETE RESTRICT,
    CHECK (category IN ('book_qa_simple','book_qa_located',
                        'book_out_of_scope','cross_book_qa','note_qa')),
    CHECK (difficulty IN ('EASY','MEDIUM','HARD'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_eval_category
    ON eval_datasets(category);
CREATE INDEX idx_eval_book
    ON eval_datasets(book_id);

-- ============================================================================
-- 18. eval_results  评测结果报告
-- ============================================================================
CREATE TABLE IF NOT EXISTS eval_results (
    eval_run_id          VARCHAR(64) PRIMARY KEY,
    started_at           VARCHAR(32) NOT NULL,
    finished_at          VARCHAR(32),
    dataset_version      VARCHAR(64) NOT NULL,
    total_items          INT NOT NULL,
    pass_items           INT NOT NULL DEFAULT 0,
    recall_at_5          DOUBLE NOT NULL DEFAULT 0.0,
    recall_at_10         DOUBLE NOT NULL DEFAULT 0.0,
    answer_accuracy      DOUBLE NOT NULL DEFAULT 0.0,
    refusal_accuracy     DOUBLE NOT NULL DEFAULT 0.0,
    citation_accuracy    DOUBLE NOT NULL DEFAULT 0.0,
    avg_first_token_ms   INT NOT NULL DEFAULT 0,
    avg_full_answer_ms   INT NOT NULL DEFAULT 0,
    fail_items_json      TEXT,
    report_path          VARCHAR(1024),
    CHECK (total_items >= 0),
    CHECK (pass_items  >= 0 AND pass_items <= total_items),
    CHECK (recall_at_5  BETWEEN 0.0 AND 1.0),
    CHECK (recall_at_10 BETWEEN 0.0 AND 1.0),
    CHECK (answer_accuracy  BETWEEN 0.0 AND 1.0),
    CHECK (refusal_accuracy BETWEEN 0.0 AND 1.0),
    CHECK (citation_accuracy BETWEEN 0.0 AND 1.0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_eval_results_started
    ON eval_results(started_at DESC);

-- ---------- 记录本次迁移 ----------
INSERT IGNORE INTO schema_migrations(version, name, applied_at) VALUES
    (1, 'init', '2026-10-15T00:00:00.000Z');

-- ---------- 恢复外键检查（建表完成） ----------
SET FOREIGN_KEY_CHECKS = 1;

-- ============================================================================
-- 验证脚本（可选，运行后请 CHECK TABLE）
-- ============================================================================
SELECT 'books'           AS table_name, COUNT(*) AS row_count FROM books
UNION ALL SELECT 'chapters',       COUNT(*) FROM chapters
UNION ALL SELECT 'chunks',         COUNT(*) FROM chunks
UNION ALL SELECT 'notes',          COUNT(*) FROM notes
UNION ALL SELECT 'tags',           COUNT(*) FROM tags
UNION ALL SELECT 'note_tag_map',   COUNT(*) FROM note_tag_map
UNION ALL SELECT 'note_links',     COUNT(*) FROM note_links
UNION ALL SELECT 'note_feedback',  COUNT(*) FROM note_feedback
UNION ALL SELECT 'ai_tasks',       COUNT(*) FROM ai_tasks
UNION ALL SELECT 'ai_versions',    COUNT(*) FROM ai_versions
UNION ALL SELECT 'chat_sessions',  COUNT(*) FROM chat_sessions
UNION ALL SELECT 'chat_messages',  COUNT(*) FROM chat_messages
UNION ALL SELECT 'build_progress', COUNT(*) FROM build_progress
UNION ALL SELECT 'prompt_versions',COUNT(*) FROM prompt_versions
UNION ALL SELECT 'backups',        COUNT(*) FROM backups
UNION ALL SELECT 'usage_logs',     COUNT(*) FROM usage_logs
UNION ALL SELECT 'eval_datasets',  COUNT(*) FROM eval_datasets
UNION ALL SELECT 'eval_results',   COUNT(*) FROM eval_results;
