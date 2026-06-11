/**
 * ReaderPage -- 文档阅读器页面（核心页面）
 *
 * 【路由】/workspace/reader
 *
 * 【页面布局】
 * 三栏响应式布局：
 * - 左侧（桌面端 56px）：常驻窄导航，只保留资料/片段入口和阅读进度展示
 *   - 完整资料列表和片段列表通过 Dialog 复用 ReaderToc 展示
 * - 中间（自适应宽度）：文档内容展示区（ReaderPaper）
 *   - 统一连续阅读：有页面预览时按页渲染 PDF 预览，无页面预览时按阅读页渲染文本片段
 *   - 底部：只保留稳定的页码/阅读页输入跳转
 * - 右侧（可拖拽调整页面占比，默认 30%）：AI 问答面板（ReaderAsk）
 *   - 支持选中文本提问
 *   - 支持流式回答（SSE）
 *   - 可折叠
 *
 * 移动端（lg 以下）：
 * - 只显示中间内容区
 * - 顶部有"目录"和"问答"按钮，点击后弹出底部抽屉
 *
 * 【核心功能详解】
 *
 * 1. 资料选择与导航：
 *    - URL 参数 materialId 标识当前资料
 *    - URL 参数 chunkId 标识当前片段
 *    - 点击左侧窄栏入口打开选择弹窗，再切换资料或片段
 *    - 底部输入页码或阅读页编号进行稳定跳转
 *
 * 2. PDF 预览：
 *    - 后端将 PDF 转为页面图片（page images）
 *    - 前端通过 MaterialImage 组件加载（需要 JWT 认证）
 *    - 支持缩放（70%~180%）
 *    - 页面下方同时展示解析文本，保证 PDF/Word 也能划选文字提问
 *
 * 3. 选中文本提问：
 *    - 在文档中选中 5-500 字符的文字
 *    - 自动填入右侧问答面板的选中文本区域
 *    - 可围绕选中内容向 AI 追问
 *
 * 4. 右侧面板宽度调整：
 *    - 拖拽中间分隔条可调整右侧问答栏页面占比
 *    - 占比保存到 localStorage，下次打开时恢复
 *    - 仅保留 5%~95% 的有效比例保护，不再使用固定像素上下限
 *
 * 5. URL 同步：
 *    - materialId 和 chunkId 通过 URL 参数保持同步
 *    - 切换资料或片段时更新 URL
 *    - 支持通过 URL 直接定位到特定资料的特定片段
 */
import { useEffect, useRef, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { motion } from 'framer-motion'
import { ReaderToc } from './ReaderToc'
import { ReaderPaper, type ReaderReadingContext } from './ReaderPaper'
import { ReaderAsk } from './ReaderAsk'
import { useMaterials, useMaterialChunks, useMaterialPages, createMaterialFileTicket } from '@/api/materials'
import {
  Bot,
  FileText,
  GripVertical,
  ListTree,
  Library,
  Layers3,
  Loader2,
  PanelRightClose,
  PanelRightOpen,
  X,
} from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { cn } from '@/lib/utils'
import { getReaderAskSnapshot, updateReaderAskSelection } from '@/lib/reader-ask-session'
import type { MaterialChunk, MaterialPage } from '@/types'

/** 右侧问答栏默认占页面宽度比例；默认紧凑，减少对阅读区的挤占。 */
const DEFAULT_ASK_RATIO = 0.3
const ASK_RATIO_STORAGE_KEY = 'reader-ask-ratio'
const READER_CONTEXT_STORAGE_KEY = 'learning-assistant.reader.current-context'
const ASK_RATIO_PRESETS = [
  { label: '紧凑', value: 0.3 },
  { label: '均衡', value: 0.4 },
  { label: '宽问答', value: 0.5 },
]
const READER_SELECTION_MIN_LENGTH = 5
const READER_SELECTION_MAX_LENGTH = 2000

interface ReaderContextSnapshot {
  materialId: string | null
  chunkId: string | null
  pageNo: number | null
}

/** 读取上次阅读上下文；仅在 URL 没带 materialId 时作为兜底恢复。 */
function readReaderContextSnapshot(): ReaderContextSnapshot {
  if (typeof window === 'undefined') return { materialId: null, chunkId: null, pageNo: null }
  try {
    const raw = window.localStorage.getItem(READER_CONTEXT_STORAGE_KEY)
    if (!raw) return { materialId: null, chunkId: null, pageNo: null }
    const parsed = JSON.parse(raw) as Partial<ReaderContextSnapshot>
    return {
      materialId: parsed.materialId ? String(parsed.materialId) : null,
      chunkId: parsed.chunkId ? String(parsed.chunkId) : null,
      pageNo: typeof parsed.pageNo === 'number' && parsed.pageNo > 0 ? parsed.pageNo : null,
    }
  } catch {
    window.localStorage.removeItem(READER_CONTEXT_STORAGE_KEY)
    return { materialId: null, chunkId: null, pageNo: null }
  }
}

/** 保存当前阅读上下文，保证从别的模块切回 Reader 时能恢复左侧资料和页码。 */
function writeReaderContextSnapshot(snapshot: ReaderContextSnapshot) {
  if (typeof window === 'undefined' || !snapshot.materialId) return
  window.localStorage.setItem(READER_CONTEXT_STORAGE_KEY, JSON.stringify(snapshot))
}

function closestReaderPaper(node: Node | null): Element | null {
  if (!node) return null
  const element = node instanceof Element ? node : node.parentElement
  return element?.closest('.reader-paper') ?? null
}

function isReaderSelection(selection: Selection) {
  if (selection.rangeCount === 0 || selection.isCollapsed) return false
  const anchorRoot = closestReaderPaper(selection.anchorNode)
  const focusRoot = closestReaderPaper(selection.focusNode)
  if (!anchorRoot || !focusRoot || anchorRoot !== focusRoot) return false

  const range = selection.getRangeAt(0)
  const commonRoot = closestReaderPaper(range.commonAncestorContainer)
  return commonRoot === anchorRoot
}

function normalizeReaderSelectedText(text: string) {
  return text
    .replace(/\u00a0/g, ' ')
    .replace(/[ \t]+\n/g, '\n')
    .replace(/\n{3,}/g, '\n\n')
    .trim()
}

export function ReaderPage() {
  // === URL 参数 ===
  const [searchParams, setSearchParams] = useSearchParams()
  const savedReaderContextRef = useRef(readReaderContextSnapshot())

  // === 数据获取 ===
  /** 所有资料列表 */
  const { data: materials = [] } = useMaterials()

  // === 状态管理 ===
  /** 当前选中的资料 ID（从 URL 参数初始化） */
  const [selectedMaterialId, setSelectedMaterialId] = useState<string | null>(() =>
    searchParams.get('materialId') || savedReaderContextRef.current.materialId || getReaderAskSnapshot().materialId,
  )
  /** 当前选中的片段索引（0-based） */
  const [selectedChunkIndex, setSelectedChunkIndex] = useState(0)
  /** 移动端当前打开的面板（'toc' 目录 | 'ask' 问答 | null 关闭） */
  const [mobilePanel, setMobilePanel] = useState<'toc' | 'ask' | null>(null)
  /** 桌面端资料/片段选择弹窗是否打开。 */
  const [navigatorOpen, setNavigatorOpen] = useState(false)
  /** 右侧问答栏是否展开（桌面端） */
  const [askOpen, setAskOpen] = useState(true)
  /**
   * 右侧问答栏宽度比例。
   * 用比例保存比像素更稳定，窗口大小变化后仍能保持用户期望的页面占比。
   */
  const [askRatio, setAskRatio] = useState(() => {
    const saved = Number(window.localStorage.getItem(ASK_RATIO_STORAGE_KEY))
    return normalizeAskRatio(Number.isFinite(saved) && saved > 0 ? saved : DEFAULT_ASK_RATIO)
  })
  /** 是否正在拖拽调整右侧问答栏宽度 */
  const [resizingAsk, setResizingAsk] = useState(false)
  /** 问答栏 DOM 引用；拖拽时直接写入宽度，避免每次 mousemove 都触发 React 重渲染。 */
  const askAsideRef = useRef<HTMLElement | null>(null)
  const dragFrameRef = useRef<number | null>(null)
  const dragRatioRef = useRef(askRatio)
  /** 阅读器当前停留页码，由 ReaderPaper 的 IntersectionObserver 实时回传给问答上下文。 */
  const [readingPageNo, setReadingPageNo] = useState<number | null>(null)
  /** 当前停留页包含的片段 ID，资料问答会用它限定当前页附近的检索范围。 */
  const [readingPageChunkIds, setReadingPageChunkIds] = useState<Array<string | number>>([])
  /** 当前停留片段索引；无页面预览资料滚动时会持续更新。 */
  const [readingChunkIndex, setReadingChunkIndex] = useState(0)

  // === 数据获取（依赖选中的资料） ===
  /** 当前资料的所有片段列表 */
  const { data: chunks = [], isLoading: chunksLoading, isFetching: chunksFetching } = useMaterialChunks(selectedMaterialId)
  /** 当前资料的所有页面列表（用于页面预览模式） */
  const { data: pages = [] } = useMaterialPages(selectedMaterialId)

  // === URL 参数解析 ===
  const materialParam = searchParams.get('materialId')
  const chunkParam = searchParams.get('chunkId')
  const pageParam = searchParams.get('pageNo')
  const requestedPageNo = parsePositiveInt(pageParam)
  /** 根据 URL 中的 chunkId 查找对应的片段索引 */
  const requestedChunkIndex = chunkParam
    ? chunks.findIndex((chunk) => String(chunk.id) === chunkParam)
    : -1
  const requestedPageChunkIndex = requestedPageNo
    ? chunkIndexForPageNo(requestedPageNo, chunks, pages)
    : -1

  // === 派生值 ===
  /** 当前选中的资料对象 */
  const selectedMaterial = materials.find((m) => m.id === selectedMaterialId) || null
  /** 当前选中的片段对象 */
  const currentChunk = chunks[selectedChunkIndex] || null
  const firstContentPage = pages.find((page) => page.chunkIds.length > 0) || pages[0]
  const currentChunkPageNo = pageNoForChunk(currentChunk, selectedChunkIndex, chunks, pages)
  /**
   * 当前页面对象
   * 优先按片段的 pageNo 匹配，其次按 chunkId 包含关系匹配，最后取第一页
   */
  const currentPage = pages.find((page) => page.pageNo === currentChunkPageNo)
    || (requestedPageNo ? pages.find((page) => page.pageNo === requestedPageNo && page.chunkIds.length > 0) : null)
    || firstContentPage
  const readingChunk = chunks[readingChunkIndex] || currentChunk
  const askCurrentPageNo = readingPageNo ?? currentPage?.pageNo ?? currentChunk?.pageNo ?? null
  const askCurrentPageChunkIds = readingPageChunkIds.length ? readingPageChunkIds : currentPage?.chunkIds || []

  // === 副作用：URL 参数同步 ===

  /**
   * 从其他模块重新进入阅读器时，侧边栏通常只导航到 /workspace/reader，不会带 materialId。
   * 这时用上次阅读快照恢复 URL，避免左侧文档区变成“请选择资料”，而右侧问答还保留旧会话。
   */
  useEffect(() => {
    if (materialParam || !selectedMaterialId) return
    const saved = savedReaderContextRef.current
    const nextParams = new URLSearchParams({ materialId: selectedMaterialId })
    if (saved.materialId === selectedMaterialId) {
      if (saved.chunkId) nextParams.set('chunkId', saved.chunkId)
      if (saved.pageNo && saved.pageNo > 0) nextParams.set('pageNo', String(saved.pageNo))
    }
    setSearchParams(nextParams, { replace: true })
  }, [materialParam, selectedMaterialId, setSearchParams])

  /**
   * 监听 URL 中的 materialId 变化
   * 如果 URL 中的 materialId 与当前选中不同，切换到新资料并重置片段索引
   */
  useEffect(() => {
    if (materialParam && materialParam !== selectedMaterialId) {
      setSelectedMaterialId(materialParam)
      setSelectedChunkIndex(0)
    }
  }, [materialParam, selectedMaterialId])

  /**
   * 监听 URL 中的 chunkId 变化
   * 如果 URL 中的 chunkId 对应的索引与当前不同，切换到目标片段
   */
  useEffect(() => {
    if (!chunkParam || requestedChunkIndex < 0) return
    if (requestedChunkIndex !== selectedChunkIndex) {
      // chunkId 必须等 chunks 加载完成后才能解析成索引，未命中时先保持当前片段不动。
      setSelectedChunkIndex(requestedChunkIndex)
    }
  }, [chunkParam, requestedChunkIndex, selectedChunkIndex])

  useEffect(() => {
    if (requestedChunkIndex >= 0 || requestedPageChunkIndex < 0) return
    if (requestedPageChunkIndex !== selectedChunkIndex) {
      setSelectedChunkIndex(requestedPageChunkIndex)
    }
  }, [requestedChunkIndex, requestedPageChunkIndex, selectedChunkIndex])

  /**
   * 将当前选中的资料和片段同步到 URL 参数
   * 使用 replace 模式避免产生浏览器历史记录
   */
  useEffect(() => {
    if (!selectedMaterialId || !currentChunk) return
    const currentPageNo = pageNoForChunk(currentChunk, selectedChunkIndex, chunks, pages)
      || currentPage?.pageNo
      || null
    // 通过 URL 直接打开指定页时，chunks/pages 可能比 selectedChunkIndex 晚一步加载。
    // 如果此时立刻把默认第 1 片段写回 URL，会把用户请求的 pageNo 覆盖掉，导致第 2 页等深链永远跳不进去。
    if (!chunkParam && requestedPageNo && requestedPageChunkIndex >= 0 && requestedPageChunkIndex !== selectedChunkIndex) return
    // 避免不必要的 URL 更新（如果参数已经一致则跳过）
    if (materialParam && materialParam !== selectedMaterialId) return
    if (chunkParam && chunkParam !== String(currentChunk.id) && requestedChunkIndex >= 0) return
    if (
      materialParam === selectedMaterialId
      && chunkParam === String(currentChunk.id)
      && (!currentPageNo || pageParam === String(currentPageNo))
    ) return
    // replace 同步内部阅读位置，避免翻片段时污染浏览器返回栈。
    const nextParams = new URLSearchParams({ materialId: selectedMaterialId, chunkId: String(currentChunk.id) })
    if (currentPageNo && currentPageNo > 0) nextParams.set('pageNo', String(currentPageNo))
    setSearchParams(nextParams, { replace: true })
  }, [chunkParam, chunks, currentChunk, currentPage, materialParam, pageParam, pages, requestedChunkIndex, requestedPageChunkIndex, requestedPageNo, selectedChunkIndex, selectedMaterialId, setSearchParams])

  /** 当前阅读上下文变化时写入本地快照，供下次无参数进入 Reader 时恢复。 */
  useEffect(() => {
    if (!selectedMaterialId) return
    writeReaderContextSnapshot({
      materialId: selectedMaterialId,
      chunkId: currentChunk?.id == null ? chunkParam || null : String(currentChunk.id),
      pageNo: askCurrentPageNo || requestedPageNo || null,
    })
  }, [askCurrentPageNo, chunkParam, currentChunk, requestedPageNo, selectedMaterialId])

  // === 副作用：右侧面板宽度调整 ===

  const askWidth = askWidthFromRatio(askRatio)

  /** 问答栏比例变化时保存到 localStorage */
  useEffect(() => {
    window.localStorage.setItem(ASK_RATIO_STORAGE_KEY, String(askRatio))
    if (askAsideRef.current && askOpen) {
      askAsideRef.current.style.width = `${askWidthFromRatio(askRatio)}px`
    }
  }, [askOpen, askRatio])

  /**
   * 拖拽调整右侧问答栏宽度。
   * 拖拽过程中直接更新 aside DOM 宽度，避免 mousemove 高频触发 React 重渲染；
   * 鼠标松开后才把最终比例写回状态和 localStorage。
   */
  useEffect(() => {
    if (!resizingAsk) return

    const applyWidth = (nextRatio: number) => {
      const normalized = normalizeAskRatio(nextRatio)
      dragRatioRef.current = normalized
      if (dragFrameRef.current !== null) return
      dragFrameRef.current = window.requestAnimationFrame(() => {
        dragFrameRef.current = null
        if (askAsideRef.current) {
          askAsideRef.current.style.width = `${askWidthFromRatio(dragRatioRef.current)}px`
        }
      })
    }

    const handleMouseMove = (event: MouseEvent) => {
      applyWidth(ratioFromAskWidth(window.innerWidth - event.clientX))
    }
    const handleMouseUp = () => {
      if (dragFrameRef.current !== null) {
        window.cancelAnimationFrame(dragFrameRef.current)
        dragFrameRef.current = null
      }
      setAskRatio(dragRatioRef.current)
      setResizingAsk(false)
    }

    // 拖拽过程中：光标变为 col-resize，禁止文本选择
    document.body.style.cursor = 'col-resize'
    document.body.style.userSelect = 'none'
    window.addEventListener('mousemove', handleMouseMove)
    window.addEventListener('mouseup', handleMouseUp)

    return () => {
      // 恢复默认样式
      document.body.style.cursor = ''
      document.body.style.userSelect = ''
      if (dragFrameRef.current !== null) {
        window.cancelAnimationFrame(dragFrameRef.current)
        dragFrameRef.current = null
      }
      window.removeEventListener('mousemove', handleMouseMove)
      window.removeEventListener('mouseup', handleMouseUp)
    }
  }, [resizingAsk])

  /** 切换资料时清空连续阅读上下文，避免右侧问答短暂使用上一份资料的页码和片段 ID。 */
  useEffect(() => {
    setReadingPageNo(null)
    setReadingPageChunkIds([])
    setReadingChunkIndex(0)
    updateReaderAskSelection(null)
  }, [selectedMaterialId])

  /**
   * 捕获阅读区划词：PDF.js 原生文字层、MinerU/OCR 后端文字层、普通文本片段都会进入这里。
   * 监听放在 ReaderPage 而不是右侧问答栏，保证问答栏收起或移动端抽屉未打开时也能保存选区。
   */
  useEffect(() => {
    let captureTimer: number | null = null
    const captureReaderSelection = () => {
      if (captureTimer !== null) window.clearTimeout(captureTimer)
      captureTimer = window.setTimeout(() => {
        captureTimer = null
        const selection = window.getSelection()
        if (!selection || !isReaderSelection(selection)) return
        const text = normalizeReaderSelectedText(selection.toString())
        if (
          text.length >= READER_SELECTION_MIN_LENGTH
          && text.length <= READER_SELECTION_MAX_LENGTH
        ) {
          updateReaderAskSelection(text)
        }
      }, 80)
    }

    document.addEventListener('selectionchange', captureReaderSelection)
    document.addEventListener('mouseup', captureReaderSelection)
    document.addEventListener('pointerup', captureReaderSelection)
    document.addEventListener('touchend', captureReaderSelection)
    document.addEventListener('keyup', captureReaderSelection)
    return () => {
      if (captureTimer !== null) window.clearTimeout(captureTimer)
      document.removeEventListener('selectionchange', captureReaderSelection)
      document.removeEventListener('mouseup', captureReaderSelection)
      document.removeEventListener('pointerup', captureReaderSelection)
      document.removeEventListener('touchend', captureReaderSelection)
      document.removeEventListener('keyup', captureReaderSelection)
    }
  }, [])

  // === 交互处理 ===

  /** 选择资料：更新选中 ID，重置片段索引为 0，关闭移动端面板 */
  const handleSelectMaterial = (id: string) => {
    setSelectedMaterialId(id)
    setSelectedChunkIndex(0)
    setMobilePanel(null)
    setNavigatorOpen(false)
    // 更新 URL 参数（push 模式，产生浏览器历史记录，支持后退）
    setSearchParams({ materialId: id }, { replace: false })
  }

  /** 选择片段：更新片段索引，关闭移动端面板，更新 URL */
  const handleSelectChunk = (index: number, options?: { view?: 'smart' | 'original'; pageNo?: number | null }) => {
    const safeIndex = Math.max(0, Math.min(chunks.length - 1, index))
    setSelectedChunkIndex(safeIndex)
    setMobilePanel(null)
    setNavigatorOpen(false)
    const targetChunk = chunks[safeIndex]
    if (selectedMaterialId && targetChunk) {
      // 用户主动点目录时使用 push，保留可后退的阅读路径。
      const nextParams = new URLSearchParams({ materialId: selectedMaterialId, chunkId: String(targetChunk.id) })
      const nextPageNo = options?.pageNo || pageNoForChunk(targetChunk, safeIndex, chunks, pages)
      if (nextPageNo && nextPageNo > 0) nextParams.set('pageNo', String(nextPageNo))
      if (options?.view) nextParams.set('view', options.view)
      setSearchParams(nextParams, { replace: false })
    }
  }

  const handleReadingContextChange = (context: ReaderReadingContext) => {
    setReadingPageNo(context.pageNo)
    setReadingPageChunkIds(context.chunkIds)
    setReadingChunkIndex(Math.max(0, Math.min(chunks.length - 1, context.chunkIndex)))
  }

  /**
   * 打开资料原文件
   * 通过后端接口获取临时访问链接（ticket），在新窗口打开
   */
  const handleOpenFile = async () => {
    if (!selectedMaterial) return
    // 先同步打开空白窗口，避免异步拿 ticket 后被浏览器弹窗策略拦截。
    const opened = window.open('', '_blank')
    try {
      const ticket = await createMaterialFileTicket(selectedMaterial.id)
      if (opened) {
        opened.location.href = ticket.url
      } else {
        window.alert('浏览器拦截了新窗口，请允许弹窗后再试')
      }
    } catch {
      opened?.close()
    }
  }

  // === 渲染 ===
  return (
    <motion.div
      className="flex h-full min-h-0 flex-col overflow-hidden lg:flex-row"
      initial={{ opacity: 0 }}
      animate={{ opacity: 1 }}
      transition={{ duration: 0.3 }}
    >
      {/* ---- 左侧窄导航：只保留高频入口，完整资料/片段列表放入弹窗。 ---- */}
      <aside className="hidden w-14 shrink-0 border-r border-slate-200 bg-white lg:flex lg:flex-col lg:items-center dark:border-slate-800 dark:bg-[#171a21]">
        <div className="flex flex-1 flex-col items-center gap-2 px-1.5 py-3">
          <button
            type="button"
            className="flex h-10 w-10 items-center justify-center rounded-xl border border-slate-200 bg-slate-50 text-slate-600 transition-colors hover:border-cyan-200 hover:bg-cyan-50 hover:text-cyan-700 dark:border-slate-800 dark:bg-slate-900 dark:text-slate-300 dark:hover:border-cyan-900 dark:hover:bg-cyan-950/40 dark:hover:text-cyan-200"
            title="选择资料"
            onClick={() => setNavigatorOpen(true)}
          >
            <Library className="h-4 w-4" />
          </button>
          <button
            type="button"
            className="flex h-10 w-10 items-center justify-center rounded-xl border border-slate-200 bg-slate-50 text-slate-600 transition-colors hover:border-cyan-200 hover:bg-cyan-50 hover:text-cyan-700 dark:border-slate-800 dark:bg-slate-900 dark:text-slate-300 dark:hover:border-cyan-900 dark:hover:bg-cyan-950/40 dark:hover:text-cyan-200"
            title="选择片段"
            onClick={() => setNavigatorOpen(true)}
          >
            <Layers3 className="h-4 w-4" />
          </button>
        </div>
        <div className="flex w-full flex-col items-center gap-1 border-t border-slate-200 px-1 py-3 dark:border-slate-800">
          <span className="text-[10px] font-medium text-slate-400" style={{ writingMode: 'vertical-rl' }}>
            {chunks.length ? `${readingChunkIndex + 1}/${chunks.length}` : '0/0'}
          </span>
          {currentChunkPageNo && <Badge variant="outline" className="px-1 py-0 text-[10px]">P{currentChunkPageNo}</Badge>}
        </div>
      </aside>

      <Dialog open={navigatorOpen} onOpenChange={setNavigatorOpen}>
        <DialogContent className="max-h-[82vh] max-w-3xl overflow-hidden p-0">
          <DialogHeader className="border-b bg-slate-50 px-5 py-4 dark:border-slate-800 dark:bg-slate-950/60">
            <DialogTitle className="flex items-center gap-2 text-base">
              <ListTree className="h-4 w-4" />
              资料与片段
            </DialogTitle>
            <DialogDescription>
              选择资料或跳转片段，阅读区会同步定位到对应内容。
            </DialogDescription>
          </DialogHeader>
          <div className="h-[68vh] min-h-0">
            <ReaderToc
              materials={materials}
              chunks={chunks}
              selectedMaterialId={selectedMaterialId}
              selectedChunkIndex={selectedChunkIndex}
              onSelectMaterial={handleSelectMaterial}
              onSelectChunk={handleSelectChunk}
              className="border-0"
            />
          </div>
        </DialogContent>
      </Dialog>

      {/* ============================================ */}
      {/* ---- 中间：文档内容展示区（ReaderPaper） ---- */}
      {/* ============================================ */}
      <div className="flex min-h-0 min-w-0 flex-1 flex-col overflow-hidden">
        {/* 移动端顶部导航栏（显示资料名 + 目录/问答按钮） */}
        <div className="flex items-center justify-between gap-2 border-b bg-background px-3 py-2 lg:hidden">
          <Button variant="outline" size="sm" className="h-8 gap-1.5 px-2 text-xs" onClick={() => setMobilePanel('toc')}>
            <ListTree className="h-3.5 w-3.5" />
            目录
          </Button>
          <div className="min-w-0 flex-1 text-center">
            <p className="truncate text-xs font-medium">{selectedMaterial?.title || selectedMaterial?.originalName || '选择资料'}</p>
          </div>
          <Button variant="outline" size="sm" className="h-8 gap-1.5 px-2 text-xs" onClick={() => setMobilePanel('ask')}>
            <Bot className="h-3.5 w-3.5" />
            问答
          </Button>
        </div>
        {/* 文档内容区（有片段时显示 ReaderPaper，无片段时显示空状态） */}
        {currentChunk ? (
          <ReaderPaper
            chunk={currentChunk}
            chunks={chunks}
            pages={pages}
            material={selectedMaterial}
            progress={chunks.length > 0 ? (readingChunkIndex + 1) / chunks.length : 0}
            onSelectChunk={handleSelectChunk}
            onReadingContextChange={handleReadingContextChange}
            onOpenFile={selectedMaterial ? handleOpenFile : undefined}
            targetPageNo={currentChunkPageNo ?? currentPage?.pageNo ?? requestedPageNo ?? null}
          />
        ) : selectedMaterialId && (chunksLoading || chunksFetching) ? (
          <div className="flex-1 flex items-center justify-center text-muted-foreground">
            <div className="text-center">
              <Loader2 className="h-9 w-9 mx-auto mb-3 animate-spin opacity-60" />
              <p className="text-sm">正在加载资料内容...</p>
            </div>
          </div>
        ) : (
          <div className="flex-1 flex items-center justify-center text-muted-foreground">
            <div className="text-center">
              <FileText className="h-10 w-10 mx-auto mb-3 opacity-40" />
              <p className="text-sm">选择一份资料开始阅读</p>
            </div>
          </div>
        )}
      </div>

      {/* ============================================ */}
      {/* ---- 右侧：AI 问答面板（ReaderAsk） ---- */}
      {/* ============================================ */}
      {/* 桌面问答栏常驻挂载：收起时只隐藏宽面板，不卸载 ReaderAsk，避免展开后丢失当前会话。 */}
      <div
        role="separator"
        aria-orientation="vertical"
        aria-label="调整问答栏宽度"
        className={cn(
          'group relative hidden w-5 shrink-0 cursor-col-resize items-center justify-center border-l border-slate-200 bg-white text-slate-400 transition-colors hover:bg-slate-50 hover:text-slate-700 lg:flex dark:border-slate-800 dark:bg-[#171a21] dark:hover:bg-slate-900 dark:hover:text-slate-200',
          !askOpen && 'w-0 cursor-default border-l-0',
          resizingAsk && 'bg-cyan-50 text-cyan-700 dark:bg-cyan-950/30 dark:text-cyan-200',
        )}
        onMouseDown={(event) => {
          if (!askOpen) return
          event.preventDefault()
          dragRatioRef.current = askRatio
          setResizingAsk(true)  // 开始拖拽
        }}
      >
        {askOpen && (
          <div className="flex h-16 w-3 items-center justify-center rounded-full bg-slate-100 text-slate-500 transition-colors group-hover:bg-cyan-50 group-hover:text-cyan-700 dark:bg-slate-800 dark:text-slate-300 dark:group-hover:bg-cyan-950/40 dark:group-hover:text-cyan-200">
            <GripVertical className="h-5 w-5" />
          </div>
        )}
        {askOpen && (
          <button
            type="button"
            className="absolute left-[-13px] top-5 flex h-9 w-6 cursor-pointer items-center justify-center rounded-full border border-slate-200 bg-white text-slate-500 shadow-sm transition hover:bg-slate-50 hover:text-slate-900 dark:border-slate-800 dark:bg-slate-900 dark:text-slate-300 dark:hover:bg-slate-800 dark:hover:text-white"
            title="隐藏问答栏"
            onMouseDown={(event) => event.stopPropagation()}  // 防止触发拖拽
            onClick={() => setAskOpen(false)}
          >
            <PanelRightClose className="h-4 w-4" />
          </button>
        )}
      </div>

      <aside
        ref={askAsideRef}
        className={cn(
          'hidden min-h-0 shrink-0 overflow-hidden lg:block',
          !resizingAsk && 'transition-[width] duration-200',
        )}
        style={{ width: askOpen ? askWidth : 32 }}
      >
        <div className={cn('flex h-full min-h-0 flex-col', !askOpen && 'hidden')}>
          <div className="flex h-9 shrink-0 items-center justify-between gap-2 border-b border-slate-200 bg-white px-3 dark:border-slate-800 dark:bg-[#171a21]">
            <span className="text-[11px] font-medium text-slate-500 dark:text-slate-400">问答占比</span>
            <div className="flex items-center gap-1">
              {ASK_RATIO_PRESETS.map((preset) => (
                <button
                  key={preset.value}
                  type="button"
                  className={cn(
                    'rounded-md px-2 py-1 text-[11px] transition-colors',
                    Math.abs(askRatio - preset.value) < 0.015
                      ? 'bg-cyan-50 text-cyan-700 dark:bg-cyan-950/40 dark:text-cyan-200'
                      : 'text-slate-500 hover:bg-slate-100 hover:text-slate-900 dark:text-slate-400 dark:hover:bg-slate-900 dark:hover:text-slate-100',
                  )}
                  onClick={() => setAskRatio(preset.value)}
                >
                  {preset.label}
                </button>
              ))}
            </div>
          </div>
          <ReaderAsk
            material={selectedMaterial}
            chunk={readingChunk}
            chunks={chunks}
            currentPageNo={askCurrentPageNo}
            currentPageChunkIds={askCurrentPageChunkIds}
            onNavigateToChunk={handleSelectChunk}
            className="lg:border-l-0"
          />
        </div>
        {!askOpen && (
          <button
            type="button"
            className="mt-5 flex h-9 w-7 translate-x-[1px] items-center justify-center rounded-l-full border border-r-0 border-slate-200 bg-white text-slate-500 shadow-sm transition-colors hover:bg-slate-50 hover:text-slate-900 dark:border-slate-800 dark:bg-slate-900 dark:text-slate-300 dark:hover:bg-slate-800 dark:hover:text-white"
            title="展开问答栏"
            onClick={() => setAskOpen(true)}
          >
            <PanelRightOpen className="h-4 w-4" />
          </button>
        )}
      </aside>

      {/* ============================================ */}
      {/* ---- 移动端底部抽屉面板（目录/问答） ---- */}
      {/* ============================================ */}
      {mobilePanel && (
        /* 遮罩层（点击关闭面板） */
        <div className="fixed inset-0 z-50 bg-black/35 lg:hidden" onClick={() => setMobilePanel(null)}>
          <div
            className={cn(
              'absolute bottom-0 left-0 right-0 flex max-h-[82dvh] min-h-[45dvh] flex-col overflow-hidden rounded-t-2xl bg-background shadow-2xl',
              mobilePanel === 'toc' && 'top-auto',
            )}
            onClick={(event) => event.stopPropagation()}  // 防止点击面板内容时关闭
          >
            {/* 抽屉头部（标题 + 关闭按钮） */}
            <div className="flex h-12 shrink-0 items-center justify-between border-b px-4">
              <span className="text-sm font-semibold">{mobilePanel === 'toc' ? '资料目录' : '资料问答'}</span>
              <Button variant="ghost" size="icon" className="h-8 w-8" onClick={() => setMobilePanel(null)}>
                <X className="h-4 w-4" />
              </Button>
            </div>
            {/* 抽屉内容（根据面板类型显示目录或问答） */}
            {mobilePanel === 'toc' ? (
              <ReaderToc
                materials={materials}
                chunks={chunks}
                selectedMaterialId={selectedMaterialId}
                selectedChunkIndex={selectedChunkIndex}
                onSelectMaterial={handleSelectMaterial}
                onSelectChunk={handleSelectChunk}
              />
            ) : (
              <ReaderAsk
                material={selectedMaterial}
                chunk={readingChunk}
                chunks={chunks}
                currentPageNo={askCurrentPageNo}
                currentPageChunkIds={askCurrentPageChunkIds}
                onNavigateToChunk={handleSelectChunk}
              />
            )}
          </div>
        </div>
      )}
    </motion.div>
  )
}

/** 将问答区比例限制在有效百分比内；不再使用固定像素上下限，只防止拖到 0 后无法恢复。 */
function normalizeAskRatio(value: number) {
  if (!Number.isFinite(value)) return DEFAULT_ASK_RATIO
  return Math.max(0.05, Math.min(0.95, Number(value.toFixed(3))))
}

/** 根据当前窗口把问答比例转成像素宽度，宽度完全由比例决定。 */
function askWidthFromRatio(ratio: number) {
  if (typeof window === 'undefined') return 480
  return Math.round(window.innerWidth * normalizeAskRatio(ratio))
}

/** 拖拽时由像素宽度反推保存比例，刷新或窗口变化后仍能保持相近占比。 */
function ratioFromAskWidth(width: number) {
  if (typeof window === 'undefined' || window.innerWidth <= 0) return DEFAULT_ASK_RATIO
  return normalizeAskRatio(width / window.innerWidth)
}

function parsePositiveInt(value: string | null) {
  const parsed = Number(value)
  return Number.isInteger(parsed) && parsed > 0 ? parsed : null
}

/** 根据页码查找最适合跳转的片段，优先使用 chunk.pageNo，其次使用页面 chunkIds，最后按页/片段比例估算。 */
function chunkIndexForPageNo(pageNo: number, chunks: MaterialChunk[], pages: MaterialPage[]) {
  const directIndex = chunks.findIndex((chunk) => Number(chunk.pageNo) === pageNo)
  if (directIndex >= 0) return directIndex
  const page = pages.find((candidate) => candidate.pageNo === pageNo)
  if (page) {
    const pageChunkIds = new Set(page.chunkIds.map(String))
    const mappedIndex = chunks.findIndex((chunk) => pageChunkIds.has(String(chunk.id)))
    if (mappedIndex >= 0) return mappedIndex
    const pageIndex = pages.findIndex((candidate) => candidate.pageNo === pageNo)
    if (pageIndex >= 0 && chunks.length > 0) {
      return Math.min(chunks.length - 1, Math.floor((pageIndex * chunks.length) / Math.max(1, pages.length)))
    }
  }
  return -1
}

function pageNoForChunk(
  chunk: MaterialChunk | null,
  chunkIndex: number,
  chunks: MaterialChunk[],
  pages: MaterialPage[],
) {
  if (!chunk) return null
  const directPageNo = Number(chunk.pageNo)
  if (Number.isFinite(directPageNo) && directPageNo > 0) return directPageNo
  const mappedPage = pages.find((page) => page.chunkIds.map(String).includes(String(chunk.id)))
  if (mappedPage?.pageNo) return mappedPage.pageNo
  if (!pages.length || !chunks.length || chunkIndex < 0) return null
  const pageIndex = Math.min(pages.length - 1, Math.floor((chunkIndex * pages.length) / chunks.length))
  return pages[pageIndex]?.pageNo ?? null
}
