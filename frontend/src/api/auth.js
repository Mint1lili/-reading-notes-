// 认证接口封装：邮箱验证码注册 / 登录 / 设置密码 / 当前用户
// 后端前缀 /api/v1/auth（http.js baseURL = /api/v1）
import http from './http'

// 发送验证码：scene = register | login | set_password
export function sendCaptcha(email, scene) {
  return http.post('/auth/captcha', { email, scene })
}

// 注册（注册即登录，返回 TokenVO）
export function register(email, code) {
  return http.post('/auth/register', { email, code })
}

// 验证码登录
export function loginByCode(email, code) {
  return http.post('/auth/login/code', { email, code })
}

// 邮箱 + 密码登录
export function loginByPassword(email, password) {
  return http.post('/auth/login/password', { email, password })
}

// 设置 / 重置密码（需邮箱验证码）
export function setPassword(email, code, password) {
  return http.post('/auth/password', { email, code, password })
}

// 当前登录用户信息（Bearer 鉴权）
export function getMe() {
  return http.get('/auth/me')
}
