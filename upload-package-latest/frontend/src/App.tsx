import { Outlet } from 'react-router-dom'
import { ToastProvider } from '@/components/ui/toast'

/**
 * 根组件 — 整个应用的最外层布局。
 *
 * 结构：
 * - ToastProvider — 全局通知/提示系统的 Provider（通过 useToast() 调用 toast() 弹出通知）
 * - Outlet — React Router 的路由出口，根据当前 URL 显示对应的页面内容
 *
 * 实际的路由配置在 routes/index.tsx 中定义，
 * RouterProvider 和 AuthProvider 在 main.tsx 中已挂载。
 */
export default function App() {
  return (
    <ToastProvider>
      <Outlet />
    </ToastProvider>
  )
}
