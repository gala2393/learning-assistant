import { Card, CardContent } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { cn, truncate } from '@/lib/utils'
import { BookOpen, ExternalLink } from 'lucide-react'
import type { RagSource, RetrievalDebugEntry } from '@/types'

interface SourceCardProps {
  source: RagSource
  debugEntry?: RetrievalDebugEntry
  rank?: number
  maxScore?: number
  onOpen?: (source: RagSource) => void
}

/**
 * 单条来源卡片。
 *
 * 在保留原有跳转行为的前提下，补一层最小解释信息：
 * - 召回路径
 * - 最终分
 * - 入选原因 / 降权原因
 *
 * 这样用户在点开来源之前，就能看到“为什么是这个片段”。
 */
export function SourceCard({ source, debugEntry, rank, maxScore, onOpen }: SourceCardProps) {
  const rawScore = Number(source.score || 0)
  const scorePercent = Math.round(Math.max(0, Math.min(1, rawScore)) * 100)
  const scoreLabel = `匹配 ${scorePercent}%`
  const relativePercent = Math.round((rawScore / Math.max(maxScore || rawScore || 1, 0.001)) * 100)
  const clickable = !!onOpen
  const sourceCardTestId = `source-card-${String(source.chunkId || rank || 'unknown')}`
  const routes = (debugEntry?.routes || []).filter(Boolean).slice(0, 3)
  const finalScore = Number(debugEntry?.finalScore ?? Number.NaN)
  const finalScorePercent = Number.isFinite(finalScore)
    ? Math.round(Math.max(0, Math.min(1, finalScore)) * 100)
    : null
  const selectedReason = cleanReason(debugEntry?.selectedReason || debugEntry?.reason)
  const penaltyReason = cleanReason(debugEntry?.penaltyReason)

  return (
    <Card
      data-testid={sourceCardTestId}
      className={cn(
        'overflow-hidden border-l-4 bg-white/90 shadow-sm dark:border-slate-700 dark:bg-slate-900/70',
        'border-l-primary/60',
        clickable && 'group cursor-pointer transition-colors hover:bg-muted/60 dark:hover:bg-slate-800/80',
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
      <CardContent className="space-y-2 px-4 py-3">
        <div className="flex items-center justify-between gap-2">
          <span className="flex items-center gap-1.5 truncate text-sm font-medium">
            {rank && (
              <span className="flex h-5 min-w-5 items-center justify-center rounded-full bg-slate-900 px-1 text-[10px] font-bold text-white dark:bg-white dark:text-slate-900">
                {rank}
              </span>
            )}
            <BookOpen className="h-3.5 w-3.5 shrink-0" />
            {source.materialTitle}
          </span>
          <div className="flex shrink-0 items-center gap-1.5">
            {source.pageNo > 0 && (
              <Badge variant="outline" className="px-1.5 py-0 text-[10px]">
                第 {source.pageNo} 页
              </Badge>
            )}
            <Badge variant="secondary" className="px-1.5 py-0 text-[10px]">
              {scoreLabel}
            </Badge>
            {clickable && (
              <span
                className="rounded p-1 text-muted-foreground transition group-hover:bg-muted group-hover:text-foreground"
                aria-hidden="true"
              >
                <ExternalLink className="h-3.5 w-3.5" />
              </span>
            )}
          </div>
        </div>

        <div className="h-1.5 overflow-hidden rounded-full bg-slate-100 dark:bg-slate-800">
          <div
            className="h-full rounded-full bg-gradient-to-r from-cyan-500 via-emerald-500 to-amber-400"
            style={{ width: `${Math.max(6, Math.min(100, relativePercent))}%` }}
          />
        </div>

        {!!routes.length && (
          <div className="flex flex-wrap gap-1">
            {routes.map((route) => (
              <Badge key={`${source.chunkId}-${route}`} variant="outline" className="px-1.5 py-0 text-[10px]">
                {route}
              </Badge>
            ))}
            {finalScorePercent !== null && (
              <Badge variant="outline" className="px-1.5 py-0 text-[10px]">
                最终分 {finalScorePercent}%
              </Badge>
            )}
          </div>
        )}

        {source.excerpt && (
          <p className="text-xs leading-relaxed text-muted-foreground">
            {truncate(source.excerpt, 120)}
          </p>
        )}

        {(selectedReason || penaltyReason) && (
          <div className="space-y-1 text-[11px] leading-relaxed text-muted-foreground">
            {selectedReason && <p>入选原因：{selectedReason}</p>}
            {penaltyReason && <p>降权说明：{penaltyReason}</p>}
          </div>
        )}
      </CardContent>
    </Card>
  )
}

function cleanReason(value?: string | null) {
  return String(value || '').replace(/\s+/g, ' ').trim() || null
}
