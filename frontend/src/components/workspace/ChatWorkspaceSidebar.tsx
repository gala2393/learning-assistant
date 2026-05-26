import { Plus } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import { ChatHistory } from './ChatHistory'
import type { HistoryItem } from '@/types'

interface ChatWorkspaceSidebarProps {
  items: HistoryItem[]
  selectedId: string | null
  onSelect: (item: HistoryItem) => void
  onDelete: (id: string) => void
  onRename: (id: string, title: string) => void
  onTogglePin: (id: string) => void
  onToggleFavorite?: (item: HistoryItem) => void
  isFavorited?: (item: HistoryItem) => boolean
  deletingId?: string | null
  renamingId?: string | null
  onNewChat: () => void
}

export function ChatWorkspaceSidebar({
  items,
  selectedId,
  onSelect,
  onDelete,
  onRename,
  onTogglePin,
  onToggleFavorite,
  isFavorited,
  deletingId,
  renamingId,
  onNewChat,
}: ChatWorkspaceSidebarProps) {
  return (
    <aside className={cn('flex h-full w-[18.5rem] min-w-[18.5rem] flex-col border-r border-[#e6e8ee] bg-[#f7f8fa] dark:border-slate-800 dark:bg-[#111318]')}>
      <div className="border-b border-[#e6e8ee] p-3 dark:border-slate-800">
        <Button
          variant="outline"
          className="h-11 w-full justify-start gap-2 rounded-xl border-[#e1e4e8] bg-white px-3 text-sm font-medium shadow-sm hover:bg-white dark:border-slate-800 dark:bg-[#171a21]"
          onClick={onNewChat}
        >
          <Plus className="h-4 w-4" />
          新建会话
          <span className="ml-auto rounded bg-slate-100 px-1.5 py-0.5 text-[10px] text-muted-foreground dark:bg-slate-800">Ctrl K</span>
        </Button>
      </div>

      <div className="flex-1 min-h-0">
        <ChatHistory
          items={items}
          selectedId={selectedId}
          onSelect={onSelect}
          onDelete={onDelete}
          onRename={onRename}
          onTogglePin={onTogglePin}
          onToggleFavorite={onToggleFavorite}
          isFavorited={isFavorited}
          deletingId={deletingId}
          renamingId={renamingId}
        />
      </div>
    </aside>
  )
}
