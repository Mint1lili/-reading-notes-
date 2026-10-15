import { createRouter, createWebHistory } from 'vue-router'

const routes = [
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
