import { useEffect, useMemo, useRef, useState } from 'react'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { ScrollArea } from '@/components/ui/scroll-area'
import {
  ChevronLeft,
  ChevronRight,
  ExternalLink,
  Image as ImageIcon,
  Loader2,
  Minus,
  Plus,
} from 'lucide-react'
import { SESSION_KEY } from '@/constants'
import { cn } from '@/lib/utils'
import type { MaterialChunk, Material, MaterialPage } from '@/types'

const IMAGE_MARKER_RE = /\[\[material-image:([^\]]+)\]\]/g
const API_BASE = ((import.meta.env.VITE_API_BASE as string) || '/api').replace(/\/$/, '')

function getAuthToken(): string {
  try {
    const raw = localStorage.getItem(SESSION_KEY)
    return raw ? (JSON.parse(raw).token || '') : ''
  } catch {
    return ''
  }
}

function imageUrl(materialId: string, fileName: string) {
  return `${API_BASE}/materials/${materialId}/images/${encodeURIComponent(fileName)}`
}

function MaterialImage({
  materialId,
  fileName,
  className,
  onError,
}: {
  materialId: string
  fileName: string
  className?: string
  onError?: () => void
}) {
  const [src, setSrc] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let revoked = false
    let objectUrl: string | null = null
    setSrc(null)
    setError(null)

    const token = getAuthToken()
    fetch(imageUrl(materialId, fileName), {
      headers: token ? { Authorization: `Bearer ${token}` } : {},
    })
      .then(async (res) => {
        if (!res.ok) {
          const message = await readErrorMessage(res)
          throw new Error(message || `图片加载失败 (${res.status})`)
        }
        return res.blob()
      })
      .then((blob) => {
        if (revoked) return
        objectUrl = URL.createObjectURL(blob)
        setSrc(objectUrl)
      })
      .catch(() => {
        if (!revoked) {
          setError('图片加载失败')
          onError?.()
        }
      })

    return () => {
      revoked = true
      if (objectUrl) URL.revokeObjectURL(objectUrl)
    }
  }, [materialId, fileName])

  if (error) {
    return (
      <span className="flex items-center gap-2 rounded-md border border-dashed bg-muted/50 px-3 py-2 text-xs text-muted-foreground">
        <ImageIcon className="h-4 w-4" />
        {fileName} ({error})
      </span>
    )
  }

  if (!src) {
    return (
      <span className="flex items-center justify-center gap-2 text-xs text-muted-foreground py-10">
        <Loader2 className="h-3.5 w-3.5 animate-spin" />
        加载页面...
      </span>
    )
  }

  return <img src={src} alt={fileName} className={cn('max-w-full', className)} />
}

async function readErrorMessage(response: Response): Promise<string> {
  try {
    const contentType = response.headers.get('content-type') || ''
    if (contentType.includes('application/json')) {
      const body = await response.json()
      if (typeof body?.message === 'string') return body.message
    }
    return (await response.text()).trim()
  } catch {
    return ''
  }
}

function ChunkContent({ text, materialId }: { text: string; materialId: string }) {
  const parts: React.ReactNode[] = []
  let lastIndex = 0
  let match: RegExpExecArray | null

  IMAGE_MARKER_RE.lastIndex = 0
  while ((match = IMAGE_MARKER_RE.exec(text)) !== null) {
    if (match.index > lastIndex) {
      parts.push(<span key={`t-${lastIndex}`}>{text.slice(lastIndex, match.index)}</span>)
    }
    const fileName = match[1]
    parts.push(
      <span key={`img-${match.index}`} className="block my-3">
        <MaterialImage materialId={materialId} fileName={fileName} className="rounded-md border shadow-sm" />
      </span>,
    )
    lastIndex = match.index + match[0].length
  }

  if (lastIndex < text.length) {
    parts.push(<span key={`t-${lastIndex}`}>{text.slice(lastIndex)}</span>)
  }

  return <>{parts}</>
}

interface ReaderPaperProps {
  chunk: MaterialChunk
  chunks: MaterialChunk[]
  pages: MaterialPage[]
  material: Material | null
  progress: number
  canPrev?: boolean
  canNext?: boolean
  onPrev: () => void
  onNext: () => void
  onSelectChunk?: (chunkIndex: number) => void
  onOpenFile?: () => void
}

export function ReaderPaper({
  chunk,
  chunks,
  pages,
  material,
  progress,
  canPrev = true,
  canNext = true,
  onPrev,
  onNext,
  onSelectChunk,
  onOpenFile,
}: ReaderPaperProps) {
  const [zoom, setZoom] = useState(1)
  const [currentPageOverride, setCurrentPageOverride] = useState<number | null>(null)
  const [pagePreviewFailed, setPagePreviewFailed] = useState(false)
  const scrollViewportRef = useRef<HTMLDivElement | null>(null)
  const progressPercent = Math.round(progress * 100)
  const hasPagePreview = !!material?.id && pages.length > 0 && material.previewStatus === 'READY' && !pagePreviewFailed
  const currentPageNo = currentPageOverride || chunk.pageNo || pages[0]?.pageNo || 1
  const currentPage = pages.find((page) => page.pageNo === currentPageNo) || pages[0]
  const currentPageIndex = currentPage ? pages.findIndex((page) => page.pageNo === currentPage.pageNo) : -1

  const pageChunkIndexes = useMemo(() => {
    if (!currentPage) return []
    const ids = new Set(currentPage.chunkIds.map(String))
    return chunks
      .map((candidate, index) => (ids.has(String(candidate.id)) ? index : -1))
      .filter((index) => index >= 0)
  }, [chunks, currentPage])

  useEffect(() => {
    setCurrentPageOverride(null)
    setPagePreviewFailed(false)
  }, [material?.id, chunk.id])

  useEffect(() => {
    scrollViewportRef.current?.scrollTo({ top: 0, left: 0 })
  }, [chunk.id, currentPage?.pageNo, material?.id])

  useEffect(() => {
    if (!currentPageOverride || pages.some((page) => page.pageNo === currentPageOverride)) return
    setCurrentPageOverride(null)
  }, [currentPageOverride, pages])

  useEffect(() => {
    if (!pages.length && currentPageOverride) {
      setCurrentPageOverride(null)
    }
  }, [currentPageOverride, pages.length])

  useEffect(() => {
    if (!material?.id || !hasPagePreview || !currentPage) return
    const token = getAuthToken()
    const headers: HeadersInit | undefined = token ? { Authorization: `Bearer ${token}` } : undefined
    const neighbors = [pages[currentPageIndex - 1], pages[currentPageIndex + 1]].filter(Boolean)
    neighbors.forEach((page) => {
      fetch(imageUrl(material.id, page.imageName), { headers }).catch(() => undefined)
    })
  }, [currentPage, currentPageIndex, hasPagePreview, material?.id, pages])

  const handlePageStep = (direction: -1 | 1) => {
    if (!pages.length || currentPageIndex < 0) return
    const nextPage = pages[currentPageIndex + direction]
    if (!nextPage) return
    setCurrentPageOverride(nextPage.pageNo)
    const nextChunkIndex = chunks.findIndex((candidate) => Number(candidate.pageNo) === nextPage.pageNo)
    if (nextChunkIndex >= 0) onSelectChunk?.(nextChunkIndex)
  }

  return (
    <div className="flex-1 flex flex-col overflow-hidden">
      <div className="flex items-center justify-between gap-2 border-b px-3 py-2 md:gap-3 md:px-6 md:py-3">
        <div className="flex items-center gap-2 min-w-0">
          <h3 className="text-sm font-medium truncate">
            {material?.title || material?.originalName || '未选择资料'}
          </h3>
          {hasPagePreview && currentPage && (
            <Badge variant="outline" className="text-xs">P{currentPage.pageNo}/{pages.length}</Badge>
          )}
          {!hasPagePreview && chunk.pageNo && (
            <Badge variant="outline" className="text-xs">P{chunk.pageNo}</Badge>
          )}
          {material?.previewStatus === 'DEGRADED' && (
            <Badge variant="secondary" className="text-xs">文本预览</Badge>
          )}
        </div>

        <div className="flex shrink-0 items-center gap-1.5 md:gap-2">
          {hasPagePreview && (
            <div className="flex items-center rounded-md border bg-background">
              <Button variant="ghost" size="sm" className="h-7 w-7 px-0" onClick={() => setZoom((z) => Math.max(0.7, Number((z - 0.1).toFixed(1))))}>
                <Minus className="h-3.5 w-3.5" />
              </Button>
              <span className="w-12 text-center text-xs text-muted-foreground">{Math.round(zoom * 100)}%</span>
              <Button variant="ghost" size="sm" className="h-7 w-7 px-0" onClick={() => setZoom((z) => Math.min(1.8, Number((z + 0.1).toFixed(1))))}>
                <Plus className="h-3.5 w-3.5" />
              </Button>
            </div>
          )}
          {onOpenFile && (
            <Button variant="ghost" size="sm" className="h-7 px-2 text-xs" onClick={onOpenFile}>
              <ExternalLink className="h-3.5 w-3.5 mr-1" /> 原文
            </Button>
          )}
          <span className="hidden text-xs text-muted-foreground sm:inline">{progressPercent}%</span>
          <div className="hidden h-1.5 w-20 overflow-hidden rounded-full bg-muted sm:block">
            <div className="h-full bg-primary rounded-full transition-all" style={{ width: `${progressPercent}%` }} />
          </div>
        </div>
      </div>

      <ScrollArea className="flex-1 bg-[#eceff1]" viewportRef={scrollViewportRef}>
        {hasPagePreview && currentPage && material?.id ? (
          <div className="min-h-full min-w-full px-2 py-3 md:w-max md:px-4 md:py-6">
            <div
              className="mx-auto max-w-full bg-white shadow-lg ring-1 ring-black/10 md:max-w-none"
              style={{
                width: `min(${Math.round(794 * zoom)}px, calc(100vw - 1rem))`,
                aspectRatio: currentPage.width && currentPage.height
                  ? `${currentPage.width} / ${currentPage.height}`
                  : '210 / 297',
              }}
            >
              <MaterialImage
                materialId={material.id}
                fileName={currentPage.imageName}
                className="h-full w-full object-contain"
                onError={() => setPagePreviewFailed(true)}
              />
            </div>
            {pageChunkIndexes.length > 0 && (
              <div className="mx-auto mt-3 flex max-w-3xl flex-wrap justify-center gap-1.5">
                {pageChunkIndexes.map((index) => (
                  <button
                    key={index}
                    className={cn(
                      'rounded border px-2 py-1 text-[11px] transition-colors',
                      chunks[index]?.id === chunk.id
                        ? 'border-primary bg-primary/10 text-primary'
                        : 'border-border bg-background text-muted-foreground hover:text-foreground',
                    )}
                    onClick={() => onSelectChunk?.(index)}
                  >
                    片段 {chunks[index]?.chunkIndex}
                  </button>
                ))}
              </div>
            )}
          </div>
        ) : (
          <div className="mx-auto min-h-full max-w-2xl bg-background px-4 py-4 md:px-6 md:py-6">
            {(material?.previewError || pagePreviewFailed) && (
              <p className="mb-3 rounded-md border border-dashed bg-muted/40 px-3 py-2 text-xs text-muted-foreground">
                {pagePreviewFailed ? '当前页图片暂时无法加载，已切换为解析文本阅读。' : material?.previewError}
              </p>
            )}
            {chunk.sectionTitle && (
              <h4 className="text-base font-semibold mb-3 text-primary">{chunk.sectionTitle}</h4>
            )}
            {chunk.hierarchyPath && (
              <p className="mb-3 rounded-md border bg-muted/30 px-3 py-2 text-[11px] font-medium text-muted-foreground">
                {chunk.hierarchyPath}
              </p>
            )}
            <div className="text-sm leading-7 whitespace-pre-wrap text-foreground/90">
              {material?.id
                ? <ChunkContent text={chunk.chunkText} materialId={material.id} />
                : chunk.chunkText}
            </div>
          </div>
        )}
      </ScrollArea>

      <div className="flex items-center justify-between gap-2 border-t px-3 py-2 md:px-6 md:py-3">
        <Button
          variant="outline"
          size="sm"
          onClick={hasPagePreview ? () => handlePageStep(-1) : onPrev}
          disabled={hasPagePreview ? currentPageIndex <= 0 : !canPrev}
        >
          <ChevronLeft className="mr-1 h-4 w-4" /> {hasPagePreview ? '上一页' : '上一片段'}
        </Button>
        <span className="min-w-0 truncate text-center text-xs text-muted-foreground">
          {hasPagePreview && currentPage ? `第 ${currentPage.pageNo} 页 / 共 ${pages.length} 页` : `片段 #${chunk.chunkIndex}`}
        </span>
        <Button
          variant="outline"
          size="sm"
          onClick={hasPagePreview ? () => handlePageStep(1) : onNext}
          disabled={hasPagePreview ? currentPageIndex >= pages.length - 1 : !canNext}
        >
          {hasPagePreview ? '下一页' : '下一片段'} <ChevronRight className="ml-1 h-4 w-4" />
        </Button>
      </div>
    </div>
  )
}

