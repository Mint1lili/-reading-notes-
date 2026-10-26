/**
 * 前端 API 层：按功能模块分组，一个后端接口对应一个函数。
 *
 * 约定：
 * - 返回值已是响应信封里的 data（由 http.js 统一解包），调用方无需判断 code；
 * - 参数名沿用后端的下划线风格（page_size / chunk_size / from_batch），
 *   避免在两层之间来回转换命名，减少对不上的风险；
 * - 失败一律 reject，页面层按需 catch。
 */
import http from './http'

// ===== 健康检查 =====
/** 整体健康：status / chroma / 豆包 key 是否配置 */
export const health = () => http.get('/health')

// =============================================================================
// 书籍管理（F1）
// =============================================================================
/** 分页列表：{ page, page_size, keyword?, status? } */
export const listBooks = (params) => http.get('/books', { params })

/** 详情（含构建状态与识别率） */
export const getBook = (bookId) => http.get(`/books/${bookId}`)

/** 编辑元数据：{ title?, author? } */
export const updateBook = (bookId, payload) => http.patch(`/books/${bookId}`, payload)

/**
 * 软删除（移入回收站）。
 * 后端契约是 DELETE + JSON body（{ keep_notes }），axios 的 delete 需要显式传 data。
 */
export const deleteBook = (bookId, payload = { keep_notes: true }) =>
  http.delete(`/books/${bookId}`, { data: payload })

/** 重建知识库：body { chunk_size, overlap }，返回 202 */
export const rebuildBook = (bookId, payload = {}) =>
  http.post(`/books/${bookId}/rebuild`, payload)

// 回收站：列表 / 恢复 / 彻底删除
export const listTrash = (params) => http.get('/books/trash', { params })

/** 恢复：原文件还在则自动重建，否则回到 FAILED 并提示重新上传 */
export const restoreBook = (bookId) => http.post(`/books/${bookId}/restore`)

/** 彻底删除：PDF、切片、向量一并清除，不可恢复 */
export const purgeBook = (bookId) => http.delete(`/books/${bookId}/purge`)

/**
 * 上传 PDF（一次性，适合 ≤10MB 的小文件）。
 * @param {File} file
 * @param {{title?:string, author?:string, confirm_duplicate?:boolean}} meta
 *        confirm_duplicate=true 用于重名二次确认（后端 20003 → 前端确认后重发）
 * @param {(percent:number)=>void} onProgress 上传进度回调（0~100）
 */
export const uploadBook = (file, meta = {}, onProgress) => {
  const form = new FormData()
  form.append('file', file)
  if (meta.title) form.append('title', meta.title)
  if (meta.author) form.append('author', meta.author)
  if (meta.confirm_duplicate) form.append('confirm_duplicate', 'true')
  return http.post('/books/upload', form, {
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: 0, // 大文件不设超时
    onUploadProgress: (e) => {
      if (onProgress && e.total) onProgress(Math.round((e.loaded / e.total) * 100))
    }
  })
}

// =============================================================================
// 分片上传（断点续传）：init → 逐片 PUT → complete
// =============================================================================

/** 初始化会话；同文件、同大小重复调用会复用未过期会话 */
export const uploadInit = (fileName, fileSize) =>
  http.post('/books/upload/init', { fileName, fileSize })

/** 上传单片（index 从 0 开始）；同一下标重复上传会覆盖，天然幂等 */
export const uploadChunk = (uploadId, index, blob) => {
  const form = new FormData()
  form.append('file', blob)
  return http.put(`/books/upload/${uploadId}/chunk?index=${index}`, form, {
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: 0
  })
}

/** 查询进度：received / missing / progress / complete，断线重连后据此补片 */
export const uploadStatus = (uploadId) => http.get(`/books/upload/${uploadId}`)

/** 合并分片并入库；返回与一次性上传相同的结构 */
export const uploadComplete = (uploadId, meta = {}) =>
  http.post(`/books/upload/${uploadId}/complete`, {
    title: meta.title,
    author: meta.author,
    confirmDuplicate: !!meta.confirm_duplicate
  })

/** 放弃上传：清除临时分片与会话 */
export const uploadAbort = (uploadId) => http.delete(`/books/upload/${uploadId}`)

/**
 * 分片上传（>10MB 时使用）：断网/刷新后从 localStorage 取回 uploadId，只补缺失片。
 * 单片刻重试 3 次，仍失败则抛错并把进度留在本地，下次进入还能续。
 *
 * @param {File} file
 * @param {{title?:string, author?:string, confirm_duplicate?:boolean}} meta
 * @param {(percent:number)=>void} onProgress
 */
export const uploadBookResumable = async (file, meta = {}, onProgress) => {
  const cacheKey = `rn_upload_${file.name}_${file.size}`
  let uploadId = localStorage.getItem(cacheKey)
  let session = null
  if (uploadId) {
    try {
      session = await uploadStatus(uploadId)
    } catch {
      // 会话过期：重新初始化
      uploadId = null
    }
  }
  if (!session) {
    session = await uploadInit(file.name, file.size)
    uploadId = session.upload_id
    localStorage.setItem(cacheKey, uploadId)
  }

  const chunkSize = session.chunk_size
  const total = session.total_chunks
  let done = (session.received || []).length
  for (const index of session.missing || []) {
    const start = index * chunkSize
    const blob = file.slice(start, Math.min(start + chunkSize, file.size))
    let ok = false
    let lastErr = null
    for (let attempt = 0; attempt < 3 && !ok; attempt++) {
      try {
        await uploadChunk(uploadId, index, blob)
        ok = true
      } catch (e) {
        lastErr = e
        await new Promise((r) => setTimeout(r, 300 * (attempt + 1)))
      }
    }
    if (!ok) throw lastErr
    done += 1
    if (onProgress) onProgress(Math.min(99, Math.round((done / total) * 100)))
  }

  try {
    const data = await uploadComplete(uploadId, meta)
    localStorage.removeItem(cacheKey)
    return data
  } catch (e) {
    // 20003 重名：后端保留了会话，确认后可直接重试，不必重传
    if (e?.response?.data?.code !== 20003) {
      localStorage.removeItem(cacheKey)
    }
    throw e
  }
}

/** 删除预检：二次确认弹窗展示影响面 */
export const deletePreview = (bookId) => http.get(`/books/${bookId}/delete-preview`)

/** 原书 PDF 地址（引用溯源在新窗口打开） */
export const bookFileUrl = (bookId) => `/api/v1/books/${bookId}/file`

// =============================================================================
// 构建任务（F2）
// =============================================================================

/** 构建状态：前端轮询用，含 build_progress / last_completed_batch / warnings */
export const getBuildStatus = (bookId) => http.get(`/books/${bookId}/build/status`)

/** 重试 / 断点续传：body { from_batch }，省略则从 last_completed_batch 续跑 */
export const retryBuild = (bookId, fromBatch) =>
  http.post(`/books/${bookId}/build/retry`,
    fromBatch === undefined || fromBatch === null ? {} : { from_batch: fromBatch })

/** 取消构建：已完成的批次保留，仍可 retry 续跑 */
export const cancelBuild = (bookId) => http.post(`/books/${bookId}/build/cancel`)

/**
 * 构建统计 / 构建前试算。
 * 不传参 → 已落库切片实测；传 { granularity } 或 { chunk_size } → 内存试算（不落库、不调 Embedding）
 */
export const getBuildPreview = (bookId, params) =>
  http.get(`/books/${bookId}/build/preview`, { params })

/** 章节结构：扁平列表（含 level / chapter_path），前端自行还原树 */
export const listChapters = (bookId) => http.get(`/books/${bookId}/chapters`)

/** 切片分页：{ page, page_size, chapter? }，chapter 为路径前缀 */
export const listChunks = (bookId, params) => http.get(`/books/${bookId}/chunks`, { params })

// =============================================================================
// AI 交互（F3，SSE 由 ReaderView 直接 fetch 处理）
// =============================================================================
// export const qaStream = (payload) => ...

// ===== 笔记（F4） =====
// 后端接口前缀：/notes（CRUD + 回流 + 链接）、/tags（标签字典）
// 全部经 http 统一拦截器解包 { code, message, data, trace_id }，调用方拿到 data。

/** 笔记列表
 * @param {Object} params  支持 book_id / source_type / keyword / tag / indexed / page / pageSize
 *                         indexed 为布尔值；http 拦截器自动序列化为 "true"/"false"
 */
export const listNotes = (params) => http.get('/notes', { params })

/** 笔记详情（含 tags、links、can_jump_to_source） */
export const getNote = (noteId) => http.get(`/notes/${noteId}`)

/** 新建笔记（API §9.1）；tag_names 不存在则后端自动创建 */
export const createNote = (payload) => http.post('/notes', payload)

/** 编辑笔记（API §9.4）；需传 editor_version 乐观锁；tag_names 全量覆盖（缺失则不动） */
export const updateNote = (noteId, payload) => http.patch(`/notes/${noteId}`, payload)

/** 软删除（回收站） */
export const deleteNote = (noteId) => http.delete(`/notes/${noteId}`)

/** 恢复软删笔记 */
export const restoreNote = (noteId) => http.post(`/notes/${noteId}/restore`)

/** 永久删除（不可恢复，会清向量 + 置灰双向链接） */
export const purgeNote = (noteId) => http.delete(`/notes/${noteId}/purge`)

/** 双向链接查询（outgoing + incoming） */
export const getNoteLinks = (noteId) => http.get(`/notes/${noteId}/links`)

/** 创建双向链接；同对幂等 */
export const createNoteLink = (noteId, payload) => http.post(`/notes/${noteId}/links`, payload)

/** 知识回流开关（API §9.8；enable=true 入库 Chroma，false 关闭并软删向量） */
export const toggleNoteIndex = (noteId, enable) => http.post(`/notes/${noteId}/index`, { enable })

/** AI 产物一键存为笔记（API §9.9；source_type=AI，来源书必须真实存在） */
export const createNoteFromAi = (payload) => http.post('/notes/from-ai', payload)

// ===== AI 书本问答（F3） =====
/** 书本内 RAG 问答：body { book_id, question }，返回 { answer, citations } */
export const aiQa = (payload) => http.post('/ai/qa', payload)

/** 导出笔记（API §9.11；scope=selected/book，format=markdown/pdf） */
export const exportNotes = (payload) => http.post('/notes/export', payload)

// ===== 标签（F4 第二阶段） =====

/** 标签列表（含 note_count） */
export const listTags = () => http.get('/tags')

/** 创建标签 */
export const createTag = (payload) => http.post('/tags', payload)

// =============================================================================
// 设置（F5）
// =============================================================================
// export const getRagSettings = () => http.get('/settings/rag')

// =============================================================================
// F3 AI 读书扩展（组员 feat/ai-core：SSE 流式问答 / 摘要 / 思维导图 / 任务轮询）
// =============================================================================
import { getToken } from '../utils/token'

/**
 * SSE 流式问答通用消费（原生 fetch，不走 axios）：
 * POST /api/v1/ai/qa/stream（也可用 path 指定 /qa/cross/stream、/qa/note/stream）
 * 事件：meta → delta* → done/error（:connected/:ping 为注释行，忽略）
 * 返回 { task, done }：task.cancel() 可中断；done 为 Promise（解析为最后的完整事件对象）。
 */
export function aiQaStream(payload, handlers = {}, extra = {}) {
  const { onMeta, onDelta, onDone, onError, onOpen } = handlers
  const controller = new AbortController()
  const path = extra.path || '/qa/stream'
  const done = new Promise((resolve, reject) => {
    ;(async () => {
      try {
        const res = await fetch('/api/v1/ai' + path, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            Authorization: `Bearer ${getToken()}`
          },
          body: JSON.stringify(payload),
          signal: controller.signal
        })
        if (!res.ok || !res.body) {
          const err = await res.json().catch(() => ({}))
          reject(new Error(err.message || `HTTP ${res.status}`))
          return
        }
        onOpen && onOpen()
        const reader = res.body.getReader()
        const decoder = new TextDecoder('utf-8')
        let buf = ''
        const result = { meta: null, deltas: [], done: null, error: null }
        for (;;) {
          const { done: rd, value } = await reader.read()
          if (rd) break
          buf += decoder.decode(value, { stream: true })
          // SSE 事件以空行分隔
          const blocks = buf.split(/\r?\n\r?\n/)
          buf = blocks.pop()
          for (const blk of blocks) {
            const lines = blk.split(/\r?\n/)
            let event = 'message'
            for (const ln of lines) {
              if (ln.startsWith('event:')) event = ln.slice(6).trim()
            }
            const dataLines = lines.filter((l) => l.startsWith('data:')).map((l) => l.slice(5).trim()).join('\n')
            if (!dataLines) continue
            let obj
            try { obj = JSON.parse(dataLines) } catch { continue }
            if (event === 'meta') { result.meta = obj; onMeta && onMeta(obj) }
            else if (event === 'delta') { result.deltas.push(obj); onDelta && onDelta(obj) }
            else if (event === 'done') { result.done = obj; onDone && onDone(obj) }
            else if (event === 'error') { result.error = obj; onError && onError(obj) }
          }
        }
        resolve(result)
      } catch (e) {
        if (e.name === 'AbortError') reject(new Error('已取消'))
        else reject(e)
      }
    })()
  })
  return { task: { cancel: () => controller.abort() }, done }
}

/** 书本内问答（SSE 流式） */
export const qaStream = (payload, handlers) => aiQaStream(payload, handlers)
/** 跨书问答（SSE 流式） */
export const qaCrossStream = (payload, handlers) => aiQaStream(payload, handlers, { path: '/qa/cross/stream' })
/** 笔记问答（SSE 流式，scope: book_only/notes_only/book_and_notes） */
export const qaNoteStream = (payload, handlers) => aiQaStream(payload, handlers, { path: '/qa/note/stream' })

// ===== 全书摘要（F3.1） =====
/** 提交摘要任务：body { book_id, temperature?, detail_level? } → 202 { task_id, status, ... } */
export const submitSummary = (payload) => http.post('/ai/summary', payload)
/** 查询摘要：GET /ai/summary/{bookId}?version=latest|N → { content, version_id?, versions? } */
export const getSummary = (bookId, version = 'latest') => http.get(`/ai/summary/${bookId}`, { params: { version } })
/** 重新生成摘要（新版本，不删除旧版本） */
export const regenerateSummary = (bookId, payload = {}) => http.post(`/ai/summary/${bookId}/regenerate`, payload)

// ===== 思维导图（F3.5） =====
/** 提交导图任务：body { book_id, source?, source_version_id?, chapter_path? } → 202 */
export const submitMindmap = (payload) => http.post('/ai/mindmap', payload)
/** 查询导图：GET /ai/mindmap/{bookId}?version=latest|N → { content, ... } */
export const getMindmap = (bookId, version = 'latest') => http.get(`/ai/mindmap/${bookId}`, { params: { version } })
/** 编辑导图内容（不递增版本号） */
export const updateMindmap = (versionId, content) => http.patch(`/ai/mindmap/${versionId}`, { content })

// ===== 任务状态轮询（§7.12） =====
/** GET /ai/tasks/{taskId} → { task_id, status, progress_hint?, result_json?, error_message? } */
export const getAiTask = (taskId) => http.get(`/ai/tasks/${taskId}`)

// ===== 反馈与版本激活（F3.6） =====
/** 反馈：POST /ai/{targetType}/{targetId}/feedback，body { rating, feedback? } */
export const feedbackAi = (targetType, targetId, payload) => http.post(`/ai/${targetType}/${targetId}/feedback`, payload)
/** 版本激活：POST /ai/versions/{versionId}/activate */
export const activateAiVersion = (versionId) => http.post(`/ai/versions/${versionId}/activate`)
