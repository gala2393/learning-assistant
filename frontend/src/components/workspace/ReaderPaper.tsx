/**
 * ReaderPaper - 阅读器内容展示区
 *
 * 功能说明：
 * - 展示资料片段的文本内容或页面预览图片
 * - 支持两种阅读模式：
 *   1. 页面预览模式：当资料有页面图片时，以类似 PDF 的方式展示页面图片
 *   2. 文本模式：当无页面图片时，直接展示解析后的文本片段
 * - 支持缩放（70%~180%），仅在页面预览模式下可用
 * - 支持上下翻页/翻片段导航
 * - 文本模式下支持内嵌图片渲染（[[material-image:xxx]] 标记会被替换为实际图片）
 * - 页面预览模式下，底部展示当前页包含的片段标签，可点击跳转
 *
 * 图片加载机制：
 * - MaterialImage 组件通过 fetch + Authorization 请求图片数据
 * - 使用 URL.createObjectURL 创建临时 URL 供 <img> 使用
 * - 组件卸载时通过 URL.revokeObjectURL 清理内存
 */
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

/** 匹配文本中的图片标记 [[material-image:文件名]] */
const IMAGE_MARKER_RE = /\[\[material-image:([^\]]+)\]\]/g

/** API 基础地址 */
const API_BASE = ((import.meta.env.VITE_API_BASE as string) || '/api').replace(/\/$/, '')

/** 从本地存储获取 JWT 认证令牌 */
function getAuthToken(): string {
  try {
    const raw = localStorage.getItem(SESSION_KEY)
    return raw ? (JSON.parse(raw).token || '') : ''
  } catch {
    return ''
  }
}

/** 构建资料图片的请求 URL */
function imageUrl(materialId: string, fileName: string) {
  return `${API_BASE}/materials/${materialId}/images/${encodeURIComponent(fileName)}`
}

/**
 * MaterialImage - 资料内嵌图片组件
 *
 * 通过 fetch 请求需要认证的图片数据，转为 Blob URL 后渲染
 * 三种状态：加载中(Loader2)、加载成功(图片)、加载失败(错误提示)
 */
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

    // 带认证头的图片请求
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

    // 清理函数：释放 ObjectURL 防止内存泄漏
    return () => {
      revoked = true
      if (objectUrl) URL.revokeObjectURL(objectUrl)
    }
  }, [materialId, fileName])

  // 加载失败状态
  if (error) {
    return (
      <span className="flex items-center gap-2 rounded-md border border-dashed bg-muted/50 px-3 py-2 text-xs text-muted-foreground">
        <ImageIcon className="h-4 w-4" />
        {fileName} ({error})
      </span>
    )
  }

  // 加载中状态
  if (!src) {
    return (
      <span className="flex items-center justify-center gap-2 text-xs text-muted-foreground py-10">
        <Loader2 className="h-3.5 w-3.5 animate-spin" />
        加载页面...
      </span>
    )
  }

  // 加载成功
  return <img src={src} alt={fileName} className={cn('max-w-full', className)} />
}

/** 解析 HTTP 响应的错误消息（尝试 JSON 或纯文本） */
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

/**
 * ChunkContent - 片段内容渲染组件
 *
 * 将片段文本中的 [[material-image:xxx]] 标记替换为实际的图片组件
 * 通过正则逐段匹配，文本部分直接输出，图片标记替换为 MaterialImage
 */
function ChunkContent({ text, materialId }: { text: string; materialId: string }) {
  const parts: React.ReactNode[] = []
  let lastIndex = 0
  let match: RegExpExecArray | null

  IMAGE_MARKER_RE.lastIndex = 0
  while ((match = IMAGE_MARKER_RE.exec(text)) !== null) {
    // 图片标记之前的文本部分
    if (match.index > lastIndex) {
      parts.push(<span key={`t-${lastIndex}`}>{text.slice(lastIndex, match.index)}</span>)
    }
    // 替换图片标记为实际图片
    const fileName = match[1]
    parts.push(
      <span key={`img-${match.index}`} className="block my-3">
        <MaterialImage materialId={materialId} fileName={fileName} className="rounded-md border shadow-sm" />
      </span>,
    )
    lastIndex = match.index + match[0].length
  }

  // 最后剩余的文本
  if (lastIndex < text.length) {
    parts.push(<span key={`t-${lastIndex}`}>{text.slice(lastIndex)}</span>)
  }

  return <>{parts}</>
}

interface ReaderPaperProps {
  chunk: MaterialChunk           // 当前展示的片段
  chunks: MaterialChunk[]        // 所有片段列表
  pages: MaterialPage[]          // 页面列表（用于页面预览模式）
  material: Material | null      // 当前资料
  progress: number               // 阅读进度（0~1）
  canPrev?: boolean              // 是否可以向前翻
  canNext?: boolean              // 是否可以向后翻
  onPrev: () => void             // 向前翻回调
  onNext: () => void             // 向后翻回调
  onSelectChunk?: (chunkIndex: number) => void  // 切换片段回调
  onOpenFile?: () => void        // 打开原文件回调
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
  const [zoom, setZoom] = useState(1)  // 缩放比例（0.7~1.8）
  // 用户手动翻页时的页码覆盖（非 null 时优先使用此值）
  const [currentPageOverride, setCurrentPageOverride] = useState<number | null>(null)
  // 页面图片是否加载失败（失败后降级为文本模式）
  const [pagePreviewFailed, setPagePreviewFailed] = useState(false)
  const scrollViewportRef = useRef<HTMLDivElement | null>(null)

  // ---- 派生状态 ----
  const progressPercent = Math.round(progress * 100)
  // 是否使用页面预览模式
  const hasPagePreview = !!material?.id && pages.length > 0 && material.previewStatus === 'READY' && !pagePreviewFailed
  // 当前页码：优先覆盖值 > 片段页码 > 第一页
  const currentPageNo = currentPageOverride || chunk.pageNo || pages[0]?.pageNo || 1
  const currentPage = pages.find((page) => page.pageNo === currentPageNo) || pages[0]
  const currentPageIndex = currentPage ? pages.findIndex((page) => page.pageNo === currentPage.pageNo) : -1

  // 当前页包含的片段索引列表
  const pageChunkIndexes = useMemo(() => {
    if (!currentPage) return []
    const ids = new Set(currentPage.chunkIds.map(String))
    return chunks
      .map((candidate, index) => (ids.has(String(candidate.id)) ? index : -1))
      .filter((index) => index >= 0)
  }, [chunks, currentPage])

  // 切换片段时重置页码覆盖和预览失败状态
  useEffect(() => {
    setCurrentPageOverride(null)
    setPagePreviewFailed(false)
  }, [material?.id, chunk.id])

  // 切换内容时自动滚回顶部
  useEffect(() => {
    scrollViewportRef.current?.scrollTo({ top: 0, left: 0 })
  }, [chunk.id, currentPage?.pageNo, material?.id])

  // 当覆盖页码不再有效时清除
  useEffect(() => {
    if (!currentPageOverride || pages.some((page) => page.pageNo === currentPageOverride)) return
    setCurrentPageOverride(null)
  }, [currentPageOverride, pages])

  useEffect(() => {
    if (!pages.length && currentPageOverride) {
      setCurrentPageOverride(null)
    }
  }, [currentPageOverride, pages.length])

  // 预加载相邻页面图片（提升翻页体验）
  useEffect(() => {
    if (!material?.id || !hasPagePreview || !currentPage) return
    const token = getAuthToken()
    const headers: HeadersInit | undefined = token ? { Authorization: `Bearer ${token}` } : undefined
    const neighbors = [pages[currentPageIndex - 1], pages[currentPageIndex + 1]].filter(Boolean)
    neighbors.forEach((page) => {
      fetch(imageUrl(material.id, page.imageName), { headers }).catch(() => undefined)
    })
  }, [currentPage, currentPageIndex, hasPagePreview, material?.id, pages])

  /** 翻页处理（上一页/下一页），同时跳转到该页的第一个片段 */
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
      {/* 顶部工具栏：标题、页码标签、缩放控制、原文件按钮、进度条 */}
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
          {/* 缩放控制（仅页面预览模式） */}
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
          {/* 阅读进度指示器 */}
          <span className="hidden text-xs text-muted-foreground sm:inline">{progressPercent}%</span>
          <div className="hidden h-1.5 w-20 overflow-hidden rounded-full bg-muted sm:block">
            <div className="h-full bg-primary rounded-full transition-all" style={{ width: `${progressPercent}%` }} />
          </div>
        </div>
      </div>

      {/* 内容展示区 */}
      <ScrollArea className="flex-1 bg-[#eceff1]" viewportRef={scrollViewportRef}>
        {hasPagePreview && currentPage && material?.id ? (
          /* ---- 页面预览模式 ---- */
          <div className="min-h-full min-w-full px-2 py-3 md:w-max md:px-4 md:py-6">
            <div
              className="mx-auto max-w-full bg-white shadow-lg ring-1 ring-black/10 md:max-w-none"
              style={{
                // A4 纸比例，宽度跟随缩放
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
                onError={() => setPagePreviewFailed(true)}  // 图片加载失败时降级为文本模式
              />
            </div>
            {/* 当前页包含的片段标签列表 */}
            {pageChunkIndexes.length > 0 && (
              <div className="mx-auto mt-3 flex max-w-3xl flex-wrap justify-center gap-1.5">
                {pageChunkIndexes.map((index) => (
                  <button
                    key={index}
                    className={cn(
                      'rounded border px-2 py-1 text-[11px] transition-colors',
                      chunks[index]?.id === chunk.id
                        ? 'border-primary bg-primary/10 text-primary'  // 当前片段高亮
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
          /* ---- 文本模式 ---- */
          <div className="mx-auto min-h-full max-w-2xl bg-background px-4 py-4 md:px-6 md:py-6">
            {/* 页面预览失败的降级提示 */}
            {(material?.previewError || pagePreviewFailed) && (
              <p className="mb-3 rounded-md border border-dashed bg-muted/40 px-3 py-2 text-xs text-muted-foreground">
                {pagePreviewFailed ? '当前页图片暂时无法加载，已切换为解析文本阅读。' : material?.previewError}
              </p>
            )}
            {/* 章节标题 */}
            {chunk.sectionTitle && (
              <h4 className="text-base font-semibold mb-3 text-primary">{chunk.sectionTitle}</h4>
            )}
            {/* 层级路径 */}
            {chunk.hierarchyPath && (
              <p className="mb-3 rounded-md border bg-muted/30 px-3 py-2 text-[11px] font-medium text-muted-foreground">
                {chunk.hierarchyPath}
              </p>
            )}
            {/* 片段正文（支持内嵌图片渲染） */}
            <div className="text-sm leading-7 whitespace-pre-wrap text-foreground/90">
              {material?.id
                ? <ChunkContent text={chunk.chunkText} materialId={material.id} />
                : chunk.chunkText}
            </div>
          </div>
        )}
      </ScrollArea>

      {/* 底部翻页导航栏 */}
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
