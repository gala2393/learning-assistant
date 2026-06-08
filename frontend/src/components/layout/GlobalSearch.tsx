/**
 * GlobalSearch 组件 —— 全局搜索对话框
 *
 * 【用途与使用场景】
 * 提供全站范围内的历史对话搜索功能。
 * 用户可以通过顶部栏的搜索按钮或 Ctrl+K 快捷键打开。
 *
 * 【核心逻辑】
 * 1. 以 Dialog 弹窗形式展示
 * 2. 顶部搜索框自动聚焦，支持关键词实时过滤
 * 3. 关键词匹配问题和回答内容，最多显示 20 条结果
 * 4. 无关键词时默认显示最近 10 条历史对话
 * 5. 点击搜索结果跳转到对应的聊天页面
 * 6. 关闭弹窗时自动清空搜索关键词
 *
 * 【数据来源】
 * 通过 useHistory() hook 获取用户的所有历史对话记录，
 * 前端进行关键词过滤（不调用后端搜索接口）。
 */

import { useNavigate } from 'react-router-dom'
import { Clock, MessageSquare, Search } from 'lucide-react'
import { useState } from 'react'
import { useHistory } from '@/api/rag'
import { Dialog, DialogContent } from '@/components/ui/dialog'
import { cn, formatDate, truncate } from '@/lib/utils'
import type { HistoryItem } from '@/types'

/**
 * GlobalSearch 组件的 Props 接口
 * @property open - 弹窗是否打开
 * @property onClose - 关闭弹窗的回调函数
 */
interface GlobalSearchProps {
  open: boolean
  onClose: () => void
}

/**
 * 根据历史对话记录构建跳转路径
 * @param item - 历史对话记录
 * @returns 路由路径字符串，包含 historyId、materialId、chunkId 参数
 *
 * 如果该对话有来源资料（sources），会把第一个来源的 materialId 和 chunkId 也带入 URL，
 * 以便聊天页面定位到具体的资料片段。
 */
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
  // 获取所有历史对话记录
  const { data: historyItems = [], isLoading } = useHistory()
  // 搜索关键词
  const [query, setQuery] = useState('')

  // 将关键词转为小写，方便不区分大小写匹配
  const keyword = query.trim().toLowerCase()
  // 根据关键词过滤历史记录
  const filtered = keyword
    ? historyItems
        .filter((item) =>
          // 全局搜索只做本地历史匹配，不额外调用后端，保证弹窗输入即时响应。
          item.question.toLowerCase().includes(keyword) ||
          item.answer.toLowerCase().includes(keyword)
        )
        .slice(0, 20) // 最多显示 20 条搜索结果
    : historyItems.slice(0, 10) // 无关键词时显示最近 10 条

  /**
   * 选择搜索结果后的处理：
   * 跳转到对应的聊天页面，清空关键词，关闭弹窗
   */
  const handleSelect = (item: HistoryItem) => {
    // 路径中保留 historyId 和首个来源定位信息，聊天页可恢复对应上下文。
    navigate(buildHistoryPath(item))
    setQuery('')
    onClose()
  }

  /**
   * 弹窗开关状态变化处理：
   * 关闭时清空关键词并调用父组件的关闭回调
   */
  const handleOpenChange = (nextOpen: boolean) => {
    if (!nextOpen) {
      setQuery('')
      onClose()
    }
  }

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent className="max-w-xl overflow-hidden p-0 shadow-lg">
        {/* 搜索输入框区域 */}
        <div className="flex items-center border-b px-3">
          <Search className="mr-2 h-4 w-4 shrink-0 text-muted-foreground" />
          <input
            className="flex h-12 w-full bg-transparent py-3 text-sm outline-none placeholder:text-muted-foreground"
            placeholder="搜索历史对话..."
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            autoFocus // 打开时自动聚焦
          />
        </div>

        {/* 搜索结果列表 */}
        <div className="max-h-[420px] overflow-y-auto p-2">
          {isLoading ? (
            // 加载中状态
            <p className="py-8 text-center text-sm text-muted-foreground">正在加载历史记录...</p>
          ) : filtered.length === 0 ? (
            // 无结果状态
            <p className="py-8 text-center text-sm text-muted-foreground">
              {keyword ? '没有找到匹配的对话' : '暂无历史对话'}
            </p>
          ) : (
            // 搜索结果列表
            <div className="space-y-1">
              {filtered.map((item) => {
                // 判断回答内容是否也匹配关键词（用于高亮显示）
                const matchedAnswer = keyword && item.answer.toLowerCase().includes(keyword)
                return (
                  <button
                    key={item.id}
                    onClick={() => handleSelect(item)}
                    className="flex w-full gap-3 rounded-lg px-3 py-2.5 text-left transition-colors hover:bg-[#f5f6f8] dark:hover:bg-white/[0.08]"
                  >
                    {/* 对话图标 */}
                    <MessageSquare className="mt-0.5 h-4 w-4 shrink-0 text-muted-foreground" />
                    <div className="min-w-0 flex-1">
                      <div className="flex items-center gap-2">
                        {/* 问题标题 */}
                        <p className="truncate text-sm font-medium">{item.question}</p>
                        {/* 如果有来源资料，显示"资料问答"标签 */}
                        {item.sources?.length ? (
                          <span className="shrink-0 rounded bg-[#eef0f2] px-1.5 py-0.5 text-[10px] text-[#4b5563]">
                            资料问答
                          </span>
                        ) : null}
                      </div>
                      {/* 回答内容预览（截断为 2 行，最多 120 字符） */}
                      <p
                        className={cn(
                          'mt-1 line-clamp-2 text-xs leading-5 text-muted-foreground',
                          // 回答内容也匹配关键词时使用更深的颜色
                          matchedAnswer && 'text-slate-600 dark:text-slate-300',
                        )}
                      >
                        {truncate(item.answer, 120)}
                      </p>
                      {/* 创建时间 */}
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
