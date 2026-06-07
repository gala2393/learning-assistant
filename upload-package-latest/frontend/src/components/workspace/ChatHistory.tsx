import { useMemo, useState } from 'react'
import { ScrollArea } from '@/components/ui/scroll-area'
import { Button } from '@/components/ui/button'
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger } from '@/components/ui/dropdown-menu'
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { cn, truncate } from '@/lib/utils'
import { ChevronUp, MoreHorizontal, PencilLine, Pin, PinOff, Star, Trash2 } from 'lucide-react'
import type { HistoryItem } from '@/types'

/**
 * ChatHistory — 聊天历史列表组件。
 *
 * 展示用户的问答历史记录，按时间分组（7天内/30天内/按月），
 * 置顶的对话排在最前面。
 *
 * 功能：
 * - 分组显示历史记录
 * - 点击选中恢复对话
 * - 右键菜单：收藏、重命名、置顶、删除
 * - 重命名弹窗
 * - 选中状态高亮
 *
 * 使用场景：ChatPage 的左侧历史列表 和 ChatWorkspaceSidebar 内部
 */

/** 组件属性 */
interface ChatHistoryProps {
  items: HistoryItem[]                    // 历史记录列表
  selectedId: string | null               // 当前选中的记录 ID
  onSelect: (item: HistoryItem) => void   // 选中回调
  onDelete: (id: string) => void          // 删除回调
  onRename: (id: string, title: string) => void  // 重命名回调
  onTogglePin: (id: string) => void       // 切换置顶回调
  onToggleFavorite?: (item: HistoryItem) => void  // 切换收藏回调
  isFavorited?: (item: HistoryItem) => boolean    // 是否已收藏
  deletingId?: string | null              // 正在删除的 ID（用于 loading 状态）
  renamingId?: string | null              // 正在重命名的 ID
}

/** 时间分组结构 */
interface HistoryGroup {
  label: string         // 分组标签（"置顶"/"7 天内"/"30 天内"/"2024-01"）
  items: HistoryItem[]  // 该组的记录
}

/** 解析日期字符串（兼容 "2024-01-15 14:30:00" 格式） */
function parseDate(value: string) {
  const normalized = String(value || '').replace(' ', 'T')
  const date = new Date(normalized)
  return Number.isNaN(date.getTime()) ? new Date() : date
}

/** 生成月份标签（如 "2024-01"） */
function monthLabel(date: Date) {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}`
}

/**
 * 将历史记录按时间分组：
 * - 置顶的单独一组
 * - 7 天内的为一组
 * - 30 天内的为一组
 * - 更早的按月份分组
 */
function groupHistoryItems(items: HistoryItem[]): HistoryGroup[] {
  const now = new Date()
  const sevenDaysAgo = new Date(now); sevenDaysAgo.setDate(now.getDate() - 7)
  const thirtyDaysAgo = new Date(now); thirtyDaysAgo.setDate(now.getDate() - 30)
  const pinned = items.filter((item) => item.pinned)
  const normal = items.filter((item) => !item.pinned)
  const groups = new Map<string, HistoryItem[]>()
  normal.forEach((item) => {
    const date = parseDate(item.createdAt)
    const label = date >= sevenDaysAgo ? '7 天内' : date >= thirtyDaysAgo ? '30 天内' : monthLabel(date)
    groups.set(label, [...(groups.get(label) || []), item])
  })
  const result: HistoryGroup[] = []
  if (pinned.length > 0) result.push({ label: '置顶', items: pinned })
  Array.from(groups.entries()).forEach(([label, groupItems]) => result.push({ label, items: groupItems }))
  return result
}

export function ChatHistory({ items, selectedId, onSelect, onDelete, onRename, onTogglePin, onToggleFavorite, isFavorited, deletingId, renamingId }: ChatHistoryProps) {
  const groups = useMemo(() => groupHistoryItems(items), [items])
  const [renameTarget, setRenameTarget] = useState<HistoryItem | null>(null)
  const [renameValue, setRenameValue] = useState('')
  const [openMenuId, setOpenMenuId] = useState<string | null>(null)

  /** 打开重命名弹窗 */
  const openRename = (item: HistoryItem) => { setRenameTarget(item); setRenameValue(item.title || item.question) }
  /** 提交重命名 */
  const submitRename = () => { if (!renameTarget) return; const next = renameValue.trim(); if (!next) return; onRename(String(renameTarget.id), next); setRenameTarget(null) }

  return (
    <div className="flex h-full w-full min-w-0 flex-col bg-transparent">
      <ScrollArea className="flex-1 px-1 pt-1">
        <div className="space-y-2.5 pb-4">
          {items.length === 0 && <p className="py-6 text-center text-xs text-muted-foreground">暂无会话</p>}
          {groups.map((group) => (
            <section key={group.label} className="space-y-1">
              {/* 分组标题 */}
              <div className="px-3 pb-1 text-xs font-medium text-[#a7adb5] dark:text-slate-500">{group.label}</div>
              {group.items.map((item) => {
                const itemId = String(item.id)
                const selected = selectedId === itemId
                const title = truncate(item.title || item.question, 26)
                return (
                  <div key={itemId} onClick={() => onSelect(item)}
                    className={cn('group relative flex w-full items-center gap-2 rounded-xl border px-3 py-2 pr-10 text-left transition-colors',
                      selected ? 'border-[#d8deea] bg-[#eef4ff] text-[#2457ff] ...' : 'border-[#e6ebf2] bg-white/90 text-[#9aa0a6] hover:bg-[#f6f8fb] ...')}>
                    <div className="min-w-0 flex-1"><div className="truncate text-[13px] leading-5">{title}</div></div>
                    {item.pinned && <ChevronUp className="h-3.5 w-3.5 shrink-0 text-amber-500" />}
                    {/* 操作菜单（收藏/重命名/置顶/删除） */}
                    <DropdownMenu>
                      <DropdownMenuTrigger asChild>
                        <button type="button" className="absolute right-0 top-1/2 z-20 flex h-7 w-7 -translate-y-1/2 ..."
                          onClick={(e) => { e.stopPropagation(); setOpenMenuId((c) => (c === itemId ? null : itemId)) }} aria-label="会话菜单">
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
                        <DropdownMenuItem onClick={() => openRename(item)}><PencilLine className="mr-2 h-4 w-4" />重命名</DropdownMenuItem>
                        <DropdownMenuItem onClick={() => onTogglePin(itemId)}>
                          {item.pinned ? <PinOff className="mr-2 h-4 w-4" /> : <Pin className="mr-2 h-4 w-4" />}
                          {item.pinned ? '取消置顶' : '置顶'}
                        </DropdownMenuItem>
                        <DropdownMenuItem className="text-destructive" onClick={() => onDelete(itemId)}>
                          <Trash2 className="mr-2 h-4 w-4" />删除
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

      {/* 重命名弹窗 */}
      <Dialog open={!!renameTarget} onOpenChange={(open) => !open && setRenameTarget(null)}>
        <DialogContent>
          <DialogHeader><DialogTitle>重命名会话</DialogTitle></DialogHeader>
          <Input value={renameValue} onChange={(e) => setRenameValue(e.target.value)} autoFocus />
          <DialogFooter>
            <Button variant="outline" onClick={() => setRenameTarget(null)}>取消</Button>
            <Button onClick={submitRename} disabled={!renameValue.trim() || renamingId === String(renameTarget?.id || '')}>保存</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  )
}
