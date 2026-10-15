import axios from 'axios'
import { ElMessage } from 'element-plus'

// axios 封装：统一响应信封解包 + 错误提示 + trace_id 透传
const http = axios.create({
  baseURL: '/api/v1',
  timeout: 30000
})

// 响应拦截：解包 { code, message, data, trace_id }
http.interceptors.response.use(
  (resp) => {
    const body = resp.data
    if (body && body.code === 0) {
      return body.data
    }
    // 业务错误（含 40002 拒答：message 即用户提示）
    ElMessage.warning(body?.message || '请求失败')
    return Promise.reject(new Error(body?.message || 'request failed'))
  },
  (err) => {
    const msg = err.response?.data?.message || '网络异常，请检查后端与 Chroma 服务'
    ElMessage.error(msg)
    return Promise.reject(err)
  }
)

export default http
