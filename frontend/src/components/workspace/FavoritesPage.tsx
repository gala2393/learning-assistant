import { useState } from 'react'
import { motion } from 'framer-motion'
import { useFavorites, useDeleteFavorite } from '@/api/favorites'
import { useDebounce } from '@/hooks/useDebounce'
import { Input } from '@/components/ui/input'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { ScrollArea } from '@/components/ui/scroll-area'
import {
  Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription, DialogFooter,
} from '@/components/ui/dialog'
import { formatDate, truncate } from '@/lib/utils'
import { Search, Star, Trash2, Eye } from 'lucide-react'
import type { FavoriteItem } from '@/types'

export function FavoritesPage() {
  const { data: items = [], isLoading } = useFavorites()
  const deleteMutation = useDeleteFavorite()

  const [keyword, setKeyword] = useState('')
  const [viewTarget, setViewTarget] = useState<FavoriteItem | null>(null)
  const [deleteTarget, setDeleteTarget] = useState<string | null>(null)

  const debouncedKeyword = useDebounce(keyword, 300)

  const filtered = debouncedKeyword
    ? items.filter((f) =>
        f.question.toLowerCase().includes(debouncedKeyword.toLowerCase()) ||
        f.answer.toLowerCase().includes(debouncedKeyword.toLowerCase())
      )
    : items

  const handleDeleteConfirm = () => {
    if (deleteTarget) {
      deleteMutation.mutate(deleteTarget, {
        onSuccess: () => setDeleteTarget(null),
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
          <Star className="h-5 w-5" /> 我的收藏
        </h2>
        <span className="text-sm text-muted-foreground">共 {filtered.length} 条</span>
      </div>

      <div className="px-6 pb-3">
        <div className="relative max-w-md">
          <Search className="absolute left-2.5 top-2.5 h-4 w-4 text-muted-foreground" />
          <Input
            placeholder="搜索收藏内容..."
            className="pl-8 h-9"
            value={keyword}
            onChange={(e) => setKeyword(e.target.value)}
          />
        </div>
      </div>

      <ScrollArea className="flex-1 px-6">
        <div className="grid grid-cols-1 md:grid-cols-2 gap-3 pb-4">
          {filtered.length === 0 && (
            <p className="text-center text-muted-foreground py-12 col-span-full">暂无收藏</p>
          )}
          {filtered.map((item) => (
            <Card key={item.id} className="hover:shadow-sm transition-shadow">
              <CardContent className="py-3 px-4 space-y-2">
                <div className="flex items-start justify-between gap-2">
                  <div className="flex items-center gap-1.5 min-w-0">
                    <Star className="h-3.5 w-3.5 text-amber-500 shrink-0" />
                    <p className="text-sm font-medium truncate">{item.question}</p>
                  </div>
                  <div className="flex items-center gap-1 shrink-0">
                    <Button variant="ghost" size="icon" className="h-6 w-6"
                      onClick={() => setViewTarget(item)}>
                      <Eye className="h-3.5 w-3.5" />
                    </Button>
                    <Button variant="ghost" size="icon" className="h-6 w-6"
                      onClick={() => setDeleteTarget(item.id)}>
                      <Trash2 className="h-3.5 w-3.5 text-destructive" />
                    </Button>
                  </div>
                </div>
                <p className="text-xs text-muted-foreground line-clamp-3 leading-relaxed">
                  {truncate(item.answer, 120)}
                </p>
                <p className="text-[10px] text-muted-foreground">{formatDate(item.createdAt)}</p>
              </CardContent>
            </Card>
          ))}
        </div>
      </ScrollArea>

      {/* View dialog */}
      <Dialog open={!!viewTarget} onOpenChange={(v) => !v && setViewTarget(null)}>
        <DialogContent className="max-w-lg max-h-[80vh] overflow-auto">
          <DialogHeader>
            <DialogTitle className="text-base">收藏详情</DialogTitle>
          </DialogHeader>
          {viewTarget && (
            <div className="space-y-3">
              <div>
                <Badge variant="outline" className="text-[10px] mb-1">问题</Badge>
                <p className="text-sm">{viewTarget.question}</p>
              </div>
              <div>
                <Badge variant="outline" className="text-[10px] mb-1">回答</Badge>
                <p className="text-sm whitespace-pre-wrap leading-relaxed">{viewTarget.answer}</p>
              </div>
              <p className="text-[10px] text-muted-foreground">{formatDate(viewTarget.createdAt)}</p>
            </div>
          )}
        </DialogContent>
      </Dialog>

      {/* Delete dialog */}
      <Dialog open={!!deleteTarget} onOpenChange={(v) => !v && setDeleteTarget(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>确认取消收藏</DialogTitle>
            <DialogDescription>确定要取消收藏吗？</DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button variant="outline" onClick={() => setDeleteTarget(null)}>取消</Button>
            <Button variant="destructive" onClick={handleDeleteConfirm} disabled={deleteMutation.isPending}>
              {deleteMutation.isPending ? '处理中...' : '确认取消'}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </motion.div>
  )
}
