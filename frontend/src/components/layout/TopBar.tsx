import { useLocation } from 'react-router-dom'
import { Search } from 'lucide-react'
import { ADMIN_SECTIONS, WORKSPACE_SECTIONS } from '@/constants'
import { Button } from '@/components/ui/button'
import { useGlobalSearch } from '@/hooks/useGlobalSearch'
import { GlobalSearch } from './GlobalSearch'

export function TopBar() {
  const location = useLocation()
  const { open, setOpen, close } = useGlobalSearch()
  const isChat = location.pathname === '/workspace/chat'

  const allSections = [...WORKSPACE_SECTIONS, ...ADMIN_SECTIONS]
  const current = allSections.find((s) => s.path === location.pathname)

  return (
    <>
      <header className="flex h-14 shrink-0 items-center justify-between px-6">
        {isChat ? (
          <div className="flex flex-1 justify-center">
            <div className="rounded-full bg-[#eef5ff] px-4 py-1.5 text-xs font-medium text-[#2f80ff] dark:bg-sky-400/10 dark:text-sky-300">
              资料智能问答
            </div>
          </div>
        ) : (
          <div>
            <h1 className="text-base font-semibold">{current?.label || '课程学习助手'}</h1>
            {current?.kicker && <span className="text-xs text-muted-foreground">{current.kicker}</span>}
          </div>
        )}
        <Button
          variant="ghost"
          className={isChat
            ? 'absolute right-6 h-9 w-36 justify-start gap-2 rounded-full border border-transparent bg-transparent px-3 text-sm text-muted-foreground hover:border-slate-200 hover:bg-[#f5f6f8] dark:hover:border-slate-700 dark:hover:bg-slate-900'
            : 'h-9 w-56 justify-start gap-2 rounded-full border border-transparent bg-[#f5f6f8] px-3 text-sm text-muted-foreground hover:border-slate-200 hover:bg-white dark:bg-slate-900 dark:hover:border-slate-700 dark:hover:bg-slate-900'}
          onClick={() => setOpen(true)}
        >
          <Search className="h-4 w-4" />
          <span>搜索...</span>
          <kbd className={isChat ? 'hidden' : 'pointer-events-none ml-auto inline-flex h-5 select-none items-center gap-1 rounded border bg-white px-1.5 font-mono text-[10px] font-medium text-muted-foreground dark:bg-slate-800'}>
            <span className="text-xs">Ctrl</span>K
          </kbd>
        </Button>
      </header>
      <GlobalSearch open={open} onClose={close} />
    </>
  )
}
