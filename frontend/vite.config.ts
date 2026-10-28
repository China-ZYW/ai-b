import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 后端地址（联调）：可用环境变量 VITE_BACKEND 覆盖，默认本机 8080
const backend = process.env.VITE_BACKEND || 'http://localhost:8080'

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  server: {
    host: '0.0.0.0',
    port: 5173,
    // 后端禁用了 CORS，故开发期统一走 Vite 代理转发 /api，规避跨域
    proxy: {
      '/api': {
        target: backend,
        changeOrigin: true,
        // SSE 流式：关闭代理缓冲，保证分步事件实时下发
        configure: (proxy) => {
          proxy.on('proxyRes', (proxyRes) => {
            const ct = proxyRes.headers['content-type'] || ''
            if (String(ct).includes('text/event-stream')) {
              proxyRes.headers['cache-control'] = 'no-cache, no-transform'
              proxyRes.headers['x-accel-buffering'] = 'no'
            }
          })
        }
      }
    }
  },
  build: {
    outDir: 'dist',
    chunkSizeWarningLimit: 1200,
    rollupOptions: {
      output: {
        // ECharts / Element Plus 体积大，单独分包便于强缓存
        manualChunks: {
          echarts: ['echarts'],
          elementPlus: ['element-plus', '@element-plus/icons-vue'],
          vendor: ['vue', 'vue-router', 'pinia', 'axios']
        }
      }
    }
  }
})
