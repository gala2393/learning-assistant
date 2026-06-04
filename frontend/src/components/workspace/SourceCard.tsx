/**
 * SourceCard - RAG 来源卡片组件
 *
 * 功能说明：
 * - 在 AI 问答结果中展示一个检索来源（来自哪份资料、哪一页）
 * - 显示相关度分数（带渐变进度条）
 * - 可点击跳转到对应资料片段
 *
 * 交互说明：
 * - 当提供 onOpen 回调时，卡片可点击（支持键盘 Enter/Space 操作）
 * - 左侧蓝色边框标记来源优先级
 */
import { Card, CardContent } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { cn, truncate } from '@/lib/utils'
import { BookOpen, ExternalLink } from 'lucide-react'
import type { RagSource } from '@/types'

interface SourceCardProps {
  source: RagSource     // 检索来源数据（包含资料标题、页码、摘要、相关度分数等）
  rank?: number         // 排名序号（显示在左侧圆形徽章中）
  maxScore?: number     // 当前批次中的最高分（用于计算相对百分比进度条宽度）
  onOpen?: (source: RagSource) => void  // 点击打开来源的回调
}

export function SourceCard({ source, rank, maxScore, onOpen }: SourceCardProps) {
  // 相关度分数：0~1 之间，转为百分比显示
  const rawScore = Number(source.score || 0)
  const scorePercent = Math.round(Math.max(0, Math.min(1, rawScore)) * 100)
  // 相对百分比：相对于最高分的占比，用于进度条宽度
  const relativePercent = Math.round((rawScore / Math.max(maxScore || rawScore || 1, 0.001)) * 100)
  const clickable = !!onOpen

  return (
    <Card
      className={cn(
        'overflow-hidden border-l-4 border-l-primary/60 bg-white/90 shadow-sm dark:border-slate-700 dark:bg-slate-900/70',
        clickable && 'cursor-pointer transition-colors hover:bg-muted/60 dark:hover:bg-slate-800/80',
      )}
      // 无障碍：可点击时设置 role="button" 和 tabIndex
      role={clickable ? 'button' : undefined}
      tabIndex={clickable ? 0 : undefined}
      onClick={() => onOpen?.(source)}
      // 键盘可访问性：Enter 和 Space 键触发
      onKeyDown={(event) => {
        if (!clickable) return
        if (event.key === 'Enter' || event.key === ' ') {
          event.preventDefault()
          onOpen(source)
        }
      }}
    >
      <CardContent className="py-3 px-4 space-y-1.5">
        <div className="flex items-center justify-between gap-2">
          <span className="text-sm font-medium truncate flex items-center gap-1.5">
            {/* 排名序号徽章 */}
            {rank && (
              <span className="flex h-5 min-w-5 items-center justify-center rounded-full bg-slate-900 px-1 text-[10px] font-bold text-white dark:bg-white dark:text-slate-900">
                {rank}
              </span>
            )}
            <BookOpen className="h-3.5 w-3.5 shrink-0" />
            {source.materialTitle}
          </span>
          <div className="flex items-center gap-1.5 shrink-0">
            {/* 页码标签（如果有的话） */}
            {source.pageNo > 0 && (
              <Badge variant="outline" className="text-[10px] px-1.5 py-0">
                第 {source.pageNo} 页
              </Badge>
            )}
            {/* 相关度百分比标签 */}
            <Badge variant="secondary" className="text-[10px] px-1.5 py-0">
              {scorePercent}%
            </Badge>
            {/* 可点击时显示外部链接图标 */}
            {clickable && <ExternalLink className="h-3.5 w-3.5 text-muted-foreground" />}
          </div>
        </div>
        {/* 相关度渐变进度条 */}
        <div className="h-1.5 overflow-hidden rounded-full bg-slate-100 dark:bg-slate-800">
          <div
            className="h-full rounded-full bg-gradient-to-r from-cyan-500 via-emerald-500 to-amber-400"
            style={{ width: `${Math.max(6, Math.min(100, relativePercent))}%` }}
          />
        </div>
        {/* 摘要文本（截断到 120 字符） */}
        {source.excerpt && (
          <p className="text-xs text-muted-foreground leading-relaxed">
            {truncate(source.excerpt, 120)}
          </p>
        )}
      </CardContent>
    </Card>
  )
}
