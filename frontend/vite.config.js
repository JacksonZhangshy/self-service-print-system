import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 开发环境把 /api 与 /mock-pay 代理到后端；生产由 Nginx 反向代理（见部署说明书）
// 后端不在本机 8080 时可用环境变量指定：VITE_API_TARGET=http://192.168.1.10:8080 npm run dev
const apiTarget = process.env.VITE_API_TARGET || 'http://localhost:8080'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/api': { target: apiTarget, changeOrigin: true },
      '/mock-pay': { target: apiTarget, changeOrigin: true }
    }
  },
  build: {
    chunkSizeWarningLimit: 900,
    rollupOptions: {
      output: {
        // 公共依赖单独分包：用户端首屏不必等待 echarts / 管理端代码
        manualChunks(id) {
          if (!id.includes('node_modules')) return
          if (id.includes('echarts') || id.includes('zrender')) return 'echarts'
          if (id.includes('element-plus') || id.includes('@element-plus')) return 'element-plus'
          if (id.includes('qrcode')) return 'qrcode'
          return 'vendor'
        }
      }
    }
  }
})
