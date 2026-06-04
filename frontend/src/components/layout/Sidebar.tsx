/**
 * Sidebar 组件 —— 桌面端左侧导航栏
 *
 * 【用途与使用场景】
 * 应用左侧的持久导航栏，仅在桌面端（md 断点以上）显示。
 * 提供以下功能区域：
 *
 * 1. 顶部 Logo + 折叠按钮：控制侧边栏的展开/收起
 * 2. 新建会话按钮：跳转到聊天页面并创建新对话
 * 3. 导航菜单：根据当前路径（工作区/管理后台）显示不同的菜单项
 * 4. 历史会话列表：仅在聊天页面展开时显示，支持以下操作：
 *    - 点击打开历史对话
 *    - 右键菜单：收藏、重命名、置顶、删除
 * 5. 管理员快捷入口：在工作区和管理后台之间切换
 * 6. LLM 状态指示器：显示 AI 模型的连接状态
 * 7. 主题切换开关：浅色/深色模式
 * 8. 底部用户信息区：头像、昵称、设置按钮、登出按钮
 * 9. 个人资料编辑弹窗
 *
 * 【技术细节】
 * - 折叠状态持久化到 localStorage
 * - 主题偏好持久化到 localStorage
 * - 历史会话使用 SWR 缓存和自动刷新
 * - 收藏/重命名/置顶/删除操作使用 React Query mutations
 */

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
import { useUserLlmConfig } from '@/api/llm'
import { cn } from '@/lib/utils'
import type { HistoryItem, LlmStatus } from '@/types'
import { ProfileDialog } from './ProfileDialog'
import { UserAvatar } from './UserAvatar'
import { useHistory, useDeleteHistory, useRenameHistory, useTogglePinHistory } from '@/api/rag'
import { useToast } from '@/components/ui/toast'
import { queryClient } from '@/lib/query-client'
import { useAddFavorite, useDeleteFavorite, useFavorites } from '@/api/favorites'

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
}

export function Sidebar() {
  const location = useLocation()
  const navigate = useNavigate()
  const { session, isAdmin, logout } = useAuth()
  const { showToast } = useToast()
  // LLM 模型连接状态
  const [llmStatus, setLlmStatus] = useState<LlmStatus | null>(null)
  // 个人资料弹窗开关
  const [profileOpen, setProfileOpen] = useState(false)
  // 侧边栏是否折叠，初始值从 localStorage 读取
  const [collapsed, setCollapsed] = useState(() => {
    if (typeof window === 'undefined') return false
    return localStorage.getItem('learning-assistant.sidebar.collapsed') === 'true'
  })
  // 主题偏好，初始值从 localStorage 读取
  const [theme, setTheme] = useState<'light' | 'dark'>(() => {
    if (typeof window === 'undefined') return 'light'
    return localStorage.getItem('learning-assistant.theme') === 'dark' ? 'dark' : 'light'
  })
  // 当前打开的下拉菜单所属的历史会话 ID
  const [menuOpenId, setMenuOpenId] = useState<string | null>(null)

  // 判断当前是否在管理后台路由下
  const isAdminRoute = location.pathname.startsWith('/admin')
  // 判断当前是否在聊天页面
  const isChat = location.pathname === '/workspace/chat'
  // 根据当前路由选择要显示的菜单分组
  const sections = isAdminRoute ? ADMIN_SECTIONS : WORKSPACE_SECTIONS

  // 获取历史会话数据、收藏数据、用户 LLM 配置
  const { data: historyItems = [] } = useHistory()
  const { data: favorites = [] } = useFavorites()
  const { data: userLlmConfig } = useUserLlmConfig()
  // 获取各种 mutation 操作
  const deleteHistoryMutation = useDeleteHistory()
  const renameHistoryMutation = useRenameHistory()
  const togglePinHistoryMutation = useTogglePinHistory()
  const addFavoriteMutation = useAddFavorite()
  const deleteFavoriteMutation = useDeleteFavorite()

  /**
   * 获取 LLM 模型连接状态
   * 通过 GET /llm/status 接口查询
   */
  useEffect(() => {
    api.get('/llm/status')
      .then((res) => setLlmStatus(res.data))
      .catch(() => setLlmStatus(null))
  }, [])

  /**
   * 主题切换副作用：
   * 在 <html> 元素上切换 'dark' class，并持久化到 localStorage
   */
  useEffect(() => {
    document.documentElement.classList.toggle('dark', theme === 'dark')
    localStorage.setItem('learning-assistant.theme', theme)
  }, [theme])

  /**
   * 折叠状态持久化副作用：
   * 每次折叠状态变化时写入 localStorage
   */
  useEffect(() => {
    localStorage.setItem('learning-assistant.sidebar.collapsed', String(collapsed))
  }, [collapsed])

  // 最近的历史会话（最多显示 6 条）
  const recentHistory = useMemo(() => historyItems.slice(0, 6), [historyItems])
  // 从 URL 参数中获取当前选中的历史会话 ID
  const selectedHistoryId = new URLSearchParams(location.search).get('historyId')
  // LLM 是否已配置（优先使用用户自定义配置，其次使用全局配置）
  const effectiveLlmConfigured = Boolean(llmStatus?.configured || userLlmConfig?.enabled)
  // LLM 状态显示文案
  const effectiveLlmLabel = userLlmConfig?.enabled
    ? `${userLlmConfig.activeLabel || '自定义模型'} 已连接`
    : llmStatus?.configured
      ? 'LLM 已连接'
      : 'LLM 未配置'

  /**
   * 打开指定的历史会话
   * @param item - 历史会话记录
   */
  const openHistory = (item: HistoryItem) => {
    navigate(`/workspace/chat?historyId=${encodeURIComponent(String(item.id))}`)
  }

  /**
   * 获取历史会话对应的收藏记录 ID
   * @param item - 历史会话记录
   * @returns 收藏记录 ID，未收藏返回 null
   */
  const getFavoriteId = (item: HistoryItem) =>
    favorites.find((favorite) => String(favorite.questionId) === String(item.id))?.id || item.favoriteId || null

  /**
   * 重命名历史会话：
   * 弹出浏览器原生 prompt 输入新名称，确认后调用 API
   */
  const handleRename = (item: HistoryItem) => {
    const next = window.prompt('重命名会话', item.title || item.question)
    if (!next || !next.trim()) return
    renameHistoryMutation.mutate(
      { id: String(item.id), title: next.trim() },
      {
        onSuccess: () => {
          showToast('会话已重命名')
          // 刷新历史会话列表缓存
          queryClient.invalidateQueries({ queryKey: ['history'] })
        },
        onError: (error) => showToast(error instanceof Error ? error.message : '重命名失败'),
      },
    )
  }

  /**
   * 切换历史会话的置顶状态
   */
  const handleTogglePin = (item: HistoryItem) => {
    togglePinHistoryMutation.mutate(String(item.id), {
      onSuccess: () => {
        showToast(item.pinned ? '已取消置顶' : '已置顶')
        queryClient.invalidateQueries({ queryKey: ['history'] })
      },
      onError: (error) => showToast(error instanceof Error ? error.message : '置顶失败'),
    })
  }

  /**
   * 删除历史会话
   */
  const handleDelete = (item: HistoryItem) => {
    deleteHistoryMutation.mutate(String(item.id), {
      onSuccess: () => {
        showToast('会话已删除')
        queryClient.invalidateQueries({ queryKey: ['history'] })
      },
      onError: (error) => showToast(error instanceof Error ? error.message : '删除失败'),
    })
  }

  /**
   * 切换收藏状态：
   * - 已收藏 → 调用删除收藏 API
   * - 未收藏 → 调用添加收藏 API
   */
  const handleToggleFavorite = (item: HistoryItem) => {
    const favoriteId = getFavoriteId(item)
    if (favoriteId) {
      deleteFavoriteMutation.mutate(favoriteId, {
        onSuccess: () => {
          showToast('已取消收藏')
          queryClient.invalidateQueries({ queryKey: ['history'] })
        },
        onError: (error) => showToast(error instanceof Error ? error.message : '取消收藏失败'),
      })
    } else {
      addFavoriteMutation.mutate(String(item.id), {
        onSuccess: () => {
          showToast('已加入收藏')
          queryClient.invalidateQueries({ queryKey: ['history'] })
        },
        onError: (error) => showToast(error instanceof Error ? error.message : '收藏失败'),
      })
    }
  }

  return (
    <aside
      className={cn(
        'flex h-screen shrink-0 flex-col bg-[#f5f6f8] py-4 transition-[width] duration-200 dark:bg-[#111318]',
        collapsed ? 'w-[76px] px-2' : 'w-[286px] px-3',
      )}
    >
      {/* ========== 顶部 Logo + 折叠按钮 ========== */}
      <div className="mb-5 flex items-center justify-between px-1">
        {/* 品牌 Logo 按钮，点击回到首页 */}
        <button
          className="flex h-8 w-8 items-center justify-center rounded-lg bg-[#111318] text-sm font-black text-white shadow-sm dark:bg-white dark:text-[#111318]"
          onClick={() => navigate(isAdmin ? '/admin/dashboard' : '/workspace/chat')}
          title="学习助手"
        >
          学
        </button>
        {/* 侧边栏折叠/展开切换按钮 */}
        <button
          type="button"
          className="rounded-md p-1.5 text-muted-foreground hover:bg-slate-200/70 hover:text-foreground dark:hover:bg-white/10"
          onClick={() => setCollapsed((value) => !value)}
          title={collapsed ? '展开侧边栏' : '收起侧边栏'}
          aria-label={collapsed ? '展开侧边栏' : '收起侧边栏'}
        >
          {/* 折叠状态下箭头旋转 180 度 */}
          <ChevronLeft className={cn('h-4 w-4 transition-transform', collapsed && 'rotate-180')} />
        </button>
      </div>

      {/* ========== 新建会话按钮（根据折叠状态显示不同样式） ========== */}
      {collapsed ? (
        // 折叠状态：只显示图标
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
        // 展开状态：显示图标 + 文字 + 快捷键提示
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

      {/* ========== 导航菜单区域（可滚动） ========== */}
      <nav className="flex-1 overflow-y-auto">
        {/* 区域标题 */}
        <div className={cn('mb-2 px-2 text-xs font-medium text-muted-foreground', collapsed && 'sr-only')}>
          {isAdminRoute ? '管理后台' : '工作区'}
        </div>
        {/* 导航菜单列表 */}
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
                  // 折叠状态下用 title 属性提示菜单名称
                  title={collapsed ? section.label : undefined}
                >
                  <Icon className="h-4 w-4" />
                  {/* 展开状态下显示文字标签 */}
                  {!collapsed && <span>{section.label}</span>}
                </button>
              </li>
            )
          })}
        </ul>

        {/* ========== 历史会话列表（仅在聊天页面且展开时显示） ========== */}
        {isChat && !collapsed && (
          <div className="mt-4 min-h-0 px-1">
            {/* 标题行 + 新建会话按钮 */}
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
            {/* 历史会话列表 */}
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
                    {/* 置顶标签 */}
                    {item.pinned && (
                      <Badge
                        variant="outline"
                        className="mr-2 h-5 border-[#dde3ea] bg-[#f7f8fa] px-1.5 py-0 text-[10px] font-medium text-[#8f96a0]"
                      >
                        置顶
                      </Badge>
                    )}
                    {/* 会话标题（优先显示自定义标题，否则显示问题内容） */}
                    <div className="min-w-0 flex-1 truncate pr-2 leading-5">
                      {item.title || item.question}
                    </div>
                    {/* 右键操作菜单（收藏、重命名、置顶、删除） */}
                    <DropdownMenu open={menuOpenId === itemId} onOpenChange={(open) => setMenuOpenId(open ? itemId : null)}>
                      <DropdownMenuTrigger asChild>
                        <button
                          type="button"
                          className={cn(
                            'ml-auto flex h-7 w-7 shrink-0 items-center justify-center rounded-md text-[#9aa0a6] transition-opacity hover:bg-white/70 dark:hover:bg-white/10',
                            // 选中状态始终显示菜单按钮，否则仅在 hover 时显示
                            selected ? 'opacity-100' : 'opacity-0 group-hover:opacity-100 group-focus-within:opacity-100',
                          )}
                          onClick={(event) => event.stopPropagation()} // 防止点击菜单时触发会话打开
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
                        {/* 收藏/取消收藏 */}
                        <DropdownMenuItem
                          className="rounded-xl px-3 py-2 text-[#3f4247] focus:bg-[#eef1f4] focus:text-[#3f4247]"
                          onClick={(event) => {
                            event.stopPropagation()
                            handleToggleFavorite(item)
                          }}
                        >
                          <Star className={cn('mr-3 h-4 w-4', getFavoriteId(item) && 'fill-amber-400 text-amber-500')} />
                          {getFavoriteId(item) ? '取消收藏' : '收藏'}
                        </DropdownMenuItem>
                        {/* 重命名 */}
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
                        {/* 置顶/取消置顶 */}
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
                        {/* 删除（红色警示） */}
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
            {/* "查看全部"链接 */}
            <button
              type="button"
              className="mt-2 px-2 py-2 text-sm text-muted-foreground hover:text-foreground"
              onClick={() => navigate('/workspace/history')}
            >
              查看全部
            </button>
          </div>
        )}

        {/* ========== 管理员快捷切换入口 ========== */}
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

      {/* ========== 底部区域 ========== */}
      <div className="space-y-3 pt-4">
        {/* LLM 模型状态指示器（仅展开状态显示） */}
        {!collapsed && llmStatus && (
          <div
            className={cn(
              'flex items-center gap-2 rounded-lg px-3 py-2 text-xs',
              // 已连接显示绿色，未配置显示橙色
              effectiveLlmConfigured ? 'bg-emerald-500/10 text-emerald-600' : 'bg-amber-500/10 text-amber-600',
            )}
          >
            {effectiveLlmConfigured ? <Wifi className="h-3.5 w-3.5" /> : <WifiOff className="h-3.5 w-3.5" />}
            <Cpu className="h-3.5 w-3.5" />
            <span className="truncate">{effectiveLlmLabel}</span>
          </div>
        )}

        {/* 主题切换开关（仅展开状态显示） */}
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

        {/* 用户信息 + 设置 + 登出 */}
        <div className={cn('flex items-center gap-2 rounded-lg px-1 py-2', collapsed && 'justify-center px-0')}>
          {/* 用户头像和名称，点击打开个人资料 */}
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
            {/* 展开状态下显示用户名和角色 */}
            {!collapsed && (
              <div className="min-w-0 flex-1">
                <div className="truncate text-sm font-medium">{session?.nickname || session?.username}</div>
                <div className="text-xs text-muted-foreground">{session?.role === 'ADMIN' ? '管理员' : '普通用户'}</div>
              </div>
            )}
          </button>
          {/* 设置按钮（仅展开状态显示） */}
          {!collapsed && (
            <Button variant="ghost" size="icon" className="h-8 w-8" onClick={() => setProfileOpen(true)} title="修改资料">
              <Settings className="h-4 w-4" />
            </Button>
          )}
          {/* 登出按钮（仅展开状态显示） */}
          {!collapsed && (
            <Button variant="ghost" size="icon" className="h-8 w-8" onClick={logout} title="退出登录">
              <LogOut className="h-4 w-4" />
            </Button>
          )}
        </div>
        {/* 个人资料编辑弹窗 */}
        <ProfileDialog open={profileOpen} onOpenChange={setProfileOpen} />
      </div>
    </aside>
  )
}
