/**
 * AppShell 组件 —— 应用主外壳布局
 *
 * 【用途与使用场景】
 * 用户登录后的主应用布局容器，包含：
 * - 左侧导航栏（桌面端显示 Sidebar 组件）
 * - 顶部栏（TopBar 组件）
 * - 主内容区（通过 <Outlet /> 渲染子路由）
 * - 移动端底部导航栏（5 个 Tab + 菜单按钮）
 * - 移动端侧滑菜单（Dialog 弹窗实现）
 * - 个人资料弹窗（ProfileDialog）
 *
 * 【核心逻辑】
 * 1. 桌面端：左侧 Sidebar + 右侧 TopBar + 内容区
 * 2. 移动端：隐藏 Sidebar，改为底部 Tab 栏 + 侧滑菜单
 * 3. 根据当前路由判断是否为聊天页面，聊天页面使用不同的滚动/布局策略
 * 4. 管理员可以看到额外的"管理员后台"菜单分组
 * 5. 支持浅色/深色主题切换
 */

import { useState } from 'react'
import { Outlet, useLocation, useNavigate } from 'react-router-dom'
import { AnimatePresence, motion } from 'framer-motion'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { ADMIN_SECTIONS, WORKSPACE_SECTIONS } from '@/constants'
import { useAuth } from '@/context/AuthContext'
import { redirectToLogin } from '@/lib/auth-gate'
import { cn } from '@/lib/utils'
import {
  BookOpen,
  ClipboardCheck,
  Clock,
  FileText,
  Folder,
  LayoutDashboard,
  LogOut,
  Menu,
  MessageSquare,
  Moon,
  ScrollText,
  Settings,
  Sparkles,
  Star,
  Sun,
  Users,
} from 'lucide-react'
import { ProfileDialog } from './ProfileDialog'
import { Sidebar } from './Sidebar'
import { TopBar } from './TopBar'
import { UserAvatar } from './UserAvatar'

/**
 * 图标名称到组件的映射表
 * 用于根据菜单配置中的 icon 字符串动态渲染对应的 Lucide 图标
 */
const iconMap: Record<string, React.ElementType> = {
  'message-square': MessageSquare,
  'book-open': BookOpen,
  'file-text': FileText,
  clock: Clock,
  star: Star,
  sparkles: Sparkles,
  'layout-dashboard': LayoutDashboard,
  users: Users,
  folder: Folder,
  'scroll-text': ScrollText,
  'clipboard-check': ClipboardCheck,
}

export function AppShell() {
  const location = useLocation()
  const navigate = useNavigate()
  // 获取当前会话信息、管理员权限和登出方法
  const { session, isAuthenticated, isAdmin, logout } = useAuth()
  // 移动端菜单弹窗的开关状态
  const [mobileMenuOpen, setMobileMenuOpen] = useState(false)
  // 个人资料弹窗的开关状态
  const [profileOpen, setProfileOpen] = useState(false)
  // 聊天和阅读器都是高频交互页面，需要自己管理内部滚动，外层不能再加 padding 和滚动条。
  const isImmersiveWorkspace = location.pathname === '/workspace/chat' || location.pathname === '/workspace/reader'
  // 路由切换动画使用完整 URL 作为 key，确保同一路由下切换查询参数也能触发进退场。
  const routeAnimationKey = `${location.pathname}${location.search}`
  // 移动端底部 Tab 栏只显示前 4 个工作区菜单项
  const mobileSections = WORKSPACE_SECTIONS.slice(0, 4)

  /**
   * 页面跳转并关闭移动端菜单
   * @param path - 目标路由路径
   */
  const goTo = (path: string) => {
    navigate(path)
    setMobileMenuOpen(false)
  }

  /**
   * 设置主题（浅色/深色）
   * 通过在 <html> 元素上切换 'dark' class 实现
   * 同时将用户偏好持久化到 localStorage
   */
  const setTheme = (theme: 'light' | 'dark') => {
    document.documentElement.classList.toggle('dark', theme === 'dark')
    localStorage.setItem('learning-assistant.theme', theme)
  }

  return (
    <div className="flex h-[100dvh] overflow-hidden bg-[#f7f8fb] text-[#202124] dark:bg-[#111318] dark:text-slate-100">
      {/* 桌面端左侧导航栏（移动端隐藏） */}
      <div className="hidden md:block">
        <Sidebar />
      </div>

      {/* 主内容区域 */}
      <section className="flex min-w-0 flex-1 flex-col overflow-hidden bg-[#fcfcfd] dark:bg-[#171a21] md:m-1.5 md:ml-0 md:rounded-[24px] md:border md:border-[#ebeef3] md:shadow-[0_1px_2px_rgba(15,23,42,0.03),0_12px_40px_rgba(15,23,42,0.04)] md:dark:border-slate-800">
        {/* 顶部栏 */}
        <TopBar onOpenMobileMenu={() => setMobileMenuOpen(true)} />
        {/* 主内容：聊天页面不使用 padding 和滚动，其他页面使用 */}
        <main className={cn('relative', isImmersiveWorkspace ? 'min-h-0 flex-1 overflow-hidden pb-16 md:pb-0' : 'min-h-0 flex-1 overflow-y-auto p-3 pb-20 md:p-6 md:pb-6')}>
          {/* 子路由内容渲染点。AnimatePresence 让侧栏切换模块时，旧页面先淡出，新页面再浮入。 */}
          <AnimatePresence mode="wait" initial={false}>
            <motion.div
              key={routeAnimationKey}
              className={isImmersiveWorkspace ? 'h-full min-h-0' : 'min-h-full'}
              initial={{ opacity: 0, y: 18, scale: 0.992, filter: 'blur(10px)' }}
              animate={{ opacity: 1, y: 0, scale: 1, filter: 'blur(0px)' }}
              exit={{ opacity: 0, y: -12, scale: 0.994, filter: 'blur(8px)' }}
              transition={{ duration: 0.56, ease: [0.16, 1, 0.3, 1] }}
            >
              <Outlet />
            </motion.div>
          </AnimatePresence>
        </main>
      </section>

      {/* ========== 移动端侧滑菜单 ========== */}
      <Dialog open={mobileMenuOpen} onOpenChange={setMobileMenuOpen}>
        <DialogContent className="left-0 top-0 h-[100dvh] max-w-[22rem] translate-x-0 translate-y-0 content-start gap-0 overflow-hidden rounded-none border-y-0 border-l-0 bg-[#f7f8fb] p-0 shadow-2xl data-[state=closed]:slide-out-to-left data-[state=closed]:slide-out-to-top-0 data-[state=open]:slide-in-from-left data-[state=open]:slide-in-from-top-0 dark:bg-[#111318] sm:rounded-none">
          <DialogHeader className="border-b border-[#e9edf2] px-4 py-4 text-left dark:border-slate-800">
            <DialogTitle className="text-base">功能菜单</DialogTitle>
          </DialogHeader>
          <div className="flex min-h-0 flex-1 flex-col overflow-y-auto p-4">
            {/* 新建对话快捷按钮 */}
            <Button
              variant="outline"
              className="mb-4 h-11 justify-start gap-2 rounded-2xl border-[#e6eaf0] bg-white px-3 text-sm font-medium shadow-[0_6px_24px_rgba(15,23,42,0.04)] dark:border-slate-800 dark:bg-[#171a21]"
              onClick={() => goTo('/workspace/chat?new=1')}
            >
              <MessageSquare className="h-4 w-4" />
              新建对话
            </Button>

            {/* 工作区菜单列表 */}
            <div className="mb-2 px-1 text-xs font-medium text-muted-foreground">工作区</div>
            <div className="grid gap-1">
              {WORKSPACE_SECTIONS.map((section) => {
                const Icon = iconMap[section.icon] || FileText
                const active = location.pathname === section.path
                return (
                  <button
                    key={section.path}
                    type="button"
                    onClick={() => goTo(section.path)}
                    className={cn(
                      'flex h-11 items-center gap-3 rounded-lg px-3 text-sm font-medium transition-colors',
                      active
                        ? 'bg-[#eceef2] text-[#202124] dark:bg-white/10 dark:text-white'
                        : 'text-[#5b6270] hover:bg-[#f0f2f5] dark:text-slate-300 dark:hover:bg-white/[0.08]',
                    )}
                  >
                    <Icon className="h-4 w-4 shrink-0" />
                    <span className="truncate">{section.label}</span>
                  </button>
                )
              })}
            </div>

            {/* 管理员后台菜单列表（仅管理员可见） */}
            {isAdmin && (
              <>
                <div className="mb-2 mt-5 px-1 text-xs font-medium text-muted-foreground">管理员后台</div>
                <div className="grid gap-1">
                  {ADMIN_SECTIONS.map((section) => {
                    const Icon = iconMap[section.icon] || LayoutDashboard
                    const active = location.pathname === section.path
                    return (
                      <button
                        key={section.path}
                        type="button"
                        onClick={() => goTo(section.path)}
                        className={cn(
                          'flex h-11 items-center gap-3 rounded-lg px-3 text-sm font-medium transition-colors',
                          active
                            ? 'bg-[#eceef2] text-[#202124] dark:bg-white/10 dark:text-white'
                            : 'text-[#5b6270] hover:bg-[#f0f2f5] dark:text-slate-300 dark:hover:bg-white/[0.08]',
                        )}
                      >
                        <Icon className="h-4 w-4 shrink-0" />
                        <span className="truncate">{section.label}</span>
                      </button>
                    )
                  })}
                </div>
              </>
            )}

            {/* 主题切换开关 */}
            <div className="mt-5 grid grid-cols-2 gap-1 rounded-2xl bg-[#eef1f5] p-1 dark:bg-white/[0.08]">
              <button
                type="button"
                onClick={() => setTheme('light')}
                className="flex h-9 items-center justify-center gap-1.5 rounded-xl bg-white text-xs font-medium text-foreground shadow-[0_4px_16px_rgba(15,23,42,0.05)] dark:bg-transparent dark:text-muted-foreground"
              >
                <Sun className="h-3.5 w-3.5" />
                浅色
              </button>
              <button
                type="button"
                onClick={() => setTheme('dark')}
                className="flex h-9 items-center justify-center gap-1.5 rounded-md text-xs font-medium text-muted-foreground dark:bg-slate-900 dark:text-foreground dark:shadow-sm"
              >
                <Moon className="h-3.5 w-3.5" />
                深色
              </button>
            </div>

            {/* 底部用户信息 + 设置 + 登出 */}
            <div className="mt-auto flex items-center gap-2 pt-5">
              {/* 用户头像和名称，点击打开个人资料 */}
              <button
                type="button"
                className="flex min-w-0 flex-1 items-center gap-3 rounded-lg px-1 py-1 text-left transition-colors hover:bg-[#eceef1] dark:hover:bg-white/[0.08]"
                onClick={() => {
                  if (!isAuthenticated) {
                    redirectToLogin(0)
                    return
                  }
                  setProfileOpen(true)
                  setMobileMenuOpen(false)
                }}
              >
                <UserAvatar session={session} />
                <div className="min-w-0 flex-1">
                  <div className="truncate text-sm font-medium">{session?.nickname || session?.username || '未登录'}</div>
                  <div className="text-xs text-muted-foreground">{session ? (session.role === 'ADMIN' ? '管理员' : '普通用户') : '点击登录'}</div>
                </div>
              </button>
              {/* 设置按钮 */}
              {isAuthenticated && (
                <Button
                  variant="ghost"
                  size="icon"
                  className="h-9 w-9 shrink-0"
                  onClick={() => {
                    setProfileOpen(true)
                    setMobileMenuOpen(false)
                  }}
                >
                  <Settings className="h-4 w-4" />
                </Button>
              )}
              {/* 登出按钮 */}
              {isAuthenticated && (
                <Button variant="ghost" size="icon" className="h-9 w-9 shrink-0" onClick={logout}>
                  <LogOut className="h-4 w-4" />
                </Button>
              )}
            </div>
          </div>
        </DialogContent>
      </Dialog>
      {/* 个人资料弹窗 */}
      <ProfileDialog open={profileOpen} onOpenChange={setProfileOpen} />

      {/* ========== 移动端底部导航栏（桌面端隐藏） ========== */}
      <nav className="fixed inset-x-0 bottom-0 z-40 border-t border-[#e8edf3] bg-white/95 px-1 pb-[max(env(safe-area-inset-bottom),0.25rem)] pt-1 shadow-[0_-10px_30px_rgba(15,23,42,0.06)] backdrop-blur dark:border-slate-800 dark:bg-[#171a21]/95 md:hidden">
        <div className="grid grid-cols-5 gap-1">
          {/* 前 4 个工作区菜单项 */}
          {mobileSections.map((section) => {
            const Icon = iconMap[section.icon] || FileText
            const active = location.pathname === section.path
            return (
              <button
                key={section.path}
                type="button"
                onClick={() => navigate(section.path)}
                className={cn(
                  'flex min-w-0 flex-col items-center justify-center gap-0.5 rounded-xl px-1 py-1.5 text-[10px] transition-colors',
                  active
                    ? 'bg-[#eef1f5] text-[#111318] dark:bg-white/10 dark:text-white'
                    : 'text-slate-500 dark:text-slate-400',
                )}
              >
                <Icon className="h-4 w-4 shrink-0" />
                <span className="max-w-full truncate">{section.label}</span>
              </button>
            )
          })}
          {/* 第 5 个：菜单按钮，点击打开侧滑菜单 */}
          <button
            type="button"
            onClick={() => setMobileMenuOpen(true)}
            className="flex min-w-0 flex-col items-center justify-center gap-0.5 rounded-xl px-1 py-1.5 text-[10px] text-slate-500 transition-colors hover:bg-[#eef1f5] dark:text-slate-400 dark:hover:bg-white/10"
          >
            <Menu className="h-4 w-4 shrink-0" />
            <span className="max-w-full truncate">菜单</span>
          </button>
        </div>
      </nav>
    </div>
  )
}
