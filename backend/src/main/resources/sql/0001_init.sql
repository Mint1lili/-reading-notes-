-- ============================================================================
-- Migration 0001: Initial Schema
-- System : 阅读笔记系统 / Personal Reading Notes
-- Version: V1.0  (对应《数据库设计说明书 V1.0》)
-- Date   : 2026-10-15
-- Target : SQLite 3.40+
-- 包含   : 18 张业务表 + schema_migrations + 全部索引 + CHECK 约束
-- ============================================================================

-- ---------- PRAGMA 初始化（每个连接只需执行一次；本脚本带头打开） ---------
PRAGMA foreign_keys = ON;
PRAGMA journal_mode = WAL;
PRAGMA synchronous = NORMAL;
PRAGMA busy_timeout = 5000;

-- ---------- 迁移版本跟踪表 ---------
CREATE TABLE IF NOT EXISTS schema_migrations (
    version    INTEGER PRIMARY KEY,
    name       TEXT    NOT NULL,
    applied_at TEXT    NOT NULL
);

-- ============================================================================
-- 1. books  书籍主表
-- ============================================================================
CREATE TABLE IF NOT EXISTS books (
    book_id                  TEXT    PRIMARY KEY,
    title                    TEXT    NOT NULL,
    author                   TEXT,
    file_path                TEXT    NOT NULL,
    file_size                INTEGER NOT NULL,
    total_pages              INTEGER,
    status                   TEXT    NOT NULL DEFAULT 'UPLOADING',
    build_sub_stage          TEXT,
    build_progress           INTEGER NOT NULL DEFAULT 0,
    error_code               TEXT,
    error_message            TEXT,
    chunk_count              INTEGER,
    chapter_recognized_rate  REAL,
    embedding_model          TEXT,
    embedding_dim            INTEGER,
    created_at               TEXT    NOT NULL,
    updated_at               TEXT    NOT NULL,
    last_ready_at            TEXT,
    soft_delete              INTEGER NOT NULL DEFAULT 0,
    deleted_at               TEXT,
    purge_at                 TEXT,
    CHECK (status IN ('UPLOADING','BUILDING','READY','REBUILDING',
                      'DELETING','DELETED','PURGED','CANCELLED','FAILED')),
    CHECK (build_sub_stage IN ('PARSE','RECOGNIZE','CHUNK','EMBED','INGEST')
           OR build_sub_stage IS NULL),
    CHECK (build_progress >= 0 AND build_progress <= 100),
    CHECK (soft_delete IN (0,1)),
    CHECK (chunk_count IS NULL OR chunk_count >= 0),
    CHECK (chapter_recognized_rate IS NULL
           OR (chapter_recognized_rate >= 0.0 AND chapter_recognized_rate <= 1.0))
);

CREATE INDEX IF NOT EXISTS idx_books_status
    ON books(status) WHERE soft_delete = 0;
CREATE INDEX IF NOT EXISTS idx_books_purge_at
    ON books(purge_at) WHERE soft_delete = 1;
CREATE INDEX IF NOT EXISTS idx_books_created_at
    ON books(created_at DESC);

-- ============================================================================
-- 2. chapters  章节结构
-- ============================================================================
CREATE TABLE IF NOT EXISTS chapters (
    chapter_id    TEXT    PRIMARY KEY,
    book_id       TEXT    NOT NULL,
    chapter_path  TEXT    NOT NULL,
    title         TEXT    NOT NULL,
    level         INTEGER NOT NULL,
    start_page    INTEGER NOT NULL,
    end_page      INTEGER,
    text_offset   INTEGER NOT NULL,
    word_count    INTEGER,
    FOREIGN KEY (book_id) REFERENCES books(book_id) ON DELETE RESTRICT,
    CHECK (level IN (1, 2, 3))
);

CREATE INDEX IF NOT EXISTS idx_chapters_book_path
    ON chapters(book_id, chapter_path);
CREATE INDEX IF NOT EXISTS idx_chapters_book_level
    ON chapters(book_id, level);

-- ============================================================================
-- 3. chunks  通用切片（原书/note 统一表，靠 chunk_type 区分）
-- ============================================================================
CREATE TABLE IF NOT EXISTS chunks (
    chunk_id           TEXT    PRIMARY KEY,
    book_id            TEXT,
    note_id            TEXT,
    chunk_type         TEXT    NOT NULL,
    content            TEXT    NOT NULL,
    page               INTEGER,
    chapter_path       TEXT,
    chapter_title      TEXT,
    position           INTEGER NOT NULL,
    word_count         INTEGER,
    embedding_status   TEXT    NOT NULL DEFAULT 'PENDING',
    embedding_batch_id INTEGER,
    vector_id          TEXT,
    created_at         TEXT    NOT NULL,
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
);

CREATE INDEX IF NOT EXISTS idx_chunks_book
    ON chunks(book_id, position);
CREATE INDEX IF NOT EXISTS idx_chunks_note
    ON chunks(note_id, position) WHERE chunk_type = 'note';
CREATE INDEX IF NOT EXISTS idx_chunks_batch
    ON chunks(embedding_batch_id) WHERE chunk_type = 'book';
CREATE INDEX IF NOT EXISTS idx_chunks_pending
    ON chunks(embedding_status) WHERE embedding_status = 'PENDING';
CREATE INDEX IF NOT EXISTS idx_chunks_chapter
    ON chunks(book_id, chapter_path) WHERE chunk_type = 'book';

-- ============================================================================
-- 4. notes  笔记主表
-- ============================================================================
CREATE TABLE IF NOT EXISTS notes (
    note_id              TEXT    PRIMARY KEY,
    title                TEXT,
    content              TEXT    NOT NULL,
    source_type          TEXT    NOT NULL DEFAULT 'MANUAL',
    source_book_id       TEXT,
    source_page          INTEGER,
    source_chunk_id      TEXT,
    source_ai_version_id TEXT,
    book_owner_id        TEXT,
    is_indexed           INTEGER NOT NULL DEFAULT 0,
    index_status         TEXT    NOT NULL DEFAULT 'NOT_INDEXED',
    last_index_version   TEXT,
    word_count           INTEGER,
    editor_version       INTEGER NOT NULL DEFAULT 1,
    soft_delete          INTEGER NOT NULL DEFAULT 0,
    deleted_at           TEXT,
    created_at           TEXT    NOT NULL,
    updated_at           TEXT    NOT NULL,
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
);

CREATE INDEX IF NOT EXISTS idx_notes_book
    ON notes(source_book_id);
CREATE INDEX IF NOT EXISTS idx_notes_indexed
    ON notes(is_indexed) WHERE soft_delete = 0;
CREATE INDEX IF NOT EXISTS idx_notes_created
    ON notes(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_notes_source_type
    ON notes(source_type);
CREATE INDEX IF NOT EXISTS idx_notes_deleted_at
    ON notes(deleted_at) WHERE soft_delete = 1;

-- ============================================================================
-- 5. tags  标签字典
-- ============================================================================
CREATE TABLE IF NOT EXISTS tags (
    tag_id     TEXT PRIMARY KEY,
    name       TEXT NOT NULL,
    color      TEXT,
    created_at TEXT NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_tags_name ON tags(name);

-- ============================================================================
-- 6. note_tag_map 笔记-标签多对多
-- ============================================================================
CREATE TABLE IF NOT EXISTS note_tag_map (
    note_id    TEXT NOT NULL,
    tag_id     TEXT NOT NULL,
    created_at TEXT NOT NULL,
    PRIMARY KEY (note_id, tag_id),
    FOREIGN KEY (note_id) REFERENCES notes(note_id) ON DELETE RESTRICT,
    FOREIGN KEY (tag_id)  REFERENCES tags(tag_id)   ON DELETE RESTRICT
);

CREATE INDEX IF NOT EXISTS idx_ntm_tag ON note_tag_map(tag_id);

-- ============================================================================
-- 7. note_links  笔记双向链接
-- ============================================================================
CREATE TABLE IF NOT EXISTS note_links (
    link_id           TEXT    PRIMARY KEY,
    source_note_id    TEXT    NOT NULL,
    target_note_id    TEXT    NOT NULL,
    context           TEXT,
    target_jumpable   INTEGER NOT NULL DEFAULT 1,
    created_at        TEXT    NOT NULL,
    FOREIGN KEY (source_note_id) REFERENCES notes(note_id) ON DELETE RESTRICT,
    FOREIGN KEY (target_note_id) REFERENCES notes(note_id) ON DELETE RESTRICT,
    CHECK (target_jumpable IN (0,1)),
    UNIQUE (source_note_id, target_note_id)
);

CREATE INDEX IF NOT EXISTS idx_links_source ON note_links(source_note_id);
CREATE INDEX IF NOT EXISTS idx_links_target ON note_links(target_note_id);

-- ============================================================================
-- 8. note_feedback  点踩/反馈
-- ============================================================================
CREATE TABLE IF NOT EXISTS note_feedback (
    feedback_id  TEXT    PRIMARY KEY,
    target_type  TEXT    NOT NULL,
    target_id    TEXT    NOT NULL,
    vote         INTEGER NOT NULL,
    reason       TEXT,
    comment      TEXT,
    created_at   TEXT    NOT NULL,
    CHECK (target_type IN ('ai_version','note')),
    CHECK (vote IN (1, -1))
);

CREATE INDEX IF NOT EXISTS idx_feedback_target
    ON note_feedback(target_type, target_id);

-- ============================================================================
-- 9. ai_tasks  AI 异步任务
-- ============================================================================
CREATE TABLE IF NOT EXISTS ai_tasks (
    task_id        TEXT    PRIMARY KEY,
    task_type      TEXT    NOT NULL,
    target_type    TEXT,
    target_id      TEXT,
    parent_task_id TEXT,
    status         TEXT    NOT NULL DEFAULT 'QUEUED',
    params_json    TEXT,
    result_json    TEXT,
    error_message  TEXT,
    retry_count    INTEGER NOT NULL DEFAULT 0,
    created_at     TEXT    NOT NULL,
    started_at     TEXT,
    finished_at    TEXT,
    trace_id       TEXT,
    FOREIGN KEY (parent_task_id) REFERENCES ai_tasks(task_id) ON DELETE RESTRICT,
    CHECK (task_type IN ('SUMMARY','QA','MINDMAP','EMBED_BOOK','EMBED_NOTE','EVAL')),
    CHECK (target_type IN ('book','note','session','dataset')
           OR target_type IS NULL),
    CHECK (status IN ('QUEUED','RUNNING','STREAMING','COMPLETED','FAILED','CANCELLED')),
    CHECK (retry_count >= 0)
);

CREATE INDEX IF NOT EXISTS idx_ai_tasks_status
    ON ai_tasks(status) WHERE status IN ('QUEUED','RUNNING','STREAMING');
CREATE INDEX IF NOT EXISTS idx_ai_tasks_target
    ON ai_tasks(target_type, target_id);
CREATE INDEX IF NOT EXISTS idx_ai_tasks_parent
    ON ai_tasks(parent_task_id);
CREATE INDEX IF NOT EXISTS idx_ai_tasks_trace
    ON ai_tasks(trace_id);
CREATE INDEX IF NOT EXISTS idx_ai_tasks_created
    ON ai_tasks(created_at DESC);

-- ============================================================================
-- 10. ai_versions  AI 产物多版本
-- ============================================================================
CREATE TABLE IF NOT EXISTS ai_versions (
    version_id            TEXT    PRIMARY KEY,
    task_id               TEXT    NOT NULL,
    target_type           TEXT    NOT NULL,
    target_id             TEXT    NOT NULL,
    artifact_type         TEXT    NOT NULL,
    version_number        INTEGER NOT NULL,
    is_active             INTEGER NOT NULL DEFAULT 0,
    content               TEXT    NOT NULL,
    citations_json        TEXT,
    params_snapshot_json  TEXT,
    prompt_version_id     TEXT,
    created_at            TEXT    NOT NULL,
    FOREIGN KEY (task_id) REFERENCES ai_tasks(task_id) ON DELETE RESTRICT,
    CHECK (target_type IN ('book','note','session')),
    CHECK (artifact_type IN ('SUMMARY','ANSWER','MINDMAP')),
    CHECK (is_active IN (0,1)),
    CHECK (version_number >= 1),
    UNIQUE (target_type, target_id, artifact_type, version_number)
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_aiver_target_active
    ON ai_versions(target_type, target_id, artifact_type)
    WHERE is_active = 1;
CREATE INDEX IF NOT EXISTS idx_aiver_task
    ON ai_versions(task_id);

-- ============================================================================
-- 11. chat_sessions  多轮对话会话
-- ============================================================================
CREATE TABLE IF NOT EXISTS chat_sessions (
    session_id  TEXT PRIMARY KEY,
    scope       TEXT NOT NULL,
    book_id     TEXT,
    title       TEXT,
    created_at  TEXT NOT NULL,
    updated_at  TEXT NOT NULL,
    FOREIGN KEY (book_id) REFERENCES books(book_id) ON DELETE RESTRICT,
    CHECK (scope LIKE 'book:%' OR scope = 'cross' OR scope LIKE 'note:%')
);

CREATE INDEX IF NOT EXISTS idx_chat_scope_book
    ON chat_sessions(scope, book_id, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_chat_updated
    ON chat_sessions(updated_at DESC);

-- ============================================================================
-- 12. chat_messages  多轮对话消息
-- ============================================================================
CREATE TABLE IF NOT EXISTS chat_messages (
    message_id          TEXT    PRIMARY KEY,
    session_id          TEXT    NOT NULL,
    role                TEXT    NOT NULL,
    content             TEXT    NOT NULL,
    cited_chunks_json   TEXT,
    token_usage_json    TEXT,
    feedback_id         TEXT,
    created_at          TEXT    NOT NULL,
    FOREIGN KEY (session_id)  REFERENCES chat_sessions(session_id)    ON DELETE RESTRICT,
    FOREIGN KEY (feedback_id) REFERENCES note_feedback(feedback_id)   ON DELETE RESTRICT,
    CHECK (role IN ('user','assistant','system'))
);

CREATE INDEX IF NOT EXISTS idx_msg_session_seq
    ON chat_messages(session_id, created_at);
CREATE INDEX IF NOT EXISTS idx_msg_feedback
    ON chat_messages(feedback_id) WHERE feedback_id IS NOT NULL;

-- ============================================================================
-- 13. build_progress  构建进度与断点
-- ============================================================================
CREATE TABLE IF NOT EXISTS build_progress (
    book_id                  TEXT    PRIMARY KEY,
    last_completed_batch     INTEGER NOT NULL DEFAULT 0,
    total_batches            INTEGER,
    chunk_count_planned      INTEGER,
    chunk_count_actual       INTEGER,
    failed_batches_json      TEXT,
    chapter_recognized_rate  REAL,
    build_started_at         TEXT,
    last_updated_at          TEXT    NOT NULL,
    FOREIGN KEY (book_id) REFERENCES books(book_id) ON DELETE RESTRICT,
    CHECK (last_completed_batch >= 0),
    CHECK (chapter_recognized_rate IS NULL
           OR (chapter_recognized_rate >= 0.0 AND chapter_recognized_rate <= 1.0))
);

-- ============================================================================
-- 14. prompt_versions  Prompt 模板多版本
-- ============================================================================
CREATE TABLE IF NOT EXISTS prompt_versions (
    name        TEXT    NOT NULL,
    version     INTEGER NOT NULL,
    content     TEXT    NOT NULL,
    is_active   INTEGER NOT NULL DEFAULT 0,
    author      TEXT,
    description TEXT,
    created_at  TEXT    NOT NULL,
    PRIMARY KEY (name, version),
    CHECK (is_active IN (0,1)),
    CHECK (version >= 1)
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_prompt_active
    ON prompt_versions(name) WHERE is_active = 1;
CREATE INDEX IF NOT EXISTS idx_prompt_name_created
    ON prompt_versions(name, version DESC);

-- ============================================================================
-- 15. backups  备份元信息
-- ============================================================================
CREATE TABLE IF NOT EXISTS backups (
    backup_id              TEXT    PRIMARY KEY,
    backup_type            TEXT    NOT NULL,
    trigger                TEXT    NOT NULL,
    file_path              TEXT    NOT NULL,
    file_size              INTEGER NOT NULL,
    sha256                 TEXT    NOT NULL,
    included_objects_json  TEXT    NOT NULL,
    started_at             TEXT    NOT NULL,
    finished_at            TEXT,
    status                 TEXT    NOT NULL DEFAULT 'RUNNING',
    error_message          TEXT,
    CHECK (backup_type IN ('auto','manual')),
    CHECK (trigger IN ('scheduled','manual_click','pre_upgrade')),
    CHECK (status IN ('RUNNING','SUCCESS','FAILED')),
    CHECK (file_size >= 0)
);

CREATE INDEX IF NOT EXISTS idx_backup_created
    ON backups(started_at DESC);
CREATE INDEX IF NOT EXISTS idx_backup_type_created
    ON backups(backup_type, started_at DESC);

-- ============================================================================
-- 16. usage_logs  API 用量统计
-- ============================================================================
CREATE TABLE IF NOT EXISTS usage_logs (
    usage_id       TEXT    PRIMARY KEY,
    log_date       TEXT    NOT NULL,
    api_type       TEXT    NOT NULL,
    feature        TEXT    NOT NULL,
    book_id        TEXT,
    note_id        TEXT,
    input_tokens   INTEGER NOT NULL DEFAULT 0,
    output_tokens  INTEGER NOT NULL DEFAULT 0,
    total_tokens   INTEGER NOT NULL DEFAULT 0,
    cost_cny       REAL    NOT NULL DEFAULT 0.0,
    latency_ms     INTEGER NOT NULL,
    status         TEXT    NOT NULL DEFAULT 'SUCCESS',
    created_at     TEXT    NOT NULL,
    FOREIGN KEY (book_id) REFERENCES books(book_id) ON DELETE RESTRICT,
    FOREIGN KEY (note_id) REFERENCES notes(note_id) ON DELETE RESTRICT,
    CHECK (api_type IN ('embedding','llm_chat','llm_stream','rerank')),
    CHECK (status IN ('SUCCESS','FAILURE')),
    CHECK (input_tokens >= 0 AND output_tokens >= 0 AND total_tokens >= 0)
);

CREATE INDEX IF NOT EXISTS idx_usage_date_feature
    ON usage_logs(log_date, feature);
CREATE INDEX IF NOT EXISTS idx_usage_created
    ON usage_logs(created_at);
CREATE INDEX IF NOT EXISTS idx_usage_feature
    ON usage_logs(feature);

-- ============================================================================
-- 17. eval_datasets  评测数据集
-- ============================================================================
CREATE TABLE IF NOT EXISTS eval_datasets (
    item_id                       TEXT    PRIMARY KEY,
    category                      TEXT    NOT NULL,
    book_id                       TEXT,
    question                      TEXT    NOT NULL,
    ground_truth_answer           TEXT    NOT NULL,
    expected_chunk_ids_json       TEXT,
    expected_citation_pages_json  TEXT,
    difficulty                    TEXT    NOT NULL DEFAULT 'EASY',
    created_at                    TEXT    NOT NULL,
    FOREIGN KEY (book_id) REFERENCES books(book_id) ON DELETE RESTRICT,
    CHECK (category IN ('book_qa_simple','book_qa_located',
                        'book_out_of_scope','cross_book_qa','note_qa')),
    CHECK (difficulty IN ('EASY','MEDIUM','HARD'))
);

CREATE INDEX IF NOT EXISTS idx_eval_category
    ON eval_datasets(category);
CREATE INDEX IF NOT EXISTS idx_eval_book
    ON eval_datasets(book_id) WHERE book_id IS NOT NULL;

-- ============================================================================
-- 18. eval_results  评测结果报告
-- ============================================================================
CREATE TABLE IF NOT EXISTS eval_results (
    eval_run_id          TEXT    PRIMARY KEY,
    started_at           TEXT    NOT NULL,
    finished_at          TEXT,
    dataset_version      TEXT    NOT NULL,
    total_items          INTEGER NOT NULL,
    pass_items           INTEGER NOT NULL DEFAULT 0,
    recall_at_5          REAL    NOT NULL DEFAULT 0.0,
    recall_at_10         REAL    NOT NULL DEFAULT 0.0,
    answer_accuracy      REAL    NOT NULL DEFAULT 0.0,
    refusal_accuracy     REAL    NOT NULL DEFAULT 0.0,
    citation_accuracy    REAL    NOT NULL DEFAULT 0.0,
    avg_first_token_ms   INTEGER NOT NULL DEFAULT 0,
    avg_full_answer_ms   INTEGER NOT NULL DEFAULT 0,
    fail_items_json      TEXT,
    report_path          TEXT,
    CHECK (total_items >= 0),
    CHECK (pass_items  >= 0 AND pass_items <= total_items),
    CHECK (recall_at_5  BETWEEN 0.0 AND 1.0),
    CHECK (recall_at_10 BETWEEN 0.0 AND 1.0),
    CHECK (answer_accuracy  BETWEEN 0.0 AND 1.0),
    CHECK (refusal_accuracy BETWEEN 0.0 AND 1.0),
    CHECK (citation_accuracy BETWEEN 0.0 AND 1.0)
);

CREATE INDEX IF NOT EXISTS idx_eval_results_started
    ON eval_results(started_at DESC);

-- ---------- 记录本次迁移 ----------
INSERT OR IGNORE INTO schema_migrations(version, name, applied_at) VALUES
    (1, 'init', '2026-10-15T00:00:00.000Z');

-- ============================================================================
-- 验证脚本（可选，运行后请 PRAGMA integrity_check）
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
