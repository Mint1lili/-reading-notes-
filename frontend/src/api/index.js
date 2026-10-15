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
// export const listNotes = (params) => http.get('/notes', { params })

// ===== 设置（F5） =====
// export const getRagSettings = () => http.get('/settings/rag')
