import axios from 'axios'
import { ElMessage } from 'element-plus'

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

/**
 * 响应拦截：
 * - code === 0：解包返回 data
 * - code !== 0：业务错误（含 40002 拒答，message 本身就是给用户看的文案），
 *   统一 warning 提示并 reject，调用方按需再 catch
 *
 * 注意：信封里的 trace_id 目前只用于后端日志排查，前端未展示；
 * 若要在报错弹窗里带 trace，改这里统一拼到 message 上即可。
 */
http.interceptors.response.use(
  (resp) => {
    const body = resp.data
    if (body && body.code === 0) {
      return body.data
    }
    ElMessage.warning(body?.message || '请求失败')
    return Promise.reject(new Error(body?.message || 'request failed'))
  },
  (err) => {
    // 传输层错误（网络断开、5xx 无信封、超时）
    const msg = err.response?.data?.message || '网络异常，请检查后端与 Chroma 服务'
    ElMessage.error(msg)
    return Promise.reject(err)
  }
)

export default http
