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
import { useMemo } from 'react'
import { Button } from '@/components/ui/button'
import { ScrollArea } from '@/components/ui/scroll-area'
import { cn, truncate } from '@/lib/utils'
import { BookOpen, FileText, Layers3 } from 'lucide-react'
import type { Material, MaterialChunk } from '@/types'

const CHUNK_WINDOW_SIZE = 160

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
  const chunkWindow = useMemo(() => {
    if (chunks.length <= CHUNK_WINDOW_SIZE) {
      return { start: 0, end: chunks.length, items: chunks }
    }
    const half = Math.floor(CHUNK_WINDOW_SIZE / 2)
    const maxStart = Math.max(0, chunks.length - CHUNK_WINDOW_SIZE)
    const start = Math.max(0, Math.min(maxStart, selectedChunkIndex - half))
    const end = Math.min(chunks.length, start + CHUNK_WINDOW_SIZE)
    return { start, end, items: chunks.slice(start, end) }
  }, [chunks, selectedChunkIndex])
  const isWindowed = chunks.length > CHUNK_WINDOW_SIZE

  return (
    <div className={cn('flex h-full min-h-0 w-full shrink-0 flex-col border-b border-slate-200 bg-white lg:h-full lg:border-b-0 lg:border-r dark:border-slate-800 dark:bg-[#171a21]', className)}>
      {/* 资料列表区域 */}
      <div className="border-b border-slate-200 px-4 py-4 dark:border-slate-800">
        <div className="mb-2 flex items-center justify-between">
          <p className="flex items-center gap-1.5 text-xs font-semibold text-slate-600 dark:text-slate-300">
            <BookOpen className="h-3.5 w-3.5" />
            资料
          </p>
          <span className="text-[11px] text-slate-400">{materials.length} 份</span>
        </div>
        <ScrollArea className="h-24 lg:h-28">
          <div className="space-y-1 pr-1">
            {materials.map((m) => (
              <Button
                key={m.id}
                variant={m.id === selectedMaterialId ? 'secondary' : 'ghost'}  // 选中高亮
                size="sm"
                className={cn(
                  'h-8 w-full justify-start gap-2 rounded-lg px-2 text-xs font-medium',
                  m.id === selectedMaterialId
                    ? 'bg-slate-100 text-slate-950 hover:bg-slate-100 dark:bg-slate-800 dark:text-white'
                    : 'text-slate-600 hover:bg-slate-50 dark:text-slate-300 dark:hover:bg-slate-800/70',
                )}
                onClick={() => onSelectMaterial(m.id)}
                title={m.title || m.originalName}
              >
                <FileText className="h-3.5 w-3.5 shrink-0" />
                <span className="min-w-0 flex-1 truncate text-left">{truncate(m.title || m.originalName, 26)}</span>
              </Button>
            ))}
          </div>
        </ScrollArea>
      </div>

      {/* 片段列表区域 */}
      <div className="flex min-h-0 flex-1 flex-col overflow-hidden px-4 py-4">
        <div className="mb-3 flex items-center justify-between">
          <p className="flex items-center gap-1.5 text-xs font-semibold text-slate-600 dark:text-slate-300">
            <Layers3 className="h-3.5 w-3.5" />
            片段
          </p>
          <span className="rounded-full bg-slate-100 px-2 py-0.5 text-[11px] font-medium text-slate-500 dark:bg-slate-800 dark:text-slate-300">
            {chunks.length}
          </span>
        </div>
        {isWindowed && (
          <div className="mb-2 flex items-center justify-between gap-2 text-[11px] text-slate-500 dark:text-slate-300">
            <span className="truncate">
              显示 #{chunkWindow.start + 1}-#{chunkWindow.end}
            </span>
            <div className="flex shrink-0 items-center gap-1">
              <button
                type="button"
                className="rounded border px-1.5 py-1 disabled:opacity-40"
                disabled={chunkWindow.start <= 0}
                onClick={() => onSelectChunk(Math.max(0, chunkWindow.start - CHUNK_WINDOW_SIZE))}
              >
                前面
              </button>
              <button
                type="button"
                className="rounded border px-1.5 py-1 disabled:opacity-40"
                disabled={chunkWindow.end >= chunks.length}
                onClick={() => onSelectChunk(Math.min(chunks.length - 1, chunkWindow.end))}
              >
                后面
              </button>
            </div>
          </div>
        )}
        <ScrollArea className="flex-1">
          <div className="grid grid-cols-4 gap-1.5 pr-1">
            {chunkWindow.items.map((c, localIndex) => {
              const i = chunkWindow.start + localIndex
              return (
              <button
                key={c.id}
                className={cn(
                  'group flex h-9 min-w-0 flex-col items-center justify-center rounded-lg text-xs font-semibold transition-colors',
                  i === selectedChunkIndex
                    ? 'bg-cyan-50 text-cyan-700 ring-1 ring-cyan-200 dark:bg-cyan-950/40 dark:text-cyan-200 dark:ring-cyan-900'
                    : 'bg-slate-50 text-slate-600 hover:bg-slate-100 hover:text-slate-950 dark:bg-slate-900 dark:text-slate-300 dark:hover:bg-slate-800'
                )}
                onClick={() => onSelectChunk(i)}
                title={getChunkTitle(c, i)}
              >
                <span>#{formatChunkIndex(c, i)}</span>
                {c.pageNo && <span className="mt-0.5 text-[10px] font-medium opacity-60">P{c.pageNo}</span>}
              </button>
              )
            })}
          </div>
        </ScrollArea>
      </div>
    </div>
  )
}

function formatChunkIndex(chunk: MaterialChunk, index: number) {
  const value = chunk.chunkIndex > 0 ? chunk.chunkIndex : index + 1
  return String(value).padStart(2, '0')
}

function getChunkTitle(chunk: MaterialChunk, index: number) {
  const title = chunk.sectionTitle || chunk.hierarchyPath || truncate(chunk.chunkText || chunk.excerpt || '', 48)
  const page = chunk.pageNo ? ` · P${chunk.pageNo}` : ''
  return `片段 #${formatChunkIndex(chunk, index)}${page}${title ? ` · ${title}` : ''}`
}
