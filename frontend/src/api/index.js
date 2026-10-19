import http from './http'

// ===== 健康检查 =====
export const health = () => http.get('/health')

// ===== 书籍管理（F1） =====
export const listBooks = (params) => http.get('/books', { params })
export const getBook = (bookId) => http.get(`/books/${bookId}`)
// 待后端实现后启用：
// export const uploadBook = (formData) => http.post('/books/upload', formData, { headers: { 'Content-Type': 'multipart/form-data' } })

// ===== AI 交互（F3，SSE 由 ReaderView 直接 fetch 处理） =====
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

/** 导出笔记（API §9.11；scope=selected/book，format=markdown/pdf） */
export const exportNotes = (payload) => http.post('/notes/export', payload)

// ===== 标签（F4 第二阶段） =====

/** 标签列表（含 note_count） */
export const listTags = () => http.get('/tags')

/** 创建标签 */
export const createTag = (payload) => http.post('/tags', payload)

// ===== 设置（F5） =====
// export const getRagSettings = () => http.get('/settings/rag')
