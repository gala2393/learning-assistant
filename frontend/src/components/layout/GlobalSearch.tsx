import { useNavigate } from 'react-router-dom'
import { Clock, MessageSquare, Search } from 'lucide-react'
import { useState } from 'react'
import { useHistory } from '@/api/rag'
import { Dialog, DialogContent } from '@/components/ui/dialog'
import { cn, formatDate, truncate } from '@/lib/utils'
import type { HistoryItem } from '@/types'

interface GlobalSearchProps {
  open: boolean
  onClose: () => void
}

function buildHistoryPath(item: HistoryItem) {
  const params = new URLSearchParams({ historyId: String(item.id) })
  const source = item.sources?.[0]
  if (source) {
    params.set('materialId', source.materialId)
    params.set('chunkId', source.chunkId)
  }
  return `/workspace/chat?${params.toString()}`
}

export function GlobalSearch({ open, onClose }: GlobalSearchProps) {
  const navigate = useNavigate()
  const { data: historyItems = [], isLoading } = useHistory()
  const [query, setQuery] = useState('')

  const keyword = query.trim().toLowerCase()
  const filtered = keyword
    ? historyItems
        .filter((item) =>
          item.question.toLowerCase().includes(keyword) ||
          item.answer.toLowerCase().includes(keyword)
        )
        .slice(0, 20)
    : historyItems.slice(0, 10)

  const handleSelect = (item: HistoryItem) => {
    navigate(buildHistoryPath(item))
    setQuery('')
    onClose()
  }

  const handleOpenChange = (nextOpen: boolean) => {
    if (!nextOpen) {
      setQuery('')
      onClose()
    }
  }

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent className="max-w-xl overflow-hidden p-0 shadow-lg">
        <div className="flex items-center border-b px-3">
          <Search className="mr-2 h-4 w-4 shrink-0 text-muted-foreground" />
          <input
            className="flex h-12 w-full bg-transparent py-3 text-sm outline-none placeholder:text-muted-foreground"
            placeholder="搜索历史对话..."
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            autoFocus
          />
        </div>
        <div className="max-h-[420px] overflow-y-auto p-2">
          {isLoading ? (
            <p className="py-8 text-center text-sm text-muted-foreground">正在加载历史记录...</p>
          ) : filtered.length === 0 ? (
            <p className="py-8 text-center text-sm text-muted-foreground">
              {keyword ? '没有找到匹配的对话' : '暂无历史对话'}
            </p>
          ) : (
            <div className="space-y-1">
              {filtered.map((item) => {
                const matchedAnswer = keyword && item.answer.toLowerCase().includes(keyword)
                return (
                  <button
                    key={item.id}
                    onClick={() => handleSelect(item)}
                    className="flex w-full gap-3 rounded-lg px-3 py-2.5 text-left transition-colors hover:bg-[#f5f6f8] dark:hover:bg-white/[0.08]"
                  >
                    <MessageSquare className="mt-0.5 h-4 w-4 shrink-0 text-muted-foreground" />
                    <div className="min-w-0 flex-1">
                      <div className="flex items-center gap-2">
                        <p className="truncate text-sm font-medium">{item.question}</p>
                        {item.sources?.length ? (
                          <span className="shrink-0 rounded bg-[#eef0f2] px-1.5 py-0.5 text-[10px] text-[#4b5563]">
                            资料问答
                          </span>
                        ) : null}
                      </div>
                      <p
                        className={cn(
                          'mt-1 line-clamp-2 text-xs leading-5 text-muted-foreground',
                          matchedAnswer && 'text-slate-600 dark:text-slate-300',
                        )}
                      >
                        {truncate(item.answer, 120)}
                      </p>
                      <div className="mt-1.5 flex items-center gap-1 text-[10px] text-muted-foreground">
                        <Clock className="h-3 w-3" />
                        {formatDate(item.createdAt)}
                      </div>
                    </div>
                  </button>
                )
              })}
            </div>
          )}
        </div>
      </DialogContent>
    </Dialog>
  )
}
