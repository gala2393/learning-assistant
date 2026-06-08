/**
 * ReaderPaper -- 阅读器内容展示区组件
 *
 * 【用途】
 * 在阅读器页面（ReaderPage）的中间区域展示资料的内容。
 * 是阅读器最核心的展示组件，负责将资料片段渲染为可视化的阅读内容。
 *
 * 【两种阅读模式】
 *
 * 1. 原文预览模式（viewMode='original'）：
 *    直接展示资料的原始文件：
 *    - PDF：通过 iframe 嵌入，支持定位到当前页
 *    - Word（DOCX）：后端转换为预览格式后通过 iframe 嵌入
 *    - 文本（TXT/MD）：通过 Blob 分段读取，每次显示 512KB，避免大文件卡死
 *
 * 2. 智能阅读模式（viewMode='smart'）：
 *    展示后端解析后的内容：
 *    - 页面预览：如果有页面图片（pages），以类似 PDF 的方式展示页面图片
 *    - 文本模式：如果没有页面图片，直接展示解析后的文本片段
 *    - 支持缩放（70%~180%，仅页面预览模式）
 *    - 文本中嵌入的图片标记 [[material-image:xxx]] 会被替换为实际图片
 *
 * 【文件类型与默认阅读模式】
 * - PDF：默认原文预览
 * - Word（DOCX）：默认原文预览
 * - TXT/MD：默认智能阅读（原文预览使用文本分段显示）
 * - 其他（PPT/HTML等）：默认智能阅读，原文预览标记为 unsupported
 *
 * 【图片加载机制】
 * - MaterialImage 组件通过 fetch + Authorization 请求图片数据
 * - 使用 URL.createObjectURL 创建临时 URL 供 <img> 使用
 * - 组件卸载时通过 URL.revokeObjectURL 清理内存
 *
 * 【翻页逻辑】
 * - 页面预览模式：按页面翻页（handlePageStep），跳转到目标页的第一个片段
 * - 文本模式：按片段翻片（由父组件的 onPrev/onNext 控制）
 * - 原文预览模式：PDF 支持按片段翻（跳转到对应页码），其他不支持翻片
 */
import { useEffect, useMemo, useRef, useState } from 'react'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { ScrollArea } from '@/components/ui/scroll-area'
import { createMaterialFileTicket } from '@/api/materials'
import {
  ChevronLeft,
  ChevronRight,
  ExternalLink,
  FileText,
  Image as ImageIcon,
  Loader2,
  Minus,
  Plus,
} from 'lucide-react'
import { SESSION_KEY } from '@/constants'
import { cn } from '@/lib/utils'
import type { MaterialChunk, Material, MaterialPage } from '@/types'

/** 匹配文本中的图片标记 [[material-image:文件名]]（正则全局匹配） */
const IMAGE_MARKER_RE = /\[\[material-image:([^\]]+)\]\]/g

/** API 基础地址（从环境变量读取，去除尾部斜杠） */
const API_BASE = ((import.meta.env.VITE_API_BASE as string) || '/api').replace(/\/$/, '')

/** 阅读视图模式：'original' 原文预览 | 'smart' 智能阅读 */
type ReaderViewMode = 'original' | 'smart'
/** 原文预览类型：PDF / 文本 / Office / 不支持 */
type OriginalPreviewKind = 'pdf' | 'text' | 'office' | 'unsupported'
/** 原文下载进度（已加载字节数 / 总字节数） */
type OriginalDownloadProgress = {
  loaded: number
  total: number | null
}

/** 文本分段预览的每段大小（512KB，避免一次渲染整篇导致页面卡死） */
const TEXT_PREVIEW_BYTES = 512 * 1024
/** 文本原文定位时最多完整解码的大小；更大的文件使用片段序号估算窗口位置。 */
const TEXT_LOCATE_MAX_BYTES = 8 * 1024 * 1024
const TEXT_LOCATE_LEAD_BYTES = 2048

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

/** 根据资料类型返回原文预览类型 */
function sourceTypeOf(material: Material | null) {
  return String(material?.sourceType || '').toUpperCase()
}

function isLegacyWordMaterial(material: Material | null) {
  const originalName = String(material?.originalName || material?.title || '').toLowerCase()
  return sourceTypeOf(material) === 'WORD' && originalName.endsWith('.doc')
}

function originalPreviewKind(material: Material | null): OriginalPreviewKind {
  const type = sourceTypeOf(material)
  if (type === 'PDF') return 'pdf'
  if (type === 'MD' || type === 'TXT') return 'text'
  if (type === 'DOCX' || type === 'WORD') return material?.previewStatus === 'READY' ? 'office' : 'unsupported'
  return 'unsupported'
}

function supportsOriginalPreview(material: Material | null) {
  return originalPreviewKind(material) !== 'unsupported'
}

function defaultReaderViewMode(material: Material | null): ReaderViewMode {
  const kind = originalPreviewKind(material)
  // 旧版 .doc 的浏览器原文预览依赖服务器转换结果，移动端默认进入更稳定的智慧阅读文本。
  if (isLegacyWordMaterial(material)) return 'smart'
  return kind === 'pdf' || kind === 'office' ? 'original' : 'smart'
}

function normalizeTicketUrl(url: string) {
  if (!url) return ''
  if (/^https?:\/\//i.test(url)) return url
  if (url.startsWith('/api/') && /^https?:\/\//i.test(API_BASE)) {
    return `${API_BASE.replace(/\/api$/, '')}${url}`
  }
  if (url.startsWith('/')) return url
  return `/${url}`
}

function previewTicketUrl(url: string) {
  return normalizeTicketUrl(url).replace('/file?', '/preview-file?')
}

function pdfPageUrl(url: string, pageNo?: number | null) {
  if (!url || !pageNo || pageNo <= 0) return url
  return `${url.split('#')[0]}#page=${pageNo}`
}

function formatBytes(bytes: number) {
  if (!Number.isFinite(bytes) || bytes <= 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB']
  let value = bytes
  let unitIndex = 0
  while (value >= 1024 && unitIndex < units.length - 1) {
    value /= 1024
    unitIndex += 1
  }
  return `${value >= 10 || unitIndex === 0 ? value.toFixed(0) : value.toFixed(1)} ${units[unitIndex]}`
}

function charsetFromContentType(contentType: string) {
  const match = (contentType || '').match(/charset=([^;]+)/i)
  return match?.[1]?.trim().replace(/^"|"$/g, '').toLowerCase() || ''
}

function startsWithBytes(bytes: Uint8Array, prefix: number[]) {
  if (bytes.length < prefix.length) return false
  return prefix.every((value, index) => bytes[index] === value)
}

function textEncodingFor(bytes: Uint8Array, contentType: string) {
  const charset = charsetFromContentType(contentType)
  if (charset) return charset
  if (startsWithBytes(bytes, [0xef, 0xbb, 0xbf])) return 'utf-8'
  if (startsWithBytes(bytes, [0xff, 0xfe])) return 'utf-16le'
  if (startsWithBytes(bytes, [0xfe, 0xff])) return 'utf-16be'
  try {
    new TextDecoder('utf-8', { fatal: true }).decode(bytes)
    return 'utf-8'
  } catch {
    return 'gb18030'
  }
}

function decodeTextWindow(buffer: ArrayBuffer, contentType: string) {
  const bytes = new Uint8Array(buffer)
  const encoding = textEncodingFor(bytes, contentType)
  try {
    return new TextDecoder(encoding).decode(bytes)
  } catch {
    return new TextDecoder('utf-8').decode(bytes)
  }
}

function fallbackTextWindowOffset(blobSize: number, chunk: MaterialChunk, chunks: MaterialChunk[]) {
  if (blobSize <= TEXT_PREVIEW_BYTES) return 0
  const chunkIndex = Math.max(0, chunks.findIndex((candidate) => String(candidate.id) === String(chunk.id)))
  const ratio = chunks.length > 1 ? chunkIndex / Math.max(1, chunks.length - 1) : 0
  const maxOffset = Math.max(0, blobSize - TEXT_PREVIEW_BYTES)
  return Math.min(maxOffset, Math.max(0, Math.floor(maxOffset * ratio)))
}

async function locateTextWindowOffset(blob: Blob, chunk: MaterialChunk, chunks: MaterialChunk[]) {
  if (!chunk?.chunkText?.trim()) return fallbackTextWindowOffset(blob.size, chunk, chunks)
  if (blob.size > TEXT_LOCATE_MAX_BYTES) return fallbackTextWindowOffset(blob.size, chunk, chunks)
  const buffer = await blob.arrayBuffer()
  const sourceText = decodeTextWindow(buffer, blob.type)
  const textIndex = findChunkTextIndex(sourceText, chunk)
  if (textIndex < 0 || sourceText.length === 0) return fallbackTextWindowOffset(blob.size, chunk, chunks)
  const approximateByteOffset = Math.floor((textIndex / sourceText.length) * blob.size)
  const maxOffset = Math.max(0, blob.size - TEXT_PREVIEW_BYTES)
  return Math.min(maxOffset, Math.max(0, approximateByteOffset - TEXT_LOCATE_LEAD_BYTES))
}

function findChunkTextIndex(sourceText: string, chunk: MaterialChunk) {
  const candidates = chunkTextCandidates(chunk)
  for (const candidate of candidates) {
    const index = sourceText.indexOf(candidate)
    if (index >= 0) return index
  }
  const compactSource = compactTextWithMap(sourceText)
  for (const candidate of candidates) {
    const compactCandidate = compactText(candidate)
    if (compactCandidate.length < 24) continue
    const compactIndex = compactSource.text.indexOf(compactCandidate.slice(0, 240))
    if (compactIndex >= 0) return compactSource.map[compactIndex] ?? -1
  }
  return -1
}

function chunkTextCandidates(chunk: MaterialChunk) {
  const values = [chunk.chunkText, chunk.excerpt, chunk.summary]
  const candidates: string[] = []
  for (const value of values) {
    const normalized = (value || '').trim()
    if (!normalized) continue
    for (const length of [500, 300, 160, 80]) {
      const candidate = normalized.slice(0, length).trim()
      if (candidate.length >= 24 && !candidates.includes(candidate)) candidates.push(candidate)
    }
    for (const line of normalized.split(/\r?\n+/)) {
      const candidate = line.trim()
      if (candidate.length >= 32 && !candidates.includes(candidate)) candidates.push(candidate.slice(0, 240))
    }
  }
  return candidates
}

function compactText(value: string) {
  return value.replace(/\s+/g, '').toLowerCase()
}

function compactTextWithMap(value: string) {
  let text = ''
  const map: number[] = []
  for (let index = 0; index < value.length; index += 1) {
    const char = value[index]
    if (/\s/.test(char)) continue
    text += char.toLowerCase()
    map.push(index)
  }
  return { text, map }
}

function fallbackChunkRangeForPage(page: MaterialPage, pages: MaterialPage[], chunkCount: number) {
  if (!page || pages.length === 0 || chunkCount <= 0) return null
  const pageIndex = Math.max(0, pages.findIndex((candidate) => candidate.pageNo === page.pageNo))
  const start = Math.floor((pageIndex * chunkCount) / pages.length)
  const end = Math.max(start + 1, Math.floor(((pageIndex + 1) * chunkCount) / pages.length))
  return { start: Math.min(chunkCount - 1, start), end: Math.min(chunkCount, end) }
}

async function fetchBlobWithProgress(
  url: string,
  signal: AbortSignal,
  onProgress: (progress: OriginalDownloadProgress) => void,
) {
  const response = await fetch(url, { signal })
  if (!response.ok) {
    const message = await readErrorMessage(response)
    throw new Error(message || `原文加载失败 (${response.status})`)
  }

  const totalHeader = Number(response.headers.get('content-length') || 0)
  const total = Number.isFinite(totalHeader) && totalHeader > 0 ? totalHeader : null

  if (!response.body) {
    const blob = await response.blob()
    onProgress({ loaded: blob.size, total: total || blob.size })
    return blob
  }

  const reader = response.body.getReader()
  const chunks: BlobPart[] = []
  let loaded = 0

  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    if (value) {
      chunks.push(value)
      loaded += value.byteLength
      onProgress({ loaded, total })
    }
  }

  return new Blob(chunks, {
    type: response.headers.get('content-type') || undefined,
  })
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

/**
 * PagePreviewCanvas -- 页面预览画布组件
 *
 * 以类似 PDF 阅读器的方式展示资料的页面图片。
 * 支持缩放，底部显示当前页包含的片段标签（可点击跳转）。
 */
function PagePreviewCanvas({
  materialId,
  currentPage,
  zoom,
  pageChunkIndexes,
  chunks,
  activeChunkId,
  onSelectChunk,
  onError,
}: {
  materialId: string
  currentPage: MaterialPage
  zoom: number
  pageChunkIndexes: number[]
  chunks: MaterialChunk[]
  activeChunkId: string
  onSelectChunk?: (chunkIndex: number) => void
  onError?: () => void
}) {
  return (
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
          materialId={materialId}
          fileName={currentPage.imageName}
          className="h-full w-full object-contain"
          onError={onError}
        />
      </div>
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
 * @property canPrev - 是否可以向前翻
 * @property canNext - 是否可以向后翻
 * @property onPrev - 向前翻片段回调
 * @property onNext - 向后翻片段回调
 * @property onSelectChunk - 切换到指定片段回调
 * @property onOpenFile - 打开原文件回调
 */
interface ReaderPaperProps {
  chunk: MaterialChunk           // 当前展示的片段
  chunks: MaterialChunk[]        // 所有片段列表
  pages: MaterialPage[]          // 页面列表（用于页面预览模式）
  material: Material | null      // 当前资料
  targetPageNo?: number | null   // URL 或外部来源指定的目标页码
  initialViewMode?: ReaderViewMode | null
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
  targetPageNo,
  initialViewMode,
  progress,
  canPrev = true,
  canNext = true,
  onPrev,
  onNext,
  onSelectChunk,
  onOpenFile,
}: ReaderPaperProps) {
  // === 状态管理 ===
  /** 缩放比例（0.7~1.8），仅页面预览模式下使用 */
  const [zoom, setZoom] = useState(1)
  /** 当前阅读视图模式：'original' 原文预览 | 'smart' 智能阅读 */
  const [viewMode, setViewMode] = useState<ReaderViewMode>('original')
  /** 原文预览的 ObjectURL（用于 iframe src） */
  const [originalUrl, setOriginalUrl] = useState('')
  /** 原文是否正在加载 */
  const [originalLoading, setOriginalLoading] = useState(false)
  /** 原文下载进度（用于加载进度条） */
  const [originalProgress, setOriginalProgress] = useState<OriginalDownloadProgress>({ loaded: 0, total: null })
  /** 原文加载错误信息 */
  const [originalError, setOriginalError] = useState('')
  /** 文本分段预览的 Blob（TXT/MD 文件的完整内容） */
  const [textPreviewBlob, setTextPreviewBlob] = useState<Blob | null>(null)
  /** 文本分段预览的当前偏移量（字节） */
  const [textWindowOffset, setTextWindowOffset] = useState(0)
  /** 文本分段预览的当前段文本 */
  const [textWindowText, setTextWindowText] = useState('')
  /** 文本分段预览是否正在加载当前段 */
  const [textWindowLoading, setTextWindowLoading] = useState(false)
  /**
   * 用户手动翻页时的页码覆盖
   * 当用户点击"上一页/下一页"时，用此值覆盖从 chunk 派生的页码
   * 切换片段时重置为 null（恢复自动匹配）
   */
  const [currentPageOverride, setCurrentPageOverride] = useState<number | null>(null)
  /** 页面图片是否加载失败（失败后降级为文本模式） */
  const [pagePreviewFailed, setPagePreviewFailed] = useState(false)
  /** 滚动区域的 DOM 引用（用于切换内容时滚回顶部） */
  const scrollViewportRef = useRef<HTMLDivElement | null>(null)
  /** 原文 ObjectURL 引用（用于清理时释放内存） */
  const originalObjectUrlRef = useRef<string | null>(null)

  // === 派生状态（从 props 和 state 计算） ===
  /** 阅读进度百分比（用于进度条显示） */
  const progressPercent = Math.round(progress * 100)
  /** 原文预览类型（PDF/文本/Office/不支持） */
  const previewKind = originalPreviewKind(material)
  /** 当前资料是否支持原文预览 */
  const hasOriginalPreview = supportsOriginalPreview(material)
  /** 是否正在使用原文预览模式 */
  const isOriginalView = viewMode === 'original' && hasOriginalPreview
  /** 是否使用页面图片预览模式（有页面图片 + 预览就绪 + 未失败） */
  const hasPagePreview = !!material?.id && pages.length > 0 && material.previewStatus === 'READY' && !pagePreviewFailed
  /** 是否使用页面图片承载当前视图；PDF 原文预览也走页面图，避免浏览器 PDF viewer 记忆滚动到末页。 */
  const usesPageCanvas = hasPagePreview && (!isOriginalView || previewKind === 'pdf')
  /** 是否显示页面控制（缩放、页码标签等，仅页面预览模式时显示） */
  const showPageControls = usesPageCanvas
  /** 原文模式下是否支持片段导航（PDF 和 Office 模式支持） */
  const originalChunkNavigationEnabled = !isOriginalView || previewKind === 'pdf' || previewKind === 'office'
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
  // 当前页码：优先覆盖值 > 片段页码 > 外部目标页码 > 第一页
  const currentPageNo = currentPageOverride || chunkPageNo || validTargetPageNo || pages[0]?.pageNo || 1
  const firstContentPage = pages.find((page) => page.chunkIds.length > 0) || pages[0]
  const currentPage = pages.find((page) => page.pageNo === currentPageNo && page.chunkIds.length > 0)
    || firstContentPage
  const currentPageIndex = currentPage ? pages.findIndex((page) => page.pageNo === currentPage.pageNo) : -1
  const originalProgressPercent = originalProgress.total
    ? Math.min(100, Math.round((originalProgress.loaded / originalProgress.total) * 100))
    : null
  const originalFrameUrl = useMemo(() => pdfPageUrl(originalUrl, currentPageNo), [currentPageNo, originalUrl])
  const textWindowEnd = textPreviewBlob
    ? Math.min(textPreviewBlob.size, textWindowOffset + TEXT_PREVIEW_BYTES)
    : 0
  const textWindowIndex = textPreviewBlob ? Math.floor(textWindowOffset / TEXT_PREVIEW_BYTES) + 1 : 0
  const textWindowCount = textPreviewBlob ? Math.max(1, Math.ceil(textPreviewBlob.size / TEXT_PREVIEW_BYTES)) : 0

  // 当前页包含的片段索引列表
  const pageChunkIndexes = useMemo(() => {
    if (!currentPage) return []
    const ids = new Set(currentPage.chunkIds.map(String))
    // 生成当前页的片段索引列表，供页预览下方的片段标签和问答上下文复用。
    const mappedIndexes = chunks
      .map((candidate, index) => (ids.has(String(candidate.id)) ? index : -1))
      .filter((index) => index >= 0)
    if (mappedIndexes.length > 0) return mappedIndexes
    const fallbackRange = fallbackChunkRangeForPage(currentPage, pages, chunks.length)
    if (!fallbackRange) return []
    return Array.from(
      { length: fallbackRange.end - fallbackRange.start },
      (_, index) => fallbackRange.start + index,
    )
  }, [chunks, currentPage, pages])

  // === 副作用 ===

  /** 切换片段时重置页码覆盖和预览失败状态 */
  useEffect(() => {
    setCurrentPageOverride(null)
    setPagePreviewFailed(false)
  }, [material?.id, chunk.id])

  /** 资料切换时重置所有原文预览相关状态 */
  useEffect(() => {
    setViewMode(initialViewMode || defaultReaderViewMode(material))
    setOriginalUrl('')
    setOriginalLoading(false)
    setOriginalProgress({ loaded: 0, total: null })
    setOriginalError('')
    setTextPreviewBlob(null)
    setTextWindowOffset(0)
    setTextWindowText('')
    setTextWindowLoading(false)
    if (originalObjectUrlRef.current) {
      URL.revokeObjectURL(originalObjectUrlRef.current)
      originalObjectUrlRef.current = null
    }
  }, [material?.id, material?.sourceType])

  useEffect(() => {
    if (initialViewMode) setViewMode(initialViewMode)
  }, [initialViewMode])

/**
 * 原文预览文件加载效果
 *
 * 当切换到原文预览模式时：
 * 1. 通过 createMaterialFileTicket 获取临时访问链接（有时效性）
 * 2. 根据资料类型选择不同的预览 URL（Office 走 /preview-file 接口）
 * 3. PDF/Office：通过 fetchBlobWithProgress 下载文件，创建 ObjectURL 用于 iframe
 * 4. TXT/MD：下载为 Blob，后续通过分段读取显示
 * 5. 组件卸载或切换时通过 AbortController 取消请求，释放 ObjectURL
 */
  useEffect(() => {
    if (!material?.id || !hasOriginalPreview || viewMode !== 'original') return

    let cancelled = false
    const controller = new AbortController()
    if (originalObjectUrlRef.current) {
      // 切换资料或预览类型前释放旧 ObjectURL，避免 iframe 资源泄漏。
      URL.revokeObjectURL(originalObjectUrlRef.current)
      originalObjectUrlRef.current = null
    }
    setOriginalLoading(true)
    setOriginalUrl('')
    setOriginalProgress({ loaded: 0, total: null })
    setOriginalError('')
    setTextPreviewBlob(null)
    setTextWindowOffset(0)
    setTextWindowText('')
    if (previewKind === 'pdf' && hasPagePreview) {
      setOriginalLoading(false)
      return () => {
        cancelled = true
        controller.abort()
      }
    }
    createMaterialFileTicket(material.id)
      .then(async (ticket) => {
        if (cancelled) return
        const url = previewKind === 'office' ? previewTicketUrl(ticket.url) : normalizeTicketUrl(ticket.url)
        const blob = await fetchBlobWithProgress(url, controller.signal, (nextProgress) => {
          if (!cancelled) setOriginalProgress(nextProgress)
        })
        if (cancelled) return
        if (previewKind === 'text') {
          // 文本文件不直接创建 iframe，先保存 Blob，再由下方窗口化读取 effect 分段解码。
          setTextWindowLoading(true)
          setTextPreviewBlob(blob)
          return
        }
        const objectUrl = URL.createObjectURL(blob)
        originalObjectUrlRef.current = objectUrl
        setOriginalUrl(objectUrl)
      })
      .catch((error) => {
        if (error instanceof Error && error.name === 'AbortError') return
        if (!cancelled) {
          setOriginalError(error instanceof Error ? error.message : '原文预览加载失败')
        }
      })
      .finally(() => {
        if (!cancelled) setOriginalLoading(false)
      })

    return () => {
      cancelled = true
      controller.abort()
      if (originalObjectUrlRef.current) {
        URL.revokeObjectURL(originalObjectUrlRef.current)
        originalObjectUrlRef.current = null
      }
    }
  }, [hasOriginalPreview, hasPagePreview, material?.id, previewKind, viewMode])

  useEffect(() => {
    if (!textPreviewBlob || previewKind !== 'text') return

    let cancelled = false
    const start = Math.max(0, Math.min(textWindowOffset, Math.max(0, textPreviewBlob.size - 1)))
    const end = Math.min(textPreviewBlob.size, start + TEXT_PREVIEW_BYTES)

    setTextWindowLoading(true)
    // 大文本按固定字节窗口读取，避免一次解码整份资料造成页面卡顿。
    textPreviewBlob
      .slice(start, end)
      .arrayBuffer()
      .then((buffer) => {
        if (!cancelled) {
          setTextWindowText(decodeTextWindow(buffer, textPreviewBlob.type))
        }
      })
      .catch((error) => {
        if (!cancelled) {
          setOriginalError(error instanceof Error ? error.message : '文本预览加载失败')
        }
      })
      .finally(() => {
        if (!cancelled) setTextWindowLoading(false)
      })

    return () => {
      cancelled = true
    }
  }, [previewKind, textPreviewBlob, textWindowOffset])

  useEffect(() => {
    if (!isOriginalView || previewKind !== 'text' || !textPreviewBlob) return
    let cancelled = false
    locateTextWindowOffset(textPreviewBlob, chunk, chunks)
      .then((offset) => {
        if (!cancelled) setTextWindowOffset(offset)
      })
      .catch(() => {
        if (!cancelled) setTextWindowOffset(fallbackTextWindowOffset(textPreviewBlob.size, chunk, chunks))
      })
    return () => {
      cancelled = true
    }
  }, [chunk.id, chunks, isOriginalView, previewKind, textPreviewBlob])

  // 切换内容时自动滚回顶部
  useEffect(() => {
    scrollViewportRef.current?.scrollTo({ top: 0, left: 0 })
  }, [chunk.id, currentPage?.pageNo, material?.id, textWindowOffset])

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
      // 预加载失败不影响当前阅读，只用于提升下一页/上一页的响应速度。
      fetch(imageUrl(material.id, page.imageName), { headers }).catch(() => undefined)
    })
  }, [currentPage, currentPageIndex, hasPagePreview, material?.id, pages])

  /**
   * 翻页处理（上一页/下一页）
   * 1. 根据方向计算目标页面
   * 2. 设置页码覆盖值（currentPageOverride）
   * 3. 跳转到该页的第一个片段
   */
  const handlePageStep = (direction: -1 | 1) => {
    if (!pages.length || currentPageIndex < 0) return
    const nextPage = direction > 0
      ? pages.slice(currentPageIndex + 1).find((page) => page.chunkIds.length > 0) || pages[currentPageIndex + direction]
      : pages.slice(0, currentPageIndex).reverse().find((page) => page.chunkIds.length > 0) || pages[currentPageIndex + direction]
    if (!nextPage) return
    setCurrentPageOverride(nextPage.pageNo)
    const nextPageChunkIds = new Set(nextPage.chunkIds.map(String))
    const fallbackChunkRange = fallbackChunkRangeForPage(nextPage, pages, chunks.length)
    const nextChunkIndex = chunks.findIndex((candidate) => (
      Number(candidate.pageNo) === nextPage.pageNo || nextPageChunkIds.has(String(candidate.id))
    ))
    // 翻页后同步到该页首个片段，让目录高亮和右侧问答上下文跟随页面变化。
    if (nextChunkIndex >= 0) onSelectChunk?.(nextChunkIndex)
    else if (fallbackChunkRange) onSelectChunk?.(fallbackChunkRange.start)
  }

  /**
   * renderOriginalPreview -- 原文预览渲染函数
   * 根据资料类型渲染不同的预览内容：
   * - 错误状态：显示错误信息 + 切换到智能阅读按钮
   * - 加载中：显示进度条
   * - 文本（TXT/MD）：分段预览，支持"上一段/下一段"导航
   * - PDF/Office：iframe 嵌入
   * - 不支持：显示降级提示 + 打开原文件按钮
   */
  const renderOriginalPreview = () => {
    if (!material?.id) return null

    if (originalError) {
      return (
        <div className="flex flex-1 items-center justify-center bg-[#eceff1] p-6 dark:bg-[#10131a]">
          <div className="max-w-md rounded-lg border bg-background p-5 text-center shadow-sm">
            <FileText className="mx-auto mb-3 h-9 w-9 text-muted-foreground" />
            <p className="text-sm font-medium">原文预览加载失败</p>
            <p className="mt-2 text-xs leading-5 text-muted-foreground">{originalError}</p>
            <Button className="mt-4" size="sm" variant="outline" onClick={() => setViewMode('smart')}>
              切换到智能阅读
            </Button>
          </div>
        </div>
      )
    }

    if (originalLoading && (previewKind === 'text' ? !textPreviewBlob : !originalUrl)) {
      return (
        <div className="flex flex-1 items-center justify-center bg-[#eceff1] p-6 text-sm text-muted-foreground dark:bg-[#10131a]">
          <div className="w-full max-w-md rounded-lg border bg-background p-5 shadow-sm">
            <div className="flex items-center gap-2 text-foreground">
              <Loader2 className="h-4 w-4 animate-spin" />
              <span className="text-sm font-medium">正在加载原文</span>
            </div>
            <div className="mt-4 h-2 overflow-hidden rounded-full bg-muted">
              <div
                className={cn(
                  'h-full rounded-full bg-primary transition-all',
                  originalProgressPercent === null && 'w-1/2 animate-pulse',
                )}
                style={originalProgressPercent === null ? undefined : { width: `${originalProgressPercent}%` }}
              />
            </div>
            <div className="mt-3 flex items-center justify-between gap-3 text-xs">
              <span>
                {formatBytes(originalProgress.loaded)}
                {originalProgress.total ? ` / ${formatBytes(originalProgress.total)}` : ''}
              </span>
              <span>{originalProgressPercent === null ? '计算中' : `${originalProgressPercent}%`}</span>
            </div>
            <p className="mt-3 text-xs leading-5">
              大文件会先完整加载，加载完成后再显示。TXT/MD 会按段预览，避免一次渲染整篇导致页面卡死。
            </p>
          </div>
        </div>
      )
    }

    if (previewKind === 'text') {
      if (!textPreviewBlob) return null

      return (
        <div className="flex flex-1 flex-col overflow-hidden bg-[#eceff1] dark:bg-[#10131a]">
          <div className="flex flex-wrap items-center justify-between gap-2 border-b bg-background px-3 py-2 text-xs">
            <span className="text-muted-foreground">
              第 {textWindowIndex}/{textWindowCount} 段 · {formatBytes(textWindowOffset)} - {formatBytes(textWindowEnd)} / {formatBytes(textPreviewBlob.size)}
            </span>
            <div className="flex items-center gap-2">
              <Button
                variant="outline"
                size="sm"
                className="h-7 px-2 text-xs"
                disabled={textWindowOffset <= 0 || textWindowLoading}
                onClick={() => setTextWindowOffset((offset) => Math.max(0, offset - TEXT_PREVIEW_BYTES))}
              >
                上一段
              </Button>
              <Button
                variant="outline"
                size="sm"
                className="h-7 px-2 text-xs"
                disabled={textWindowEnd >= textPreviewBlob.size || textWindowLoading}
                onClick={() => setTextWindowOffset((offset) => Math.min(textPreviewBlob.size - 1, offset + TEXT_PREVIEW_BYTES))}
              >
                下一段
              </Button>
            </div>
          </div>
          <div ref={scrollViewportRef} className="flex-1 overflow-auto p-3">
            {textWindowLoading ? (
              <div className="flex h-full items-center justify-center text-sm text-muted-foreground">
                <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                加载当前段...
              </div>
            ) : (
              <pre className="min-h-full min-w-max rounded-md bg-white p-4 font-mono text-[13px] leading-6 text-slate-900 shadow-sm ring-1 ring-black/10 dark:bg-[#0f1117] dark:text-slate-100 dark:ring-white/10">
                {textWindowText}
              </pre>
            )}
          </div>
        </div>
      )
    }

    if (previewKind === 'pdf' && hasPagePreview && currentPage) {
      return (
        <ScrollArea className="flex-1 bg-[#eceff1]" viewportRef={scrollViewportRef}>
          <PagePreviewCanvas
            materialId={material.id}
            currentPage={currentPage}
            zoom={zoom}
            pageChunkIndexes={pageChunkIndexes}
            chunks={chunks}
            activeChunkId={chunk.id}
            onSelectChunk={onSelectChunk}
            onError={() => setPagePreviewFailed(true)}
          />
        </ScrollArea>
      )
    }

    if (previewKind === 'pdf' || previewKind === 'office') {
      return (
        <div className="flex-1 bg-[#2f3338]">
          {originalUrl ? (
            <iframe
              key={`${material.id}-${previewKind}-${currentPageNo}-${originalUrl}`}
              title={material.title || material.originalName || '原文预览'}
              src={originalFrameUrl}
              className="h-full w-full border-0 bg-white"
            />
          ) : null}
        </div>
      )
    }

    if (previewKind === 'unsupported') {
      if (hasPagePreview && currentPage) {
        return (
          <ScrollArea className="flex-1 bg-[#eceff1]" viewportRef={scrollViewportRef}>
            <div className="mx-auto mt-3 max-w-3xl rounded-md border border-amber-200 bg-amber-50 px-3 py-2 text-xs leading-5 text-amber-900 dark:border-amber-900/50 dark:bg-amber-950/30 dark:text-amber-100">
              Word 在浏览器中无法保证 100% 原生还原；这里展示的是转换后的 PDF 版式预览，完整原文件可用右上角打开。
            </div>
            <PagePreviewCanvas
              materialId={material.id}
              currentPage={currentPage}
              zoom={zoom}
              pageChunkIndexes={pageChunkIndexes}
              chunks={chunks}
              activeChunkId={chunk.id}
              onSelectChunk={onSelectChunk}
              onError={() => setPagePreviewFailed(true)}
            />
          </ScrollArea>
        )
      }

      return (
        <div className="flex flex-1 items-center justify-center bg-[#eceff1] p-6 dark:bg-[#10131a]">
          <div className="max-w-md rounded-lg border bg-background p-5 text-center shadow-sm">
            <FileText className="mx-auto mb-3 h-9 w-9 text-muted-foreground" />
            <p className="text-sm font-medium">Word 版式预览暂不可用</p>
            <p className="mt-2 text-xs leading-5 text-muted-foreground">
              {material.previewError || '请安装 LibreOffice/soffice 后重新解析，或打开原文件查看完整格式。'}
            </p>
            {onOpenFile && (
              <Button className="mt-4" size="sm" variant="outline" onClick={onOpenFile}>
                <ExternalLink className="mr-1 h-3.5 w-3.5" />
                打开原文件
              </Button>
            )}
          </div>
        </div>
      )
    }

    return null
  }

  // === 主渲染 ===
  return (
    <div className="flex-1 flex flex-col overflow-hidden">
      {/* ---- 顶部工具栏：标题、页码标签、阅读模式切换、缩放控制、原文件按钮、进度条 ---- */}
      <div className="flex flex-wrap items-center justify-between gap-2 border-b px-3 py-2 md:gap-3 md:px-6 md:py-3">
        <div className="flex min-w-[10rem] flex-1 items-center gap-2">
          <h3 className="text-sm font-medium truncate">
            {material?.title || material?.originalName || '未选择资料'}
          </h3>
          {showPageControls && currentPage && (
            <Badge variant="outline" className="text-xs">P{currentPage.pageNo}/{pages.length}</Badge>
          )}
          {!showPageControls && !isOriginalView && chunkPageNo && (
            <Badge variant="outline" className="text-xs">P{chunkPageNo}</Badge>
          )}
          {!showPageControls && isOriginalView && (previewKind === 'pdf' || previewKind === 'office') && currentPageNo && (
            <Badge variant="outline" className="text-xs">P{currentPageNo}</Badge>
          )}
          {isOriginalView && previewKind === 'pdf' && (
            <Badge variant="outline" className="text-xs">
              {hasPagePreview ? 'PDF 版式预览' : 'PDF 原文'}
            </Badge>
          )}
          {isOriginalView && previewKind === 'text' && (
            <Badge variant="outline" className="text-xs">原始文本</Badge>
          )}
          {isOriginalView && previewKind === 'office' && (
            <Badge variant="outline" className="text-xs">Word 转换预览</Badge>
          )}
          {material?.previewStatus === 'DEGRADED' && (
            <Badge variant="secondary" className="text-xs">文本预览</Badge>
          )}
        </div>

        <div className="flex shrink-0 items-center gap-1.5 md:gap-2">
          {hasOriginalPreview && (
            <div className="flex items-center rounded-md border bg-background p-0.5">
              <button
                type="button"
                className={cn(
                  'rounded px-2 py-1 text-xs transition-colors',
                  viewMode === 'original'
                    ? 'bg-primary text-primary-foreground shadow-sm'
                    : 'text-muted-foreground hover:text-foreground',
                )}
                onClick={() => setViewMode('original')}
              >
                原文预览
              </button>
              <button
                type="button"
                className={cn(
                  'rounded px-2 py-1 text-xs transition-colors',
                  viewMode === 'smart'
                    ? 'bg-primary text-primary-foreground shadow-sm'
                    : 'text-muted-foreground hover:text-foreground',
                )}
                onClick={() => setViewMode('smart')}
              >
                智能阅读
              </button>
            </div>
          )}
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

      {/* 内容展示区 */}
      {isOriginalView ? (
        renderOriginalPreview()
      ) : (
        <ScrollArea className="flex-1 bg-[#eceff1]" viewportRef={scrollViewportRef}>
          {hasPagePreview && currentPage && material?.id ? (
            /* ---- 页面预览模式 ---- */
            <PagePreviewCanvas
              materialId={material.id}
              currentPage={currentPage}
              zoom={zoom}
              pageChunkIndexes={pageChunkIndexes}
              chunks={chunks}
              activeChunkId={chunk.id}
              onSelectChunk={onSelectChunk}
              onError={() => setPagePreviewFailed(true)}
            />
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
      )}

      {/* 底部翻页导航栏 */}
      <div className="flex items-center justify-between gap-2 border-t px-3 py-2 md:px-6 md:py-3">
        <Button
          variant="outline"
          size="sm"
          onClick={showPageControls ? () => handlePageStep(-1) : onPrev}
          disabled={showPageControls ? currentPageIndex <= 0 : !originalChunkNavigationEnabled || !canPrev}
        >
          <ChevronLeft className="mr-1 h-4 w-4" /> {showPageControls ? '上一页' : '上一片段'}
        </Button>
        <span className="min-w-0 truncate text-center text-xs text-muted-foreground">
          {showPageControls && currentPage
            ? `第 ${currentPage.pageNo} 页 / 共 ${pages.length} 页`
            : isOriginalView
              ? currentPageNo
                ? `第 ${currentPageNo} 页 · 片段 #${chunk.chunkIndex}`
                : '原文预览中，当前片段暂无页码映射'
              : `片段 #${chunk.chunkIndex}`}
        </span>
        <Button
          variant="outline"
          size="sm"
          onClick={showPageControls ? () => handlePageStep(1) : onNext}
          disabled={showPageControls ? currentPageIndex >= pages.length - 1 : !originalChunkNavigationEnabled || !canNext}
        >
          {showPageControls ? '下一页' : '下一片段'} <ChevronRight className="ml-1 h-4 w-4" />
        </Button>
      </div>
    </div>
  )
}
