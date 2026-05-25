import { useMemo, useState } from 'react'
import { ScrollArea } from '@/components/ui/scroll-area'
import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { cn, truncate } from '@/lib/utils'
import { ChevronUp, MoreHorizontal, PencilLine, Pin, PinOff, Star, Trash2 } from 'lucide-react'
import type { HistoryItem } from '@/types'

interface ChatHistoryProps {
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
}

interface HistoryGroup {
  label: string
  items: HistoryItem[]
}

function parseDate(value: string) {
  const normalized = String(value || '').replace(' ', 'T')
  const date = new Date(normalized)
  return Number.isNaN(date.getTime()) ? new Date() : date
}

function monthLabel(date: Date) {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}`
}

function groupHistoryItems(items: HistoryItem[]): HistoryGroup[] {
  const now = new Date()
  const sevenDaysAgo = new Date(now)
  sevenDaysAgo.setDate(now.getDate() - 7)
  const thirtyDaysAgo = new Date(now)
  thirtyDaysAgo.setDate(now.getDate() - 30)

  const pinned = items.filter((item) => item.pinned)
  const normal = items.filter((item) => !item.pinned)

  const groups = new Map<string, HistoryItem[]>()
  normal.forEach((item) => {
    const date = parseDate(item.createdAt)
    const label = date >= sevenDaysAgo
      ? '7 天内'
      : date >= thirtyDaysAgo
        ? '30 天内'
        : monthLabel(date)
    groups.set(label, [...(groups.get(label) || []), item])
  })

  const result: HistoryGroup[] = []
  if (pinned.length > 0) {
    result.push({ label: '置顶', items: pinned })
  }
  Array.from(groups.entries()).forEach(([label, groupItems]) => {
    result.push({ label, items: groupItems })
  })
  return result
}

export function ChatHistory({
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
}: ChatHistoryProps) {
  const groups = useMemo(() => groupHistoryItems(items), [items])
  const [renameTarget, setRenameTarget] = useState<HistoryItem | null>(null)
  const [renameValue, setRenameValue] = useState('')
  const [openMenuId, setOpenMenuId] = useState<string | null>(null)

  const openRename = (item: HistoryItem) => {
    setRenameTarget(item)
    setRenameValue(item.title || item.question)
  }

  const submitRename = () => {
    if (!renameTarget) return
    const next = renameValue.trim()
    if (!next) return
    onRename(String(renameTarget.id), next)
    setRenameTarget(null)
  }

  return (
    <div className="flex h-full w-full min-w-0 flex-col bg-[#f7f8fa] dark:bg-[#111318]">
      <ScrollArea className="flex-1 px-3 pt-3">
        <div className="space-y-4 pb-5">
          {items.length === 0 && (
            <p className="py-6 text-center text-xs text-muted-foreground">暂无会话</p>
          )}
          {groups.map((group) => (
            <section key={group.label} className="space-y-1">
              <div className="px-3 pb-1 text-[13px] font-semibold text-[#9aa0a6] dark:text-slate-500">
                {group.label}
              </div>
              {group.items.map((item) => {
                const itemId = String(item.id)
                const selected = selectedId === itemId
                const title = truncate(item.title || item.question, 26)
                return (
                  <div
                    key={itemId}
                    onClick={() => onSelect(item)}
                    onMouseLeave={() => setOpenMenuId((current) => (current === itemId ? null : current))}
                    className={cn(
                      'group relative flex w-full items-center gap-2 rounded-lg border border-transparent px-3 py-2 text-left transition-colors',
                      selected
                        ? 'border-[#d8deea] bg-[#eaf1ff] text-[#2457ff] dark:border-white/10 dark:bg-white/[0.08] dark:text-white'
                        : 'border-[#e6ebf2] bg-transparent text-[#202124] hover:bg-[#f1f4f8] dark:border-slate-800 dark:text-slate-300 dark:hover:bg-white/[0.04]',
                    )}
                  >
                    <div className="min-w-0 flex-1">
                      <div className="truncate text-[13px] leading-5">
                        {title}
                      </div>
                    </div>
                    {item.pinned && <ChevronUp className="h-3.5 w-3.5 shrink-0 text-amber-500" />}
                    <DropdownMenu>
                      <DropdownMenuTrigger asChild>
                        <button
                          type="button"
                          className={cn(
                            'absolute right-24 top-1/2 z-20 flex h-7 w-7 -translate-y-1/2 items-center justify-center rounded-md text-[#667085] transition-all',
                            openMenuId === itemId || selected
                              ? 'opacity-100'
                              : 'opacity-0 group-hover:opacity-100 group-focus-within:opacity-100',
                            openMenuId === itemId
                              ? 'border border-white/50 bg-white/65 shadow-sm backdrop-blur-[2px] dark:border-white/10 dark:bg-slate-900/55'
                              : 'border border-transparent bg-transparent shadow-none hover:bg-white/60 dark:hover:bg-slate-900/40',
                          )}
                          onClick={(event) => {
                            event.stopPropagation()
                            setOpenMenuId((current) => (current === itemId ? null : itemId))
                          }}
                          aria-label="浼氳瘽鑿滃崟"
                        >
                          <MoreHorizontal className="h-4 w-4" />
                        </button>
                      </DropdownMenuTrigger>
                      <DropdownMenuContent align="end" sideOffset={6} className="w-36">
                        {onToggleFavorite && (
                          <DropdownMenuItem onClick={() => onToggleFavorite(item)}>
                            <Star className={cn('mr-2 h-4 w-4', isFavorited?.(item) && 'fill-amber-400 text-amber-500')} />
                            {isFavorited?.(item) ? '取消收藏' : '收藏'}
                          </DropdownMenuItem>
                        )}
                        <DropdownMenuItem onClick={() => openRename(item)}>
                          <PencilLine className="mr-2 h-4 w-4" />
                          重命名
                        </DropdownMenuItem>
                        <DropdownMenuItem onClick={() => onTogglePin(itemId)}>
                          {item.pinned ? <PinOff className="mr-2 h-4 w-4" /> : <Pin className="mr-2 h-4 w-4" />}
                          {item.pinned ? '取消置顶' : '置顶'}
                        </DropdownMenuItem>
                        <DropdownMenuItem
                          className="text-destructive focus:text-destructive"
                          onClick={() => onDelete(itemId)}
                        >
                          <Trash2 className="mr-2 h-4 w-4" />
                          删除
                        </DropdownMenuItem>
                      </DropdownMenuContent>
                    </DropdownMenu>
                  </div>
                )
              })}
            </section>
          ))}
        </div>
      </ScrollArea>

      <Dialog open={!!renameTarget} onOpenChange={(open) => !open && setRenameTarget(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>重命名会话</DialogTitle>
          </DialogHeader>
          <Input value={renameValue} onChange={(e) => setRenameValue(e.target.value)} autoFocus />
          <DialogFooter>
            <Button variant="outline" onClick={() => setRenameTarget(null)}>取消</Button>
            <Button onClick={submitRename} disabled={!renameValue.trim() || renamingId === String(renameTarget?.id || '')}>
              保存
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  )
}
