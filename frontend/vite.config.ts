import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import path from 'path'

const devProxyTarget = process.env.VITE_DEV_PROXY_TARGET || 'http://127.0.0.1:8080'

export default defineConfig({
  plugins: [react()],
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
