import { createBrowserRouter, Navigate } from 'react-router-dom'
import App from '@/App'
import { AuthLayout } from '@/components/auth/AuthLayout'
import { LoginForm } from '@/components/auth/LoginForm'
import { RegisterForm } from '@/components/auth/RegisterForm'
import { AppShell } from '@/components/layout/AppShell'
import { ProtectedRoute } from '@/components/layout/ProtectedRoute'
import { RouteFallback } from '@/components/layout/RouteFallback'
import { ChatPage } from '@/components/workspace/ChatPage'
import { MaterialsPage } from '@/components/workspace/MaterialsPage'
import { ReaderPage } from '@/components/workspace/ReaderPage'
import { HistoryPage } from '@/components/workspace/HistoryPage'
import { FavoritesPage } from '@/components/workspace/FavoritesPage'
import { SummaryPage } from '@/components/workspace/SummaryPage'
import { DashboardPage } from '@/components/admin/DashboardPage'
import { UsersPage } from '@/components/admin/UsersPage'
import { MaterialsAdminPage } from '@/components/admin/MaterialsAdminPage'
import { LogsPage } from '@/components/admin/LogsPage'

export const router = createBrowserRouter([
  {
    path: '/',
    element: <App />,
    children: [
      { index: true, element: <Navigate to="/login" replace /> },
      {
        element: <AuthLayout />,
        children: [
          { path: 'login', element: <LoginForm /> },
          { path: 'register', element: <RegisterForm /> },
        ],
      },
      {
        path: 'workspace',
        element: <ProtectedRoute />,
        children: [
          {
            element: <AppShell />,
            children: [
              { index: true, element: <Navigate to="/workspace/chat?new=1" replace /> },
              { path: 'chat', element: <ChatPage /> },
              { path: 'materials', element: <MaterialsPage /> },
              { path: 'reader', element: <ReaderPage /> },
              { path: 'history', element: <HistoryPage /> },
              { path: 'favorites', element: <FavoritesPage /> },
              { path: 'summary', element: <SummaryPage /> },
            ],
          },
        ],
      },
      {
        path: 'admin',
        element: <ProtectedRoute requireAdmin />,
        children: [
          {
            element: <AppShell />,
            children: [
              { index: true, element: <Navigate to="/admin/dashboard" replace /> },
              { path: 'dashboard', element: <DashboardPage /> },
              { path: 'users', element: <UsersPage /> },
              { path: 'materials', element: <MaterialsAdminPage /> },
              { path: 'logs', element: <LogsPage /> },
            ],
          },
        ],
      },
      { path: '*', element: <RouteFallback /> },
    ],
  },
])
