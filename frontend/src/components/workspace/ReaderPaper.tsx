/**
 * ReaderPaper -- 阅读器内容展示区组件
 *
 * 【用途】
 * 在阅读器页面（ReaderPage）的中间区域展示资料的内容。
 * 是阅读器最核心的展示组件，负责将资料片段渲染为可视化的阅读内容。
 *
 * 【统一连续阅读】
 *
 * 1. 有页面预览的资料：
 *    - PDF、DOC、DOCX 等资料优先使用 PDF.js 直接渲染预览 PDF，避免依赖后端 page-N.png 资产。
 *    - 前端只渲染当前页附近的小窗口，防止大 PDF 一次性挂载几百页导致页面卡死。
 *    - PDF.js 会叠加透明文字层，可复制 PDF 能直接在原页上划词提问。
 *    - 扫描件或 Office 预览没有原生文字层时，叠加后端 MinerU/legacy 文本层用于原文划词。
 *
 * 2. 无页面预览的资料：
 *    - TXT、MD、HTML、WEB 等资料按后端解析片段分页式渲染当前窗口。
 *    - 当前滚动停留片段会实时回传，确保提问携带正确片段上下文。
 *    - 文本中嵌入的图片标记 [[material-image:xxx]] 会被替换为实际图片。
 *
 * 【跳转逻辑】
 * - 来源点击、目录点击和页码输入都通过同一套 DOM ref 定位。
 * - 有页面预览时跳真实页码；无页面预览时按每 5 个片段组成的阅读页跳转，避免直接输入片段编号造成定位不稳。
 *
 * 【图片加载机制】
 * - MaterialImage 组件通过 fetch + Authorization 请求图片数据
 * - 使用 URL.createObjectURL 创建临时 URL 供 <img> 使用
 * - 组件卸载时通过 URL.revokeObjectURL 清理内存
 *
 */
import { useEffect, useMemo, useRef, useState } from 'react'
import * as pdfjsLib from 'pdfjs-dist'
import pdfWorkerUrl from 'pdfjs-dist/build/pdf.worker.mjs?url'
import type { PDFDocumentProxy } from 'pdfjs-dist/types/src/display/api'
import type { TextLayer } from 'pdfjs-dist/types/src/display/text_layer'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { ScrollArea } from '@/components/ui/scroll-area'
import {
  ExternalLink,
  Image as ImageIcon,
  Loader2,
  Minus,
  Plus,
} from 'lucide-react'
import { SESSION_KEY } from '@/constants'
import { apiBaseUrl } from '@/lib/api-base'
import { cn } from '@/lib/utils'
import { useMaterialPageTextLayer } from '@/api/materials'
import type { MaterialChunk, Material, MaterialPage, MaterialPageTextBlock } from '@/types'

/** 匹配文本中的图片标记 [[material-image:文件名]]（正则全局匹配） */
const IMAGE_MARKER_RE = /\[\[material-image:([^\]]+)\]\]/g

/** PDF 页数超过该阈值才启用窗口化；小文件全量渲染，保证连续滚动体验。 */
const FULL_RENDER_PAGE_LIMIT = 20

/** 文本片段超过该阈值才启用窗口化；少量片段直接全量渲染，避免跳转和滑动被窗口限制。 */
const FULL_RENDER_CHUNK_LIMIT = 80

/** 大 PDF 每次只挂载当前页前后 2 页，兼顾翻页顺滑和大文件性能。 */
const PAGE_RENDER_WINDOW = 2

/** 大文本资料每次只挂载当前片段前后 8 段，避免超长资料一次性渲染全部 DOM。 */
const CHUNK_RENDER_WINDOW = 8

/** 文本资料没有真实 PDF 页码时，每个阅读页对应的片段数，避免用户直接输入片段序号导致跳转不稳定。 */
const TEXT_PAGE_CHUNK_SIZE = 5

/** 阅读器统一使用 A4 纸张比例作为视觉页面，避免不同 PDF MediaBox 把页面撑成异常比例。 */
const A4_PAGE_WIDTH = 794
const A4_PAGE_ASPECT_RATIO = 210 / 297
const A4_PAGE_HEIGHT_RATIO = 297 / 210

/** API 基础地址（从环境变量读取，去除尾部斜杠） */
const API_BASE = apiBaseUrl()

/** PDF.js Worker 必须显式指定，否则 Vite 构建后会找不到 worker 文件。 */
pdfjsLib.GlobalWorkerOptions.workerSrc = pdfWorkerUrl

/** 页面图片 ObjectURL 缓存上限；手机端翻大 PDF 时反复回到相邻页，不必重复下载同一张预览图。 */
const MATERIAL_IMAGE_CACHE_LIMIT = 40

/** 后端页面图片请求缓存，key 为 materialId + fileName。 */
const materialImageUrlCache = new Map<string, string>()

/** 正在请求中的页面图片 Promise，避免同一页被多个组件重复 fetch。 */
const materialImageInflight = new Map<string, Promise<string>>()

function rememberMaterialImageUrl(cacheKey: string, objectUrl: string) {
  const old = materialImageUrlCache.get(cacheKey)
  if (old && old !== objectUrl) URL.revokeObjectURL(old)
  materialImageUrlCache.delete(cacheKey)
  materialImageUrlCache.set(cacheKey, objectUrl)

  while (materialImageUrlCache.size > MATERIAL_IMAGE_CACHE_LIMIT) {
    const firstKey = materialImageUrlCache.keys().next().value
    if (!firstKey) break
    const firstUrl = materialImageUrlCache.get(firstKey)
    if (firstUrl) URL.revokeObjectURL(firstUrl)
    materialImageUrlCache.delete(firstKey)
  }
}

function getCachedMaterialImageUrl(cacheKey: string) {
  const cached = materialImageUrlCache.get(cacheKey)
  if (!cached) return null
  materialImageUrlCache.delete(cacheKey)
  materialImageUrlCache.set(cacheKey, cached)
  return cached
}

/** 连续阅读实时上下文：右侧问答只依赖用户当前停留的页/片段，不再依赖旧的阅读模式。 */
export type ReaderReadingContext = {
  pageNo: number | null
  chunkIds: Array<string | number>
  chunkIndex: number
}

/** 从本地存储获取 JWT 认证令牌（用于请求需要认证的图片/文件） */
function getAuthToken(): string {
  try {
    const raw = localStorage.getItem(SESSION_KEY)
    return raw ? (JSON.parse(raw).token || '') : ''
  } catch {
    return ''
  }
}

/** 构建资料图片的请求 URL（格式：/api/materials/{id}/images/{fileName}） */
function imageUrl(materialId: string, fileName: string) {
  return `${API_BASE}/materials/${materialId}/images/${encodeURIComponent(fileName)}`
}

/** 构建预览 PDF 的请求 URL；PDF.js 会带 Authorization 请求该地址并按页渲染。 */
function previewFileUrl(materialId: string) {
  return `${API_BASE}/materials/${materialId}/preview-file`
}

/** 计算窗口化渲染范围，返回 [start, end) 索引区间。 */
function windowRange(centerIndex: number, total: number, radius: number) {
  if (total <= 0) return { start: 0, end: 0 }
  const safeCenter = Math.max(0, Math.min(total - 1, centerIndex))
  return {
    start: Math.max(0, safeCenter - radius),
    end: Math.min(total, safeCenter + radius + 1),
  }
}

/** 判断资料类型是否适合用预览 PDF 阅读器展示。 */
function supportsPdfPreview(material: Material | null) {
  const type = String(material?.sourceType || '').toUpperCase()
  return ['PDF', 'DOC', 'DOCX', 'PPT', 'PPTX'].includes(type)
}

function prefersLightweightPagePreview() {
  if (typeof window === 'undefined') return false
  const ua = navigator.userAgent || ''
  const isCoarsePointer = window.matchMedia?.('(pointer: coarse)').matches
  const isNarrowScreen = window.matchMedia?.('(max-width: 767px)').matches
  const isMobileUa = /Mobile|Android|iPhone|iPad|iPod|MicroMessenger/i.test(ua)
  return Boolean(isCoarsePointer || isNarrowScreen || isMobileUa)
}

/** 根据真实资料类型展示阅读器模式，避免 Word/PPT 预览被误标为 PDF 阅读器。 */
function readerModeLabel(material: Material | null, usesPageCanvas: boolean, usesPdfPreview: boolean) {
  if (!usesPageCanvas) return '文本阅读'
  const type = String(material?.sourceType || '').toUpperCase()
  if (type === 'PDF') return usesPdfPreview ? 'PDF 阅读器' : 'PDF 页面预览'
  if (type === 'DOC' || type === 'DOCX') return 'Word 预览'
  if (type === 'PPT' || type === 'PPTX') return '演示文稿预览'
  return '页面预览'
}

function fallbackChunkRangeForPage(page: MaterialPage, pages: MaterialPage[], chunkCount: number) {
  if (!page || pages.length === 0 || chunkCount <= 0) return null
  const pageIndex = Math.max(0, pages.findIndex((candidate) => candidate.pageNo === page.pageNo))
  const start = Math.floor((pageIndex * chunkCount) / pages.length)
  const end = Math.max(start + 1, Math.floor(((pageIndex + 1) * chunkCount) / pages.length))
  return { start: Math.min(chunkCount - 1, start), end: Math.min(chunkCount, end) }
}

function chunkIndexesForPage(page: MaterialPage, pages: MaterialPage[], chunks: MaterialChunk[]) {
  const ids = new Set(page.chunkIds.map(String))
  const mappedIndexes = chunks
    .map((candidate, index) => (ids.has(String(candidate.id)) ? index : -1))
    .filter((index) => index >= 0)
  if (mappedIndexes.length > 0) return mappedIndexes

  // 兼容旧数据：页面没有 chunkIds 时，按页码与片段总量的比例估算本页片段范围。
  const fallbackRange = fallbackChunkRangeForPage(page, pages, chunks.length)
  if (!fallbackRange) return []
  return Array.from(
    { length: fallbackRange.end - fallbackRange.start },
    (_, index) => fallbackRange.start + index,
  )
}

function pageNoForChunkIndex(chunkIndex: number, chunks: MaterialChunk[], pages: MaterialPage[]) {
  const chunk = chunks[chunkIndex]
  if (!chunk) return null
  const directPageNo = Number(chunk.pageNo)
  if (Number.isFinite(directPageNo) && directPageNo > 0) return directPageNo
  const mappedPage = pages.find((page) => page.chunkIds.map(String).includes(String(chunk.id)))
  if (mappedPage?.pageNo) return mappedPage.pageNo
  if (!pages.length || !chunks.length) return null
  const pageIndex = Math.min(pages.length - 1, Math.floor((chunkIndex * pages.length) / chunks.length))
  return pages[pageIndex]?.pageNo ?? null
}

/**
 * MaterialImage -- 资料内嵌图片组件
 *
 * 由于资料图片存储在需要认证的后端接口上，不能直接用 <img src="..."> 引用。
 * 因此通过 fetch + Authorization 请求图片数据，转为 Blob ObjectURL 后渲染。
 *
 * 三种渲染状态：
 * - 加载中：显示 Loader2 旋转图标
 * - 加载成功：显示图片
 * - 加载失败：显示文件名和错误提示
 *
 * 内存管理：组件卸载时通过 URL.revokeObjectURL 释放 ObjectURL，防止内存泄漏
 */
function MaterialImage({
  materialId,
  fileName,
  pageNo,
  className,
  onError,
}: {
  materialId: string
  fileName: string
  pageNo?: number
  className?: string
  onError?: () => void
}) {
  const [src, setSrc] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    const cacheKey = `${materialId}:${fileName}`
    setSrc(null)
    setError(null)

    const cachedUrl = getCachedMaterialImageUrl(cacheKey)
    if (cachedUrl) {
      setSrc(cachedUrl)
      return () => {
        cancelled = true
      }
    }

    // 带认证头的图片请求；请求结果会进入模块级 LRU 缓存，提升手机端大 PDF 翻页体验。
    const token = getAuthToken()
    const loadPromise = materialImageInflight.get(cacheKey) || fetch(imageUrl(materialId, fileName), {
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
        const objectUrl = URL.createObjectURL(blob)
        rememberMaterialImageUrl(cacheKey, objectUrl)
        materialImageInflight.delete(cacheKey)
        return objectUrl
      })
      .catch((error) => {
        materialImageInflight.delete(cacheKey)
        throw error
      })

    materialImageInflight.set(cacheKey, loadPromise)
    loadPromise
      .then((objectUrl) => {
        if (!cancelled) setSrc(objectUrl)
      })
      .catch(() => {
        if (cancelled) return
        setError('图片加载失败')
        onError?.()
      })

    // ObjectURL 交给模块级缓存统一释放，组件卸载时只取消本次状态更新。
    return () => {
      cancelled = true
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
      <span className="flex items-center justify-center gap-2 px-4 py-10 text-center text-xs text-muted-foreground">
        <Loader2 className="h-3.5 w-3.5 shrink-0 animate-spin" />
        <span className="flex flex-col gap-0.5">
          <span>{pageNo ? `正在加载第 ${pageNo} 页预览...` : '正在加载页面预览...'}</span>
          <span>图片型 PDF 可先预览页面，文字识别会继续在后台补齐</span>
        </span>
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
 * ChunkContent -- 片段内容渲染组件
 *
 * 将片段文本中的 [[material-image:xxx]] 标记替换为实际的图片组件。
 * 渲染流程：
 * 1. 用正则 IMAGE_MARKER_RE 逐段匹配文本
 * 2. 图片标记之前的文本部分直接输出为 <span>
 * 3. 图片标记替换为 <MaterialImage> 组件（加载实际图片）
 * 4. 最后剩余的文本直接输出
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

/** 将连续文本整理为更接近文档正文的段落，避免 TXT/Word 降级预览变成密集代码块。 */
function readableParagraphs(text: string) {
  const normalized = text.replace(/\r\n?/g, '\n').trim()
  if (!normalized) return ['']
  const paragraphs = normalized
    .split(/\n{2,}/)
    .map((paragraph) => paragraph.trim())
    .filter(Boolean)
  return paragraphs.length ? paragraphs : [normalized]
}

/**
 * ReadableChunkContent -- 无页面预览资料的正文排版组件
 *
 * 后端仍按 chunk 维护定位和问答上下文，前端在每个 chunk 内按自然段渲染。
 * 这样 TXT、Markdown、Word 降级文本不会只有一整坨等宽感文本，同时保留图片标记替换能力。
 */
function ReadableChunkContent({ text, materialId }: { text: string; materialId: string }) {
  return (
    <div className="space-y-4 text-[15px] leading-8 text-slate-800 selection:bg-cyan-100 dark:text-slate-100 dark:selection:bg-cyan-900/70">
      {readableParagraphs(text).map((paragraph, index) => (
        <p key={`${index}-${paragraph.slice(0, 16)}`} className="m-0 whitespace-pre-wrap break-words">
          <ChunkContent text={paragraph} materialId={materialId} />
        </p>
      ))}
    </div>
  )
}

function textBlockStyle(block: MaterialPageTextBlock, page: MaterialPage, renderedPageHeight: number): React.CSSProperties {
  const pageWidth = block.pageWidth || page.width || 1000
  const pageHeight = block.pageHeight || page.height || 1000
  const hasBox = [block.bboxX, block.bboxY, block.bboxWidth, block.bboxHeight]
    .every((value) => typeof value === 'number' && Number.isFinite(value))
  if (!hasBox) {
    return {
      left: '6%',
      top: '6%',
      width: '88%',
      height: '88%',
      fontSize: `${Math.max(12, renderedPageHeight * 0.018)}px`,
      lineHeight: 1.7,
    }
  }
  const renderedBlockHeight = ((block.bboxHeight || 14) / pageHeight) * renderedPageHeight
  return {
    left: `${((block.bboxX || 0) / pageWidth) * 100}%`,
    top: `${((block.bboxY || 0) / pageHeight) * 100}%`,
    width: `${Math.max(0.5, ((block.bboxWidth || 1) / pageWidth) * 100)}%`,
    height: `${Math.max(0.5, ((block.bboxHeight || 1) / pageHeight) * 100)}%`,
    fontSize: `${Math.max(7, renderedBlockHeight * 0.82)}px`,
    lineHeight: 1.15,
  }
}

function BackendTextLayer({
  materialId,
  page,
  renderedPageHeight,
  enabled,
}: {
  materialId: string
  page: MaterialPage
  renderedPageHeight: number
  enabled: boolean
}) {
  const { data: blocks = [] } = useMaterialPageTextLayer(enabled ? materialId : null, enabled ? page.pageNo : null)
  if (!enabled || blocks.length === 0) return null
  return (
    <div className="backend-text-layer" data-page-no={page.pageNo}>
      {blocks.map((block) => (
        <span
          key={block.id}
          className="backend-text-layer__block"
          data-block-id={block.id}
          data-page-no={block.pageNo}
          data-chunk-id={block.chunkId ?? undefined}
          style={textBlockStyle(block, page, renderedPageHeight)}
        >
          {block.text}
        </span>
      ))}
    </div>
  )
}

/**
 * PdfPageCanvas -- PDF.js 单页渲染组件
 *
 * 只接收已经加载好的 PDFDocumentProxy 和页码，组件挂载时渲染对应页面到 canvas。
 * 父组件通过窗口化控制挂载数量，因此这里不做分页缓存，避免长 PDF 占用过多显存。
 */
function PdfPageCanvas({
  document,
  materialId,
  page,
  pageNo,
  zoom,
  onError,
}: {
  document: PDFDocumentProxy
  materialId: string
  page: MaterialPage
  pageNo: number
  zoom: number
  onError?: () => void
}) {
  const paperRef = useRef<HTMLDivElement | null>(null)
  const canvasRef = useRef<HTMLCanvasElement | null>(null)
  const contentFrameRef = useRef<HTMLDivElement | null>(null)
  const textLayerRef = useRef<HTMLDivElement | null>(null)
  const [loading, setLoading] = useState(true)
  const [hasTextLayer, setHasTextLayer] = useState(false)
  const [renderedPageHeight, setRenderedPageHeight] = useState(1)
  const paperWidth = Math.round(A4_PAGE_WIDTH * zoom)
  const paperHeight = Math.round(paperWidth * A4_PAGE_HEIGHT_RATIO)
  const [paperSize, setPaperSize] = useState({ width: paperWidth, height: paperHeight })

  useEffect(() => {
    const paper = paperRef.current
    if (!paper) return
    const updatePaperSize = () => {
      const width = Math.max(1, Math.round(paper.clientWidth || paperWidth))
      const height = Math.round(width * A4_PAGE_HEIGHT_RATIO)
      setPaperSize((current) => (
        current.width === width && current.height === height ? current : { width, height }
      ))
    }

    updatePaperSize()
    const observer = new ResizeObserver(updatePaperSize)
    observer.observe(paper)
    return () => observer.disconnect()
  }, [paperHeight, paperWidth])

  useEffect(() => {
    let cancelled = false
    let renderTask: { cancel: () => void; promise: Promise<unknown> } | null = null
    let textLayer: TextLayer | null = null
    setLoading(true)
    setHasTextLayer(false)
    if (textLayerRef.current) textLayerRef.current.innerHTML = ''

    document.getPage(pageNo)
      .then((page) => {
        if (cancelled || !canvasRef.current) return null
        const baseViewport = page.getViewport({ scale: 1 })
        const fitScale = Math.min(paperSize.width / baseViewport.width, paperSize.height / baseViewport.height)
        const viewport = page.getViewport({ scale: fitScale })
        const canvas = canvasRef.current
        const contentFrame = contentFrameRef.current
        const textLayerContainer = textLayerRef.current
        const context = canvas.getContext('2d')
        if (!context) return null
        const viewportWidth = viewport.width
        const viewportHeight = viewport.height
        const outputScale = Math.max(1, window.devicePixelRatio || 1)
        const contentLeft = Math.max(0, (paperSize.width - viewportWidth) / 2)
        const contentTop = Math.max(0, (paperSize.height - viewportHeight) / 2)
        if (!cancelled) setRenderedPageHeight(viewportHeight)
        canvas.width = Math.ceil(viewportWidth * outputScale)
        canvas.height = Math.ceil(viewportHeight * outputScale)
        canvas.style.width = `${viewportWidth}px`
        canvas.style.height = `${viewportHeight}px`
        if (contentFrame) {
          contentFrame.style.left = `${contentLeft}px`
          contentFrame.style.top = `${contentTop}px`
          contentFrame.style.width = `${viewportWidth}px`
          contentFrame.style.height = `${viewportHeight}px`
        }
        if (textLayerContainer) {
          textLayerContainer.style.width = `${viewportWidth}px`
          textLayerContainer.style.height = `${viewportHeight}px`
        }
        // canvas 使用设备像素比渲染，CSS 尺寸仍与 viewport 保持一致，避免视觉层和透明文字层产生亚像素漂移。
        renderTask = page.render({
          canvasContext: context,
          viewport,
          transform: outputScale !== 1 ? [outputScale, 0, 0, outputScale, 0, 0] : undefined,
        })
        return Promise.all([
          renderTask.promise,
          page.getTextContent().then((textContent) => {
            if (cancelled || !textLayerContainer || textContent.items.length === 0) return
            // PDF 原文划词依赖 PDF 自带文本层；扫描件没有文本层时，下方 OCR 文本仍可划选提问。
            textLayer = new pdfjsLib.TextLayer({
              textContentSource: textContent,
              container: textLayerContainer,
              viewport,
            }) as TextLayer
            return textLayer.render().then(() => {
              if (!cancelled) setHasTextLayer(true)
            })
          }),
        ])
      })
      .then(() => {
        if (!cancelled) setLoading(false)
      })
      .catch((error) => {
        // 主动取消渲染会进入 catch，这不是用户可见错误。
        if (cancelled || error?.name === 'RenderingCancelledException') return
        setLoading(false)
        onError?.()
      })

    return () => {
      cancelled = true
      renderTask?.cancel()
      textLayer?.cancel()
      if (textLayerRef.current) textLayerRef.current.innerHTML = ''
    }
  }, [document, onError, pageNo, paperSize.height, paperSize.width])

  return (
    <div
      ref={paperRef}
      className="relative mx-auto max-w-full overflow-hidden bg-white shadow-lg ring-1 ring-black/10"
      style={{
        width: `min(${paperWidth}px, calc(100vw - 1rem))`,
        aspectRatio: '210 / 297',
      }}
    >
      {loading && (
        <div className="absolute inset-0 z-10 flex min-h-64 items-center justify-center gap-2 bg-white/80 text-xs text-slate-500">
          <Loader2 className="h-3.5 w-3.5 animate-spin" />
          正在渲染第 {pageNo} 页...
        </div>
      )}
      <div ref={contentFrameRef} className="absolute transform-gpu">
        <canvas ref={canvasRef} className="block max-w-none" />
        <div
          ref={textLayerRef}
          className={cn('textLayer pdf-text-layer', !hasTextLayer && 'pointer-events-none')}
          data-page-no={pageNo}
        />
        <BackendTextLayer materialId={materialId} page={page} renderedPageHeight={renderedPageHeight} enabled={!hasTextLayer} />
      </div>
    </div>
  )
}

/**
 * PagePreviewCanvas -- 页面预览画布组件
 *
 * 后端图片预览兜底组件。
 *
 * PDF.js 无法加载预览 PDF 时，才回退到后端 page-N.png，避免旧资料或非 PDF 预览完全不可读。
 * 支持缩放，底部显示当前页包含的片段标签（可点击跳转）。
 */
function PagePreviewCanvas({
  materialId,
  page,
  zoom,
  pdfDocument,
  pageChunkIndexes,
  chunks,
  activeChunkId,
  onSelectChunk,
  onError,
}: {
  materialId: string
  page: MaterialPage
  zoom: number
  pdfDocument?: PDFDocumentProxy | null
  pageChunkIndexes: number[]
  chunks: MaterialChunk[]
  activeChunkId: string
  onSelectChunk?: (chunkIndex: number) => void
  onError?: () => void
}) {
  const pageRatio = page.width && page.height ? page.width / page.height : A4_PAGE_ASPECT_RATIO
  const isWiderThanA4 = pageRatio > A4_PAGE_ASPECT_RATIO
  const fallbackPaperWidth = Math.round(A4_PAGE_WIDTH * zoom)
  const fallbackPaperHeight = Math.round(fallbackPaperWidth * A4_PAGE_HEIGHT_RATIO)
  const fallbackRenderedPageHeight = isWiderThanA4
    ? fallbackPaperWidth / pageRatio
    : fallbackPaperHeight

  return (
    <div className="min-w-full px-2 py-4 md:px-4 md:py-6">
      {pdfDocument ? (
        <PdfPageCanvas document={pdfDocument} materialId={materialId} page={page} pageNo={page.pageNo} zoom={zoom} onError={onError} />
      ) : (
        <div
          className="relative mx-auto flex max-w-full items-center justify-center overflow-hidden bg-white shadow-lg ring-1 ring-black/10 md:max-w-none"
          style={{
            width: `min(${fallbackPaperWidth}px, calc(100vw - 1rem))`,
            aspectRatio: '210 / 297',
          }}
        >
          <div
            className="relative"
            style={{
              aspectRatio: `${pageRatio}`,
              width: isWiderThanA4 ? '100%' : 'auto',
              height: isWiderThanA4 ? 'auto' : '100%',
              maxWidth: '100%',
              maxHeight: '100%',
            }}
          >
            <MaterialImage
              materialId={materialId}
              fileName={page.imageName}
              pageNo={page.pageNo}
              className="h-full w-full object-contain"
              onError={onError}
            />
            <BackendTextLayer materialId={materialId} page={page} renderedPageHeight={fallbackRenderedPageHeight} enabled />
          </div>
        </div>
      )}
      {pageChunkIndexes.length > 0 && (
        <div className="mx-auto mt-3 flex max-w-3xl flex-wrap justify-center gap-1.5">
          {pageChunkIndexes.map((index) => (
            <button
              key={index}
              className={cn(
                'rounded border px-2 py-1 text-[11px] transition-colors',
                chunks[index]?.id === activeChunkId
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
  )
}

/**
 * ReaderPaper 组件属性
 *
 * @property chunk - 当前展示的片段
 * @property chunks - 所有片段列表（用于翻片和片段标签导航）
 * @property pages - 页面列表（用于页面预览模式和翻页）
 * @property material - 当前资料对象
 * @property progress - 阅读进度（0~1，用于进度条显示）
 * @property onSelectChunk - 切换到指定片段回调
 * @property onOpenFile - 打开原文件回调
 */
interface ReaderPaperProps {
  chunk: MaterialChunk           // 当前展示的片段
  chunks: MaterialChunk[]        // 所有片段列表
  pages: MaterialPage[]          // 页面列表（用于页面预览模式）
  material: Material | null      // 当前资料
  targetPageNo?: number | null   // URL 或外部来源指定的目标页码
  progress: number               // 阅读进度（0~1）
  onSelectChunk?: (chunkIndex: number) => void  // 切换片段回调
  onReadingContextChange?: (context: ReaderReadingContext) => void
  onOpenFile?: () => void        // 打开原文件回调
}

export function ReaderPaper({
  chunk,
  chunks,
  pages,
  material,
  targetPageNo,
  progress,
  onSelectChunk,
  onReadingContextChange,
  onOpenFile,
}: ReaderPaperProps) {
  // === 状态管理 ===
  /** 缩放比例（0.7~1.8），仅页面预览模式下使用 */
  const [zoom, setZoom] = useState(1)
  /**
   * 用户手动翻页时的页码覆盖
   * 当用户点击"上一页/下一页"时，用此值覆盖从 chunk 派生的页码
   * 切换片段时重置为 null（恢复自动匹配）
   */
  const [currentPageOverride, setCurrentPageOverride] = useState<number | null>(null)
  /** 页面图片是否加载失败（失败后降级为文本模式） */
  const [pagePreviewFailed, setPagePreviewFailed] = useState(false)
  /** PDF.js 加载出的预览 PDF 文档；加载失败时自动回退到后端页面图片。 */
  const [pdfDocument, setPdfDocument] = useState<PDFDocumentProxy | null>(null)
  /** PDF.js 是否加载失败；失败后不再反复请求同一份预览 PDF。 */
  const [pdfPreviewFailed, setPdfPreviewFailed] = useState(false)
  /** 移动端和微信内置浏览器优先使用后端页面图片，避免 PDF.js 在手机上加载整份大 PDF。 */
  const [lightweightPreview, setLightweightPreview] = useState(() => prefersLightweightPagePreview())
  /** 底部页码/片段跳转输入值，跟随当前滚动上下文同步。 */
  const [jumpValue, setJumpValue] = useState('1')
  /** 用户正在编辑跳转输入时暂停滚动同步，避免输入内容被 IntersectionObserver 覆盖。 */
  const [jumpFocused, setJumpFocused] = useState(false)
  /** 滚动区域的 DOM 引用（用于切换内容时滚回顶部） */
  const scrollViewportRef = useRef<HTMLDivElement | null>(null)
  /** 连续阅读中每一页的 DOM 引用，用于来源点击、页码输入和片段按钮统一定位。 */
  const pageRefs = useRef<Record<number, HTMLElement | null>>({})
  /** 连续阅读中每个片段的 DOM 引用，无页面预览资料按片段定位。 */
  const chunkRefs = useRef<Record<string, HTMLElement | null>>({})
  const lastContextKeyRef = useRef('')
  /** 只在用户主动跳转/切换片段时定位，避免手动滚动时被 effect 反复拉回当前片段。 */
  const pendingScrollRef = useRef<{ type: 'page'; pageNo: number } | { type: 'chunk'; chunkId: string } | null>(null)

  // === 派生状态（从 props 和 state 计算） ===
  /** 阅读进度百分比（用于进度条显示） */
  const progressPercent = Math.round(progress * 100)
  /** 是否使用页面图片预览模式（有页面图片 + 预览就绪 + 未失败） */
  const hasPagePreview = !!material?.id && pages.length > 0 && material.previewStatus === 'READY' && !pagePreviewFailed
  /** 是否优先使用 PDF.js 阅读器；它直接读取预览 PDF，不依赖后端 page-N.png 图片资产。 */
  const usesPdfPreview = hasPagePreview && !lightweightPreview && supportsPdfPreview(material) && !!pdfDocument && !pdfPreviewFailed
  /** 是否按页面连续阅读：PDF/Word 等已转换出页面图片时，统一渲染所有页面。 */
  const usesPageCanvas = hasPagePreview
  /** 是否显示页面控制（缩放、页码标签等，仅页面预览模式时显示） */
  const showPageControls = usesPageCanvas
  const chunkPageNo = useMemo(() => {
    const directPageNo = Number(chunk.pageNo)
    if (Number.isFinite(directPageNo) && directPageNo > 0) return directPageNo
    const chunkId = String(chunk.id)
    // 旧数据可能没有 pageNo，只能反查页面的 chunkIds 来定位当前片段所在页。
    const mappedPageNo = pages.find((page) => page.chunkIds.map(String).includes(chunkId))?.pageNo
    if (mappedPageNo) return mappedPageNo
    const chunkIndex = chunks.findIndex((candidate) => String(candidate.id) === chunkId)
    if (chunkIndex >= 0 && pages.length > 0 && chunks.length > 0) {
      const pageIndex = Math.min(pages.length - 1, Math.floor((chunkIndex * pages.length) / chunks.length))
      return pages[pageIndex]?.pageNo || null
    }
    return null
  }, [chunk.id, chunk.pageNo, chunks, pages])
  const validTargetPageNo = Number.isFinite(Number(targetPageNo)) && Number(targetPageNo) > 0 ? Number(targetPageNo) : null
  const targetScrollKey = `${material?.id || ''}:${validTargetPageNo || ''}`
  // 当前页码：优先用户手动覆盖，其次使用外部恢复页码，最后才回落到当前片段页码。
  // 从其他模块切回阅读器时，组件会先用默认片段挂载；如果片段页码优先，会把已保存的第 54 页覆盖成第 1 页。
  const currentPageNo = currentPageOverride || validTargetPageNo || chunkPageNo || pages[0]?.pageNo || 1
  const firstContentPage = pages.find((page) => page.chunkIds.length > 0) || pages[0]
  const currentPage = pages.find((page) => page.pageNo === currentPageNo)
    || firstContentPage
  const currentPageIndex = currentPage ? pages.findIndex((page) => page.pageNo === currentPage.pageNo) : -1
  const pageRenderRange = pages.length <= FULL_RENDER_PAGE_LIMIT
    ? { start: 0, end: pages.length }
    : windowRange(Math.max(0, currentPageIndex), pages.length, PAGE_RENDER_WINDOW)
  const visiblePages = pages.slice(pageRenderRange.start, pageRenderRange.end)
  const isPageWindowed = pages.length > FULL_RENDER_PAGE_LIMIT

  const pageChunkIndexesByNo = useMemo(() => {
    const map = new Map<number, number[]>()
    pages.forEach((page) => {
      map.set(page.pageNo, chunkIndexesForPage(page, pages, chunks))
    })
    return map
  }, [chunks, pages])
  const pageChunkIndexes = currentPage ? pageChunkIndexesByNo.get(currentPage.pageNo) || [] : []
  const activeChunkIndex = Math.max(0, chunks.findIndex((candidate) => String(candidate.id) === String(chunk.id)))
  const activeChunkPageNo = chunkPageNo || currentPage?.pageNo || null
  const chunkRenderRange = chunks.length <= FULL_RENDER_CHUNK_LIMIT
    ? { start: 0, end: chunks.length }
    : windowRange(activeChunkIndex, chunks.length, CHUNK_RENDER_WINDOW)
  const visibleChunks = chunks.slice(chunkRenderRange.start, chunkRenderRange.end)
  const isChunkWindowed = chunks.length > FULL_RENDER_CHUNK_LIMIT
  const pendingScrollKeyRef = useRef<string | null>(null)
  const textPageCount = Math.max(1, Math.ceil(chunks.length / TEXT_PAGE_CHUNK_SIZE))
  const jumpCount = usesPageCanvas ? pages.length : textPageCount
  const currentJumpPosition = usesPageCanvas
    ? (activeChunkPageNo || currentPage?.pageNo || pages[0]?.pageNo || 1)
    : Math.floor(activeChunkIndex / TEXT_PAGE_CHUNK_SIZE) + 1

  // === 副作用 ===

  /** 切换片段时重置页码覆盖和预览失败状态 */
  useEffect(() => {
    if (validTargetPageNo) return
    setCurrentPageOverride(null)
  }, [material?.id, chunk.id, validTargetPageNo])

  /** 资料切换时重置连续阅读定位状态，避免新资料沿用上一份资料的页码或预览失败标记。 */
  useEffect(() => {
    setCurrentPageOverride(null)
    setPagePreviewFailed(false)
    setPdfPreviewFailed(false)
    setLightweightPreview(prefersLightweightPagePreview())
    pageRefs.current = {}
    chunkRefs.current = {}
  }, [material?.id, material?.sourceType])

  /** 加载预览 PDF：PDF.js 只负责显示，扫描件文字抽取仍由后端 OCR 产出 chunks。 */
  useEffect(() => {
    if (!material?.id || lightweightPreview || !hasPagePreview || !supportsPdfPreview(material) || pdfPreviewFailed) {
      setPdfDocument(null)
      return
    }

    let cancelled = false
    const token = getAuthToken()
    const loadingTask = pdfjsLib.getDocument({
      url: previewFileUrl(material.id),
      httpHeaders: token ? { Authorization: `Bearer ${token}` } : undefined,
      withCredentials: false,
    })

    loadingTask.promise
      .then((document) => {
        if (cancelled) {
          document.destroy()
          return
        }
        setPdfDocument(document)
      })
      .catch(() => {
        if (!cancelled) {
          setPdfDocument(null)
          setPdfPreviewFailed(true)
        }
      })

    return () => {
      cancelled = true
      loadingTask.destroy()
      setPdfDocument((document) => {
        document?.destroy()
        return null
      })
    }
  }, [hasPagePreview, lightweightPreview, material, pdfPreviewFailed])

  useEffect(() => {
    if (jumpFocused) return
    setJumpValue(String(currentJumpPosition || 1))
  }, [currentJumpPosition])

  useEffect(() => {
    lastContextKeyRef.current = ''
  }, [material?.id])

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

  useEffect(() => {
    if (!onReadingContextChange || !chunks.length) return

    const emitContext = (context: ReaderReadingContext) => {
      const key = `${context.pageNo || ''}:${context.chunkIndex}:${context.chunkIds.map(String).join(',')}`
      if (lastContextKeyRef.current === key) return
      lastContextKeyRef.current = key
      onReadingContextChange(context)
    }

    if (usesPageCanvas && currentPage) {
      emitContext({
        pageNo: currentPage.pageNo,
        chunkIds: currentPage.chunkIds.length ? currentPage.chunkIds : pageChunkIndexes.map((index) => chunks[index]?.id).filter(Boolean),
        chunkIndex: pageChunkIndexes[0] ?? activeChunkIndex,
      })
    } else {
      emitContext({
        pageNo: chunkPageNo,
        chunkIds: chunk?.id ? [chunk.id] : [],
        chunkIndex: activeChunkIndex,
      })
    }
  }, [activeChunkIndex, chunk.id, chunkPageNo, chunks, currentPage, onReadingContextChange, pageChunkIndexes, usesPageCanvas])

  useEffect(() => {
    if (!scrollViewportRef.current || !chunks.length) return

    const root = scrollViewportRef.current
    const observedElements = (usesPageCanvas
      ? visiblePages.map((page) => pageRefs.current[page.pageNo]).filter(Boolean)
      : visibleChunks.map((candidate) => chunkRefs.current[String(candidate.id)]).filter(Boolean)) as Element[]
    if (!observedElements.length) return

    const observer = new IntersectionObserver(
      (entries) => {
        const visible = entries
          .filter((entry) => entry.isIntersecting)
          .sort((a, b) => b.intersectionRatio - a.intersectionRatio)[0]
        if (!visible) return

        if (usesPageCanvas) {
          const pageNo = Number((visible.target as HTMLElement).dataset.pageNo)
          const page = pages.find((candidate) => candidate.pageNo === pageNo)
          if (!page) return
          const indexes = pageChunkIndexesByNo.get(page.pageNo) || []
          const firstIndex = indexes[0] ?? 0
          if (pendingScrollKeyRef.current) return
          setCurrentPageOverride(page.pageNo)
          if (!jumpFocused) {
            setJumpValue(String(page.pageNo))
          }
          onReadingContextChange?.({
            pageNo: page.pageNo,
            chunkIds: page.chunkIds.length ? page.chunkIds : indexes.map((index) => chunks[index]?.id).filter(Boolean),
            chunkIndex: firstIndex,
          })
          return
        }

        const chunkId = (visible.target as HTMLElement).dataset.chunkId
        const nextIndex = chunks.findIndex((candidate) => String(candidate.id) === String(chunkId))
        if (nextIndex < 0) return
        const nextPageNo = pageNoForChunkIndex(nextIndex, chunks, pages)
        if (!jumpFocused) {
          setJumpValue(String(Math.floor(nextIndex / TEXT_PAGE_CHUNK_SIZE) + 1))
        }
        onReadingContextChange?.({
          pageNo: nextPageNo,
          chunkIds: [chunks[nextIndex].id],
          chunkIndex: nextIndex,
        })
      },
      {
        root,
        threshold: [0.15, 0.35, 0.6],
        rootMargin: '-12% 0px -55% 0px',
      },
    )

    observedElements.forEach((element) => observer.observe(element))
    return () => observer.disconnect()
  }, [chunks, jumpFocused, onReadingContextChange, pageChunkIndexesByNo, pages, usesPageCanvas, visibleChunks, visiblePages])

  useEffect(() => {
    const viewport = scrollViewportRef.current
    if (!viewport) return
    const pendingTarget = pendingScrollRef.current
    if (!pendingTarget) return
    const raf = window.requestAnimationFrame(() => {
      if (pendingTarget.type === 'page') {
        const target = pageRefs.current[pendingTarget.pageNo]
        if (target) {
          target.scrollIntoView({ block: 'start', behavior: 'auto' })
          pendingScrollRef.current = null
          window.setTimeout(() => {
            pendingScrollKeyRef.current = null
          }, 600)
        }
        return
      }
      const target = chunkRefs.current[pendingTarget.chunkId]
      if (target) {
        target.scrollIntoView({ block: 'start', behavior: 'auto' })
        pendingScrollRef.current = null
        window.setTimeout(() => {
          pendingScrollKeyRef.current = null
        }, 600)
      }
    })
    return () => window.cancelAnimationFrame(raf)
  }, [activeChunkIndex, currentPage?.pageNo, material?.id, visiblePages, visibleChunks])

  /** 外部 URL 带 pageNo 进入阅读器时，只在初次目标变化时定位一次。 */
  useEffect(() => {
    if (!validTargetPageNo || !usesPageCanvas) return
    if (pendingScrollKeyRef.current === targetScrollKey) return
    pendingScrollKeyRef.current = targetScrollKey
    pendingScrollRef.current = { type: 'page', pageNo: validTargetPageNo }
    setCurrentPageOverride(validTargetPageNo)
  }, [targetScrollKey, validTargetPageNo, usesPageCanvas])

  // 预加载相邻页面图片（提升翻页体验）
  useEffect(() => {
    if (!material?.id || !hasPagePreview || usesPdfPreview || !currentPage) return
    const token = getAuthToken()
    const headers: HeadersInit | undefined = token ? { Authorization: `Bearer ${token}` } : undefined
    const neighbors = [pages[currentPageIndex - 1], pages[currentPageIndex + 1]].filter(Boolean)
    neighbors.forEach((page) => {
      // 预加载失败不影响当前阅读，只用于提升下一页/上一页的响应速度。
      fetch(imageUrl(material.id, page.imageName), { headers }).catch(() => undefined)
    })
  }, [currentPage, currentPageIndex, hasPagePreview, material?.id, pages, usesPdfPreview])

  const scrollToPage = (pageNo: number) => {
    const targetPage = pages.find((page) => page.pageNo === pageNo)
    if (!targetPage) return
    pendingScrollRef.current = { type: 'page', pageNo: targetPage.pageNo }
    setCurrentPageOverride(targetPage.pageNo)
    const mountedTarget = pageRefs.current[targetPage.pageNo]
    if (mountedTarget) {
      mountedTarget.scrollIntoView({ block: 'start', behavior: 'smooth' })
      pendingScrollRef.current = null
    }
    const indexes = pageChunkIndexesByNo.get(targetPage.pageNo) || []
    if (indexes[0] !== undefined) onSelectChunk?.(indexes[0])
    onReadingContextChange?.({
      pageNo: targetPage.pageNo,
      chunkIds: targetPage.chunkIds.length ? targetPage.chunkIds : indexes.map((index) => chunks[index]?.id).filter(Boolean),
      chunkIndex: indexes[0] ?? activeChunkIndex,
    })
  }

  const scrollToChunkIndex = (chunkIndex: number) => {
    const safeIndex = Math.max(0, Math.min(chunks.length - 1, chunkIndex))
    const targetChunk = chunks[safeIndex]
    if (!targetChunk) return
    pendingScrollRef.current = { type: 'chunk', chunkId: String(targetChunk.id) }
    const mountedTarget = chunkRefs.current[String(targetChunk.id)]
    if (mountedTarget) {
      mountedTarget.scrollIntoView({ block: 'start', behavior: 'smooth' })
      pendingScrollRef.current = null
    }
    onSelectChunk?.(safeIndex)
    onReadingContextChange?.({
      pageNo: pageNoForChunkIndex(safeIndex, chunks, pages),
      chunkIds: [targetChunk.id],
      chunkIndex: safeIndex,
    })
  }

  const handleJumpSubmit = () => {
    const value = Number(jumpValue)
    if (!Number.isInteger(value) || value <= 0) return
    if (usesPageCanvas) {
      const pageNo = Math.max(1, Math.min(pages[pages.length - 1]?.pageNo || 1, value))
      scrollToPage(pageNo)
      setJumpValue(String(pageNo))
      return
    }
    const targetTextPage = Math.max(1, Math.min(textPageCount, value))
    const nextIndex = Math.max(0, Math.min(chunks.length - 1, (targetTextPage - 1) * TEXT_PAGE_CHUNK_SIZE))
    scrollToChunkIndex(nextIndex)
    setJumpValue(String(targetTextPage))
  }

  const renderContinuousPages = () => (
    <ScrollArea className="flex-1 bg-[#eceff1]" viewportRef={scrollViewportRef}>
      <div className="min-h-full pb-4">
        {material?.previewError && (
          <p className="mx-auto mt-3 max-w-3xl rounded-md border border-dashed bg-background px-3 py-2 text-xs text-muted-foreground">
            {material.previewError}
          </p>
        )}
        {isPageWindowed && (
          <div className="mx-auto max-w-3xl px-3 pt-3 text-center text-[11px] text-muted-foreground">
            已启用按需渲染：当前只加载第 {visiblePages[0]?.pageNo || 1} - {visiblePages[visiblePages.length - 1]?.pageNo || 1} 页，避免长文档卡顿。
          </div>
        )}
        {visiblePages.map((page) => {
          const indexes = pageChunkIndexesByNo.get(page.pageNo) || []
          return (
            <section
              key={page.pageNo}
              ref={(node) => {
                pageRefs.current[page.pageNo] = node
              }}
              data-page-no={page.pageNo}
              className="scroll-mt-3"
            >
              <div className="mx-auto max-w-3xl px-3 pt-4">
                <Badge variant={page.pageNo === currentPage?.pageNo ? 'default' : 'outline'} className="text-[10px]">
                  第 {page.pageNo} 页
                </Badge>
              </div>
              {material?.id && (
                <PagePreviewCanvas
                  materialId={material.id}
                  page={page}
                  zoom={zoom}
                  pdfDocument={usesPdfPreview ? pdfDocument : null}
                  pageChunkIndexes={indexes}
                  chunks={chunks}
                  activeChunkId={chunk.id}
                  onSelectChunk={(index) => {
                    onSelectChunk?.(index)
                    const pageNo = pageNoForChunkIndex(index, chunks, pages) || page.pageNo
                    pendingScrollRef.current = { type: 'page', pageNo }
                    setCurrentPageOverride(pageNo)
                  }}
                  onError={() => {
                    if (usesPdfPreview) {
                      setPdfPreviewFailed(true)
                      return
                    }
                    setPagePreviewFailed(true)
                  }}
                />
              )}
            </section>
          )
        })}
      </div>
    </ScrollArea>
  )

  const renderContinuousChunks = () => (
    <ScrollArea className="flex-1 bg-[#eef1f2] dark:bg-[#111318]" viewportRef={scrollViewportRef}>
      <div className="mx-auto min-h-full max-w-[880px] px-3 py-5 md:px-8 md:py-8">
        <article className="min-h-[calc(100vh-12rem)] bg-[#fffefd] px-5 py-6 shadow-sm ring-1 ring-slate-200 md:px-10 md:py-9 dark:bg-[#15171d] dark:ring-slate-800">
          <header className="border-b border-slate-200 pb-5 dark:border-slate-800">
            <div className="flex flex-wrap items-center gap-2 text-xs text-slate-500 dark:text-slate-400">
              <span>{String(material?.sourceType || '文档').toUpperCase()}</span>
              <span>·</span>
              <span>{chunks.length} 个片段</span>
              {isChunkWindowed && (
                <>
                  <span>·</span>
                  <span>当前 {chunkRenderRange.start + 1}-{chunkRenderRange.end}</span>
                </>
              )}
            </div>
            <h2 className="mt-2 text-xl font-semibold leading-8 text-slate-950 md:text-2xl dark:text-slate-50">
              {material?.title || material?.originalName || '未命名资料'}
            </h2>
            {(material?.previewError || pagePreviewFailed) && (
              <p className="mt-3 rounded-md border border-dashed bg-slate-50 px-3 py-2 text-xs text-slate-600 dark:border-slate-700 dark:bg-slate-900/40 dark:text-slate-300">
                {pagePreviewFailed ? '页面图片暂时无法加载，已切换为解析文本连续阅读。' : material?.previewError}
              </p>
            )}
          </header>

          <div className="divide-y divide-slate-100 dark:divide-slate-800">
            {visibleChunks.map((item, offset) => {
              const index = chunkRenderRange.start + offset
              const active = String(item.id) === String(chunk.id)
              return (
                <section
                  key={item.id}
                  ref={(node) => {
                    chunkRefs.current[String(item.id)] = node
                  }}
                  data-chunk-id={String(item.id)}
                  className={cn(
                    'relative scroll-mt-6 py-6 pl-5 transition-colors md:py-7 md:pl-7',
                    'before:absolute before:bottom-6 before:left-0 before:top-6 before:w-[3px] before:rounded-full',
                    active
                      ? 'bg-cyan-50/40 before:bg-cyan-500 dark:bg-cyan-950/20'
                      : 'before:bg-slate-200 hover:bg-slate-50/70 dark:before:bg-slate-700 dark:hover:bg-slate-900/30',
                  )}
                >
                  <div className="mb-4 flex flex-wrap items-center gap-2 text-xs text-slate-500 dark:text-slate-400">
                    <Badge variant={active ? 'default' : 'outline'} className="h-5 text-[10px]">
                      片段 {item.chunkIndex || index + 1}
                    </Badge>
                    {item.pageNo && <Badge variant="secondary" className="h-5 text-[10px]">P{item.pageNo}</Badge>}
                    {item.hierarchyPath && (
                      <span className="min-w-0 truncate font-medium text-slate-500 dark:text-slate-400">
                        {item.hierarchyPath}
                      </span>
                    )}
                  </div>
                  {item.sectionTitle && (
                    <h3 className="mb-3 text-base font-semibold leading-7 text-slate-950 dark:text-slate-50">
                      {item.sectionTitle}
                    </h3>
                  )}
                  {material?.id ? (
                    <ReadableChunkContent text={item.chunkText} materialId={material.id} />
                  ) : (
                    <div className="whitespace-pre-wrap break-words text-[15px] leading-8 text-slate-800 dark:text-slate-100">
                      {item.chunkText}
                    </div>
                  )}
                </section>
              )
            })}
          </div>
        </article>
      </div>
    </ScrollArea>
  )

  // === 主渲染 ===
  return (
    <div data-testid="reader-paper" className="reader-paper flex-1 flex flex-col overflow-hidden">
      {/* ---- 顶部工具栏：单一连续阅读状态、缩放控制、原文件按钮、进度条 ---- */}
      <div className="flex flex-wrap items-center justify-between gap-2 border-b px-2 py-1.5 md:gap-3 md:px-6 md:py-3">
        <div className="flex min-w-0 flex-1 flex-wrap items-center gap-1.5 md:min-w-[10rem] md:gap-2">
          <h3 className="min-w-0 flex-[1_1_100%] truncate text-xs font-medium md:flex-1 md:text-sm">
            {material?.title || material?.originalName || '未选择资料'}
          </h3>
          {showPageControls && currentPage && (
            <Badge data-testid="reader-current-page" variant="outline" className="text-[10px] md:text-xs">P{currentPage.pageNo}/{pages.length}</Badge>
          )}
          {!showPageControls && chunkPageNo && (
            <Badge data-testid="reader-current-page" variant="outline" className="text-[10px] md:text-xs">P{chunkPageNo}</Badge>
          )}
          <Badge variant="secondary" className="text-[10px] md:text-xs">
            {readerModeLabel(material, usesPageCanvas, usesPdfPreview)}
          </Badge>
          {material?.previewStatus === 'DEGRADED' && (
            <Badge variant="secondary" className="text-xs">文本预览</Badge>
          )}
        </div>

        <div className="flex shrink-0 items-center gap-1 md:gap-2">
          {/* 缩放控制（仅页面预览模式） */}
          {showPageControls && (
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

      {/* 内容展示区：有页面预览则按页连续渲染，否则按解析片段连续渲染。 */}
      {usesPageCanvas ? renderContinuousPages() : renderContinuousChunks()}

      {/* 底部跳转栏：只保留页码输入，避免片段式导航和窗口化滚动互相抢状态。 */}
      <div className="flex items-center justify-center gap-2 border-t px-3 pb-[max(env(safe-area-inset-bottom),0.5rem)] pt-2 md:px-6 md:py-3">
        <form
          className="flex min-w-0 flex-wrap items-center justify-center gap-2 text-xs text-muted-foreground"
          onSubmit={(event) => {
            event.preventDefault()
            handleJumpSubmit()
          }}
        >
          <span>跳转到第</span>
          <input
            data-testid="reader-jump-input"
            value={jumpValue}
            onChange={(event) => setJumpValue(event.target.value.replace(/[^\d]/g, ''))}
            onFocus={() => setJumpFocused(true)}
            onBlur={() => {
              setJumpFocused(false)
            }}
            className="h-8 w-16 rounded-md border bg-background px-2 text-center text-base text-foreground outline-none focus:border-cyan-400 md:text-sm"
            inputMode="numeric"
            aria-label={usesPageCanvas ? '跳转页码' : '跳转阅读页'}
          />
          <span>{`页 / 共 ${jumpCount} 页`}</span>
          <Button variant="outline" size="sm" className="h-8 px-3 text-xs" type="submit">
            跳转
          </Button>
        </form>
      </div>
    </div>
  )
}
