-- ============================================================
-- 阅读笔记系统 · MySQL 完整建库脚本（一键执行版）
-- 目标：MySQL 8.0+ / InnoDB / utf8mb4
-- 内容：建库 + 0001 建表 + 0002 Prompt 模板 + 0003 评测题 + 0004 标签
-- 用法：Navicat 连接 MySQL（任意库）→ 新建查询 → 运行本文件
-- 注意：本脚本开头会先 DROP 已存在的 reading_notes 库再重建，可重复执行（幂等）
--      或命令行：mysql -u root -p < 本文件
-- ============================================================

DROP DATABASE IF EXISTS reading_notes;
CREATE DATABASE IF NOT EXISTS reading_notes
    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE reading_notes;

-- ===================== 0001_init.sql =====================
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

-- ===================== 0002_seed_prompts.sql =====================
-- ============================================================================
-- Migration 0002: Seed Prompt Templates
-- 写入 8 个内置 Prompt 模板（每个 v1，is_active=1）
-- 对应《需求分析说明书 V2.2》§5
-- 注意：本迁移必须在 0001 之后执行；运行前确认 prompt_versions 表为空
-- ============================================================================

-- 单条插入失败由 OR ABORT 终止；用 INSERT IGNORE 跳过重复版本
-- 若需清空：DELETE FROM prompt_versions;

INSERT IGNORE INTO prompt_versions
    (name, version, content, is_active, author, description, created_at) VALUES
-- 1. 书本内问答 System 提示
('book_qa_system', 1,
'你是一个严谨的读书助手。请严格根据我提供的书籍原文片段回答用户问题。

规则：
1. 回答必须完全基于提供的片段，不得引入片段之外的知识、观点或常识
2. 如果片段中找不到答案，直接回答"本书中未提及相关内容"，不要猜测
3. 回答末尾请标注引用来源，格式为：[来源：第X页]
4. 保持客观中立，不要加入主观评价',
1, 'system', '初版：书本内问答系统 Prompt',
'2026-10-15T00:00:00.000Z'),

-- 2. 书本内问答 User 模板（动态片段填充）
('book_qa_user', 1,
'【书籍片段】
[片段1]（第12页）：{chunk_1}
[片段2]（第45页）：{chunk_2}
[片段3]（第78页）：{chunk_3}
[片段4]（第102页）：{chunk_4}
[片段5]（第156页）：{chunk_5}

【用户问题】{question}',
1, 'system', '初版：书本内问答用户模板',
'2026-10-15T00:00:00.000Z'),

-- 3. 摘要 Map 阶段
('summary_map', 1,
'你是一个读书助手。以下是书籍某一章节的部分内容片段，请总结这一章节的核心要点。

【章节片段】
{chunks}

【要求】
1. 用 200~300 字总结本章核心内容
2. 列出本章 3~5 个关键要点
3. 只基于片段内容，不要补充外部信息',
1, 'system', '初版：章节级摘要 Map',
'2026-10-15T00:00:00.000Z'),

-- 4. 摘要 Reduce 阶段
('summary_reduce', 1,
'以下是一本书各章节的小结。请综合这些小结，生成全书摘要。

【各章小结】
{chapter_summaries}

【输出格式】
## 核心思想
（一句话概括全书）

## 全书简介
（200~300字）

## 章节要点
### 第X章 XXX
- 要点1
- 要点2',
1, 'system', '初版：全书摘要 Reduce',
'2026-10-15T00:00:00.000Z'),

-- 5. 跨书问答
('cross_book_qa', 1,
'你是一个读书助手。以下是来自多本书籍和笔记的片段，请综合回答用户问题。

【片段列表】
{sources}

【要求】
1. 按来源分别陈述不同书的观点，对比异同
2. 每个观点标注来源（书名+页码 / 笔记标题）
3. 如果某本书没有相关内容，明确说明
4. 不要引入片段之外的信息

【用户问题】{question}',
1, 'system', '初版：跨书问答',
'2026-10-15T00:00:00.000Z'),

-- 6. 笔记问答（基于笔记）
('note_qa_system', 1,
'你是一个严谨的读书助手。用户问题可能希望从原书、用户笔记或两者结合中寻找答案。
请严格基于提供的片段回答，回答末尾标注来源："原书第 X 页" 或 "我的笔记《XXX》"。',
1, 'system', '初版：基于笔记问答系统',
'2026-10-15T00:00:00.000Z'),

-- 7. 思维导图
('mindmap', 1,
'请根据以下书籍摘要内容，生成 Markdown 格式的层级思维导图。

【书籍摘要】
{summary}

【要求】
1. 使用 # / ## / ### 表示层级
2. 最顶层是书名，第二层是章，第三层是核心观点
3. 每个观点用 - 列表
4. 逻辑要与原书章节结构对应',
1, 'system', '初版：思维导图生成',
'2026-10-15T00:00:00.000Z'),

-- 8. 问题改写（多轮对话指代补全）
('query_rewrite', 1,
'你是一个查询改写助手。用户输入了一个问题，可能含指代（"那它""这个""上一条提到的"等）。
请基于最近 {multi_turn_n} 轮对话历史，把问题改写为不依赖上下文的完整问句。

【对话历史】
{chat_history}

【用户当前问题】{question}

【要求】
1. 输出仅一行改写后的问题，不要解释
2. 如果问题已自洽，直接原样输出',
1, 'system', '初版：多轮对话改写',
'2026-10-15T00:00:00.000Z');

-- ---------- 记录本次迁移 ----------
INSERT IGNORE INTO schema_migrations(version, name, applied_at) VALUES
    (2, 'seed_prompts', '2026-10-15T00:00:00.000Z');

-- ---------- 验证 ----------
SELECT name, version, is_active,
       length(content) AS content_len
  FROM prompt_versions
 ORDER BY name;

-- ===================== 0003_seed_eval.sql =====================
-- ============================================================================
-- Migration 0003: Seed RAG Evaluation Dataset (50 items)
-- 5 本演示用书 x 10 题 = 50 题，分布：
--   book_qa_simple    x 10（主题直接命中）
--   book_qa_located   x 10（含章节定位）
--   book_out_of_scope x 10（应拒答）
--   cross_book_qa     x 10（多书对比）
--   note_qa           x 10（笔记检索）
-- 对应《需求分析说明书 V2.2》§4.7
-- ============================================================================

-- ---------- 5 本演示用书的占位数据 ----------
-- 说明：这里只插 books 表的占位记录，用于满足 eval_datasets 的外键约束。
-- 真实业务上传后会由业务代码覆盖这些记录；若环境中书已真实存在，
-- 请按需修改下方 book_id 或先执行 DELETE FROM eval_datasets。

INSERT IGNORE INTO books
    (book_id, title, author, file_path, file_size, total_pages,
     status, build_progress, created_at, updated_at)
VALUES
    ('book_a', '认知觉醒',       '周岭',            'placeholder', 0, 420,
     'READY', 100, '2026-10-15T00:00:00.000Z', '2026-10-15T00:00:00.000Z'),
    ('book_b', '原子习惯',       'James Clear',     'placeholder', 0, 320,
     'READY', 100, '2026-10-15T00:00:00.000Z', '2026-10-15T00:00:00.000Z'),
    ('book_c', '把时间当作朋友', '李笑来',          'placeholder', 0, 360,
     'READY', 100, '2026-10-15T00:00:00.000Z', '2026-10-15T00:00:00.000Z'),
    ('book_d', '习惯的力量',     'Charles Duhigg',  'placeholder', 0, 300,
     'READY', 100, '2026-10-15T00:00:00.000Z', '2026-10-15T00:00:00.000Z'),
    ('book_e', '思考，快与慢',   'Daniel Kahneman', 'placeholder', 0, 480,
     'READY', 100, '2026-10-15T00:00:00.000Z', '2026-10-15T00:00:00.000Z');

-- ============================================================================
-- 类别 1：book_qa_simple（主题直接命中）— 10 题（每本 2 题）
-- ============================================================================
INSERT INTO eval_datasets
    (item_id, category, book_id, question, ground_truth_answer, difficulty, created_at)
VALUES
('e_a_q01', 'book_qa_simple', 'book_a', '这本书的核心观点是什么',
 '元认知、成长型思维、自控力等，是通过提升认知来改变行为。', 'EASY',
 '2026-10-15T00:00:00.000Z'),
('e_a_q02', 'book_qa_simple', 'book_a', '什么是元认知',
 '对自身思维过程的认知与监控，是认知觉醒的核心概念。', 'EASY',
 '2026-10-15T00:00:00.000Z'),

('e_b_q01', 'book_qa_simple', 'book_b', '习惯的四大法则是什么',
 '提示、渴求、反应、奖赏。', 'EASY',
 '2026-10-15T00:00:00.000Z'),
('e_b_q02', 'book_qa_simple', 'book_b', '作者如何定义身份',
 '身份是某种类型的人，习惯是变成那种人的途径。', 'EASY',
 '2026-10-15T00:00:00.000Z'),

('e_c_q01', 'book_qa_simple', 'book_c', '作者对时间的态度是什么',
 '时间是朋友，关键在于长期持续做事而非短期高效。', 'EASY',
 '2026-10-15T00:00:00.000Z'),
('e_c_q02', 'book_qa_simple', 'book_c', '什么是心智',
 '心智是过往学习、认知与思维方式的综合表现，决定了看待世界的角度。', 'EASY',
 '2026-10-15T00:00:00.000Z'),

('e_d_q01', 'book_qa_simple', 'book_d', '习惯回路的三要素是什么',
 '暗示、惯常行为、奖赏。', 'EASY',
 '2026-10-15T00:00:00.000Z'),
('e_d_q02', 'book_qa_simple', 'book_d', '作者如何解释渴求',
 '渴求是促使大脑采取行动的内在驱动力，是习惯回路的核心环节之一。', 'EASY',
 '2026-10-15T00:00:00.000Z'),

('e_e_q01', 'book_qa_simple', 'book_e', '系统1和系统2的核心区别',
 '系统1快速直觉，系统2缓慢理性，二者协同决策。', 'EASY',
 '2026-10-15T00:00:00.000Z'),
('e_e_q02', 'book_qa_simple', 'book_e', '什么是锚定效应',
 '过度依赖第一印象做出判断的倾向。', 'EASY',
 '2026-10-15T00:00:00.000Z');

-- ============================================================================
-- 类别 2：book_qa_located（含章节定位）— 10 题（每本 2 题）
-- ============================================================================
INSERT INTO eval_datasets
    (item_id, category, book_id, question, ground_truth_answer, difficulty, created_at)
VALUES
('e_a_q03', 'book_qa_located', 'book_a', '第3章讲了什么主要内容',
 '主要讲潜意识与情绪的关系。', 'MEDIUM',
 '2026-10-15T00:00:00.000Z'),
('e_a_q04', 'book_qa_located', 'book_a', '5.2 节关于成长型思维是如何解释的',
 '强调信念对能力的影响，可以通过练习改变。', 'MEDIUM',
 '2026-10-15T00:00:00.000Z'),

('e_b_q03', 'book_qa_located', 'book_b', '第 8 章主要讲什么',
 '如何让习惯不可抗拒。', 'MEDIUM',
 '2026-10-15T00:00:00.000Z'),
('e_b_q04', 'book_qa_located', 'book_b', '10.3 节关于堆叠习惯的方法',
 '把新习惯叠加在已有习惯之上。', 'MEDIUM',
 '2026-10-15T00:00:00.000Z'),

('e_c_q03', 'book_qa_located', 'book_c', '第 5 章的核心论点是什么',
 '要事第一与效率陷阱的区分。', 'MEDIUM',
 '2026-10-15T00:00:00.000Z'),
('e_c_q04', 'book_qa_located', 'book_c', '7.1 节关于学习方法的建议',
 '重视基础概念与长期持续。', 'MEDIUM',
 '2026-10-15T00:00:00.000Z'),

('e_d_q03', 'book_qa_located', 'book_d', '第 4 章主要讨论什么',
 '核心习惯如何撬动生活整体改变。', 'MEDIUM',
 '2026-10-15T00:00:00.000Z'),
('e_d_q04', 'book_qa_located', 'book_d', '7.2 节关于咖啡因习惯的案例',
 '通过替换而非戒断来改变习惯。', 'MEDIUM',
 '2026-10-15T00:00:00.000Z'),

('e_e_q03', 'book_qa_located', 'book_e', '第 6 章主要讲什么',
 '主要讲可得性启发与过度自信。', 'MEDIUM',
 '2026-10-15T00:00:00.000Z'),
('e_e_q04', 'book_qa_located', 'book_e', '11.4 节关于锚定效应的实验',
 '通过随机数字影响估值的经典实验。', 'MEDIUM',
 '2026-10-15T00:00:00.000Z');

-- ============================================================================
-- 类别 3：book_out_of_scope（应拒答）— 10 题（每本 2 题）
-- 期望：系统返回"本书中未提及相关内容"
-- ============================================================================
INSERT INTO eval_datasets
    (item_id, category, book_id, question, ground_truth_answer, difficulty, created_at)
VALUES
('e_a_q05', 'book_out_of_scope', 'book_a', '作者小时候的家庭背景是什么',
 '本书中未提及相关内容', 'EASY',
 '2026-10-15T00:00:00.000Z'),
('e_a_q06', 'book_out_of_scope', 'book_a', '本书的出版社是哪家',
 '本书中未提及相关内容', 'EASY',
 '2026-10-15T00:00:00.000Z'),

('e_b_q05', 'book_out_of_scope', 'book_b', '作者的配偶是谁',
 '本书中未提及相关内容', 'EASY',
 '2026-10-15T00:00:00.000Z'),
('e_b_q06', 'book_out_of_scope', 'book_b', '本书的版税收益是多少',
 '本书中未提及相关内容', 'EASY',
 '2026-10-15T00:00:00.000Z'),

('e_c_q05', 'book_out_of_scope', 'book_c', '作者的投资组合有哪些',
 '本书中未提及相关内容', 'EASY',
 '2026-10-15T00:00:00.000Z'),
('e_c_q06', 'book_out_of_scope', 'book_c', '本书的读者评分是多少',
 '本书中未提及相关内容', 'EASY',
 '2026-10-15T00:00:00.000Z'),

('e_d_q05', 'book_out_of_scope', 'book_d', '作者的童年经历是怎样的',
 '本书中未提及相关内容', 'EASY',
 '2026-10-15T00:00:00.000Z'),
('e_d_q06', 'book_out_of_scope', 'book_d', '本书的销量数据是多少',
 '本书中未提及相关内容', 'EASY',
 '2026-10-15T00:00:00.000Z'),

('e_e_q05', 'book_out_of_scope', 'book_e', '作者的诺贝尔奖颁奖词原文',
 '本书中未提及相关内容', 'EASY',
 '2026-10-15T00:00:00.000Z'),
('e_e_q06', 'book_out_of_scope', 'book_e', '本书的参考文献有多少篇',
 '本书中未提及相关内容', 'EASY',
 '2026-10-15T00:00:00.000Z');

-- ============================================================================
-- 类别 4：cross_book_qa（跨书对比）— 10 题（每本 2 题）
-- ============================================================================
INSERT INTO eval_datasets
    (item_id, category, book_id, question, ground_truth_answer, difficulty, created_at)
VALUES
('e_a_q07', 'cross_book_qa', 'book_a', '认知觉醒和原子习惯对习惯形成的看法有什么异同',
 '前者强调认知驱动，后者强调系统与身份驱动。', 'HARD',
 '2026-10-15T00:00:00.000Z'),
('e_a_q08', 'cross_book_qa', 'book_a', '关于时间管理，这几本书各自的建议是什么',
 '认知觉醒强调专注，原子习惯强调习惯堆叠，把时间当作朋友强调长期主义。', 'HARD',
 '2026-10-15T00:00:00.000Z'),

('e_b_q07', 'cross_book_qa', 'book_b', '原子习惯和习惯的力量在模型上的对比',
 '前者是四大法则模型，后者是三要素回路模型。', 'HARD',
 '2026-10-15T00:00:00.000Z'),
('e_b_q08', 'cross_book_qa', 'book_b', '这几本书中关于动机与系统的讨论',
 '原子习惯强调系统优于目标，其他书强调心智与认知。', 'HARD',
 '2026-10-15T00:00:00.000Z'),

('e_c_q07', 'cross_book_qa', 'book_c', '把时间当作朋友与原子习惯在"坚持"上的观点差异',
 '前者强调心智成熟后的长期投入，后者强调降低启动成本的环境设计。', 'HARD',
 '2026-10-15T00:00:00.000Z'),
('e_c_q08', 'cross_book_qa', 'book_c', '关于学习，这几本书的共同主张',
 '都强调基础、重复与长期积累，而非速成技巧。', 'HARD',
 '2026-10-15T00:00:00.000Z'),

('e_d_q07', 'cross_book_qa', 'book_d', '习惯的力量与原子习惯对"奖赏"的解释差异',
 '前者把奖赏作为回路终点，后者把奖赏作为可设计的手段。', 'HARD',
 '2026-10-15T00:00:00.000Z'),
('e_d_q08', 'cross_book_qa', 'book_d', '这几本书中关于改变行为的关键抓手',
 '分别是认知、法则、心智、回路与系统二。', 'HARD',
 '2026-10-15T00:00:00.000Z'),

('e_e_q07', 'cross_book_qa', 'book_e', '思考快与慢与认知觉醒对"直觉"的看法',
 '前者把直觉作为系统1的自动输出，后者强调对直觉的元认知监控。', 'HARD',
 '2026-10-15T00:00:00.000Z'),
('e_e_q08', 'cross_book_qa', 'book_e', '关于决策偏差，这几本书的互补视角',
 '思考快与慢列举偏差，其他书给出习惯与认知层面的应对方法。', 'HARD',
 '2026-10-15T00:00:00.000Z');

-- ============================================================================
-- 类别 5：note_qa（笔记检索）— 10 题（每本 2 题）
-- 期望：系统命中用户笔记并标注"我的笔记《XXX》"
-- ============================================================================
INSERT INTO eval_datasets
    (item_id, category, book_id, question, ground_truth_answer, difficulty, created_at)
VALUES
('e_a_q09', 'note_qa', 'book_a', '我在笔记中关于元认知的总结是什么',
 '我的笔记《元认知的三个层次》记录了监控、评估与调节三层结构。', 'MEDIUM',
 '2026-10-15T00:00:00.000Z'),
('e_a_q10', 'note_qa', 'book_a', '我笔记里关于成长型思维的重点是什么',
 '我的笔记《成长型思维摘录》强调能力可通过练习改变。', 'MEDIUM',
 '2026-10-15T00:00:00.000Z'),

('e_b_q09', 'note_qa', 'book_b', '我的笔记中关于两分钟法则的心得',
 '我的笔记《两分钟法则实践》记录了把新习惯压缩到两分钟内启动。', 'MEDIUM',
 '2026-10-15T00:00:00.000Z'),
('e_b_q10', 'note_qa', 'book_b', '我笔记中关于身份转变的理解',
 '我的笔记《身份与习惯》强调先定义身份再设计行为。', 'MEDIUM',
 '2026-10-15T00:00:00.000Z'),

('e_c_q09', 'note_qa', 'book_c', '我在笔记中记录的心智成长阶段',
 '我的笔记《心智成长笔记》分为接受、积累、开窍三阶段。', 'MEDIUM',
 '2026-10-15T00:00:00.000Z'),
('e_c_q10', 'note_qa', 'book_c', '我笔记里关于时间记录的实践',
 '我的笔记《时间开销记录》记录了每日事件与耗时统计方法。', 'MEDIUM',
 '2026-10-15T00:00:00.000Z'),

('e_d_q09', 'note_qa', 'book_d', '我的笔记中关于习惯替换的案例',
 '我的笔记《替换而非戒断》记录了用新奖赏替代旧渴求的做法。', 'MEDIUM',
 '2026-10-15T00:00:00.000Z'),
('e_d_q10', 'note_qa', 'book_d', '我笔记里关于核心习惯的理解',
 '我的笔记《核心习惯的力量》强调以点带面的撬动效应。', 'MEDIUM',
 '2026-10-15T00:00:00.000Z'),

('e_e_q09', 'note_qa', 'book_e', '我在笔记中记录的系统1误判案例',
 '我的笔记《系统1误判集》收录了锚定与可得性偏误的实例。', 'MEDIUM',
 '2026-10-15T00:00:00.000Z'),
('e_e_q10', 'note_qa', 'book_e', '我笔记里关于慢思考训练的方法',
 '我的笔记《慢思考训练》记录了刻意停顿与二次校验的步骤。', 'MEDIUM',
 '2026-10-15T00:00:00.000Z');

-- ---------- 记录本次迁移 ----------
INSERT IGNORE INTO schema_migrations(version, name, applied_at) VALUES
    (3, 'seed_eval', '2026-10-15T00:00:00.000Z');

-- ---------- 验证：确认 50 题分布正确 ----------
SELECT category, COUNT(*) AS item_count
  FROM eval_datasets
 GROUP BY category
 ORDER BY category;

-- ===================== 0004_seed_tags.sql =====================
-- ============================================================================
-- Migration 0004: Seed System Tags
-- 写入 6 个预置系统标签
-- ============================================================================

INSERT IGNORE INTO tags (tag_id, name, color, created_at) VALUES
('tag_high_freq', '高频考点',  '#E6A23C', '2026-10-15T00:00:00.000Z'),
('tag_book_club', '读书会',    '#409EFF', '2026-10-15T00:00:00.000Z'),
('tag_golden',    '金句',      '#67C23A', '2026-10-15T00:00:00.000Z'),
('tag_review',    '待复习',    '#F56C6C', '2026-10-15T00:00:00.000Z'),
('tag_classic',   '经典',      '#909399', '2026-10-15T00:00:00.000Z'),
('tag_personal',  '个人总结',  '#9C27B0', '2026-10-15T00:00:00.000Z');

INSERT IGNORE INTO schema_migrations(version, name, applied_at) VALUES
    (4, 'seed_tags', '2026-10-15T00:00:00.000Z');

SELECT * FROM tags ORDER BY tag_id;

