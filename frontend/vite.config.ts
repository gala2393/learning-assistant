import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import path from 'path'

const devProxyTarget = process.env.VITE_DEV_PROXY_TARGET || 'http://127.0.0.1:8080'

export default defineConfig({
  plugins: [react()],
  build: {
    rollupOptions: {
      output: {
        /**
         * 将稳定的大依赖拆成独立 chunk，避免所有页面共用同一个超大 index 包。
         * 这里优先拆 PDF.js、React 运行时、表格/动画等重依赖；业务页面继续按现有路由加载。
         */
        manualChunks(id) {
          const normalizedId = id.replace(/\\/g, '/')
          if (!normalizedId.includes('/node_modules/')) return undefined
          if (normalizedId.includes('/node_modules/pdfjs-dist/')) return 'pdf-viewer'
          if (normalizedId.includes('/node_modules/@tanstack/')) return 'data-grid'
          if (normalizedId.includes('/node_modules/framer-motion/')) return 'motion'
          if (normalizedId.includes('/node_modules/@radix-ui/') || normalizedId.includes('/node_modules/lucide-react/')) return 'ui-vendor'
          if (
            normalizedId.includes('/node_modules/react/')
            || normalizedId.includes('/node_modules/react-dom/')
            || normalizedId.includes('/node_modules/react-router-dom/')
            || normalizedId.includes('/node_modules/@remix-run/')
            || normalizedId.includes('/node_modules/scheduler/')
            || normalizedId.includes('/node_modules/use-sync-external-store/')
          ) {
            return 'react-vendor'
          }
          return undefined
        },
      },
    },
  },
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  server: {
    port: 5174,
    proxy: {
      '/api': {
        target: devProxyTarget,
        changeOrigin: true,
        configure: (proxy) => {
          proxy.on('proxyReq', (proxyReq) => {
            // 本地开发时浏览器先请求 Vite 同源 /api，再由 Vite 转发给后端。
            // 如果把浏览器 Origin 原样转发到后端，后端会把代理请求当作跨域请求校验，
            // SSE 流式问答容易被判定为 Invalid CORS request。
            proxyReq.removeHeader('Origin')
          })
        },
      },
    },
  },
})
