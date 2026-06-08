/**
 * AuthLayout 组件 —— 认证页面的整体布局容器
 *
 * 【用途与使用场景】
 * 所有认证相关页面（登录、注册、忘记密码）的公共外壳。
 * 它通过 React Router 的 <Outlet /> 渲染子路由对应的表单组件，
 * 并提供统一的背景、粒子动画和居中布局。
 *
 * 【核心逻辑】
 * 1. 如果用户已经登录（isAuthenticated 为 true），自动跳转到对应角色的首页：
 *    - 管理员 → /admin/dashboard
 *    - 普通用户 → /workspace/chat?new=1
 * 2. 认证状态加载中时，显示全屏加载动画。
 * 3. 未认证时，渲染认证子页面（登录/注册/忘记密码表单）。
 */

import { useEffect } from 'react'
import { Outlet, useLocation, useNavigate } from 'react-router-dom'
import { AuthParticles } from './AuthParticles'
import { useAuth } from '@/context/AuthContext'

export function AuthLayout() {
  // 从认证上下文中获取当前用户状态
  const { isAuthenticated, isLoading, isAdmin } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()

  /**
   * 已登录用户自动跳转：
   * - 清除当前会话的聊天缓存
   * - 根据角色跳转到不同的默认页面
   */
  useEffect(() => {
    if (isAuthenticated && !isLoading) {
      // 清除之前保存的聊天会话 ID，确保进入全新对话
      sessionStorage.removeItem('learning-assistant.chat.current')
      const redirect = new URLSearchParams(location.search).get('redirect')
      if (redirect?.startsWith('/') && !redirect.startsWith('//')) {
        navigate(redirect, { replace: true })
        return
      }
      // replace: true 表示替换历史记录，用户无法通过"后退"按钮回到认证页面
      navigate(isAdmin ? '/admin/dashboard' : '/workspace/chat?new=1', { replace: true })
    }
  }, [isAuthenticated, isLoading, isAdmin, location.search, navigate])

  // 认证状态仍在加载中，显示全屏加载提示
  if (isLoading) {
    return (
      <div className="flex h-screen items-center justify-center bg-[#eef3f7]">
        <div className="flex flex-col items-center gap-3 text-[#222833]">
          {/* 旋转加载圆圈 */}
          <div className="h-10 w-10 animate-spin rounded-full border-4 border-[#4b5563] border-t-transparent" />
          <p className="text-sm text-slate-500">正在验证身份...</p>
        </div>
      </div>
    )
  }

  return (
    <main className="relative flex min-h-screen items-center justify-center overflow-hidden bg-[#eef3f7] px-6 py-10 text-[#222833]">
      {/* 背景装饰：径向渐变光效 */}
      <div className="absolute inset-0 bg-[radial-gradient(circle_at_18%_18%,rgba(255,255,255,0.95),transparent_30%),radial-gradient(circle_at_84%_78%,rgba(107,114,128,0.12),transparent_28%)]" />
      {/* 粒子动画背景层 */}
      <AuthParticles />
      {/* 子路由内容区（登录/注册/忘记密码表单），z-10 确保在粒子上方 */}
      <div className="relative z-10 flex w-full justify-center">
        <Outlet />
      </div>
    </main>
  )
}
