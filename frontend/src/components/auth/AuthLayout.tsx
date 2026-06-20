import { useEffect } from 'react'
import { Outlet, useLocation, useNavigate } from 'react-router-dom'
import { useAuth } from '@/context/AuthContext'

/**
 * 认证页公共布局。
 * 这一版去掉了旧的粒子背景，改为和新首页一致的白底、细网格和少量冷色光斑，
 * 让登录、注册、忘记密码都处在同一条品牌视觉线上。
 */
export function AuthLayout() {
  const { isAuthenticated, isLoading, isAdmin } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()

  /**
   * 已登录用户访问认证页时直接回到对应工作区，避免无意义地停留在登录流程。
   */
  useEffect(() => {
    if (isAuthenticated && !isLoading) {
      sessionStorage.removeItem('learning-assistant.chat.current')
      const redirect = new URLSearchParams(location.search).get('redirect')
      if (redirect?.startsWith('/') && !redirect.startsWith('//')) {
        navigate(redirect, { replace: true })
        return
      }
      navigate(isAdmin ? '/admin/dashboard' : '/workspace/chat?new=1', { replace: true })
    }
  }, [isAuthenticated, isLoading, isAdmin, location.search, navigate])

  if (isLoading) {
    return (
      <div className="flex h-screen items-center justify-center bg-[#f7f8fb]">
        <div className="flex flex-col items-center gap-3 text-[#111111]">
          <div className="h-10 w-10 animate-spin rounded-full border-4 border-[#1a2a3a] border-t-transparent" />
          <p className="text-sm text-slate-500">正在验证身份...</p>
        </div>
      </div>
    )
  }

  return (
    <main className="relative flex min-h-screen items-center justify-center overflow-hidden bg-[#f7f8fb] px-5 py-8 text-[#111111] sm:px-8 lg:px-10">
      <div className="absolute inset-0 bg-[radial-gradient(circle_at_18%_12%,rgba(255,255,255,0.98),transparent_26%),radial-gradient(circle_at_82%_22%,rgba(198,214,234,0.72),transparent_24%),radial-gradient(circle_at_48%_82%,rgba(220,230,242,0.78),transparent_30%),linear-gradient(180deg,#fcfcfd_0%,#f7f8fb_100%)]" />
      <div className="absolute inset-0 opacity-[0.28] [background-image:linear-gradient(rgba(100,116,139,0.08)_1px,transparent_1px),linear-gradient(90deg,rgba(100,116,139,0.08)_1px,transparent_1px)] [background-size:52px_52px]" />
      <div className="pointer-events-none absolute -right-24 top-20 h-72 w-72 rounded-full border border-[#d6deea]" />
      <div className="pointer-events-none absolute left-10 top-20 h-24 w-[420px] -rotate-12 rounded-full bg-white/50 blur-2xl" />
      <div className="relative z-10 flex w-full justify-center">
        <Outlet />
      </div>
    </main>
  )
}
