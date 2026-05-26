import { Navigate } from 'react-router-dom'
import { useAuth } from '@/context/AuthContext'

export function RouteFallback() {
  const { isAuthenticated, isAdmin, isLoading } = useAuth()

  if (isLoading) {
    return (
      <div className="flex h-screen items-center justify-center bg-[#eef3f7]">
        <div className="h-8 w-8 animate-spin rounded-full border-4 border-[#4f73e8] border-t-transparent" />
      </div>
    )
  }

  if (!isAuthenticated) {
    return <Navigate to="/login" replace />
  }

  return <Navigate to={isAdmin ? '/admin/dashboard' : '/workspace/chat?new=1'} replace />
}
