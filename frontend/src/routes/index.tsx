import { lazy, Suspense, type ReactNode } from 'react'
import { createBrowserRouter, Navigate } from 'react-router-dom'
import App from '@/App'
import { AuthLayout } from '@/components/auth/AuthLayout'
import { AppShell } from '@/components/layout/AppShell'
import { ProtectedRoute } from '@/components/layout/ProtectedRoute'
import { RouteFallback } from '@/components/layout/RouteFallback'

const ProductLandingPage = lazy(() => import('@/components/landing/ProductLandingPage').then((module) => ({ default: module.ProductLandingPage })))
const LoginForm = lazy(() => import('@/components/auth/LoginForm').then((module) => ({ default: module.LoginForm })))
const RegisterForm = lazy(() => import('@/components/auth/RegisterForm').then((module) => ({ default: module.RegisterForm })))
const ForgotPasswordForm = lazy(() => import('@/components/auth/ForgotPasswordForm').then((module) => ({ default: module.ForgotPasswordForm })))
const ChatPage = lazy(() => import('@/components/workspace/ChatPage').then((module) => ({ default: module.ChatPage })))
const MaterialsPage = lazy(() => import('@/components/workspace/MaterialsPage').then((module) => ({ default: module.MaterialsPage })))
const ReaderPage = lazy(() => import('@/components/workspace/ReaderPage').then((module) => ({ default: module.ReaderPage })))
const HistoryPage = lazy(() => import('@/components/workspace/HistoryPage').then((module) => ({ default: module.HistoryPage })))
const FavoritesPage = lazy(() => import('@/components/workspace/FavoritesPage').then((module) => ({ default: module.FavoritesPage })))
const SummaryPage = lazy(() => import('@/components/workspace/SummaryPage').then((module) => ({ default: module.SummaryPage })))
const EvaluationPage = lazy(() => import('@/components/workspace/EvaluationPage').then((module) => ({ default: module.EvaluationPage })))
const DashboardPage = lazy(() => import('@/components/admin/DashboardPage').then((module) => ({ default: module.DashboardPage })))
const UsersPage = lazy(() => import('@/components/admin/UsersPage').then((module) => ({ default: module.UsersPage })))
const MaterialsAdminPage = lazy(() => import('@/components/admin/MaterialsAdminPage').then((module) => ({ default: module.MaterialsAdminPage })))
const LogsPage = lazy(() => import('@/components/admin/LogsPage').then((module) => ({ default: module.LogsPage })))
const UsageRecordsPage = lazy(() => import('@/components/admin/UsageRecordsPage').then((module) => ({ default: module.UsageRecordsPage })))

/** 路由级加载态，用于懒加载页面组件时保持布局稳定。 */
function RouteLoading() {
  return (
    <div className="flex min-h-[320px] items-center justify-center">
      <div className="h-8 w-8 animate-spin rounded-full border-4 border-[#4b5563] border-t-transparent" />
    </div>
  )
}

/** 给页面路由统一包一层 Suspense，避免首屏一次性下载所有页面代码。 */
function lazyElement(element: ReactNode) {
  return <Suspense fallback={<RouteLoading />}>{element}</Suspense>
}

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
      { index: true, element: lazyElement(<ProductLandingPage />) },

      // ===== 认证页面（不需要登录） =====
      {
        element: <AuthLayout />,  // AuthLayout 提供登录/注册的统一样式
        children: [
          { path: 'login', element: lazyElement(<LoginForm />) },
          { path: 'register', element: lazyElement(<RegisterForm />) },
          { path: 'forgot-password', element: lazyElement(<ForgotPasswordForm />) },
        ],
      },

      // ===== 工作区（需要登录） =====
      {
        path: 'workspace',
        element: <ProtectedRoute allowGuest />,  // 工作区允许游客预览；具体功能入口再提示登录
        children: [
          {
            element: <AppShell />,  // AppShell 提供侧边栏 + 顶栏 + 内容区布局
            children: [
              // 工作区默认重定向到聊天页
              { index: true, element: <Navigate to="/workspace/chat?new=1" replace /> },
              { path: 'chat', element: lazyElement(<ChatPage />) },        // 智能问答
              { path: 'materials', element: lazyElement(<MaterialsPage />) }, // 资料管理
              { path: 'reader', element: lazyElement(<ReaderPage />) },     // 文档阅读器
              { path: 'history', element: lazyElement(<HistoryPage />) },   // 问答历史
              { path: 'favorites', element: lazyElement(<FavoritesPage />) }, // 收藏夹
              { path: 'summary', element: lazyElement(<SummaryPage />) },   // 资料总结
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
              { path: 'dashboard', element: lazyElement(<DashboardPage />) },      // 仪表盘
              { path: 'users', element: lazyElement(<UsersPage />) },              // 用户管理
              { path: 'materials', element: lazyElement(<MaterialsAdminPage />) }, // 资料管理
              { path: 'evaluation', element: lazyElement(<EvaluationPage />) },    // RAG 评估
              { path: 'usage-records', element: lazyElement(<UsageRecordsPage />) }, // 使用记录
              { path: 'logs', element: lazyElement(<LogsPage />) },               // 系统日志
            ],
          },
        ],
      },

      // ===== 404 回退 =====
      { path: '*', element: <RouteFallback /> },
    ],
  },
])
