import axios from 'axios'
import { ElMessage } from 'element-plus'
import { getToken, clearAuth } from '../utils/token'

/**
 * axios 实例：全部请求走同源 /api/v1（开发环境由 Vite proxy 转发到 8080）。
 *
 * 约定：后端所有响应都是 { code, message, data, trace_id } 信封，
 * 因此调用方拿到的直接是 data，不需要再判断 code。
 */
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

/**
 * 响应拦截：
 * - code === 0：解包返回 data
 * - code !== 0：业务错误（含 40002 拒答，message 本身就是给用户看的文案），
 *   统一 warning 提示并 reject，调用方按需再 catch
 * - 80008 未登录 / token 过期：清除本地登录态，跳登录页
 *
 * 注意：信封里的 trace_id 目前只用于后端日志排查，前端未展示；
 * 若要在报错弹窗里带 trace，改这里统一拼到 message 上即可。
 */
http.interceptors.response.use(
  (resp) => {
    // 204 No Content（删除/彻底删除等成功响应无 body）：视为成功，返回 null
    if (resp.status === 204) {
      return null
    }
    const body = resp.data
    if (body && body.code === 0) {
      return body.data
    }
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
    // 404（资源不存在）：完全静默 reject，由调用方按场景提示
    // （如删除已进回收站的书 → 幂等视为成功，前端显示"该书已在回收站"）
    if (err.response?.status === 404) {
      return Promise.reject(err)
    }
    // 传输层错误（网络断开、5xx 无信封、超时）——按真实原因分类给用户可操作的文案
    let msg
    if (err.code === 'ECONNABORTED') {
      msg = '请求超时，请稍后重试'
    } else if (!err.response) {
      msg = '网络连接失败，请确认后端服务已启动（IDEA 运行 backend，Chroma 在 8000 端口）'
    } else if (err.response.status === 502 || err.response.status === 504) {
      msg = '后端或 Chroma 向量库无响应，请检查两个服务是否在运行'
    } else if (err.response.status >= 500) {
      msg = '后端服务异常（' + err.response.status + '），请查看后端日志后重试'
    } else {
      msg = err.response?.data?.message || '请求失败（' + (err.response.status || '') + '）'
    }
    ElMessage.error(msg)
    return Promise.reject(err)
  }
)

export default http
