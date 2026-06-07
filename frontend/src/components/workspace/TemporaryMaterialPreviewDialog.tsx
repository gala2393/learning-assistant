import { useEffect, useMemo, useRef, useState } from 'react'
import { FileText } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { ScrollArea } from '@/components/ui/scroll-area'
import { cleanTemporaryMaterialText, formatBytes } from '@/lib/utils'
import type { TemporaryMaterial } from '@/types'

const PREVIEW_WINDOW_CHARS = 80_000

interface TemporaryMaterialPreviewDialogProps {
  material: TemporaryMaterial | null
  onClose: () => void
}

export function TemporaryMaterialPreviewDialog({ material, onClose }: TemporaryMaterialPreviewDialogProps) {
  const [windowOffset, setWindowOffset] = useState(0)
  const viewportRef = useRef<HTMLDivElement | null>(null)
  const title = material?.title || material?.originalName || '临时资料'
  const sourceType = (material?.sourceType || 'FILE').toUpperCase()
  const detail = material?.fileSize ? `${sourceType} ${formatBytes(material.fileSize)}` : sourceType
  const rawText = material?.text || ''
  const textLength = rawText.length
  const safeWindowOffset = Math.max(0, Math.min(windowOffset, Math.max(0, textLength - 1)))
  const windowEnd = Math.min(textLength, safeWindowOffset + PREVIEW_WINDOW_CHARS)
  const isWindowed = textLength > PREVIEW_WINDOW_CHARS
  const windowIndex = isWindowed ? Math.floor(safeWindowOffset / PREVIEW_WINDOW_CHARS) + 1 : 1
  const windowCount = isWindowed ? Math.ceil(textLength / PREVIEW_WINDOW_CHARS) : 1
  const previewText = useMemo(
    () => cleanTemporaryMaterialText(rawText.slice(safeWindowOffset, windowEnd)),
    [rawText, safeWindowOffset, windowEnd],
  )
  const emptyText = sourceType === 'PDF'
    ? '该 PDF 暂无可抽取文本。临时资料不会保存原文件，如需完整 PDF 版式预览，请上传到资料问答。'
    : '暂无可预览文本。'

  useEffect(() => {
    setWindowOffset(0)
  }, [material?.id])

  useEffect(() => {
    viewportRef.current?.scrollTo({ top: 0, left: 0 })
  }, [material?.id, safeWindowOffset])

  return (
    <Dialog open={!!material} onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="max-h-[86dvh] max-w-4xl overflow-hidden p-0">
        <DialogHeader>
          <div className="border-b bg-slate-50 px-6 py-5 dark:border-slate-800 dark:bg-slate-950/60">
            <DialogTitle className="flex min-w-0 items-center gap-3 pr-8">
              <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-blue-50 text-blue-600 dark:bg-blue-950/40 dark:text-blue-300">
                <FileText className="h-5 w-5" />
              </span>
              <span className="min-w-0">
                <span className="block truncate text-base">{title}</span>
                <span className="mt-1 block text-xs font-normal text-slate-500 dark:text-slate-400">{detail}</span>
              </span>
            </DialogTitle>
            <DialogDescription className="mt-3">
              这里显示智能问答已解析的临时资料文本，仅用于当前对话预览。
            </DialogDescription>
          </div>
        </DialogHeader>
        {isWindowed && (
          <div className="flex flex-wrap items-center justify-between gap-2 border-b bg-background px-4 py-2 text-xs text-slate-500 dark:border-slate-800 dark:text-slate-400">
            <span>
              第 {windowIndex}/{windowCount} 段 · {safeWindowOffset.toLocaleString()} - {windowEnd.toLocaleString()} / {textLength.toLocaleString()} 字
            </span>
            <div className="flex items-center gap-2">
              <Button
                variant="outline"
                size="sm"
                className="h-7 px-2 text-xs"
                disabled={safeWindowOffset <= 0}
                onClick={() => setWindowOffset((offset) => Math.max(0, offset - PREVIEW_WINDOW_CHARS))}
              >
                上一段
              </Button>
              <Button
                variant="outline"
                size="sm"
                className="h-7 px-2 text-xs"
                disabled={windowEnd >= textLength}
                onClick={() => setWindowOffset((offset) => Math.min(Math.max(0, textLength - 1), offset + PREVIEW_WINDOW_CHARS))}
              >
                下一段
              </Button>
            </div>
          </div>
        )}
        <ScrollArea className="h-[64dvh] bg-[#f8fafc] dark:bg-[#10131a]" viewportRef={viewportRef}>
          <article className="mx-auto min-h-full max-w-3xl bg-white px-6 py-6 text-sm leading-7 text-slate-800 shadow-sm dark:bg-slate-950 dark:text-slate-100 md:px-8">
            {previewText ? (
              <pre className="whitespace-pre-wrap break-words font-sans">{previewText}</pre>
            ) : (
              <p className="text-slate-500 dark:text-slate-400">{emptyText}</p>
            )}
          </article>
        </ScrollArea>
      </DialogContent>
    </Dialog>
  )
}
