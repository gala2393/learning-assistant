import { useEffect } from 'react'
import { Outlet, useNavigate } from 'react-router-dom'
import { AuthParticles } from './AuthParticles'
import { useAuth } from '@/context/AuthContext'

export function AuthLayout() {
  const { isAuthenticated, isLoading, isAdmin } = useAuth()
  const navigate = useNavigate()

  useEffect(() => {
    if (isAuthenticated && !isLoading) {
      sessionStorage.removeItem('learning-assistant.chat.current')
      navigate(isAdmin ? '/admin/dashboard' : '/workspace/chat?new=1', { replace: true })
    }
  }, [isAuthenticated, isLoading, isAdmin, navigate])

  if (isLoading) {
    return (
      <div className="flex h-screen items-center justify-center bg-[#eef3f7]">
        <div className="flex flex-col items-center gap-3 text-[#222833]">
          <div className="h-10 w-10 animate-spin rounded-full border-4 border-[#4f73e8] border-t-transparent" />
          <p className="text-sm text-slate-500">正在验证身份...</p>
        </div>
      </div>
    )
  }

  return (
    <main className="relative flex min-h-screen items-center justify-center overflow-hidden bg-[#eef3f7] px-6 py-10 text-[#222833]">
      <div className="absolute inset-0 bg-[radial-gradient(circle_at_18%_18%,rgba(255,255,255,0.95),transparent_30%),radial-gradient(circle_at_84%_78%,rgba(79,115,232,0.12),transparent_28%)]" />
      <AuthParticles />
      <div className="relative z-10 flex w-full justify-center">
        <Outlet />
      </div>
    </main>
  )
}
