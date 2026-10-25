import axios from 'axios'
import { ElMessage } from 'element-plus'
import { getToken, clearAuth } from '../utils/token'

// axios 封装：统一响应信封解包 + 错误提示 + trace_id 透传
const http = axios.create({
  baseURL: '/api/v1',
  timeout: 30000
})

// 请求拦截：自动携带登录 token
http.interceptors.request.use(
  (config) => {
    const token = getToken()
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }
    return config
  },
  (err) => Promise.reject(err)
)

// 响应拦截：解包 { code, message, data, trace_id }
http.interceptors.response.use(
  (resp) => {
    const body = resp.data
    if (body && body.code === 0) {
      return body.data
    }
    // 业务错误（含 40002 拒答：message 即用户提示）
    ElMessage.warning(body?.message || '请求失败')
    // 登录态失效（80008 未登录 / token 过期）：清除本地登录态，跳登录页
    if (body?.code === 80008) {
      clearAuth()
      if (window.location.pathname !== '/login') {
        window.location.href = '/login'
      }
    }
    return Promise.reject(new Error(body?.message || 'request failed'))
  },
  (err) => {
    if (err.response?.status === 401) {
      clearAuth()
      if (window.location.pathname !== '/login') {
        window.location.href = '/login'
      }
    }
    const msg = err.response?.data?.message || '网络异常，请检查后端与 Chroma 服务'
    ElMessage.error(msg)
    return Promise.reject(err)
  }
)

export default http
