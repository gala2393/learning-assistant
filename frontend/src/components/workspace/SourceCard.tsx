import { Card, CardContent } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { cn, truncate } from '@/lib/utils'
import { BookOpen, ExternalLink } from 'lucide-react'
import type { RagSource } from '@/types'

interface SourceCardProps {
  source: RagSource
  rank?: number
  maxScore?: number
  onOpen?: (source: RagSource) => void
}

export function SourceCard({ source, rank, maxScore, onOpen }: SourceCardProps) {
  const rawScore = Number(source.score || 0)
  const scorePercent = Math.round(Math.max(0, Math.min(1, rawScore)) * 100)
  const relativePercent = Math.round((rawScore / Math.max(maxScore || rawScore || 1, 0.001)) * 100)
  const clickable = !!onOpen

  return (
    <Card
      className={cn(
        'overflow-hidden border-l-4 border-l-primary/60 bg-white/90 shadow-sm dark:border-slate-700 dark:bg-slate-900/70',
        clickable && 'cursor-pointer transition-colors hover:bg-muted/60 dark:hover:bg-slate-800/80',
      )}
      role={clickable ? 'button' : undefined}
      tabIndex={clickable ? 0 : undefined}
      onClick={() => onOpen?.(source)}
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
            {rank && (
              <span className="flex h-5 min-w-5 items-center justify-center rounded-full bg-slate-900 px-1 text-[10px] font-bold text-white dark:bg-white dark:text-slate-900">
                {rank}
              </span>
            )}
            <BookOpen className="h-3.5 w-3.5 shrink-0" />
            {source.materialTitle}
          </span>
          <div className="flex items-center gap-1.5 shrink-0">
            {source.pageNo > 0 && (
              <Badge variant="outline" className="text-[10px] px-1.5 py-0">
                第 {source.pageNo} 页
              </Badge>
            )}
            <Badge variant="secondary" className="text-[10px] px-1.5 py-0">
              {scorePercent}%
            </Badge>
            {clickable && <ExternalLink className="h-3.5 w-3.5 text-muted-foreground" />}
          </div>
        </div>
        <div className="h-1.5 overflow-hidden rounded-full bg-slate-100 dark:bg-slate-800">
          <div
            className="h-full rounded-full bg-gradient-to-r from-cyan-500 via-emerald-500 to-amber-400"
            style={{ width: `${Math.max(6, Math.min(100, relativePercent))}%` }}
          />
        </div>
        {source.excerpt && (
          <p className="text-xs text-muted-foreground leading-relaxed">
            {truncate(source.excerpt, 120)}
          </p>
        )}
      </CardContent>
    </Card>
  )
}
