import { Button } from '@/components/ui/button'
import { ScrollArea } from '@/components/ui/scroll-area'
import { Badge } from '@/components/ui/badge'
import { Separator } from '@/components/ui/separator'
import { cn, truncate } from '@/lib/utils'
import { BookOpen, FileText } from 'lucide-react'
import type { Material, MaterialChunk } from '@/types'

interface ReaderTocProps {
  materials: Material[]
  chunks: MaterialChunk[]
  selectedMaterialId: string | null
  selectedChunkIndex: number
  onSelectMaterial: (id: string) => void
  onSelectChunk: (index: number) => void
  className?: string
}

export function ReaderToc({
  materials, chunks, selectedMaterialId, selectedChunkIndex,
  onSelectMaterial, onSelectChunk,
  className,
}: ReaderTocProps) {
  return (
    <div className={cn('flex h-full min-h-0 w-full shrink-0 flex-col border-b bg-muted/20 lg:h-full lg:border-b-0 lg:border-r', className)}>
      {/* Material list */}
      <div className="p-3">
        <p className="text-xs font-semibold text-muted-foreground mb-2 flex items-center gap-1">
          <BookOpen className="h-3.5 w-3.5" /> 资料列表
        </p>
        <ScrollArea className="h-28 lg:h-32">
          <div className="space-y-1">
            {materials.map((m) => (
              <Button
                key={m.id}
                variant={m.id === selectedMaterialId ? 'secondary' : 'ghost'}
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

      {/* Chunk list */}
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
                    ? 'bg-primary/10 text-primary font-medium'
                    : 'hover:bg-muted text-muted-foreground'
                )}
                onClick={() => onSelectChunk(i)}
              >
                <div className="flex items-center gap-2">
                  <Badge variant="outline" className="text-[10px] px-1 py-0 shrink-0">
                    #{c.chunkIndex}
                  </Badge>
                  {c.pageNo && (
                    <Badge variant="secondary" className="text-[10px] px-1 py-0 shrink-0">
                      P{c.pageNo}
                    </Badge>
                  )}
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
