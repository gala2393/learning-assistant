import { Plus } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import { ChatHistory } from './ChatHistory'
import type { HistoryItem } from '@/types'

/**
 * ChatWorkspaceSidebar — 聊天工作区左侧边栏。
 *
 * 包含两部分：
 * 1. 顶部"新建会话"按钮（快捷键 Ctrl+K）
 * 2. 聊天历史列表（ChatHistory 组件）
 *
 * 使用场景：ChatPage 的左侧边栏，展示所有对话历史。
 */

/** 组件属性 — 与 ChatHistory 基本一致，额外增加 onNewChat */
interface ChatWorkspaceSidebarProps {
  items: HistoryItem[]                                    // 历史记录列表
  selectedId: string | null                               // 当前选中 ID
  onSelect: (item: HistoryItem) => void                   // 选中回调
  onDelete: (id: string) => void                          // 删除回调
  onRename: (id: string, title: string) => void           // 重命名回调
  onTogglePin: (id: string) => void                       // 置顶回调
  onToggleFavorite?: (item: HistoryItem) => void          // 收藏回调
  isFavorited?: (item: HistoryItem) => boolean            // 是否已收藏
  deletingId?: string | null                              // 正在删除的 ID
  renamingId?: string | null                              // 正在重命名的 ID
  onNewChat: () => void                                   // 新建会话回调
}

export function ChatWorkspaceSidebar({
  items, selectedId, onSelect, onDelete, onRename, onTogglePin,
  onToggleFavorite, isFavorited, deletingId, renamingId, onNewChat,
}: ChatWorkspaceSidebarProps) {
  return (
    <aside className="flex h-full w-[18.5rem] min-w-[18.5rem] flex-col border-r border-[#e6e8ee] bg-[#f7f8fa] dark:border-slate-800 dark:bg-[#111318]">
      {/* 顶部：新建会话按钮 */}
      <div className="border-b border-[#e6e8ee] p-3 dark:border-slate-800">
        <Button variant="outline" className="h-11 w-full justify-start gap-2 rounded-xl ..." onClick={onNewChat}>
          <Plus className="h-4 w-4" />
          新建会话
          <span className="ml-auto rounded bg-slate-100 px-1.5 py-0.5 text-[10px] text-muted-foreground dark:bg-slate-800">Ctrl K</span>
        </Button>
      </div>
      {/* 底部：历史列表（可滚动） */}
      <div className="flex-1 min-h-0">
        <ChatHistory items={items} selectedId={selectedId} onSelect={onSelect} onDelete={onDelete}
          onRename={onRename} onTogglePin={onTogglePin} onToggleFavorite={onToggleFavorite}
          isFavorited={isFavorited} deletingId={deletingId} renamingId={renamingId} />
      </div>
    </aside>
  )
}
