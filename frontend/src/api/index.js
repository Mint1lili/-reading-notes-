import http from './http'

// ===== 健康检查 =====
export const health = () => http.get('/health')

// =============================================================================
// 书籍管理（F1）
// =============================================================================
export const listBooks = (params) => http.get('/books', { params })
export const getBook = (bookId) => http.get(`/books/${bookId}`)
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

// 回收站
export const listTrash = (params) => http.get('/books/trash', { params })
export const restoreBook = (bookId) => http.post(`/books/${bookId}/restore`)
export const purgeBook = (bookId) => http.delete(`/books/${bookId}/purge`)

/**
 * 上传 PDF。
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
// 构建任务（F2）
// =============================================================================
export const getBuildStatus = (bookId) => http.get(`/books/${bookId}/build/status`)

/** 重试 / 断点续传：body { from_batch }，省略则从 last_completed_batch 续跑 */
export const retryBuild = (bookId, fromBatch) =>
  http.post(`/books/${bookId}/build/retry`,
    fromBatch === undefined || fromBatch === null ? {} : { from_batch: fromBatch })

export const cancelBuild = (bookId) => http.post(`/books/${bookId}/build/cancel`)
export const getBuildPreview = (bookId) => http.get(`/books/${bookId}/build/preview`)

export const listChapters = (bookId) => http.get(`/books/${bookId}/chapters`)
export const listChunks = (bookId, params) => http.get(`/books/${bookId}/chunks`, { params })

// =============================================================================
// AI 交互（F3，SSE 由 ReaderView 直接 fetch 处理）
// =============================================================================
// export const qaStream = (payload) => ...

// =============================================================================
// 笔记（F4）
// =============================================================================
// export const listNotes = (params) => http.get('/notes', { params })

// =============================================================================
// 设置（F5）
// =============================================================================
// export const getRagSettings = () => http.get('/settings/rag')
