import React from 'react'
import ReactDOM from 'react-dom/client'
import { RouterProvider } from 'react-router-dom'
import { QueryClientProvider } from '@tanstack/react-query'
import { queryClient } from '@/lib/query-client'
import { AuthProvider } from '@/context/AuthContext'
import { router } from '@/routes'
import { TooltipProvider } from '@/components/ui/tooltip'
import './index.css'

document.getElementById('static-beian-footer')?.remove()

/**
 * 前端应用入口文件 — 整个 React 应用从这里开始。
 *
 * 组件嵌套顺序（从外到内）：
 * 1. React.StrictMode — 开发模式下的额外检查（检测副作用、废弃 API 等）
 * 2. QueryClientProvider — TanStack Query 数据缓存层（管理所有 API 请求的缓存和状态）
 * 3. AuthProvider — 认证上下文（管理登录状态、用户信息）
 * 4. TooltipProvider — Radix UI 提示气泡的全局 Provider
 * 5. RouterProvider — React Router 路由（根据 URL 显示对应的页面）
 */
ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <TooltipProvider>
          <RouterProvider router={router} />
        </TooltipProvider>
      </AuthProvider>
    </QueryClientProvider>
  </React.StrictMode>
)
