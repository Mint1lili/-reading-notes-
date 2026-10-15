# 《阅读笔记系统》API 接口文档

> 版本：**V1.0**
> 编写日期：2026-10-15
> 对应文档：《需求分析说明书 V2.2》+《数据库设计说明书 V1.0》
> 协议：RESTful over HTTP/1.1，SSE 用于流式
> 状态：本接口文档为前后端联调的契约基线

---

## 目录

- §1 概述
- §2 通用约定
- §3 错误码体系
- §4 接口清单
- §5 书籍管理接口
- §6 知识库构建接口
- §7 AI 交互接口（含 SSE）
- §8 会话管理接口
- §9 笔记管理接口
- §10 系统设置接口
- §11 RAG 评估接口
- §12 健康检查与运维接口
- §13 SSE 流式协议
- §14 限流与幂等
- §15 版本兼容策略

---

## 1. 概述

### 1.1 服务信息

| 项 | 值 |
|----|----|
| Base URL | `http://127.0.0.1:8080/api/v1` |
| 协议 | HTTP/1.1（本地） |
| 数据格式 | `application/json; charset=utf-8` |
| 字符编码 | UTF-8 |
| 流式 | `text/event-stream`（SSE） |
| 认证 | 本期单用户本地部署，无 token；后续扩展预留 `Authorization` header |

### 1.2 接口设计原则
1. **资源化 URL**：`/books/{book_id}`、`/notes/{note_id}`
2. **语义化动作**：非 CRUD 动作放 URL 末段（`/rebuild`、`/restore`、`/purge`、`/index`）
3. **统一响应包裹**：所有响应统一为 `{code, message, data, trace_id}` 信封
4. **分页统一**：`page` + `page_size`，响应含 `total`
5. **流式独立**：LLM 生成类接口走 SSE，`POST .../stream`
6. **失败可重试**：写操作提供幂等键或状态查询接口

---

## 2. 通用约定

### 2.1 统一响应信封

**成功**：
```json
{
  "code": 0,
  "message": "success",
  "data": { ... },
  "trace_id": "a1b2c3d4e5f6"
}
```

**失败**：
```json
{
  "code": 40001,
  "message": "请上传 PDF 文件",
  "data": null,
  "trace_id": "a1b2c3d4e5f6"
}
```

### 2.2 HTTP 状态码映射

| HTTP | 含义 | 何时使用 |
|------|------|---------|
| 200 | OK | 查询/更新成功 |
| 201 | Created | 创建成功（上传、新建笔记） |
| 202 | Accepted | 异步任务已接受（摘要、构建） |
| 204 | No Content | 删除成功 |
| 400 | Bad Request | 参数校验失败 |
| 404 | Not Found | 资源不存在或已软删 |
| 409 | Conflict | 状态冲突（如构建中不能删） |
| 413 | Payload Too Large | 文件 > 50MB |
| 415 | Unsupported Media Type | 非 PDF |
| 422 | Unprocessable Entity | 业务规则拒绝（如扫描件） |
| 429 | Too Many Requests | 限流 |
| 500 | Internal Server Error | 未捕获异常 |
| 503 | Service Unavailable | Chroma / 豆包 API 不可用 |

### 2.3 通用请求头

| Header | 必填 | 说明 |
|--------|------|------|
| `Content-Type` | 是 | `application/json` 或 `multipart/form-data`（上传） |
| `Accept` | 否 | `application/json` |
| `X-Trace-Id` | 否 | 客户端传入则服务端沿用，便于串联 |
| `X-Client-Version` | 否 | 前端版本号 |

### 2.4 通用查询参数（分页/排序）

| 参数 | 类型 | 默认 | 说明 |
|------|------|------|------|
| `page` | int | 1 | 页码，从 1 开始 |
| `page_size` | int | 20 | 每页条数，最大 100 |
| `sort_by` | string | `created_at` | 排序字段 |
| `sort_order` | string | `desc` | `asc` / `desc` |

---

## 3. 错误码体系

### 3.1 错误码分段

| 段 | 范围 | 含义 |
|----|------|------|
| 0 | 0 | 成功 |
| 通用 | 1xxxx | 参数、鉴权、限流 |
| 书籍 | 2xxxx | F1 相关 |
| 构建 | 3xxxx | F2 相关 |
| AI | 4xxxx | F3 相关 |
| 笔记 | 5xxxx | F4 相关 |
| 设置 | 6xxxx | F5 相关 |
| 外部依赖 | 7xxxx | Chroma / 豆包 |

### 3.2 错误码明细

| code | HTTP | message（前端展示） | 触发场景 |
|------|------|-------------------|---------|
| 0 | 200 | success | — |
| **通用** | | | |
| 10001 | 400 | 请求参数有误 | 通用参数校验 |
| 10002 | 404 | 资源不存在 | 通用 |
| 10003 | 429 | 操作过于频繁，请稍后再试 | 单 IP 20 次/分钟 |
| 10004 | 500 | 服务内部错误，请查看日志 | 未捕获 |
| **书籍 F1** | | | |
| 20001 | 415 | 请上传 PDF 文件 | 非 `.pdf` |
| 20002 | 413 | 文件不能超过 50MB | size > 50MB |
| 20003 | 409 | 已存在同名书籍，是否继续上传？ | 重名确认 |
| 20004 | 409 | 书籍正在构建中，请先等待或取消 | 构建中删除 |
| 20005 | 404 | 书籍不存在或已删除 | — |
| **构建 F2** | | | |
| 30001 | 422 | 未检测到文本内容，可能是扫描件，本期暂不支持 | PDFBox < 50 字符 |
| 30002 | 503 | 向量化服务超时，请重试 | Embedding 30s 超时 |
| 30003 | 500 | 章节识别率过低，问答将退化为纯向量检索 | 识别率 < 50%（警告非错误） |
| 30004 | 500 | 构建失败，可点击重试 | 重试 3 次仍失败 |
| 30005 | 409 | 构建任务不存在或已完成 | — |
| **AI F3** | | | |
| 40001 | 503 | AI 服务暂时不可用，请重试 | 豆包调用失败 |
| 40002 | 200 | 本书中未提及相关内容 | 相似度 < 阈值（业务成功） |
| 40003 | 404 | 指定的 AI 产物版本不存在 | — |
| 40004 | 409 | AI 任务正在生成中，请稍候 | 重复提交 |
| 40005 | 400 | 未选择任何书籍，无法进行跨书问答 | 跨书范围为空 |
| **笔记 F4** | | | |
| 50001 | 404 | 笔记不存在或已删除 | — |
| 50002 | 500 | 内容已恢复，未保存（向量同步失败） | 编辑回流回滚 |
| 50003 | 500 | 笔记入库失败，请重试 | 回流开关写入失败 |
| 50004 | 409 | 笔记已被他人更新，是否重载？ | `editor_version` 冲突 |
| **设置 F5** | | | |
| 60001 | 404 | Prompt 模板不存在 | — |
| 60002 | 400 | Prompt 模板语法错误 | YAML 解析失败 |
| 60003 | 500 | 备份失败，请检查磁盘空间 | — |
| 60004 | 500 | 恢复失败，文件未损坏前请勿继续 | SHA-256 校验失败 |
| 60005 | 400 | 已超出单日预算，AI 调用已暂停 | 用量超限 |
| **外部依赖** | | | |
| 70001 | 503 | 知识库服务未启动，请检查 Chroma | 健康检查失败 |
| 70002 | 503 | Chroma 与 Embedding 维度不一致 | 维度不匹配 |
| 70003 | 503 | 豆包 API 不可用 | — |

---

## 4. 接口清单

### 4.1 汇总表

| # | 方法 | 路径 | 说明 | 模块 |
|---|------|------|------|------|
| **书籍管理** | | | | |
| 1 | POST | `/books/upload` | 上传 PDF | F1.1 |
| 2 | GET | `/books` | 书籍列表 | F1 |
| 3 | GET | `/books/{book_id}` | 书籍详情 | F1 |
| 4 | PATCH | `/books/{book_id}` | 编辑书籍 | F1 |
| 5 | DELETE | `/books/{book_id}` | 软删除 | F1.2 |
| 6 | POST | `/books/{book_id}/restore` | 恢复 | F1.2 |
| 7 | DELETE | `/books/{book_id}/purge` | 永久删除 | F1.2 |
| 8 | GET | `/books/trash` | 回收站 | F1.2 |
| 9 | POST | `/books/{book_id}/rebuild` | 重建知识库 | F2 |
| **知识库构建** | | | | |
| 10 | GET | `/books/{book_id}/build/status` | 构建状态 | F2.5 |
| 11 | POST | `/books/{book_id}/build/retry` | 重试构建 | F2.5 |
| 12 | POST | `/books/{book_id}/build/cancel` | 取消构建 | F2.5 |
| 13 | GET | `/books/{book_id}/chunks` | 切片预览 | F2.3 |
| 14 | GET | `/books/{book_id}/chapters` | 章节结构 | F2.1 |
| **AI 交互** | | | | |
| 15 | POST | `/ai/summary` | 生成全书摘要 | F3.1 |
| 16 | GET | `/ai/summary/{book_id}` | 获取摘要（含版本） | F3.1 |
| 17 | POST | `/ai/summary/{book_id}/regenerate` | 重新生成摘要 | F3.6 |
| 18 | POST | `/ai/qa/stream` | 书本内问答（SSE） | F3.2 |
| 19 | POST | `/ai/qa/cross/stream` | 跨书问答（SSE） | F3.3 |
| 20 | POST | `/ai/qa/note/stream` | 基于笔记问答（SSE） | F3.4 |
| 21 | POST | `/ai/mindmap` | 生成思维导图 | F3.5 |
| 22 | GET | `/ai/mindmap/{book_id}` | 获取导图 | F3.5 |
| 23 | PATCH | `/ai/mindmap/{version_id}` | 编辑导图 | F3.5 |
| 24 | POST | `/ai/{target_type}/{target_id}/feedback` | 点踩反馈 | F3.6 |
| 25 | POST | `/ai/versions/{version_id}/activate` | 版本回滚 | F3.6 |
| 26 | GET | `/ai/tasks/{task_id}` | 任务状态 | F3 |
| **会话管理** | | | | |
| 27 | POST | `/chat/sessions` | 创建会话 | F3.2 |
| 28 | GET | `/chat/sessions` | 会话列表 | F3.2 |
| 29 | GET | `/chat/sessions/{session_id}/messages` | 消息历史 | F3.2 |
| 30 | DELETE | `/chat/sessions/{session_id}` | 删除会话 | F3.2 |
| **笔记管理** | | | | |
| 31 | POST | `/notes` | 新建笔记 | F4 |
| 32 | GET | `/notes` | 笔记列表 | F4 |
| 33 | GET | `/notes/{note_id}` | 笔记详情 | F4 |
| 34 | PATCH | `/notes/{note_id}` | 编辑笔记 | F4 |
| 35 | DELETE | `/notes/{note_id}` | 软删除 | F4 |
| 36 | POST | `/notes/{note_id}/restore` | 恢复 | F4 |
| 37 | DELETE | `/notes/{note_id}/purge` | 永久删除 | F4 |
| 38 | POST | `/notes/{note_id}/index` | 回流开关 | F4 |
| 39 | POST | `/notes/from-ai` | AI 产物存为笔记 | F4 |
| 40 | GET | `/notes/{note_id}/links` | 双向链接 | F4 |
| 41 | POST | `/notes/{note_id}/links` | 创建链接 | F4 |
| 42 | POST | `/notes/export` | 导出 md/pdf | F4 |
| 43 | GET | `/tags` | 标签列表 | F4 |
| 44 | POST | `/tags` | 创建标签 | F4 |
| **系统设置** | | | | |
| 45 | GET/PUT | `/settings/model` | 模型参数 | F5.1 |
| 46 | GET/PUT | `/settings/rag` | RAG 参数 | F5.2 |
| 47 | GET | `/settings/prompts` | Prompt 列表 | F5.3 |
| 48 | GET | `/settings/prompts/{name}` | 单个模板 | F5.3 |
| 49 | POST | `/settings/prompts/{name}` | 保存新版本 | F5.3 |
| 50 | POST | `/settings/prompts/{name}/versions/{version}/activate` | 回滚 | F5.3 |
| 51 | GET | `/settings/usage` | 用量统计 | F5.4 |
| 52 | GET | `/settings/backups` | 备份列表 | F5.5 |
| 53 | POST | `/settings/backups` | 创建备份 | F5.5 |
| 54 | POST | `/settings/backups/{backup_id}/restore` | 恢复 | F5.5 |
| 55 | GET | `/settings/logs` | 日志查询 | F5.6 |
| **RAG 评估** | | | | |
| 56 | POST | `/eval/run` | 运行评估 | §4.7 |
| 57 | GET | `/eval/results` | 评估结果列表 | §4.7 |
| 58 | GET | `/eval/results/{eval_run_id}` | 单个报告 | §4.7 |
| **健康检查** | | | | |
| 59 | GET | `/health` | 整体健康 | §6 |
| 60 | GET | `/health/chroma` | Chroma 状态 | §6 |

**共 60 个接口**。

---

## 5. 书籍管理接口

### 5.1 上传 PDF

```
POST /api/v1/books/upload
Content-Type: multipart/form-data
```

**Request（form-data）**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `file` | file | 是 | PDF 文件 ≤ 50MB |
| `title` | string | 否 | 书名，缺省从文件名/PDF 元数据提取 |
| `confirm_duplicate` | boolean | 否 | 重名时二次确认标志，默认 false |

**Response 201**
```json
{
  "code": 0,
  "message": "success",
  "data": {
    "book_id": "book_2026a1b2c3d4e5f67890a1b2c3d4e5f6",
    "title": "认知觉醒",
    "status": "BUILDING",
    "build_progress": 0,
    "trace_id": "a1b2c3d4e5f6"
  },
  "trace_id": "a1b2c3d4e5f6"
}
```

**重名确认 409**
```json
{
  "code": 20003,
  "message": "已存在同名书籍，是否继续上传？",
  "data": { "existing_book_id": "book_xxx" },
  "trace_id": "a1b2c3d4e5f6"
}
```

**错误**：`20001`（非 PDF）、`20002`（>50MB）、`30001`（扫描件）

**后端行为**：
1. 校验扩展名/size → 失败拦截
2. 生成 `book_id`，保存 `uploads/{book_id}.pdf`
3. `books` 表插入 `status='UPLOADING'`
4. PDFBox 提取：字符数 < 50 → 回滚并返回 `30001`
5. 状态置 `BUILDING`，异步触发构建任务（返回不阻塞）

---

### 5.2 书籍列表

```
GET /api/v1/books?page=1&page_size=20&status=READY&keyword=认知
```

**Query**

| 参数 | 类型 | 说明 |
|------|------|------|
| `status` | string | 过滤：`READY` / `BUILDING` / `FAILED` |
| `keyword` | string | 书名/作者模糊搜索 |

**Response 200**
```json
{
  "code": 0,
  "message": "success",
  "data": {
    "total": 12,
    "page": 1,
    "page_size": 20,
    "items": [
      {
        "book_id": "book_2026a1...",
        "title": "认知觉醒",
        "author": "周岭",
        "status": "READY",
        "total_pages": 420,
        "chunk_count": 624,
        "chapter_recognized_rate": 0.87,
        "note_count": 8,
        "created_at": "2026-10-15T08:00:00.000Z",
        "last_ready_at": "2026-10-15T08:05:00.000Z"
      }
    ]
  },
  "trace_id": "a1b2c3d4e5f6"
}
```

> `note_count` 由 `notes.source_book_id` 聚合得出（软删不计）。

---

### 5.3 书籍详情

```
GET /api/v1/books/{book_id}
```

**Response 200**
```json
{
  "code": 0,
  "message": "success",
  "data": {
    "book_id": "book_2026a1...",
    "title": "认知觉醒",
    "author": "周岭",
    "file_path": "uploads/book_2026a1.pdf",
    "file_size": 12345678,
    "total_pages": 420,
    "status": "READY",
    "build_sub_stage": null,
    "build_progress": 100,
    "chunk_count": 624,
    "chapter_recognized_rate": 0.87,
    "embedding_model": "Doubao-embedding-text-240715",
    "embedding_dim": 1024,
    "created_at": "2026-10-15T08:00:00.000Z",
    "updated_at": "2026-10-15T08:05:00.000Z",
    "last_ready_at": "2026-10-15T08:05:00.000Z"
  },
  "trace_id": "a1b2c3d4e5f6"
}
```

---

### 5.4 编辑书籍

```
PATCH /api/v1/books/{book_id}
```

**Request**
```json
{ "title": "认知觉醒（修订版）", "author": "周岭" }
```

**Response 200**：返回更新后的书籍对象（同 5.3）

**说明**：仅允许改 `title` / `author`，文件路径与状态不可改。

---

### 5.5 软删除书籍

```
DELETE /api/v1/books/{book_id}
```

**Request（body 可选）**
```json
{ "keep_notes": true }
```

**Response 204**（无 body）

**后端行为（对应 F1.2 事务）**：
1. `books.status = 'DELETING'`
2. 删除 Chroma `book_{book_id}` 集合
3. `books.soft_delete=1`、`deleted_at=now`、`purge_at=now+7d`
   - 关联 `notes` 保留，仅前端"查看原文"置灰
4. 删除 `uploads/{book_id}.pdf`
5. 任一步失败 → `status='FAILED'` + `error_code`，前端可重试

**错误**：`20004`（构建中，需先 cancel）、`20005`（不存在）

---

### 5.6 恢复书籍

```
POST /api/v1/books/{book_id}/restore
```

**Response 200**：返回书籍对象，`status` 回到 `READY` 或 `BUILDING`（若需重建向量）

**说明**：仅 `soft_delete=1` 且未过 `purge_at` 可恢复。若向量已清理，走重建流程。

---

### 5.7 永久删除

```
DELETE /api/v1/books/{book_id}/purge
```

**Response 204**

**后端行为**：置 `status='PURGED'` → 清 Chroma → 清 SQLite `books`/`chapters`/`chunks`（`chunk_type='book'`）→ 清 PDF。

**错误**：`20005`

---

### 5.8 回收站列表

```
GET /api/v1/books/trash
```

**Response 200**
```json
{
  "code": 0,
  "data": {
    "total": 2,
    "items": [
      {
        "book_id": "book_xxx",
        "title": "旧书",
        "deleted_at": "2026-10-10T08:00:00.000Z",
        "purge_at": "2026-10-17T08:00:00.000Z",
        "remaining_hours": 48
      }
    ]
  },
  "trace_id": "a1b2c3d4e5f6"
}
```

---

### 5.9 重建知识库

```
POST /api/v1/books/{book_id}/rebuild
```

**Request**
```json
{ "chunk_size": 600, "overlap": 100 }
```

**Response 202**
```json
{
  "code": 0,
  "message": "已开始重建",
  "data": { "book_id": "book_2026a1...", "status": "REBUILDING", "build_progress": 0 },
  "trace_id": "..."
}
```

**后端行为**：`status='REBUILDING'` → 删旧 Chroma 集合 → 重新走 F2 全流程。

---

## 6. 知识库构建接口

### 6.1 构建状态

```
GET /api/v1/books/{book_id}/build/status
```

> 前端轮询（建议 1s~2s 间隔）或配合 SSE。

**Response 200**
```json
{
  "code": 0,
  "data": {
    "book_id": "book_2026a1...",
    "status": "BUILDING",
    "build_sub_stage": "EMBED",
    "build_progress": 62,
    "error_code": null,
    "error_message": null,
    "chunk_count_planned": 624,
    "chunk_count_actual": 610,
    "total_batches": 39,
    "last_completed_batch": 24,
    "failed_batches": [],
    "chapter_recognized_rate": 0.87
  },
  "trace_id": "..."
}
```

**阶段映射**（对应 `books.build_sub_stage`）：

| sub_stage | 进度区间 | 含义 |
|-----------|---------|------|
| `PARSE` | 10~20 | PDFBox 解析 |
| `RECOGNIZE` | 20~30 | 章节识别 |
| `CHUNK` | 30 | 切片 |
| `EMBED` | 30~90 | 向量化（批次推进） |
| `INGEST` | 90~100 | 写入 Chroma |

---

### 6.2 重试构建

```
POST /api/v1/books/{book_id}/build/retry
```

**Request（可选）**
```json
{ "from_batch": 24 }
```

**Response 202**

**说明**：缺省从 `build_progress.last_completed_batch` 继续（F2.5 断点续传）。

**错误**：`30005`（无失败任务）

---

### 6.3 取消构建

```
POST /api/v1/books/{book_id}/build/cancel
```

**Response 200**：`status='CANCELLED'`

**说明**：已完成的批次保留，下次 retry 可续跑。

---

### 6.4 切片预览

```
GET /api/v1/books/{book_id}/chunks?page=1&page_size=20&chapter=1.2
```

**Response 200**
```json
{
  "code": 0,
  "data": {
    "total": 624,
    "items": [
      {
        "chunk_id": "chunk_xxx",
        "content": "本章主要讨论...",
        "page": 45,
        "chapter_path": "1 / 1.2",
        "chapter_title": "大脑的重新认知",
        "position": 12,
        "word_count": 612,
        "embedding_status": "DONE"
      }
    ]
  },
  "trace_id": "..."
}
```

---

### 6.5 章节结构

```
GET /api/v1/books/{book_id}/chapters
```

**Response 200**
```json
{
  "code": 0,
  "data": {
    "book_id": "book_2026a1...",
    "chapter_recognized_rate": 0.87,
    "chapters": [
      { "chapter_id": "ch_1", "chapter_path": "1", "title": "大脑", "level": 1, "start_page": 1, "word_count": 12000 },
      { "chapter_id": "ch_2", "chapter_path": "1.1", "title": "重新认识大脑", "level": 2, "start_page": 3, "word_count": 4200 }
    ]
  },
  "trace_id": "..."
}
```

---

## 7. AI 交互接口（含 SSE）

### 7.1 生成全书摘要

```
POST /api/v1/ai/summary
```

**Request**
```json
{
  "book_id": "book_2026a1...",
  "temperature": 0.3,
  "detail_level": "standard"
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `detail_level` | string | `brief` / `standard` / `detailed` |

**Response 202**（异步，返回 task）
```json
{
  "code": 0,
  "message": "摘要任务已提交",
  "data": {
    "task_id": "task_2026xxx",
    "book_id": "book_2026a1...",
    "status": "QUEUED",
    "trace_id": "..."
  },
  "trace_id": "..."
}
```

**后端行为（对应 F3.1 map-reduce）**：
1. `ai_tasks` 插入 `task_type='SUMMARY'`，`status='QUEUED'`
2. Map：每章一个子任务（`parent_task_id` 指向主任务）
3. Reduce：汇总生成最终摘要
4. `ai_versions` 写入 `artifact_type='SUMMARY'`，`version_number+1`，`is_active=1`
5. `books.status` 不变；任务状态查 7.6

---

### 7.2 获取摘要（含版本）

```
GET /api/v1/ai/summary/{book_id}?version=latest
```

**Response 200**
```json
{
  "code": 0,
  "data": {
    "book_id": "book_2026a1...",
    "current_version": 2,
    "versions": [
      { "version_id": "ver_1", "version_number": 1, "is_active": false, "created_at": "..." },
      { "version_id": "ver_2", "version_number": 2, "is_active": true, "created_at": "..." }
    ],
    "content": "## 核心思想\n...\n\n## 全书简介\n...\n\n## 章节要点\n### 第1章 大脑\n- 要点1\n"
  },
  "trace_id": "..."
}
```

---

### 7.3 重新生成摘要

```
POST /api/v1/ai/summary/{book_id}/regenerate
```

**Request**
```json
{ "temperature": 0.6, "detail_level": "detailed" }
```

**Response 202**：同 7.1

**说明**：生成新版本（`version_number+1`），旧版本保留可回滚。

---

### 7.4 书本内问答（SSE 流式）

```
POST /api/v1/ai/qa/stream
Accept: text/event-stream
```

**Request**
```json
{
  "book_id": "book_2026a1...",
  "session_id": "sess_2026xxx",
  "question": "作者在 5.2 节怎么解释这个概念",
  "enable_metadata_filter": true,
  "enable_rerank": true,
  "top_k": 10,
  "rerank_top_n": 5,
  "similarity_threshold": 0.5,
  "multi_turn_n": 3,
  "temperature": 0.3
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `session_id` | string | 是 | 多轮上下文归属；不存在则后端创建 |
| `question` | string | 是 | 用户问题 |
| `enable_metadata_filter` | bool | 否 | 默认取 F5.2 配置 |
| `enable_rerank` | bool | 否 | 默认取配置 |
| `top_k` / `rerank_top_n` / `similarity_threshold` | int/float | 否 | 覆盖全局配置 |

**SSE 事件流**（详见 §13）：
```
event: meta
data: {"task_id":"task_xxx","retrieved_chunks":[...],"rewritten_question":"..."}

event: delta
data: {"content":"作者在第 5.2 节认为..."}

event: citation
data: {"page":102,"snippet":"...","chunk_id":"chunk_xxx"}

event: done
data: {"message_id":"msg_xxx","version_id":"ver_xxx","token_usage":{...},"latency_ms":4200}

event: error
data: {"code":40001,"message":"AI 服务暂时不可用，请重试"}
```

**拒答场景（业务成功）**：
```
event: done
data: {"refused":true,"message":"本书中未提及相关内容","threshold":0.5,"top1_score":0.31}
```

**后端行为（对应 F3.2 三层 RAG）**：
1. 查询理解：正则提取章节/页码线索
2. 问题改写（多轮指代补全，调小模型，透明执行）
3. 向量召回 Top-K（有元数据过滤则在子集内）
4. Rerank 精排取 Top-N
5. 阈值兜底：Top-1 < threshold → 直接拒答，不调 LLM
6. 流式调用豆包，SSE 推送
7. `chat_messages` 落库（user + assistant），`ai_versions` 存 `ANSWER`

---

### 7.5 跨书问答（SSE）

```
POST /api/v1/ai/qa/cross/stream
```

**Request**
```json
{
  "book_ids": ["book_a", "book_b", "book_c"],
  "include_notes": true,
  "session_id": "sess_2026xxx",
  "question": "这三本书对'习惯回路'分别怎么解释",
  "top_k": 12,
  "rerank_top_n": 8
}
```

| 字段 | 说明 |
|------|------|
| `book_ids` | 参与的书；为空表示全部已完成书籍 |
| `include_notes` | 是否纳入 `notes_all`（默认 true，对应 F3.3 独立开关） |

**SSE 事件流**：同 7.4，但 `citation` 事件含 `book_id` / `book_title` / `note_title`：
```
event: citation
data: {"source_type":"book","book_id":"book_a","book_title":"习惯的力量","page":88,"snippet":"..."}

event: citation
data: {"source_type":"note","note_id":"note_xxx","note_title":"我的习惯笔记","snippet":"..."}
```

**错误**：`40005`（未选任何书且无已完成书籍）

---

### 7.6 基于笔记问答（SSE）

```
POST /api/v1/ai/qa/note/stream
```

**Request**
```json
{
  "book_id": "book_2026a1...",
  "scope": "book_and_notes",
  "session_id": "sess_2026xxx",
  "question": "我之前对这个概念有什么思考"
}
```

| `scope` | 说明 |
|---------|------|
| `book_only` | 仅原书 |
| `notes_only` | 仅笔记 |
| `book_and_notes` | 原书 + 笔记（默认） |

**SSE**：同 7.4，citation 标注 `原书第 X 页` 或 `我的笔记《XXX》`

---

### 7.7 生成思维导图

```
POST /api/v1/ai/mindmap
```

**Request**
```json
{
  "book_id": "book_2026a1...",
  "source": "summary",
  "source_version_id": "ver_2"
}
```

| `source` | 说明 |
|----------|------|
| `summary` | 基于全书摘要（默认） |
| `chapter` | 基于指定章节，需传 `chapter_path` |

**Response 202**：同 7.1 结构（`task_type='MINDMAP'`）

---

### 7.8 获取思维导图

```
GET /api/v1/ai/mindmap/{book_id}?version=latest
```

**Response 200**
```json
{
  "code": 0,
  "data": {
    "book_id": "book_2026a1...",
    "current_version": 1,
    "versions": [ {"version_id":"ver_m1","version_number":1,"is_active":true} ],
    "content": "# 认知觉醒\n## 第一章 大脑\n- 要点1\n- 要点2\n## 第二章 潜意识\n- 要点1\n"
  },
  "trace_id": "..."
}
```

---

### 7.9 编辑思维导图

```
PATCH /api/v1/ai/mindmap/{version_id}
```

**Request**
```json
{ "content": "# 认知觉醒\n## 第一章\n- 修改后的要点\n" }
```

**Response 200**：返回更新后的导图对象

**说明**：手动编辑**不递增** `version_number`，仅更新当前版本内容（避免版本爆炸）。若需留痕，走 `POST /ai/mindmap` 重新生成。

---

### 7.10 点踩反馈

```
POST /api/v1/ai/{target_type}/{target_id}/feedback
```

| `target_type` | `target_id` |
|---------------|-------------|
| `ai_version` | `version_id` |
| `note` | `note_id` |

**Request**
```json
{ "vote": -1, "reason": "答非所问", "comment": "我想要的是定义不是例子" }
```

**Response 201**
```json
{
  "code": 0,
  "data": { "feedback_id": "fb_2026xxx" },
  "trace_id": "..."
}
```

**说明**：写入 `note_feedback` 表；`target_type='ai_version'` 时同步回填 `chat_messages.feedback_id`。

---

### 7.11 版本回滚

```
POST /api/v1/ai/versions/{version_id}/activate
```

**Response 200**：将该 `version_id` 置 `is_active=1`，同 target 同类产物的其他版本置 0

---

### 7.12 任务状态查询

```
GET /api/v1/ai/tasks/{task_id}
```

**Response 200**
```json
{
  "code": 0,
  "data": {
    "task_id": "task_2026xxx",
    "task_type": "SUMMARY",
    "status": "RUNNING",
    "retry_count": 0,
    "progress_hint": "已生成 7/12 章小结",
    "params_json": "{\"temperature\":0.3}",
    "result_json": null,
    "error_message": null,
    "created_at": "2026-10-15T09:00:00.000Z",
    "started_at": "2026-10-15T09:00:01.000Z",
    "finished_at": null,
    "trace_id": "..."
  },
  "trace_id": "..."
}
```

---

## 8. 会话管理接口

### 8.1 创建会话

```
POST /api/v1/chat/sessions
```

**Request**
```json
{ "scope": "book:book_2026a1...", "book_id": "book_2026a1...", "title": "第5章疑问" }
```

| `scope` | 说明 |
|---------|------|
| `book:{id}` | 单书问答 |
| `cross` | 跨书问答 |
| `note:{id}` | 笔记问答 |

**Response 201**
```json
{
  "code": 0,
  "data": {
    "session_id": "sess_2026xxx",
    "scope": "book:book_2026a1...",
    "title": "第5章疑问",
    "created_at": "2026-10-15T09:00:00.000Z"
  },
  "trace_id": "..."
}
```

---

### 8.2 会话列表

```
GET /api/v1/chat/sessions?book_id=book_2026a1...&page=1&page_size=20
```

**Response 200**：`chat_sessions` 列表，含 `message_count` 聚合

---

### 8.3 消息历史

```
GET /api/v1/chat/sessions/{session_id}/messages?limit=20
```

**Response 200**
```json
{
  "code": 0,
  "data": {
    "session_id": "sess_2026xxx",
    "messages": [
      {
        "message_id": "msg_1",
        "role": "user",
        "content": "作者在 5.2 节怎么解释这个概念",
        "created_at": "..."
      },
      {
        "message_id": "msg_2",
        "role": "assistant",
        "content": "作者在第 5.2 节认为...",
        "cited_chunks": [
          { "chunk_id": "chunk_xxx", "page": 102, "snippet": "...", "score": 0.82 }
        ],
        "token_usage": { "input_tokens": 1200, "output_tokens": 350 },
        "feedback": { "vote": -1, "reason": "答非所问" },
        "created_at": "..."
      }
    ]
  },
  "trace_id": "..."
}
```

---

### 8.4 删除会话

```
DELETE /api/v1/chat/sessions/{session_id}
```

**Response 204**：级联删除 `chat_messages`

---

## 9. 笔记管理接口

### 9.1 新建笔记

```
POST /api/v1/notes
```

**Request**
```json
{
  "title": "关于习惯回路的思考",
  "content": "# 我的思考\n习惯回路包括暗示、惯常行为、奖赏三部分...",
  "source_type": "MANUAL",
  "source_book_id": "book_2026a1...",
  "source_page": 102,
  "source_chunk_id": "chunk_xxx",
  "tag_names": ["读书会", "高频考点"]
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `content` | string | 是 | Markdown 文本 |
| `source_type` | string | 否 | `MANUAL` 默认；AI 来源由后端自动判定 |
| `tag_names` | array | 否 | 不存在则自动创建标签 |

**Response 201**
```json
{
  "code": 0,
  "data": {
    "note_id": "note_2026xxx",
    "title": "关于习惯回路的思考",
    "is_indexed": 0,
    "index_status": "NOT_INDEXED",
    "editor_version": 1,
    "created_at": "2026-10-15T09:10:00.000Z"
  },
  "trace_id": "..."
}
```

---

### 9.2 笔记列表

```
GET /api/v1/notes?book_id=book_2026a1...&tag=读书会&keyword=习惯&indexed=true
```

**Query**

| 参数 | 说明 |
|------|------|
| `book_id` | 按来源书过滤 |
| `tag` | 按标签名过滤 |
| `keyword` | 标题/内容模糊搜索 |
| `indexed` | `true` / `false` 按回流状态过滤 |
| `source_type` | `MANUAL` / `AI_QA` / ... |

**Response 200**
```json
{
  "code": 0,
  "data": {
    "total": 8,
    "items": [
      {
        "note_id": "note_2026xxx",
        "title": "关于习惯回路的思考",
        "content_preview": "习惯回路包括暗示、惯常行为...",
        "source_type": "MANUAL",
        "source_book_title": "认知觉醒",
        "source_page": 102,
        "tags": ["读书会", "高频考点"],
        "is_indexed": true,
        "index_status": "INDEXED",
        "word_count": 1250,
        "link_count": 2,
        "created_at": "2026-10-15T09:10:00.000Z",
        "updated_at": "2026-10-15T09:20:00.000Z"
      }
    ]
  },
  "trace_id": "..."
}
```

---

### 9.3 笔记详情

```
GET /api/v1/notes/{note_id}
```

**Response 200**
```json
{
  "code": 0,
  "data": {
    "note_id": "note_2026xxx",
    "title": "关于习惯回路的思考",
    "content": "# 我的思考\n...",
    "source_type": "MANUAL",
    "source_book_id": "book_2026a1...",
    "source_book_title": "认知觉醒",
    "source_page": 102,
    "source_chunk_id": "chunk_xxx",
    "can_jump_to_source": true,
    "tags": [{"tag_id":"tag_1","name":"读书会","color":"#409EFF"}],
    "is_indexed": true,
    "index_status": "INDEXED",
    "word_count": 1250,
    "editor_version": 3,
    "links": [
      {"link_id":"link_1","target_note_id":"note_yyy","target_title":"另一篇笔记","target_jumpable":true}
    ],
    "created_at": "...",
    "updated_at": "..."
  },
  "trace_id": "..."
}
```

> `can_jump_to_source`：当 `source_book_id` 对应的书已被删除时返回 `false`（前端置灰，对应 F1.2 第 3 步）。

---

### 9.4 编辑笔记

```
PATCH /api/v1/notes/{note_id}
```

**Request**
```json
{
  "content": "# 我的思考（已更新）\n...",
  "title": "更新后的标题",
  "editor_version": 3,
  "tag_names": ["读书会"]
}
```

| 字段 | 说明 |
|------|------|
| `editor_version` | 必填，用于并发控制。与库中不一致返回 `50004` |

**Response 200**（回流笔记走三步事务）

**后端行为（对应 §7.3.1 三步事务）**：
1. `SQLite.begin_transaction()`，暂存新内容（不提交）
2. `Chroma.notes_all.delete(where={"note_id": note_id})` → 失败回滚
3. 重新切片 + Embedding → 失败回滚
4. `Chroma.notes_all.upsert(...)` → 失败回滚
5. `SQLite.commit()`：`is_indexed=1` + 新内容 + `editor_version+1`

**错误**：
- `50004`（`editor_version` 冲突，提示"笔记已被他人更新，是否重载？"）
- `50002`（向量同步失败 → SQLite 回滚 → 提示"内容已恢复，未保存"）

**非回流笔记**：跳过 2~4 步，直接提交内容变更，`editor_version+1`。

---

### 9.5 软删除笔记

```
DELETE /api/v1/notes/{note_id}
```

**Response 204**

**后端行为（对应 §7.3.2）**：
1. `notes.soft_delete=1`、`deleted_at=now`
2. 若 `is_indexed=1` → `Chroma.notes_all.delete(where={"note_id": note_id})`
3. 校验 `is_indexed=0`

---

### 9.6 恢复笔记

```
POST /api/v1/notes/{note_id}/restore
```

**Response 200**

**后端行为（对应 §7.3.3）**：`soft_delete=0` → 若曾回流转态，走 §7.3.4 重新入库。

---

### 9.7 永久删除笔记

```
DELETE /api/v1/notes/{note_id}/purge
```

**Response 204**

**后端行为（对应 §7.3.4）**：
1. 若回流：删 Chroma 向量
2. 校验 `notes_all` 查询返回空（否则重试 1 次并报警）
3. SQLite 硬删 `notes` 记录
4. 扫描 `note_links`，`target_note_id=note_id` 的链接置 `target_jumpable=0`、标题改"已删除笔记（链接失效）"

---

### 9.8 知识回流开关

```
POST /api/v1/notes/{note_id}/index
```

**Request**
```json
{ "enable": true }
```

**Response 200**
```json
{
  "code": 0,
  "data": {
    "note_id": "note_2026xxx",
    "is_indexed": true,
    "index_status": "INDEXED",
    "indexed_chunk_count": 3
  },
  "trace_id": "..."
}
```

**后端行为**：
- `enable=true` → `index_status='INDEXING'` → 切片（>800 字按段切，≤20 片）→ Embedding → `notes_all.upsert` → `is_indexed=1`、`index_status='INDEXED'`
- 失败 → `index_status='INDEX_FAILED'` → **`is_indexed` 回弹为 0** + 提示"笔记入库失败，请重试"（对应 F4 开关弹回）
- `enable=false` → 删 `notes_all` 向量 → `is_indexed=0`

**错误**：`50003`

---

### 9.9 AI 产物一键存为笔记

```
POST /api/v1/notes/from-ai
```

**Request**
```json
{
  "source_type": "AI_QA",
  "source_ai_version_id": "ver_2026xxx",
  "title": "关于习惯回路的问答",
  "auto_tag": true
}
```

| `source_type` | 说明 |
|---------------|------|
| `AI_SUMMARY` | 存摘要 |
| `AI_QA` | 存问答 |
| `AI_MINDMAP` | 存导图 |

**Response 201**：返回新建的笔记对象

**后端行为**：
1. 从 `ai_versions` 取内容，或从 `chat_messages` 取问答对
2. 写入 `notes`（`source_ai_version_id` 回填）
3. `auto_tag=true` 时按 `source_type` 自动打类型标签（如 `AI问答`、`AI摘要`）

---

### 9.10 双向链接

**查询**
```
GET /api/v1/notes/{note_id}/links
```

**Response 200**
```json
{
  "code": 0,
  "data": {
    "outgoing": [
      {"link_id":"link_1","target_note_id":"note_yyy","target_title":"另一篇","target_jumpable":true}
    ],
    "incoming": [
      {"link_id":"link_2","source_note_id":"note_zzz","source_title":"第三篇"}
    ]
  },
  "trace_id": "..."
}
```

**创建**
```
POST /api/v1/notes/{note_id}/links
```
```json
{ "target_note_id": "note_yyy", "context": "参见习惯回路部分" }
```

**Response 201**

---

### 9.11 导出

```
POST /api/v1/notes/export
```

**Request**
```json
{
  "note_ids": ["note_1", "note_2"],
  "format": "markdown",
  "include_front_matter": true,
  "scope": "selected"
}
```

| 字段 | 说明 |
|------|------|
| `format` | `markdown` / `pdf` |
| `scope` | `selected`（按 note_ids） / `book`（按 book_id） |

**Response 200**
```json
{
  "code": 0,
  "data": {
    "download_url": "/api/v1/files/exports/export_20261015_091000.zip",
    "file_name": "笔记导出_20261015.zip",
    "file_size": 102400,
    "expires_at": "2026-10-15T10:00:00.000Z"
  },
  "trace_id": "..."
}
```

> `include_front_matter=true` 时，每条 md 头部带 YAML front-matter（书名、页码、标签、创建时间）。

---

### 9.12 标签管理

**列表**
```
GET /api/v1/tags
```
```json
{
  "code": 0,
  "data": {
    "items": [
      {"tag_id":"tag_1","name":"读书会","color":"#409EFF","note_count":5},
      {"tag_id":"tag_2","name":"高频考点","color":"#67C23A","note_count":12}
    ]
  }
}
```

**创建**
```
POST /api/v1/tags
```
```json
{ "name": "方法论", "color": "#E6A23C" }
```
**Response 201**

---

## 10. 系统设置接口

### 10.1 模型参数

```
GET  /api/v1/settings/model
PUT  /api/v1/settings/model
```

**GET Response 200**
```json
{
  "code": 0,
  "data": {
    "temperature": 0.3,
    "max_tokens": 2048,
    "top_p": 0.9,
    "stream": true
  }
}
```

**PUT Request**
```json
{ "temperature": 0.5, "max_tokens": 3072 }
```

**Response 200**：返回更新后的完整参数对象

**校验**：`temperature ∈ [0,1]`、`max_tokens ∈ [256,4096]`、`top_p ∈ [0.7,1]` → 越界返回 `10001`

---

### 10.2 RAG 参数

```
GET  /api/v1/settings/rag
PUT  /api/v1/settings/rag
```

**GET Response 200**
```json
{
  "code": 0,
  "data": {
    "recall_top_k": 10,
    "rerank_top_n": 5,
    "similarity_threshold": 0.5,
    "chunk_size": 600,
    "overlap": 100,
    "multi_turn_n": 3,
    "enable_rerank": true,
    "enable_metadata_filter": true
  }
}
```

**PUT**：支持单字段更新，立即生效（新请求生效，进行中的请求不中断）

---

### 10.3 Prompt 模板

**列表**
```
GET /api/v1/settings/prompts
```
```json
{
  "code": 0,
  "data": {
    "items": [
      {"name":"book_qa_system","active_version":2,"total_versions":3,"updated_at":"..."},
      {"name":"summary_map","active_version":1,"total_versions":1,"updated_at":"..."}
    ]
  }
}
```

**详情**
```
GET /api/v1/settings/prompts/{name}?version=latest
```
```json
{
  "code": 0,
  "data": {
    "name": "book_qa_system",
    "version": 2,
    "is_active": true,
    "content": "你是一个严谨的读书助手...",
    "description": "收紧引用格式要求",
    "author": "admin",
    "created_at": "..."
  }
}
```

**保存新版本**
```
POST /api/v1/settings/prompts/{name}
```
```json
{ "content": "你是一个严谨的读书助手...", "description": "调整引用格式" }
```

**Response 201**：返回新版本号对象

**规则**：版本号 +1，`is_active=1`，旧版本 `is_active=0`；超过 10 版自动归档到 `archive/`

**错误**：`60002`（YAML 语法错误）

**回滚**
```
POST /api/v1/settings/prompts/{name}/versions/{version}/activate
```
**Response 200**

---

### 10.4 用量统计

```
GET /api/v1/settings/usage?start_date=2026-10-01&end_date=2026-10-15&group_by=day
```

**Response 200**
```json
{
  "code": 0,
  "data": {
    "summary": {
      "total_input_tokens": 1250000,
      "total_output_tokens": 320000,
      "total_calls": 862,
      "total_cost_cny": 12.45
    },
    "budget": {
      "daily_input_limit": 80000,
      "daily_output_limit": 20000,
      "today_used_input": 45200,
      "today_used_output": 11800,
      "usage_percent": 56.5,
      "alert_level": "normal"
    },
    "by_feature": [
      {"feature":"book_qa","calls":520,"input_tokens":780000,"output_tokens":210000},
      {"feature":"summary_map","calls":240,"input_tokens":420000,"output_tokens":95000}
    ],
    "timeline": [
      {"date":"2026-10-14","input_tokens":82000,"output_tokens":21000,"calls":58}
    ]
  },
  "trace_id": "..."
}
```

| `alert_level` | 含义 |
|---------------|------|
| `normal` | < 80% |
| `warning` | ≥ 80% |
| `blocked` | ≥ 100%，AI 调用暂停 |

**说明**：`blocked` 状态下调用 AI 接口返回 `60005`。

---

### 10.5 备份与恢复

**列表**
```
GET /api/v1/settings/backups
```
```json
{
  "code": 0,
  "data": {
    "items": [
      {
        "backup_id": "bk_2026xxx",
        "backup_type": "auto",
        "trigger": "scheduled",
        "file_size": 104857600,
        "sha256": "a1b2c3...",
        "status": "SUCCESS",
        "started_at": "2026-10-14T03:00:00.000Z",
        "finished_at": "2026-10-14T03:00:08.000Z"
      }
    ]
  }
}
```

**创建**
```
POST /api/v1/settings/backups
```
```json
{ "backup_type": "manual", "trigger": "manual_click" }
```

**Response 202**
```json
{
  "code": 0,
  "data": {"backup_id":"bk_2026xxx","status":"RUNNING"},
  "trace_id": "..."
}
```

**说明**：备份内容 = SQLite + `uploads/` + Chroma snapshot → `backups/{ts}.zip`，计算 SHA-256。

**恢复**
```
POST /api/v1/settings/backups/{backup_id}/restore
```

**Response 200**（服务会短暂中断后重启）
```json
{
  "code": 0,
  "message": "恢复已开始，服务将重启",
  "data": {"backup_id":"bk_2026xxx","verified":true},
  "trace_id": "..."
}
```

**错误**：`60004`（SHA-256 校验失败，阻断恢复）

---

### 10.6 日志查询

```
GET /api/v1/settings/logs?trace_id=a1b2c3&level=ERROR&start=2026-10-15T08:00:00Z&limit=100
```

**Query**

| 参数 | 说明 |
|------|------|
| `trace_id` | 精确追踪单条链路 |
| `level` | `DEBUG`/`INFO`/`WARN`/`ERROR` |
| `module` | 模块名过滤（如 `rag.retrieve`） |
| `start` / `end` | 时间区间 |

**Response 200**
```json
{
  "code": 0,
  "data": {
    "total": 7,
    "items": [
      {
        "timestamp": "2026-10-15T10:23:45.123Z",
        "level": "INFO",
        "trace_id": "a1b2c3",
        "module": "rag.retrieve",
        "message": "query=\"作者在5.2节怎么解释这个概念\", top_k=10, hits=10"
      }
    ]
  },
  "trace_id": "..."
}
```

---

## 11. RAG 评估接口

### 11.1 运行评估

```
POST /api/v1/eval/run
```

**Request**
```json
{
  "dataset_version": "v1.0",
  "categories": ["book_qa_simple", "book_qa_located", "book_out_of_scope", "cross_book_qa", "note_qa"],
  "sample_size": 50
}
```

**Response 202**
```json
{
  "code": 0,
  "data": {
    "eval_run_id": "eval_2026xxx",
    "status": "RUNNING",
    "total_items": 50
  },
  "trace_id": "..."
}
```

**说明**：后端从 `eval_datasets` 取题，逐条跑完整 RAG 链路，计算 §4.7.2 的 7 项指标。

---

### 11.2 评估结果列表

```
GET /api/v1/eval/results?page=1&page_size=10
```

**Response 200**：`eval_results` 列表（精简字段）

---

### 11.3 单个评估报告

```
GET /api/v1/eval/results/{eval_run_id}
```

**Response 200**
```json
{
  "code": 0,
  "data": {
    "eval_run_id": "eval_2026xxx",
    "dataset_version": "v1.0",
    "started_at": "2026-10-15T10:00:00.000Z",
    "finished_at": "2026-10-15T10:03:20.000Z",
    "total_items": 50,
    "pass_items": 43,
    "recall_at_5": 0.84,
    "recall_at_10": 0.92,
    "answer_accuracy": 0.78,
    "refusal_accuracy": 0.97,
    "citation_accuracy": 0.88,
    "avg_first_token_ms": 980,
    "avg_full_answer_ms": 6200,
    "report_path": "reports/eval_report_20261015.md",
    "fail_items": [
      {
        "item_id": "item_007",
        "category": "book_qa_located",
        "question": "第 3.4 节提到的三个步骤是什么",
        "expected": "暗示、惯常行为、奖赏",
        "got": "本书中未提及相关内容",
        "diff_note": "章节过滤过严导致召回为空"
      }
    ]
  },
  "trace_id": "..."
}
```

---

## 12. 健康检查与运维接口

### 12.1 整体健康

```
GET /api/v1/health
```

**Response 200**
```json
{
  "code": 0,
  "data": {
    "status": "healthy",
    "version": "1.0.0",
    "uptime_seconds": 86400,
    "components": {
      "sqlite": {"status":"up","wal_mode":"on"},
      "chroma": {"status":"up","collections":42},
      "doubao_api": {"status":"up","latency_ms":210},
      "disk": {"status":"up","free_gb":120}
    },
    "active_tasks": {"building":1,"ai_running":0}
  },
  "trace_id": "..."
}
```

**Response 503**（组件异常）
```json
{
  "code": 70001,
  "message": "知识库服务未启动，请检查 Chroma",
  "data": {"components":{"chroma":{"status":"down"}}},
  "trace_id": "..."
}
```

---

### 12.2 Chroma 状态

```
GET /api/v1/health/chroma
```

**Response 200**
```json
{
  "code": 0,
  "data": {
    "status": "up",
    "host": "127.0.0.1:8000",
    "collections": [
      {"name":"book_book_2026a1","count":624,"dimension":1024},
      {"name":"notes_all","count":86,"dimension":1024}
    ],
    "embedding_dim_matched": true
  },
  "trace_id": "..."
}
```

**错误**：`70001`（未启动）、`70002`（维度不匹配）

---

## 13. SSE 流式协议

### 13.1 连接约定

| 项 | 值 |
|----|----|
| Content-Type | `text/event-stream` |
| Cache-Control | `no-cache` |
| Connection | `keep-alive` |
| 心跳 | 每 15s 发送 `:ping\n\n`（注释行） |
| 编码 | UTF-8 |

### 13.2 事件类型

| event | 时机 | data 结构 |
|-------|------|-----------|
| `meta` | 检索完成后、LLM 开始前 | `{task_id, retrieved_chunks[], rewritten_question}` |
| `delta` | LLM 每产生一个 chunk | `{content}` |
| `citation` | 回答中解析出引用时（可多次） | `{source_type, book_id/note_id, page, snippet, chunk_id}` |
| `done` | 生成结束 | `{message_id, version_id, token_usage, latency_ms, refused?}` |
| `error` | 生成失败 | `{code, message}` |
| `abort` | 用户主动取消 | `{task_id}` |

### 13.3 完整示例

```
:ping

event: meta
data: {"task_id":"task_2026xxx","retrieved_chunks":[{"chunk_id":"chunk_1","page":102,"score":0.87}],"rewritten_question":"作者在第 5.2 节如何解释习惯回路这一概念"}

event: delta
data: {"content":"作者在第 5.2 节认为，"}

event: delta
data: {"content":"习惯回路由三个要素构成：暗示、惯常行为和奖赏。"}

event: citation
data: {"source_type":"book","book_id":"book_2026a1","book_title":"认知觉醒","page":102,"snippet":"习惯回路包括暗示、惯常行为、奖赏...","chunk_id":"chunk_1"}

event: done
data: {"message_id":"msg_2026xxx","version_id":"ver_2026xxx","token_usage":{"input_tokens":1200,"output_tokens":350},"latency_ms":4200,"refused":false}

```

### 13.4 客户端处理建议
1. `meta` → 展示"已检索到 N 个相关片段"，提升感知速度
2. `delta` → 增量拼接渲染，首字延迟目标 ≤ 1s
3. `citation` → 收集并渲染为可点击的页码角标
4. `done` → 落库完成，写入 `message_id` 便于"存为笔记"
5. `error` → 展示 `message`，任务标记失败，可重试
6. 断线 → 客户端应提示"连接中断"，服务端任务标记 FAILED，用户可重新生成

---

## 14. 限流与幂等

### 14.1 限流规则

| 接口类别 | 限制 | 说明 |
|---------|------|------|
| 问答类（`/ai/qa/*`） | 20 次/分钟/IP | 返回 `10003` |
| 上传（`/books/upload`） | 5 次/分钟/IP | — |
| 摘要/导图生成 | 3 次/分钟/IP | 重量级任务 |
| 其他查询 | 120 次/分钟/IP | — |

**超限响应**
```json
{
  "code": 10003,
  "message": "操作过于频繁，请稍后再试",
  "data": {"retry_after_seconds": 42},
  "trace_id": "..."
}
```

### 14.2 幂等策略

| 场景 | 策略 |
|------|------|
| 上传重名 | 需 `confirm_duplicate=true` 才真正创建 |
| 笔记编辑 | `editor_version` 乐观锁，冲突返回 `50004` |
| 重复提交摘要/导图 | 检查 `ai_tasks` 是否已有 `QUEUED/RUNNING` 同 target 任务，有则返回 `40004` |
| 删除重试 | 状态机保证：已 DELETED 再删返回 `20005`，不报错 |
| 回流开关 | 重复 `enable=true` 且已 INDEXED 时直接返回成功，不重复嵌入 |

---

## 15. 版本兼容策略

1. **URL 版本**：路径前缀 `/api/v1`，破坏性变更升 `/api/v2`
2. **字段兼容**：
   - 新增字段：直接加，旧客户端忽略
   - 删除字段：先标 `deprecated` 两个版本，再移除
   - 改字段名：同时返回新旧两个字段，过渡期后移除旧的
3. **枚举扩展**：客户端应对未知枚举值做容错处理（不崩溃）
4. **错误码**：新增错误码需同步更新本文档 §3.2
5. **SSE 事件**：新增 event 类型时，旧客户端应忽略不识别的 event

---

> **API 接口文档结束**。
>
> **配套文档**：
> - 《需求分析说明书 V2.2》——功能、状态机、验收标准
> - 《数据库设计说明书 V1.0》——表结构、字段、索引（本文件的 `data.xxx` 字段均对应其中的表字段）
