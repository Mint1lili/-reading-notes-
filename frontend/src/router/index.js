import { createRouter, createWebHistory } from 'vue-router'

/**
 * 路由表：一级页面全部懒加载（首屏只加载 LibraryView），
 * 页面名与左侧导航一一对应，F3/F4/F5 目前是占位页。
 */
const routes = [
  // 根路径直接进书库
  { path: '/', redirect: '/library' },
  { path: '/library', name: 'Library', component: () => import('../views/LibraryView.vue') },
  { path: '/reader', name: 'Reader', component: () => import('../views/ReaderView.vue') },
  { path: '/notes', name: 'Notes', component: () => import('../views/NotesView.vue') },
  { path: '/eval', name: 'Eval', component: () => import('../views/EvalView.vue') },
  { path: '/settings', name: 'Settings', component: () => import('../views/SettingsView.vue') }
]

export default createRouter({
  history: createWebHistory(),
  routes
})
