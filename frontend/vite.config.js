import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 前端开发服务器：5173 → 后端 8080（CORS 已在后端配置）
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true
      }
    }
  },
  build: {
    rollupOptions: {
      output: {
        manualChunks(id) {
          // 拆出体积大户，避免主包 1MB+：element-plus / markmap / 其它第三方库
          if (id.includes('node_modules')) {
            if (id.includes('element-plus')) return 'element-plus'
            if (id.includes('markmap') || id.includes('d3')) return 'markmap'
            return 'vendor'
          }
        }
      }
    },
    chunkSizeWarningLimit: 600
  }
})
