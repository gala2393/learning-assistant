/**
 * ReaderToc - 阅读器目录导航组件
 *
 * 功能说明：
 * - 左侧边栏展示资料列表和片段列表
 * - 上半部分：资料列表，支持切换不同资料
 * - 下半部分：当前资料的片段列表，支持跳转到指定片段
 * - 当前选中的资料/片段会高亮显示
 * - 移动端纵向排列（底部），桌面端横向排列（左侧）
 */
import { Button } from '@/components/ui/button'
import { ScrollArea } from '@/components/ui/scroll-area'
import { Badge } from '@/components/ui/badge'
import { Separator } from '@/components/ui/separator'
import { cn, truncate } from '@/lib/utils'
import { BookOpen, FileText } from 'lucide-react'
import type { Material, MaterialChunk } from '@/types'

interface ReaderTocProps {
  materials: Material[]            // 所有可用资料列表
  chunks: MaterialChunk[]          // 当前资料的所有片段
  selectedMaterialId: string | null  // 当前选中的资料 ID
  selectedChunkIndex: number         // 当前选中的片段索引
  onSelectMaterial: (id: string) => void   // 切换资料回调
  onSelectChunk: (index: number) => void   // 切换片段回调
  className?: string
}

export function ReaderToc({
  materials, chunks, selectedMaterialId, selectedChunkIndex,
  onSelectMaterial, onSelectChunk,
  className,
}: ReaderTocProps) {
  return (
    <div className={cn('flex h-full min-h-0 w-full shrink-0 flex-col border-b bg-muted/20 lg:h-full lg:border-b-0 lg:border-r', className)}>
      {/* 资料列表区域 */}
      <div className="p-3">
        <p className="text-xs font-semibold text-muted-foreground mb-2 flex items-center gap-1">
          <BookOpen className="h-3.5 w-3.5" /> 资料列表
        </p>
        <ScrollArea className="h-28 lg:h-32">
          <div className="space-y-1">
            {materials.map((m) => (
              <Button
                key={m.id}
                variant={m.id === selectedMaterialId ? 'secondary' : 'ghost'}  // 选中高亮
                size="sm"
                className="w-full justify-start text-xs h-7"
                onClick={() => onSelectMaterial(m.id)}
              >
                <FileText className="h-3.5 w-3.5 mr-1.5" />
                {truncate(m.title || m.originalName, 18)}
              </Button>
            ))}
          </div>
        </ScrollArea>
      </div>

      <Separator />

      {/* 片段列表区域 */}
      <div className="flex min-h-0 flex-1 flex-col overflow-hidden p-3">
        <p className="text-xs font-semibold text-muted-foreground mb-2">
          片段列表 ({chunks.length})
        </p>
        <ScrollArea className="flex-1">
          <div className="space-y-0.5">
            {chunks.map((c, i) => (
              <button
                key={c.id}
                className={cn(
                  'w-full text-left px-2 py-1.5 rounded text-xs transition-colors',
                  i === selectedChunkIndex
                    ? 'bg-primary/10 text-primary font-medium'  // 选中片段高亮
                    : 'hover:bg-muted text-muted-foreground'
                )}
                onClick={() => onSelectChunk(i)}
              >
                <div className="flex items-center gap-2">
                  {/* 片段序号标签 */}
                  <Badge variant="outline" className="text-[10px] px-1 py-0 shrink-0">
                    #{c.chunkIndex}
                  </Badge>
                  {/* 页码标签（如果有） */}
                  {c.pageNo && (
                    <Badge variant="secondary" className="text-[10px] px-1 py-0 shrink-0">
                      P{c.pageNo}
                    </Badge>
                  )}
                  {/* 片段标题或文本预览 */}
                  <span className="truncate">
                    {c.sectionTitle || truncate(c.chunkText, 20)}
                  </span>
                </div>
              </button>
            ))}
          </div>
        </ScrollArea>
      </div>
    </div>
  )
}
