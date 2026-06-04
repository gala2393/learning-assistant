/**
 * RouteFallback 组件 —— 路由兜底/默认重定向
 *
 * 【用途与使用场景】
 * 处理根路径 "/" 或任何未匹配路由的默认跳转逻辑。
 * 在路由配置中作为 catch-all 路由使用。
 *
 * 【核心逻辑】
 * 1. 认证状态加载中：显示全屏加载动画
 * 2. 未登录：重定向到 /login
 * 3. 已登录管理员：重定向到 /admin/dashboard
 * 4. 已登录普通用户：重定向到 /workspace/chat?new=1
 *
 * 这样用户访问网站根目录时，会被自动引导到合适的页面。
 */

import { Navigate } from 'react-router-dom'
import { useAuth } from '@/context/AuthContext'

export function RouteFallback() {
  // 从认证上下文获取当前用户的状态
  const { isAuthenticated, isAdmin, isLoading } = useAuth()

  // 认证状态仍在加载中，显示全屏加载动画
  if (isLoading) {
    return (
      <div className="flex h-screen items-center justify-center bg-[#eef3f7]">
        <div className="h-8 w-8 animate-spin rounded-full border-4 border-[#4b5563] border-t-transparent" />
      </div>
    )
  }

  // 未登录：重定向到登录页面
  if (!isAuthenticated) {
    return <Navigate to="/login" replace />
  }

  // 已登录：根据角色重定向到对应首页
  // - 管理员 → 管理后台仪表盘
  // - 普通用户 → 新建对话的聊天页面
  return <Navigate to={isAdmin ? '/admin/dashboard' : '/workspace/chat?new=1'} replace />
}
