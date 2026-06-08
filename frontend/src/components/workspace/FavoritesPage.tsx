/**
 * FavoritesPage - 我的收藏页面
 *
 * 功能说明：
 * - 展示用户收藏的问答记录列表
 * - 支持关键词搜索（带 300ms 防抖）
 * - 支持查看收藏详情（弹窗展示问题、回答和会话记录）
 * - 支持取消收藏（二次确认弹窗）
 *
 * 数据流：
 * 1. 通过 useFavorites() 从后端获取收藏列表
 * 2. 用户输入关键词后，useDebounce 延迟 300ms 触发过滤
 * 3. 删除操作通过 useDeleteFavorite() 发送到后端
 */
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
import { useAuth } from '@/context/AuthContext'
import { useToast } from '@/components/ui/toast'
import { LOGIN_REQUIRED_MESSAGE, redirectToLogin } from '@/lib/auth-gate'
import { Search, Star, Trash2, Eye } from 'lucide-react'
import type { FavoriteItem } from '@/types'

export function FavoritesPage() {
  const { isAuthenticated } = useAuth()
  const { showToast } = useToast()
  // 获取收藏列表数据和加载状态
  const { data: items = [], isLoading } = useFavorites()
  // 删除收藏的 mutation，调用 mutate(id) 即可触发
  const deleteMutation = useDeleteFavorite()

  // 搜索关键词
  const [keyword, setKeyword] = useState('')
  // 当前正在查看的收藏项（控制详情弹窗显示）
  const [viewTarget, setViewTarget] = useState<FavoriteItem | null>(null)
  // 待删除的收藏 ID（控制删除确认弹窗显示）
  const [deleteTarget, setDeleteTarget] = useState<string | null>(null)

  // 对搜索关键词做 300ms 防抖处理，避免每次输入都触发过滤
  const debouncedKeyword = useDebounce(keyword, 300)

  // 根据关键词过滤收藏列表：同时匹配问题和回答内容
  const filtered = debouncedKeyword
    ? items.filter((f) =>
        // 收藏页只做本地模糊搜索，问题和回答任一命中即可展示。
        f.question.toLowerCase().includes(debouncedKeyword.toLowerCase()) ||
        f.answer.toLowerCase().includes(debouncedKeyword.toLowerCase())
      )
    : items

  // 确认删除收藏的回调
  const handleDeleteConfirm = () => {
    if (!isAuthenticated) {
      showToast(LOGIN_REQUIRED_MESSAGE, 2000)
      redirectToLogin()
      return
    }
    if (deleteTarget) {
      // 删除成功后只关闭确认弹窗，列表刷新由 useDeleteFavorite 的缓存失效逻辑处理。
      deleteMutation.mutate(deleteTarget, {
        onSuccess: () => setDeleteTarget(null), // 删除成功后关闭弹窗
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
      {/* 页面标题和统计 */}
      <div className="flex items-center justify-between px-6 pt-4 pb-2">
        <h2 className="text-lg font-semibold flex items-center gap-2">
          <Star className="h-5 w-5" /> 我的收藏
        </h2>
        <span className="text-sm text-muted-foreground">共 {filtered.length} 条</span>
      </div>

      {/* 搜索栏 */}
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

      {/* 收藏列表 - 双列网格布局 */}
      <ScrollArea className="flex-1 px-6">
        <div className="grid grid-cols-1 md:grid-cols-2 gap-3 pb-4">
          {/* 空状态提示 */}
          {filtered.length === 0 && (
            <p className="text-center text-muted-foreground py-12 col-span-full">暂无收藏</p>
          )}
          {/* 遍历渲染每条收藏卡片 */}
          {filtered.map((item) => (
            <Card key={item.id} className="hover:shadow-sm transition-shadow">
              <CardContent className="py-3 px-4 space-y-2">
                <div className="flex items-start justify-between gap-2">
                  <div className="flex items-center gap-1.5 min-w-0">
                    <Star className="h-3.5 w-3.5 text-amber-500 shrink-0" />
                    {/* 问题标题，超长截断 */}
                    <p className="text-sm font-medium truncate">{item.question}</p>
                  </div>
                  {/* 操作按钮：查看详情、取消收藏 */}
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
                {/* 回答预览，最多显示 3 行 */}
                <p className="text-xs text-muted-foreground line-clamp-3 leading-relaxed">
                  {truncate(item.answer, 120)}
                </p>
                {/* 收藏时间 */}
                <p className="text-[10px] text-muted-foreground">{formatDate(item.createdAt)}</p>
              </CardContent>
            </Card>
          ))}
        </div>
      </ScrollArea>

      {/* 收藏详情弹窗 - 展示完整的问题、回答和会话记录 */}
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
              {/* 如果有会话记录，展示完整对话 */}
              {viewTarget.messages && viewTarget.messages.length > 0 && (
                <div className="space-y-2">
                  <Badge variant="outline" className="text-[10px] mb-1">会话记录</Badge>
                  <div className="space-y-2 rounded-lg border bg-muted/20 p-3">
                    {viewTarget.messages.map((message) => (
                      <div key={message.id} className="text-sm">
                        <span className="mr-2 font-medium">{message.role === 'user' ? '你' : 'AI'}</span>
                        <span className="whitespace-pre-wrap leading-relaxed">{message.text}</span>
                      </div>
                    ))}
                  </div>
                </div>
              )}
              <p className="text-[10px] text-muted-foreground">{formatDate(viewTarget.createdAt)}</p>
            </div>
          )}
        </DialogContent>
      </Dialog>

      {/* 删除确认弹窗 */}
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
