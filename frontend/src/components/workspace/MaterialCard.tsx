import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { formatDate, formatBytes, truncate, cn } from '@/lib/utils'
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

export function MaterialCard({ material, selected, onSelect, onEdit, onDelete, onContinueReading, onOpenFile, onReparse, reparsing }: MaterialCardProps) {
  const statusColor = PARSE_STATUS_COLORS[material.parseStatus] || 'secondary'
  const statusLabel = PARSE_STATUS_LABELS[material.parseStatus] || material.parseStatus
  const typeLabel = SOURCE_TYPE_LABELS[material.sourceType] || material.sourceType
  const isProcessing = material.parseStatus === 'PARSING' || material.parseStatus === 'PROCESSING'

  return (
    <Card
      className={cn(
        'cursor-pointer transition-all hover:shadow-md',
        selected && 'ring-2 ring-primary'
      )}
      onClick={() => onSelect?.(material)}
    >
      <CardHeader className="pb-2">
        <div className="flex items-start justify-between gap-2">
          <div className="flex items-center gap-2 min-w-0">
            {typeIcon(material.sourceType)}
            <CardTitle className="text-sm font-medium truncate">
              {material.title || material.originalName}
            </CardTitle>
          </div>
          <Badge variant={statusColor as any} className="shrink-0 text-[10px]">
            {statusLabel}
          </Badge>
        </div>
      </CardHeader>
      <CardContent className="pt-0">
        <div className="flex items-center justify-between text-xs text-muted-foreground">
          <div className="flex items-center gap-3">
            <span>{typeLabel}</span>
            <span>{material.chunkCount} 片段</span>
            {material.fileSize > 0 && <span>{formatBytes(material.fileSize)}</span>}
          </div>
          <span>{formatDate(material.createdAt)}</span>
        </div>
        <div className="flex items-center gap-1 mt-3">
          <Button variant="ghost" size="sm" className="h-7 px-2 text-xs"
            onClick={(e) => { e.stopPropagation(); onSelect?.(material) }}>
            <Eye className="h-3.5 w-3.5 mr-1" /> 查看
          </Button>
          {onContinueReading && (
            <Button variant="ghost" size="sm" className="h-7 px-2 text-xs"
              onClick={(e) => { e.stopPropagation(); onContinueReading(material) }}>
              <BookMarked className="h-3.5 w-3.5 mr-1" /> 继续阅读
            </Button>
          )}
          {onOpenFile && (
            <Button variant="ghost" size="sm" className="h-7 px-2 text-xs"
              onClick={(e) => { e.stopPropagation(); onOpenFile(material) }}>
              <ExternalLink className="h-3.5 w-3.5 mr-1" /> 原文件
            </Button>
          )}
          {onReparse && (
            <Button variant="ghost" size="sm" className="h-7 px-2 text-xs"
              disabled={isProcessing || reparsing}
              onClick={(e) => { e.stopPropagation(); onReparse(material) }}>
              <RefreshCw className={cn('h-3.5 w-3.5 mr-1', (isProcessing || reparsing) && 'animate-spin')} /> 重新解析
            </Button>
          )}
          <Button variant="ghost" size="sm" className="h-7 px-2 text-xs"
            onClick={(e) => { e.stopPropagation(); onEdit?.(material) }}>
            <Pencil className="h-3.5 w-3.5 mr-1" /> 编辑
          </Button>
          <Button variant="ghost" size="sm" className="h-7 px-2 text-xs text-destructive hover:text-destructive"
            onClick={(e) => { e.stopPropagation(); onDelete?.(material) }}>
            <Trash2 className="h-3.5 w-3.5 mr-1" /> 删除
          </Button>
        </div>
      </CardContent>
    </Card>
  )
}
