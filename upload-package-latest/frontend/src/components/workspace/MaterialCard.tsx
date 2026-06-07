/**
 * MaterialCard - 资料卡片组件
 *
 * 功能说明：
 * - 以卡片形式展示单份学习资料的基本信息
 * - 显示资料类型图标、标题、解析状态徽章
 * - 解析中的资料会展示进度条和解析阶段信息
 * - 底部提供操作按钮行：查看、继续阅读、原文件、重新解析、编辑、删除
 *
 * 交互说明：
 * - 点击卡片整体触发 onSelect
 * - 操作按钮使用 e.stopPropagation() 防止冒泡到卡片的点击事件
 * - 重新解析按钮在解析中状态时禁用并显示旋转动画
 */
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { formatDate, formatBytes, cn } from '@/lib/utils'
import { SOURCE_TYPE_LABELS, PARSE_STATUS_LABELS, PARSE_STATUS_COLORS } from '@/constants'
import { Eye, Pencil, Trash2, FileText, Globe, BookOpen, ExternalLink, BookMarked, RefreshCw } from 'lucide-react'
import type { Material } from '@/types'

interface MaterialCardProps {
  material: Material         // 资料数据对象
  selected?: boolean         // 是否处于选中状态（高亮边框）
  onSelect?: (m: Material) => void     // 点击卡片回调
  onEdit?: (m: Material) => void       // 编辑资料回调
  onDelete?: (m: Material) => void     // 删除资料回调
  onContinueReading?: (m: Material) => void  // 继续阅读回调
  onOpenFile?: (m: Material) => void   // 打开原文件回调
  onReparse?: (m: Material) => void    // 重新解析回调
  reparsing?: boolean                  // 是否正在重新解析中
}

/**
 * 根据资料来源类型返回对应的图标
 * @param sourceType - 来源类型字符串（PDF/DOCX/WEB 等）
 */
function typeIcon(sourceType: string) {
  const t = (sourceType || '').toUpperCase()
  if (t === 'WEB') return <Globe className="h-4 w-4" />        // 网页来源
  if (t === 'MD' || t === 'HTML') return <FileText className="h-4 w-4" />  // Markdown/HTML
  return <BookOpen className="h-4 w-4" />  // 默认：文档类型
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
  // 解析状态相关计算
  const statusColor = PARSE_STATUS_COLORS[material.parseStatus] || 'secondary'
  const statusLabel = PARSE_STATUS_LABELS[material.parseStatus] || material.parseStatus
  const typeLabel = SOURCE_TYPE_LABELS[material.sourceType] || material.sourceType
  // 是否正在解析中（包括 PARSING、PROCESSING、PENDING 三种状态）
  const isProcessing = material.parseStatus === 'PARSING' || material.parseStatus === 'PROCESSING' || material.parseStatus === 'PENDING'
  // 解析进度百分比，限制在 0~100 范围
  const parsePercent = Math.max(0, Math.min(100, Math.round(material.parseProgressPercent ?? (isProcessing ? 0 : 100))))

  return (
    <Card
      className={cn(
        'cursor-pointer transition-all hover:shadow-md',
        selected && 'ring-2 ring-primary',  // 选中时显示蓝色边框
      )}
      onClick={() => onSelect?.(material)}
    >
      <CardHeader className="p-3 pb-2 md:p-6 md:pb-2">
        <div className="flex items-start justify-between gap-2">
          <div className="flex min-w-0 items-center gap-2">
            {typeIcon(material.sourceType)}
            {/* 标题：优先显示自定义标题，否则显示原始文件名 */}
            <CardTitle className="truncate text-sm font-medium">{material.title || material.originalName}</CardTitle>
          </div>
          {/* 解析状态徽章 */}
          <Badge variant={statusColor as any} className="shrink-0 text-[10px]">
            {statusLabel}
          </Badge>
        </div>
      </CardHeader>
      <CardContent className="p-3 pt-0 md:p-6 md:pt-0">
        {/* 资料元信息：类型、片段数、文件大小、创建时间 */}
        <div className="flex flex-col gap-1 text-xs text-muted-foreground sm:flex-row sm:items-center sm:justify-between">
          <div className="flex min-w-0 flex-wrap items-center gap-x-3 gap-y-1">
            <span>{typeLabel}</span>
            <span>{material.chunkCount} 片段</span>
            {material.fileSize > 0 && <span>{formatBytes(material.fileSize)}</span>}
          </div>
          <span className="truncate sm:shrink-0">{formatDate(material.createdAt)}</span>
        </div>
        {/* 解析进度条（仅在解析中且进度 < 100% 时显示） */}
        {isProcessing && parsePercent < 100 && (
          <div className="mt-3 rounded-lg border border-slate-200 bg-slate-50 px-2.5 py-2 dark:border-slate-800 dark:bg-slate-900/40">
            <div className="mb-1.5 flex items-center justify-between gap-2 text-[11px]">
              <span className="truncate font-medium text-slate-700 dark:text-slate-200">
                {material.parseStage || '后台解析中'}
              </span>
              <span className="shrink-0 tabular-nums text-slate-500">{parsePercent}%</span>
            </div>
            {/* 渐变进度条 */}
            <div className="h-1.5 overflow-hidden rounded-full bg-slate-200 dark:bg-slate-800">
              <div
                className="h-full rounded-full bg-gradient-to-r from-[#2563eb] via-[#0f766e] to-[#65a30d] transition-all duration-500"
                style={{ width: `${parsePercent}%` }}
              />
            </div>
            {/* 解析阶段说明文字 */}
            {material.parseMessage && (
              <p className="mt-1.5 line-clamp-1 text-[11px] text-slate-500 dark:text-slate-400">{material.parseMessage}</p>
            )}
          </div>
        )}
        {/* 操作按钮行 */}
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
              // 解析中或正在重新解析时禁用按钮
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
