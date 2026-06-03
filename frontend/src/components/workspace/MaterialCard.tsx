import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { formatDate, formatBytes, cn } from '@/lib/utils'
import { SOURCE_TYPE_LABELS, PARSE_STATUS_LABELS, PARSE_STATUS_COLORS } from '@/constants'
import { Eye, Pencil, Trash2, FileText, Globe, BookOpen, ExternalLink, BookMarked, RefreshCw } from 'lucide-react'
import type { Material } from '@/types'

interface MaterialCardProps {
  material: Material
  selected?: boolean
  onSelect?: (m: Material) => void
  onEdit?: (m: Material) => void
  onDelete?: (m: Material) => void
  onContinueReading?: (m: Material) => void
  onOpenFile?: (m: Material) => void
  onReparse?: (m: Material) => void
  reparsing?: boolean
}

function typeIcon(sourceType: string) {
  const t = (sourceType || '').toUpperCase()
  if (t === 'WEB') return <Globe className="h-4 w-4" />
  if (t === 'MD' || t === 'HTML') return <FileText className="h-4 w-4" />
  return <BookOpen className="h-4 w-4" />
}

export function MaterialCard({
  material,
  selected,
  onSelect,
  onEdit,
  onDelete,
  onContinueReading,
  onOpenFile,
  onReparse,
  reparsing,
}: MaterialCardProps) {
  const statusColor = PARSE_STATUS_COLORS[material.parseStatus] || 'secondary'
  const statusLabel = PARSE_STATUS_LABELS[material.parseStatus] || material.parseStatus
  const typeLabel = SOURCE_TYPE_LABELS[material.sourceType] || material.sourceType
  const isProcessing = material.parseStatus === 'PARSING' || material.parseStatus === 'PROCESSING' || material.parseStatus === 'PENDING'
  const parsePercent = Math.max(0, Math.min(100, Math.round(material.parseProgressPercent ?? (isProcessing ? 0 : 100))))

  return (
    <Card
      className={cn(
        'cursor-pointer transition-all hover:shadow-md',
        selected && 'ring-2 ring-primary',
      )}
      onClick={() => onSelect?.(material)}
    >
      <CardHeader className="p-3 pb-2 md:p-6 md:pb-2">
        <div className="flex items-start justify-between gap-2">
          <div className="flex min-w-0 items-center gap-2">
            {typeIcon(material.sourceType)}
            <CardTitle className="truncate text-sm font-medium">{material.title || material.originalName}</CardTitle>
          </div>
          <Badge variant={statusColor as any} className="shrink-0 text-[10px]">
            {statusLabel}
          </Badge>
        </div>
      </CardHeader>
      <CardContent className="p-3 pt-0 md:p-6 md:pt-0">
        <div className="flex flex-col gap-1 text-xs text-muted-foreground sm:flex-row sm:items-center sm:justify-between">
          <div className="flex min-w-0 flex-wrap items-center gap-x-3 gap-y-1">
            <span>{typeLabel}</span>
            <span>{material.chunkCount} 片段</span>
            {material.fileSize > 0 && <span>{formatBytes(material.fileSize)}</span>}
          </div>
          <span className="truncate sm:shrink-0">{formatDate(material.createdAt)}</span>
        </div>
        {isProcessing && parsePercent < 100 && (
          <div className="mt-3 rounded-lg border border-slate-200 bg-slate-50 px-2.5 py-2 dark:border-slate-800 dark:bg-slate-900/40">
            <div className="mb-1.5 flex items-center justify-between gap-2 text-[11px]">
              <span className="truncate font-medium text-slate-700 dark:text-slate-200">
                {material.parseStage || '后台解析中'}
              </span>
              <span className="shrink-0 tabular-nums text-slate-500">{parsePercent}%</span>
            </div>
            <div className="h-1.5 overflow-hidden rounded-full bg-slate-200 dark:bg-slate-800">
              <div
                className="h-full rounded-full bg-gradient-to-r from-[#2563eb] via-[#0f766e] to-[#65a30d] transition-all duration-500"
                style={{ width: `${parsePercent}%` }}
              />
            </div>
            {material.parseMessage && (
              <p className="mt-1.5 line-clamp-1 text-[11px] text-slate-500 dark:text-slate-400">{material.parseMessage}</p>
            )}
          </div>
        )}
        <div className="mt-2 flex flex-wrap gap-1 md:mt-3 md:gap-1.5">
          <Button variant="ghost" size="sm" className="h-7 px-2 text-xs" onClick={(e) => { e.stopPropagation(); onSelect?.(material) }}>
            <Eye className="mr-1 h-3.5 w-3.5" /> 查看
          </Button>
          {onContinueReading && (
            <Button variant="ghost" size="sm" className="h-7 px-2 text-xs" onClick={(e) => { e.stopPropagation(); onContinueReading(material) }}>
              <BookMarked className="mr-1 h-3.5 w-3.5" /> 继续阅读
            </Button>
          )}
          {onOpenFile && (
            <Button variant="ghost" size="sm" className="h-7 px-2 text-xs" onClick={(e) => { e.stopPropagation(); onOpenFile(material) }}>
              <ExternalLink className="mr-1 h-3.5 w-3.5" /> 原文件
            </Button>
          )}
          {onReparse && (
            <Button
              variant="ghost"
              size="sm"
              className="h-7 px-2 text-xs"
              disabled={isProcessing || reparsing}
              onClick={(e) => { e.stopPropagation(); onReparse(material) }}
            >
              <RefreshCw className={cn('mr-1 h-3.5 w-3.5', (isProcessing || reparsing) && 'animate-spin')} /> 重新解析
            </Button>
          )}
          <Button variant="ghost" size="sm" className="h-7 px-2 text-xs" onClick={(e) => { e.stopPropagation(); onEdit?.(material) }}>
            <Pencil className="mr-1 h-3.5 w-3.5" /> 编辑
          </Button>
          <Button variant="ghost" size="sm" className="h-7 px-2 text-xs text-destructive hover:text-destructive" onClick={(e) => { e.stopPropagation(); onDelete?.(material) }}>
            <Trash2 className="mr-1 h-3.5 w-3.5" /> 删除
          </Button>
        </div>
      </CardContent>
    </Card>
  )
}
