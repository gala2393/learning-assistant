import { useEffect, useMemo, useState } from 'react'
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
  MoreHorizontal,
  Moon,
  Plus,
  PencilLine,
  Pin,
  PinOff,
  Settings,
  ScrollText,
  Sparkles,
  Star,
  Sun,
  Trash2,
  Users,
  Wifi,
  WifiOff,
} from 'lucide-react'
import { ADMIN_SECTIONS, WORKSPACE_SECTIONS } from '@/constants'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger } from '@/components/ui/dropdown-menu'
import { Separator } from '@/components/ui/separator'
import { useAuth } from '@/context/AuthContext'
import api from '@/lib/axios'
import { cn } from '@/lib/utils'
import type { HistoryItem, LlmStatus } from '@/types'
import { ProfileDialog } from './ProfileDialog'
import { UserAvatar } from './UserAvatar'
import { useHistory, useDeleteHistory, useRenameHistory, useTogglePinHistory } from '@/api/rag'
import { useToast } from '@/components/ui/toast'
import { queryClient } from '@/lib/query-client'

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
  const { showToast } = useToast()
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
  const [menuOpenId, setMenuOpenId] = useState<string | null>(null)

  const isAdminRoute = location.pathname.startsWith('/admin')
  const isChat = location.pathname === '/workspace/chat'
  const sections = isAdminRoute ? ADMIN_SECTIONS : WORKSPACE_SECTIONS
  const { data: historyItems = [] } = useHistory()
  const deleteHistoryMutation = useDeleteHistory()
  const renameHistoryMutation = useRenameHistory()
  const togglePinHistoryMutation = useTogglePinHistory()

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

  const recentHistory = useMemo(() => historyItems.slice(0, 6), [historyItems])
  const selectedHistoryId = new URLSearchParams(location.search).get('historyId')

  const openHistory = (item: HistoryItem) => {
    navigate(`/workspace/chat?historyId=${encodeURIComponent(String(item.id))}`)
  }

  const handleRename = (item: HistoryItem) => {
    const next = window.prompt('重命名会话', item.title || item.question)
    if (!next || !next.trim()) return
    renameHistoryMutation.mutate(
      { id: String(item.id), title: next.trim() },
      {
        onSuccess: () => {
          showToast('会话已重命名')
          queryClient.invalidateQueries({ queryKey: ['history'] })
        },
        onError: (error) => showToast(error instanceof Error ? error.message : '重命名失败'),
      },
    )
  }

  const handleTogglePin = (item: HistoryItem) => {
    togglePinHistoryMutation.mutate(String(item.id), {
      onSuccess: () => {
        showToast(item.pinned ? '已取消置顶' : '已置顶')
        queryClient.invalidateQueries({ queryKey: ['history'] })
      },
      onError: (error) => showToast(error instanceof Error ? error.message : '置顶失败'),
    })
  }

  const handleDelete = (item: HistoryItem) => {
    deleteHistoryMutation.mutate(String(item.id), {
      onSuccess: () => {
        showToast('会话已删除')
        queryClient.invalidateQueries({ queryKey: ['history'] })
      },
      onError: (error) => showToast(error instanceof Error ? error.message : '删除失败'),
    })
  }

  return (
    <aside
      className={cn(
        'flex h-screen shrink-0 flex-col bg-[#f5f6f8] py-4 transition-[width] duration-200 dark:bg-[#111318]',
        collapsed ? 'w-[76px] px-2' : 'w-[286px] px-3',
      )}
    >
      <div className="mb-5 flex items-center justify-between px-1">
        <button
          className="flex h-8 w-8 items-center justify-center rounded-lg bg-[#111318] text-sm font-black text-white shadow-sm dark:bg-white dark:text-[#111318]"
          onClick={() => navigate(isAdmin ? '/admin/dashboard' : '/workspace/chat')}
          title="学习助手"
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

        {isChat && !collapsed && (
          <div className="mt-4 min-h-0 px-1">
            <div className="mb-2 flex items-center justify-between px-2">
              <span className="text-xs font-medium text-[#a7adb5]">历史会话</span>
              <button
                type="button"
                className="mr-1 flex h-7 w-7 items-center justify-center rounded-md text-muted-foreground hover:bg-slate-100 hover:text-foreground dark:hover:bg-white/10"
                onClick={() => navigate('/workspace/chat?new=1')}
                title="新建会话"
                aria-label="新建会话"
              >
                <Plus className="h-4 w-4" />
              </button>
            </div>
            <div className="space-y-1">
              {recentHistory.map((item) => {
                const itemId = String(item.id)
                const selected = selectedHistoryId === itemId
                return (
                  <div
                    key={itemId}
                    className={cn(
                      'group flex h-10 items-center rounded-lg px-2 pr-1 text-sm transition-colors',
                      selected
                        ? 'bg-[#e9eaec] text-[#202124]'
                        : 'text-muted-foreground hover:bg-[#eceef1] hover:text-foreground dark:hover:bg-white/[0.06]',
                    )}
                    onClick={() => openHistory(item)}
                  >
                    {item.pinned && (
                      <Badge
                        variant="outline"
                        className="mr-2 h-5 border-[#dde3ea] bg-[#f7f8fa] px-1.5 py-0 text-[10px] font-medium text-[#8f96a0]"
                      >
                        置顶
                      </Badge>
                    )}
                    <div className="min-w-0 flex-1 truncate pr-2 leading-5">
                      {item.title || item.question}
                    </div>
                    <DropdownMenu open={menuOpenId === itemId} onOpenChange={(open) => setMenuOpenId(open ? itemId : null)}>
                      <DropdownMenuTrigger asChild>
                        <button
                          type="button"
                          className={cn(
                            'ml-auto flex h-7 w-7 shrink-0 items-center justify-center rounded-md text-[#9aa0a6] transition-opacity hover:bg-white/70 dark:hover:bg-white/10',
                            selected ? 'opacity-100' : 'opacity-0 group-hover:opacity-100 group-focus-within:opacity-100',
                          )}
                          onClick={(event) => event.stopPropagation()}
                          aria-label="历史会话菜单"
                        >
                          <MoreHorizontal className="h-4 w-4" />
                        </button>
                      </DropdownMenuTrigger>
                      <DropdownMenuContent
                        align="end"
                        sideOffset={8}
                        className="w-40 rounded-2xl border-[#eceff3] bg-white/95 p-1.5 shadow-[0_14px_32px_rgba(15,23,42,0.10)] backdrop-blur-sm"
                      >
                        <DropdownMenuItem
                          className="rounded-xl px-3 py-2 text-[#3f4247] focus:bg-[#eef1f4] focus:text-[#3f4247]"
                          onClick={(event) => {
                            event.stopPropagation()
                            handleRename(item)
                          }}
                        >
                          <PencilLine className="mr-3 h-4 w-4 text-[#3f4247]" />
                          重命名
                        </DropdownMenuItem>
                        <DropdownMenuItem
                          className="rounded-xl px-3 py-2 text-[#3f4247] focus:bg-[#eef1f4] focus:text-[#3f4247]"
                          onClick={(event) => {
                            event.stopPropagation()
                            handleTogglePin(item)
                          }}
                        >
                          {item.pinned ? <PinOff className="mr-3 h-4 w-4 text-[#3f4247]" /> : <Pin className="mr-3 h-4 w-4 text-[#3f4247]" />}
                          {item.pinned ? '取消置顶' : '置顶'}
                        </DropdownMenuItem>
                        <DropdownMenuItem
                          className="rounded-xl px-3 py-2 text-[#ff3b30] focus:bg-[#fff1f0] focus:text-[#ff3b30]"
                          onClick={(event) => {
                            event.stopPropagation()
                            handleDelete(item)
                          }}
                        >
                          <Trash2 className="mr-3 h-4 w-4 text-[#ff3b30]" />
                          删除
                        </DropdownMenuItem>
                      </DropdownMenuContent>
                    </DropdownMenu>
                  </div>
                )
              })}
            </div>
            <button
              type="button"
              className="mt-2 px-2 py-2 text-sm text-muted-foreground hover:text-foreground"
              onClick={() => navigate('/workspace/history')}
            >
              查看全部
            </button>
          </div>
        )}

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

        {!collapsed && (
          <div className="grid grid-cols-2 gap-1 rounded-lg bg-[#eceef1] p-1 dark:bg-white/[0.08]">
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
          </div>
        )}

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
            {!collapsed && (
              <div className="min-w-0 flex-1">
                <div className="truncate text-sm font-medium">{session?.nickname || session?.username}</div>
                <div className="text-xs text-muted-foreground">{session?.role === 'ADMIN' ? '管理员' : '普通用户'}</div>
              </div>
            )}
          </button>
          {!collapsed && (
            <Button variant="ghost" size="icon" className="h-8 w-8" onClick={() => setProfileOpen(true)} title="修改资料">
              <Settings className="h-4 w-4" />
            </Button>
          )}
          {!collapsed && (
            <Button variant="ghost" size="icon" className="h-8 w-8" onClick={logout} title="退出登录">
              <LogOut className="h-4 w-4" />
            </Button>
          )}
        </div>
        <ProfileDialog open={profileOpen} onOpenChange={setProfileOpen} />
      </div>
    </aside>
  )
}
