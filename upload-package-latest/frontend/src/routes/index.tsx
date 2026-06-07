import { createBrowserRouter, Navigate } from 'react-router-dom'
import App from '@/App'
import { AuthLayout } from '@/components/auth/AuthLayout'
import { ForgotPasswordForm } from '@/components/auth/ForgotPasswordForm'
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
import { EvaluationPage } from '@/components/workspace/EvaluationPage'
import { DashboardPage } from '@/components/admin/DashboardPage'
import { UsersPage } from '@/components/admin/UsersPage'
import { MaterialsAdminPage } from '@/components/admin/MaterialsAdminPage'
import { LogsPage } from '@/components/admin/LogsPage'
import { UsageRecordsPage } from '@/components/admin/UsageRecordsPage'

/**
 * 路由配置 — 定义整个应用的页面结构。
 *
 * 路由层级：
 * / (App)
 *   ├── index → 重定向到 /login
 *   ├── 认证页面（不需要登录，AuthLayout 布局）
 *   │   ├── /login          → 登录页
 *   │   ├── /register       → 注册页
 *   │   └── /forgot-password → 忘记密码页
 *   ├── /workspace（需要登录，ProtectedRoute + AppShell 布局）
 *   │   ├── chat            → 智能问答主页
 *   │   ├── materials       → 资料管理
 *   │   ├── reader          → 文档阅读器
 *   │   ├── history         → 问答历史
 *   │   ├── favorites       → 收藏夹
 *   │   └── summary         → 资料总结
 *   ├── /admin（需要管理员权限，ProtectedRoute(requireAdmin) + AppShell）
 *   │   ├── dashboard       → 仪表盘
 *   │   ├── users           → 用户管理
 *   │   ├── materials       → 资料管理（管理员视图）
 *   │   ├── evaluation      → RAG 评估
 *   │   ├── usage-records   → 使用记录
 *   │   └── logs            → 系统日志
 *   └── * → 404 回退页
 */
export const router = createBrowserRouter([
  {
    path: '/',
    element: <App />,  // App 是根组件（ToastProvider + Outlet）
    children: [
      // 根路径重定向到登录页
      { index: true, element: <Navigate to="/login" replace /> },

      // ===== 认证页面（不需要登录） =====
      {
        element: <AuthLayout />,  // AuthLayout 提供登录/注册的统一样式
        children: [
          { path: 'login', element: <LoginForm /> },
          { path: 'register', element: <RegisterForm /> },
          { path: 'forgot-password', element: <ForgotPasswordForm /> },
        ],
      },

      // ===== 工作区（需要登录） =====
      {
        path: 'workspace',
        element: <ProtectedRoute />,  // 路由守卫：未登录则跳转到 /login
        children: [
          {
            element: <AppShell />,  // AppShell 提供侧边栏 + 顶栏 + 内容区布局
            children: [
              // 工作区默认重定向到聊天页
              { index: true, element: <Navigate to="/workspace/chat?new=1" replace /> },
              { path: 'chat', element: <ChatPage /> },        // 智能问答
              { path: 'materials', element: <MaterialsPage /> }, // 资料管理
              { path: 'reader', element: <ReaderPage /> },     // 文档阅读器
              { path: 'history', element: <HistoryPage /> },   // 问答历史
              { path: 'favorites', element: <FavoritesPage /> }, // 收藏夹
              { path: 'summary', element: <SummaryPage /> },   // 资料总结
            ],
          },
        ],
      },

      // ===== 管理后台（需要管理员权限） =====
      {
        path: 'admin',
        element: <ProtectedRoute requireAdmin />,  // requireAdmin 确保只有管理员能进入
        children: [
          {
            element: <AppShell />,
            children: [
              { index: true, element: <Navigate to="/admin/dashboard" replace /> },
              { path: 'dashboard', element: <DashboardPage /> },      // 仪表盘
              { path: 'users', element: <UsersPage /> },              // 用户管理
              { path: 'materials', element: <MaterialsAdminPage /> }, // 资料管理
              { path: 'evaluation', element: <EvaluationPage /> },    // RAG 评估
              { path: 'usage-records', element: <UsageRecordsPage /> }, // 使用记录
              { path: 'logs', element: <LogsPage /> },               // 系统日志
            ],
          },
        ],
      },

      // ===== 404 回退 =====
      { path: '*', element: <RouteFallback /> },
    ],
  },
])
