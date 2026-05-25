import { useEffect, useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import {
  BookOpen,
  ChevronLeft,
  Clock,
  Cpu,
  FileText,
  Folder,
  LayoutDashboard,
  LogOut,
  MessageSquare,
  Moon,
  Plus,
  Settings,
  ScrollText,
  Sparkles,
  Star,
  Sun,
  Users,
  Wifi,
  WifiOff,
} from 'lucide-react'
import { ADMIN_SECTIONS, WORKSPACE_SECTIONS } from '@/constants'
import { Button } from '@/components/ui/button'
import { Separator } from '@/components/ui/separator'
import { useAuth } from '@/context/AuthContext'
import api from '@/lib/axios'
import { cn } from '@/lib/utils'
import type { LlmStatus } from '@/types'
import { ProfileDialog } from './ProfileDialog'
import { UserAvatar } from './UserAvatar'

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
}

export function Sidebar() {
  const location = useLocation()
  const navigate = useNavigate()
  const { session, isAdmin, logout } = useAuth()
  const [llmStatus, setLlmStatus] = useState<LlmStatus | null>(null)
  const [profileOpen, setProfileOpen] = useState(false)
  const [collapsed, setCollapsed] = useState(() => {
    if (typeof window === 'undefined') return false
    return localStorage.getItem('learning-assistant.sidebar.collapsed') === 'true'
  })
  const [theme, setTheme] = useState<'light' | 'dark'>(() => {
    if (typeof window === 'undefined') return 'light'
    return localStorage.getItem('learning-assistant.theme') === 'dark' ? 'dark' : 'light'
  })

  useEffect(() => {
    api.get('/llm/status')
      .then((res) => setLlmStatus(res.data))
      .catch(() => setLlmStatus(null))
  }, [])

  useEffect(() => {
    document.documentElement.classList.toggle('dark', theme === 'dark')
    localStorage.setItem('learning-assistant.theme', theme)
  }, [theme])

  useEffect(() => {
    localStorage.setItem('learning-assistant.sidebar.collapsed', String(collapsed))
  }, [collapsed])

  const isAdminRoute = location.pathname.startsWith('/admin')
  const sections = isAdminRoute ? ADMIN_SECTIONS : WORKSPACE_SECTIONS

  return (
    <aside className={cn(
      'flex h-screen shrink-0 flex-col bg-[#f5f6f8] py-4 transition-[width] duration-200 dark:bg-[#111318]',
      collapsed ? 'w-[68px] px-2' : 'w-[242px] px-3',
    )}>
      <div className="mb-5 flex items-center justify-between px-1">
        <button
          className="flex h-8 w-8 items-center justify-center rounded-lg bg-[#111318] text-sm font-black text-white shadow-sm dark:bg-white dark:text-[#111318]"
          onClick={() => navigate(isAdmin ? '/admin/dashboard' : '/workspace/chat')}
          title="课程学习助手"
        >
          学
        </button>
        <button
          type="button"
          className="rounded-md p-1.5 text-muted-foreground hover:bg-slate-200/70 hover:text-foreground dark:hover:bg-white/10"
          onClick={() => setCollapsed((value) => !value)}
          title={collapsed ? '展开侧边栏' : '收起侧边栏'}
          aria-label={collapsed ? '展开侧边栏' : '收起侧边栏'}
        >
          <ChevronLeft className={cn('h-4 w-4 transition-transform', collapsed && 'rotate-180')} />
        </button>
      </div>

      {collapsed ? (
        <Button
          variant="outline"
          size="icon"
          className="mb-4 h-10 w-10 self-center rounded-xl border-[#e1e4e8] bg-white shadow-sm hover:bg-white dark:border-slate-800 dark:bg-[#171a21]"
          onClick={() => navigate('/workspace/chat?new=1')}
          title="新建会话"
        >
          <Plus className="h-4 w-4" />
        </Button>
      ) : (
        <Button
          variant="outline"
          className="mb-4 h-11 justify-start gap-2 rounded-xl border-[#e1e4e8] bg-white px-3 text-sm font-medium shadow-sm hover:bg-white dark:border-slate-800 dark:bg-[#171a21]"
          onClick={() => navigate('/workspace/chat?new=1')}
        >
          <Plus className="h-4 w-4" />
          新建会话
          <span className="ml-auto rounded bg-slate-100 px-1.5 py-0.5 text-[10px] text-muted-foreground dark:bg-slate-800">Ctrl K</span>
        </Button>
      )}

      <nav className="flex-1 overflow-y-auto">
        <div className={cn('mb-2 px-2 text-xs font-medium text-muted-foreground', collapsed && 'sr-only')}>
          {isAdminRoute ? '管理后台' : '工作区'}
        </div>
        <ul className="space-y-1">
          {sections.map((section) => {
            const isActive = location.pathname === section.path
            const Icon = iconMap[section.icon] || FileText
            return (
              <li key={section.path}>
                <button
                  onClick={() => navigate(section.path)}
                  className={cn(
                    'flex w-full items-center rounded-lg text-sm font-medium transition-colors',
                    collapsed ? 'h-10 justify-center px-0' : 'gap-3 px-3 py-2.5',
                    isActive
                      ? 'bg-[#e9eaec] text-[#202124] dark:bg-white/10 dark:text-white'
                      : 'text-[#3f4247] hover:bg-[#eceef1] dark:text-slate-300 dark:hover:bg-white/[0.08]',
                  )}
                  title={collapsed ? section.label : undefined}
                >
                  <Icon className="h-4 w-4" />
                  {!collapsed && <span>{section.label}</span>}
                </button>
              </li>
            )
          })}
        </ul>

        {isAdmin && (
          <>
            <Separator className="my-4" />
            <button
              onClick={() => navigate(isAdminRoute ? '/workspace/chat' : '/admin/dashboard')}
              className="flex w-full items-center gap-3 rounded-lg px-3 py-2.5 text-sm font-medium text-muted-foreground transition-colors hover:bg-[#eceef1] hover:text-foreground dark:hover:bg-white/[0.08]"
            >
              <ChevronLeft className="h-4 w-4" />
              <span>{isAdminRoute ? '返回工作区' : '进入管理后台'}</span>
            </button>
          </>
        )}
      </nav>

      <div className="space-y-3 pt-4">
        {!collapsed && llmStatus && (
          <div
            className={cn(
              'flex items-center gap-2 rounded-lg px-3 py-2 text-xs',
              llmStatus.configured ? 'bg-emerald-500/10 text-emerald-600' : 'bg-amber-500/10 text-amber-600',
            )}
          >
            {llmStatus.configured ? <Wifi className="h-3.5 w-3.5" /> : <WifiOff className="h-3.5 w-3.5" />}
            <Cpu className="h-3.5 w-3.5" />
            <span className="truncate">{llmStatus.configured ? 'LLM 已连接' : 'LLM 未配置'}</span>
          </div>
        )}

        {!collapsed && <div className="grid grid-cols-2 gap-1 rounded-lg bg-[#eceef1] p-1 dark:bg-white/[0.08]">
          <button
            type="button"
            onClick={() => setTheme('light')}
            className={cn(
              'flex h-8 items-center justify-center gap-1.5 rounded-md text-xs font-medium transition-colors',
              theme === 'light' ? 'bg-white text-foreground shadow-sm dark:bg-slate-900' : 'text-muted-foreground',
            )}
          >
            <Sun className="h-3.5 w-3.5" />
            浅色
          </button>
          <button
            type="button"
            onClick={() => setTheme('dark')}
            className={cn(
              'flex h-8 items-center justify-center gap-1.5 rounded-md text-xs font-medium transition-colors',
              theme === 'dark' ? 'bg-white text-foreground shadow-sm dark:bg-slate-900' : 'text-muted-foreground',
            )}
          >
            <Moon className="h-3.5 w-3.5" />
            深色
          </button>
        </div>}

        <div className={cn('flex items-center gap-2 rounded-lg px-1 py-2', collapsed && 'justify-center px-0')}>
          <button
            type="button"
            className={cn(
              'flex min-w-0 items-center gap-3 rounded-lg px-1 py-1 text-left transition-colors hover:bg-[#eceef1] dark:hover:bg-white/[0.08]',
              collapsed ? 'justify-center' : 'flex-1',
            )}
            onClick={() => setProfileOpen(true)}
            title="修改个人资料"
          >
            <UserAvatar session={session} />
            {!collapsed && <div className="min-w-0 flex-1">
              <div className="truncate text-sm font-medium">{session?.nickname || session?.username}</div>
              <div className="text-xs text-muted-foreground">{session?.role === 'ADMIN' ? '管理员' : '普通用户'}</div>
            </div>}
          </button>
          {!collapsed && <Button variant="ghost" size="icon" className="h-8 w-8" onClick={() => setProfileOpen(true)} title="修改资料">
            <Settings className="h-4 w-4" />
          </Button>}
          {!collapsed && <Button variant="ghost" size="icon" className="h-8 w-8" onClick={logout} title="退出登录">
            <LogOut className="h-4 w-4" />
          </Button>}
        </div>
        <ProfileDialog open={profileOpen} onOpenChange={setProfileOpen} />
      </div>
    </aside>
  )
}
