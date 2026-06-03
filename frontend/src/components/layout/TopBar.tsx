import { useLocation } from 'react-router-dom'
import { Menu, Search } from 'lucide-react'
import { ADMIN_SECTIONS, WORKSPACE_SECTIONS } from '@/constants'
import { Button } from '@/components/ui/button'
import { useGlobalSearch } from '@/hooks/useGlobalSearch'
import { GlobalSearch } from './GlobalSearch'

interface TopBarProps {
  onOpenMobileMenu?: () => void
}

export function TopBar({ onOpenMobileMenu }: TopBarProps) {
  const location = useLocation()
  const { open, setOpen, close } = useGlobalSearch()
  const isChat = location.pathname === '/workspace/chat'

  const allSections = [...WORKSPACE_SECTIONS, ...ADMIN_SECTIONS]
  const current = allSections.find((s) => s.path === location.pathname)

  return (
    <>
      <header className="flex h-14 shrink-0 items-center justify-between gap-3 border-b border-transparent px-3 md:px-6">
        <Button
          variant="ghost"
          size="icon"
          className="h-9 w-9 shrink-0 rounded-full bg-[#f5f6f8] text-muted-foreground hover:bg-[#eef0f2] dark:bg-slate-900 dark:hover:bg-slate-800 md:hidden"
          onClick={onOpenMobileMenu}
        >
          <Menu className="h-4 w-4" />
        </Button>
        {isChat ? (
          <div className="flex min-w-0 flex-1 justify-start md:justify-center">
            <div className="rounded-full bg-[#eef0f2] px-4 py-1.5 text-xs font-medium text-[#4b5563] dark:bg-white/10 dark:text-slate-200">
              资料智能问答
            </div>
          </div>
        ) : (
          <div className="min-w-0">
            <h1 className="truncate text-base font-semibold">{current?.label || '课程学习助手'}</h1>
            {current?.kicker && <span className="hidden text-xs text-muted-foreground sm:inline">{current.kicker}</span>}
          </div>
        )}
        <Button
          variant="ghost"
          className={isChat
            ? 'h-9 w-9 shrink-0 justify-center rounded-full border border-transparent bg-transparent px-0 text-sm text-muted-foreground hover:border-slate-200 hover:bg-[#f5f6f8] dark:hover:border-slate-700 dark:hover:bg-slate-900 md:absolute md:right-6 md:w-36 md:justify-start md:px-3'
            : 'h-9 w-9 shrink-0 justify-center rounded-full border border-transparent bg-[#f5f6f8] px-0 text-sm text-muted-foreground hover:border-slate-200 hover:bg-white dark:bg-slate-900 dark:hover:border-slate-700 dark:hover:bg-slate-900 sm:w-44 sm:justify-start sm:px-3 md:w-56'}
          onClick={() => setOpen(true)}
        >
          <Search className="h-4 w-4" />
          <span className="hidden sm:inline">搜索...</span>
          <kbd className={isChat ? 'hidden' : 'pointer-events-none ml-auto inline-flex h-5 select-none items-center gap-1 rounded border bg-white px-1.5 font-mono text-[10px] font-medium text-muted-foreground dark:bg-slate-800'}>
            <span className="text-xs">Ctrl</span>K
          </kbd>
        </Button>
      </header>
      <GlobalSearch open={open} onClose={close} />
    </>
  )
}
