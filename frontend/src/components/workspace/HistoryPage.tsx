import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { motion } from 'framer-motion'
import { useClearHistory, useDeleteHistory, useHistory } from '@/api/rag'
import { useAddFavorite, useDeleteFavorite, useFavorites } from '@/api/favorites'
import { useDebounce } from '@/hooks/useDebounce'
import { Input } from '@/components/ui/input'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { ScrollArea } from '@/components/ui/scroll-area'
import { Checkbox } from '@/components/ui/checkbox'
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
  DialogFooter,
} from '@/components/ui/dialog'
import { cn, formatDate, truncate } from '@/lib/utils'
import { Search, Eye, Trash2, Clock, MessageSquare, Star } from 'lucide-react'
import type { HistoryItem } from '@/types'
import { useToast } from '@/components/ui/toast'

export function HistoryPage() {
  const navigate = useNavigate()
  const { data: items = [], isLoading } = useHistory()
  const { data: favorites = [] } = useFavorites()
  const deleteMutation = useDeleteHistory()
  const clearMutation = useClearHistory()
  const addFavoriteMutation = useAddFavorite()
  const deleteFavoriteMutation = useDeleteFavorite()
  const { showToast } = useToast()

  const [keyword, setKeyword] = useState('')
  const [viewTarget, setViewTarget] = useState<HistoryItem | null>(null)
  const [deleteTarget, setDeleteTarget] = useState<string | null>(null)
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set())
  const [confirmBatchDelete, setConfirmBatchDelete] = useState(false)
  const [confirmClear, setConfirmClear] = useState(false)

  const debouncedKeyword = useDebounce(keyword, 300)
  const normalizedKeyword = debouncedKeyword.toLowerCase()

  const filtered = normalizedKeyword
    ? items.filter(
        (h) =>
          h.question.toLowerCase().includes(normalizedKeyword) ||
          h.answer.toLowerCase().includes(normalizedKeyword),
      )
    : items

  const filteredIds = filtered.map((item) => String(item.id))
  const selectedCount = selectedIds.size
  const allFilteredSelected = filteredIds.length > 0 && filteredIds.every((id) => selectedIds.has(id))
  const someFilteredSelected = filteredIds.some((id) => selectedIds.has(id))

  useEffect(() => {
    const validIds = new Set(items.map((item) => String(item.id)))
    setSelectedIds((prev) => {
      const next = new Set(Array.from(prev).filter((id) => validIds.has(id)))
      return next.size === prev.size ? prev : next
    })
  }, [items])

  const openHistory = (item: HistoryItem) => {
    const params = new URLSearchParams({ historyId: String(item.id) })
    const source = item.sources?.[0]
    if (source) {
      params.set('materialId', source.materialId)
      params.set('chunkId', source.chunkId)
    }
    navigate(`/workspace/chat?${params.toString()}`)
  }

  const getFavoriteId = (item: HistoryItem) =>
    favorites.find((f) => String(f.questionId) === String(item.id))?.id || item.favoriteId || null

  const updateSelection = (id: string, checked: boolean) => {
    setSelectedIds((prev) => {
      const next = new Set(prev)
      if (checked) next.add(id)
      else next.delete(id)
      return next
    })
  }

  const clearSelection = () => setSelectedIds(new Set())

  const toggleFilteredSelection = (checked: boolean) => {
    setSelectedIds((prev) => {
      const next = new Set(prev)
      filteredIds.forEach((id) => {
        if (checked) next.add(id)
        else next.delete(id)
      })
      return next
    })
  }

  const handleDeleteConfirm = () => {
    if (!deleteTarget) return
    deleteMutation.mutate(deleteTarget, {
      onSuccess: () => {
        if (String(viewTarget?.id) === deleteTarget) setViewTarget(null)
        setSelectedIds((prev) => {
          const next = new Set(prev)
          next.delete(deleteTarget)
          return next
        })
        setDeleteTarget(null)
        showToast('已删除历史记录')
      },
      onError: (error) => showToast(error instanceof Error ? error.message : '删除失败'),
    })
  }

  const handleBatchDeleteConfirm = async () => {
    if (selectedCount === 0) return
    const ids = Array.from(selectedIds)
    const results = await Promise.allSettled(ids.map((id) => deleteMutation.mutateAsync(id)))
    const successIds = ids.filter((_, index) => results[index].status === 'fulfilled')
    const failedCount = results.length - successIds.length

    setSelectedIds((prev) => {
      const next = new Set(prev)
      successIds.forEach((id) => next.delete(id))
      return next
    })
    setConfirmBatchDelete(false)

    if (failedCount === 0) {
      showToast(`已删除 ${successIds.length} 条历史记录`)
    } else {
      showToast(`已删除 ${successIds.length} 条，${failedCount} 条删除失败`)
    }
  }

  const handleClearConfirm = () => {
    clearMutation.mutate(undefined, {
      onSuccess: () => {
        setViewTarget(null)
        setDeleteTarget(null)
        setSelectedIds(new Set())
        setConfirmClear(false)
        showToast('已清空历史记录')
      },
      onError: (error) => showToast(error instanceof Error ? error.message : '清空失败'),
    })
  }

  const handleToggleFavorite = (item: HistoryItem) => {
    const favoriteId = getFavoriteId(item)
    if (favoriteId) {
      deleteFavoriteMutation.mutate(favoriteId, {
        onSuccess: () => showToast('已取消收藏'),
        onError: (error) => showToast(error instanceof Error ? error.message : '取消收藏失败'),
      })
    } else {
      addFavoriteMutation.mutate(String(item.id), {
        onSuccess: () => showToast('已加入收藏'),
        onError: (error) => showToast(error instanceof Error ? error.message : '收藏失败'),
      })
    }
  }

  return (
    <motion.div
      className="flex flex-col h-full"
      initial={{ opacity: 0, y: 12 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.3 }}
    >
      <div className="flex items-center justify-between px-6 pt-4 pb-2">
        <h2 className="text-lg font-semibold flex items-center gap-2">
          <Clock className="h-5 w-5" /> 历史记录
        </h2>
        <div className="flex items-center gap-2">
          <span className="text-sm text-muted-foreground">共 {filtered.length} 条</span>
          <Button
            variant="outline"
            size="sm"
            className="h-8 text-destructive hover:text-destructive"
            disabled={items.length === 0 || clearMutation.isPending}
            onClick={() => setConfirmClear(true)}
          >
            <Trash2 className="h-3.5 w-3.5 mr-1.5" />
            清空
          </Button>
        </div>
      </div>

      <div className="px-6 pb-3">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div className="relative w-full max-w-md">
            <Search className="absolute left-2.5 top-2.5 h-4 w-4 text-muted-foreground" />
            <Input
              placeholder="搜索问答内容..."
              className="pl-8 h-9"
              value={keyword}
              onChange={(e) => setKeyword(e.target.value)}
            />
          </div>
          <div className="flex items-center gap-2">
            <Checkbox
              checked={allFilteredSelected ? true : someFilteredSelected ? 'indeterminate' : false}
              disabled={filteredIds.length === 0}
              onCheckedChange={(checked) => toggleFilteredSelection(checked === true)}
              aria-label="全选当前结果"
            />
            <span className="text-xs text-muted-foreground">全选当前结果</span>
          </div>
        </div>
      </div>

      {selectedCount > 0 && (
        <div className="px-6 pb-3">
          <div className="flex items-center justify-between gap-3 rounded-lg border bg-muted/30 px-3 py-2">
            <div className="flex items-center gap-2">
              <Badge variant="secondary" className="text-[10px] px-2 py-0 h-5">
                已选 {selectedCount} 条
              </Badge>
              <button
                type="button"
                className="text-xs text-muted-foreground hover:text-foreground transition-colors"
                onClick={clearSelection}
              >
                清除选择
              </button>
            </div>
            <Button
              variant="destructive"
              size="sm"
              className="h-8"
              onClick={() => setConfirmBatchDelete(true)}
            >
              <Trash2 className="h-3.5 w-3.5 mr-1.5" />
              批量删除
            </Button>
          </div>
        </div>
      )}

      <ScrollArea className="flex-1 px-6">
        <div className="space-y-2 pb-4">
          {!isLoading && filtered.length === 0 && (
            <p className="text-center text-muted-foreground py-12">暂无历史记录</p>
          )}
          {filtered.map((item) => {
            const itemId = String(item.id)
            const favoriteId = getFavoriteId(item)
            const deleting = deleteMutation.isPending && String(deleteMutation.variables || '') === itemId
            const favoriting =
              addFavoriteMutation.isPending && String(addFavoriteMutation.variables || '') === itemId
            const selected = deleteTarget === itemId || String(viewTarget?.id || '') === itemId

            return (
              <Card
                key={itemId}
                className={cn(
                  'group relative hover:shadow-sm transition-shadow cursor-pointer',
                  selected && 'ring-1 ring-primary/20',
                )}
                onClick={() => openHistory(item)}
              >
                <CardContent className="py-3 pl-4 pr-[88px]">
                  <div className="flex gap-3 min-w-0">
                    <div className="pt-0.5">
                      <Checkbox
                        checked={selectedIds.has(itemId)}
                        onCheckedChange={(checked) => updateSelection(itemId, checked === true)}
                        onClick={(e) => e.stopPropagation()}
                        aria-label={`选择历史记录 ${item.question}`}
                      />
                    </div>
                    <div className="min-w-0 flex-1">
                      <div className="flex items-center gap-2 mb-1">
                        <MessageSquare className="h-4 w-4 text-primary shrink-0" />
                        <p className="text-sm font-medium truncate">{item.question}</p>
                      </div>
                      <p className="text-xs text-muted-foreground line-clamp-2 leading-relaxed">
                        {truncate(item.answer, 150)}
                      </p>
                      <p className="text-[10px] text-muted-foreground mt-1.5">
                        {formatDate(item.createdAt)}
                      </p>
                    </div>
                    <div
                      className={cn(
                        'absolute right-3 top-1/2 z-10 flex -translate-y-1/2 items-center gap-1 transition-opacity',
                        selected
                          ? 'opacity-100 pointer-events-auto'
                          : 'opacity-0 pointer-events-none group-hover:opacity-100 group-hover:pointer-events-auto group-focus-within:opacity-100 group-focus-within:pointer-events-auto',
                      )}
                    >
                      <Button
                        variant="outline"
                        size="icon"
                        className="h-7 w-7 bg-background/95 shadow-sm"
                        aria-label={favoriteId ? '取消收藏问答' : '收藏问答'}
                        title={favoriteId ? '取消收藏' : '收藏'}
                        disabled={favoriting || deleteFavoriteMutation.isPending}
                        onClick={(e) => {
                          e.stopPropagation()
                          handleToggleFavorite(item)
                        }}
                      >
                        <Star
                          className={cn('h-3.5 w-3.5', favoriteId && 'fill-yellow-400 text-yellow-400')}
                        />
                      </Button>
                      <Button
                        variant="outline"
                        size="icon"
                        className="h-7 w-7 bg-background/95 shadow-sm"
                        aria-label="查看问答详情"
                        title="查看详情"
                        onClick={(e) => {
                          e.stopPropagation()
                          setViewTarget(item)
                        }}
                      >
                        <Eye className="h-3.5 w-3.5" />
                      </Button>
                      <Button
                        variant="outline"
                        size="icon"
                        className="h-7 w-7 bg-background/95 shadow-sm"
                        aria-label="删除历史记录"
                        title="删除历史记录"
                        disabled={deleting}
                        onClick={(e) => {
                          e.stopPropagation()
                          setDeleteTarget(itemId)
                        }}
                      >
                        <Trash2 className="h-3.5 w-3.5 text-destructive" />
                      </Button>
                    </div>
                  </div>
                </CardContent>
              </Card>
            )
          })}
        </div>
      </ScrollArea>

      <Dialog open={!!viewTarget} onOpenChange={(v) => !v && setViewTarget(null)}>
        <DialogContent className="max-w-lg max-h-[80vh] overflow-auto">
          <DialogHeader>
            <DialogTitle className="text-base">问答详情</DialogTitle>
          </DialogHeader>
          {viewTarget && (
            <div className="space-y-3">
              <div>
                <Badge variant="outline" className="text-[10px] mb-1">
                  问题
                </Badge>
                <p className="text-sm">{viewTarget.question}</p>
              </div>
              <div>
                <Badge variant="outline" className="text-[10px] mb-1">
                  回答
                </Badge>
                <p className="text-sm whitespace-pre-wrap leading-relaxed">{viewTarget.answer}</p>
              </div>
              <p className="text-[10px] text-muted-foreground">{formatDate(viewTarget.createdAt)}</p>
            </div>
          )}
        </DialogContent>
      </Dialog>

      <Dialog open={!!deleteTarget} onOpenChange={(v) => !v && setDeleteTarget(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>确认删除</DialogTitle>
            <DialogDescription>确定要删除这条记录吗？</DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button variant="outline" onClick={() => setDeleteTarget(null)}>
              取消
            </Button>
            <Button variant="destructive" onClick={handleDeleteConfirm} disabled={deleteMutation.isPending}>
              {deleteMutation.isPending ? '删除中...' : '确认删除'}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog open={confirmBatchDelete} onOpenChange={setConfirmBatchDelete}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>批量删除历史记录</DialogTitle>
            <DialogDescription>
              确定要删除当前选中的 {selectedCount} 条历史记录吗？删除后无法恢复。
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button variant="outline" onClick={() => setConfirmBatchDelete(false)}>
              取消
            </Button>
            <Button
              variant="destructive"
              onClick={handleBatchDeleteConfirm}
              disabled={deleteMutation.isPending || selectedCount === 0}
            >
              {deleteMutation.isPending ? '删除中...' : '确认删除'}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog open={confirmClear} onOpenChange={setConfirmClear}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>清空全部历史</DialogTitle>
            <DialogDescription>这会删除所有问答历史和对应收藏，删除后不能恢复。</DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button variant="outline" onClick={() => setConfirmClear(false)}>
              取消
            </Button>
            <Button variant="destructive" onClick={handleClearConfirm} disabled={clearMutation.isPending}>
              {clearMutation.isPending ? '清空中...' : '确认清空'}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </motion.div>
  )
}
