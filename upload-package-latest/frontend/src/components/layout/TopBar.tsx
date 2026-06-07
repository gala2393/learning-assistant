/**
 * TopBar 组件 —— 页面顶部栏
 *
 * 【用途与使用场景】
 * 显示在主内容区的顶部，提供以下功能：
 * 1. 移动端菜单按钮：点击打开移动端侧滑菜单（仅移动端显示）
 * 2. 页面标题：根据当前路由显示对应的页面名称
 *    - 聊天页面显示"资料智能问答"标签
 *    - 其他页面显示菜单配置中的 label 和 kicker（副标题）
 * 3. 搜索按钮：打开全局搜索弹窗，支持 Ctrl+K 快捷键
 *
 * 【技术细节】
 * - 搜索功能通过 useGlobalSearch hook 管理弹窗开关状态
 * - 搜索弹窗（GlobalSearch 组件）内联渲染在 TopBar 内部
 * - 聊天页面和非聊天页面的搜索按钮有不同的样式表现
 */

import { useLocation } from 'react-router-dom'
import { Menu, Search } from 'lucide-react'
import { ADMIN_SECTIONS, WORKSPACE_SECTIONS } from '@/constants'
import { Button } from '@/components/ui/button'
import { useGlobalSearch } from '@/hooks/useGlobalSearch'
import { GlobalSearch } from './GlobalSearch'

/**
 * TopBar 组件的 Props 接口
 * @property onOpenMobileMenu - 打开移动端侧滑菜单的回调函数
 */
interface TopBarProps {
  onOpenMobileMenu?: () => void
}

export function TopBar({ onOpenMobileMenu }: TopBarProps) {
  const location = useLocation()
  // 全局搜索弹窗的开关状态管理
  const { open, setOpen, close } = useGlobalSearch()
  // 判断当前是否为聊天页面
  const isChat = location.pathname === '/workspace/chat'

  // 合并所有菜单分组，用于查找当前路由对应的菜单项信息
  const allSections = [...WORKSPACE_SECTIONS, ...ADMIN_SECTIONS]
  const current = allSections.find((s) => s.path === location.pathname)

  return (
    <>
      <header className={isChat ? 'hidden' : 'flex h-14 shrink-0 items-center justify-between gap-3 border-b border-transparent px-3 md:px-6'}>
        {/* 移动端菜单按钮（桌面端隐藏） */}
        <Button
          variant="ghost"
          size="icon"
          className="h-9 w-9 shrink-0 rounded-full bg-[#f5f6f8] text-muted-foreground hover:bg-[#eef0f2] dark:bg-slate-900 dark:hover:bg-slate-800 md:hidden"
          onClick={onOpenMobileMenu}
        >
          <Menu className="h-4 w-4" />
        </Button>

        {/* 页面标题区域 */}
        {isChat ? (
          <div className="min-w-0 flex-1" />
        ) : (
          // 其他页面：显示菜单标题和副标题
          <div className="min-w-0">
            <h1 className="truncate text-base font-semibold">{current?.label || '智学引擎'}</h1>
            {/* kicker 是菜单配置中的副标题，仅在 sm 断点以上显示 */}
            {current?.kicker && <span className="hidden text-xs text-muted-foreground sm:inline">{current.kicker}</span>}
          </div>
        )}

        {/* 搜索按钮 */}
        <Button
          variant="ghost"
          title="搜索"
          className={isChat
            // 聊天页面样式：桌面端定位到右侧
            ? 'h-8 w-8 shrink-0 justify-center rounded-full border border-slate-200 bg-[#f7f8fa] px-0 text-muted-foreground shadow-sm hover:bg-white hover:text-slate-700 dark:border-slate-800 dark:bg-slate-900 dark:hover:bg-slate-800 dark:hover:text-slate-100'
            // 非聊天页面样式：带宽度的搜索栏
            : 'h-9 w-9 shrink-0 justify-center rounded-full border border-transparent bg-[#f5f6f8] px-0 text-sm text-muted-foreground hover:border-slate-200 hover:bg-white dark:bg-slate-900 dark:hover:border-slate-700 dark:hover:bg-slate-900 sm:w-44 sm:justify-start sm:px-3 md:w-56'}
          onClick={() => setOpen(true)}
        >
          <Search className="h-4 w-4" />
          {/* 搜索文字提示（小屏以上显示） */}
          <span className={isChat ? 'sr-only' : 'hidden sm:inline'}>搜索...</span>
          {/* Ctrl+K 快捷键提示（聊天页面隐藏） */}
          <kbd className={isChat ? 'hidden' : 'pointer-events-none ml-auto inline-flex h-5 select-none items-center gap-1 rounded border bg-white px-1.5 font-mono text-[10px] font-medium text-muted-foreground dark:bg-slate-800'}>
            <span className="text-xs">Ctrl</span>K
          </kbd>
        </Button>
      </header>
      {/* 全局搜索弹窗 */}
      {!isChat && <GlobalSearch open={open} onClose={close} />}
    </>
  )
}
