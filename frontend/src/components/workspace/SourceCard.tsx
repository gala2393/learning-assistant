import { Card, CardContent } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { cn, truncate } from '@/lib/utils'
import { BookOpen } from 'lucide-react'
import type { RagSource } from '@/types'

interface SourceCardProps {
  source: RagSource
  onOpen?: (source: RagSource) => void
}

export function SourceCard({ source, onOpen }: SourceCardProps) {
  const scorePercent = Math.round((source.score || 0) * 100)
  const clickable = !!onOpen

  return (
    <Card
      className={cn('border-l-4 border-l-primary/60', clickable && 'cursor-pointer transition-colors hover:bg-muted/60')}
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
            {clickable && <BookOpen className="h-3.5 w-3.5 shrink-0" />}
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
          </div>
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
