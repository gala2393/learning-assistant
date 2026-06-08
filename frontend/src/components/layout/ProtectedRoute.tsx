/**
 * ProtectedRoute 组件 —— 路由守卫（受保护路由）
 *
 * 【用途与使用场景】
 * 用于包裹需要登录才能访问的路由，以及需要管理员权限才能访问的路由。
 * 在路由配置中作为父路由使用，通过 <Outlet /> 渲染子路由。
 *
 * 【核心逻辑】
 * 1. 认证状态加载中：显示全屏加载动画
 * 2. 未登录：重定向到 /login 页面
 * 3. 已登录但需要管理员权限却没有：重定向到 /workspace/chat
 * 4. 权限校验通过：渲染子路由内容
 *
 * 【使用示例】
 * <Route element={<ProtectedRoute />}>
 *   <Route path="/workspace" element={<Workspace />} />
 * </Route>
 *
 * <Route element={<ProtectedRoute requireAdmin />}>
 *   <Route path="/admin" element={<AdminDashboard />} />
 * </Route>
 */

import { Navigate, Outlet } from 'react-router-dom'
import { useAuth } from '@/context/AuthContext'

/**
 * ProtectedRoute 组件的 Props 接口
 * @property requireAdmin - 是否要求管理员权限，默认 false（仅要求登录）
 */
interface ProtectedRouteProps {
  requireAdmin?: boolean
  allowGuest?: boolean
}

export function ProtectedRoute({ requireAdmin, allowGuest }: ProtectedRouteProps) {
  // 从认证上下文获取当前用户的状态
  const { isAuthenticated, isAdmin, isLoading } = useAuth()

  // 认证状态仍在加载中，显示全屏加载动画
  if (isLoading) {
    return (
      <div className="flex h-screen items-center justify-center">
        <div className="h-8 w-8 animate-spin rounded-full border-4 border-[#4b5563] border-t-transparent" />
      </div>
    )
  }

  // 未登录：重定向到登录页面
  // replace: true 表示替换历史记录，防止用户通过"后退"按钮回到受保护页面
  if (!isAuthenticated && !allowGuest) {
    return <Navigate to="/login" replace />
  }

  // 需要管理员权限但当前用户不是管理员：重定向到工作台
  if (requireAdmin && !isAdmin) {
    // 普通登录用户仍可进入工作台，但不能停留在管理端 route。
    return <Navigate to="/workspace/chat" replace />
  }

  // 权限校验通过，渲染子路由内容
  return <Outlet />
}
