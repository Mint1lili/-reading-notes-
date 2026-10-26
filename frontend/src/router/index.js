import { createRouter, createWebHistory } from 'vue-router'
import { getToken } from '../utils/token'

/**
 * 路由表：一级页面全部懒加载（首屏只加载 LibraryView），
 * 页面名与左侧导航一一对应，F3/F4/F5 目前是占位页。
 */
const routes = [
  // 根路径直接进书库
  { path: '/login', name: 'Login', component: () => import('../views/LoginView.vue'), meta: { public: true } },
  { path: '/', redirect: '/library' },
  { path: '/library', name: 'Library', component: () => import('../views/LibraryView.vue') },
  { path: '/reader', name: 'Reader', component: () => import('../views/ReaderView.vue') },
  { path: '/book/:bookId/read', name: 'Read', component: () => import('../views/ReadingView.vue') },
  { path: '/notes', name: 'Notes', component: () => import('../views/NotesView.vue') },
  { path: '/eval', name: 'Eval', component: () => import('../views/EvalView.vue') },
  { path: '/settings', name: 'Settings', component: () => import('../views/SettingsView.vue') }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

// 全局守卫：未登录一律跳登录页；已登录访问登录页则回书库
router.beforeEach((to) => {
  const token = getToken()
  if (!to.meta.public && !token) {
    return { path: '/login', query: to.fullPath !== '/' ? { redirect: to.fullPath } : {} }
  }
  if (to.path === '/login' && token) {
    return { path: '/library' }
  }
  return true
})

export default router
