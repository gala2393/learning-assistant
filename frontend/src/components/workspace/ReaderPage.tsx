/**
 * ReaderPage -- 文档阅读器页面（核心页面）
 *
 * 【路由】/workspace/reader
 *
 * 【页面布局】
 * 三栏响应式布局：
 * - 左侧（桌面端 288px）：目录/分块列表（ReaderToc），可折叠
 *   - 上半部分：资料列表（切换不同资料）
 *   - 下半部分：片段网格（跳转到指定片段）
 * - 中间（自适应宽度）：文档内容展示区（ReaderPaper）
 *   - 支持两种阅读模式：
 *     1. 原文预览模式：PDF iframe / Word 转换预览 / 文本分段预览
 *     2. 智能阅读模式：页面图片预览 + 缩放 / 解析后文本 + 内嵌图片
 *   - 底部：翻页/翻片段导航栏
 * - 右侧（可拖拽调整宽度，默认 480px）：AI 问答面板（ReaderAsk）
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
 *    - 点击左侧目录项切换片段
 *    - 底部"上一片段/下一片段"或"上一页/下一页"翻页
 *
 * 2. PDF 预览：
 *    - 后端将 PDF 转为页面图片（page images）
 *    - 前端通过 MaterialImage 组件加载（需要 JWT 认证）
 *    - 支持缩放（70%~180%）
 *    - 自动预加载相邻页面图片
 *
 * 3. 选中文本提问：
 *    - 在文档中选中 5-500 字符的文字
 *    - 自动填入右侧问答面板的选中文本区域
 *    - 可围绕选中内容向 AI 追问
 *
 * 4. 右侧面板宽度调整：
 *    - 拖拽中间分隔条可调整右侧问答栏宽度
 *    - 宽度保存到 localStorage，下次打开时恢复
 *    - 限制最小/最大宽度，响应窗口大小变化
 *
 * 5. URL 同步：
 *    - materialId 和 chunkId 通过 URL 参数保持同步
 *    - 切换资料或片段时更新 URL
 *    - 支持通过 URL 直接定位到特定资料的特定片段
 */
import { useEffect, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { motion } from 'framer-motion'
import { ReaderToc } from './ReaderToc'
import { ReaderPaper } from './ReaderPaper'
import { ReaderAsk } from './ReaderAsk'
import { useMaterials, useMaterialChunks, useMaterialPages, createMaterialFileTicket } from '@/api/materials'
import {
  Bot,
  FileText,
  GripVertical,
  ListTree,
  PanelLeftClose,
  PanelLeftOpen,
  PanelRightClose,
  PanelRightOpen,
  X,
} from 'lucide-react'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'

/** 右侧问答栏的默认宽度（像素） */
const DEFAULT_ASK_WIDTH = 480

export function ReaderPage() {
  // === URL 参数 ===
  const [searchParams, setSearchParams] = useSearchParams()

  // === 数据获取 ===
  /** 所有资料列表 */
  const { data: materials = [] } = useMaterials()

  // === 状态管理 ===
  /** 当前选中的资料 ID（从 URL 参数初始化） */
  const [selectedMaterialId, setSelectedMaterialId] = useState<string | null>(() => searchParams.get('materialId'))
  /** 当前选中的片段索引（0-based） */
  const [selectedChunkIndex, setSelectedChunkIndex] = useState(0)
  /** 移动端当前打开的面板（'toc' 目录 | 'ask' 问答 | null 关闭） */
  const [mobilePanel, setMobilePanel] = useState<'toc' | 'ask' | null>(null)
  /** 左侧目录栏是否展开（桌面端） */
  const [tocOpen, setTocOpen] = useState(true)
  /** 右侧问答栏是否展开（桌面端） */
  const [askOpen, setAskOpen] = useState(true)
  /**
   * 右侧问答栏宽度（像素）
   * 从 localStorage 读取上次保存的宽度，默认 480px
   */
  const [askWidth, setAskWidth] = useState(() => {
    const saved = Number(window.localStorage.getItem('reader-ask-width'))
    return Number.isFinite(saved) && saved > 0 ? saved : DEFAULT_ASK_WIDTH
  })
  /** 是否正在拖拽调整右侧问答栏宽度 */
  const [resizingAsk, setResizingAsk] = useState(false)

  // === 数据获取（依赖选中的资料） ===
  /** 当前资料的所有片段列表 */
  const { data: chunks = [] } = useMaterialChunks(selectedMaterialId)
  /** 当前资料的所有页面列表（用于页面预览模式） */
  const { data: pages = [] } = useMaterialPages(selectedMaterialId)

  // === URL 参数解析 ===
  const materialParam = searchParams.get('materialId')
  const chunkParam = searchParams.get('chunkId')
  /** 根据 URL 中的 chunkId 查找对应的片段索引 */
  const requestedChunkIndex = chunkParam
    ? chunks.findIndex((chunk) => String(chunk.id) === chunkParam)
    : -1

  // === 派生值 ===
  /** 当前选中的资料对象 */
  const selectedMaterial = materials.find((m) => m.id === selectedMaterialId) || null
  /** 当前选中的片段对象 */
  const currentChunk = chunks[selectedChunkIndex] || null
  /**
   * 当前页面对象
   * 优先按片段的 pageNo 匹配，其次按 chunkId 包含关系匹配，最后取第一页
   */
  const currentPage = pages.find((page) => page.pageNo === currentChunk?.pageNo)
    || pages.find((page) => page.chunkIds.map(String).includes(String(currentChunk?.id)))
    || pages[0]

  // === 副作用：URL 参数同步 ===

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

  /**
   * 将当前选中的资料和片段同步到 URL 参数
   * 使用 replace 模式避免产生浏览器历史记录
   */
  useEffect(() => {
    if (!selectedMaterialId || !currentChunk) return
    // 避免不必要的 URL 更新（如果参数已经一致则跳过）
    if (materialParam && materialParam !== selectedMaterialId) return
    if (chunkParam && chunkParam !== String(currentChunk.id) && requestedChunkIndex >= 0) return
    if (materialParam === selectedMaterialId && chunkParam === String(currentChunk.id)) return
    // replace 同步内部阅读位置，避免翻片段时污染浏览器返回栈。
    setSearchParams({ materialId: selectedMaterialId, chunkId: String(currentChunk.id) }, { replace: true })
  }, [chunkParam, currentChunk, materialParam, requestedChunkIndex, selectedMaterialId, setSearchParams])

  // === 副作用：右侧面板宽度调整 ===

  /**
   * 监听窗口大小变化，重新计算问答栏宽度
   * 确保在窗口缩小时问答栏不会超出可用空间
   */
  useEffect(() => {
    const handleResize = () => {
      setAskWidth((width) => clampAskWidth(width, tocOpen))
    }
    handleResize()
    window.addEventListener('resize', handleResize)
    return () => window.removeEventListener('resize', handleResize)
  }, [tocOpen])

  /** 问答栏宽度变化时保存到 localStorage */
  useEffect(() => {
    window.localStorage.setItem('reader-ask-width', String(Math.round(askWidth)))
  }, [askWidth])

  /**
   * 拖拽调整右侧问答栏宽度
   *
   * 拖拽原理：
   * 1. 鼠标按下分隔条 -> setResizingAsk(true)
   * 2. 监听 mousemove 事件 -> 根据鼠标 X 坐标计算新宽度
   * 3. 鼠标松开 -> setResizingAsk(false)，结束拖拽
   * 4. 拖拽过程中禁用文本选择和修改光标样式
   */
  useEffect(() => {
    if (!resizingAsk) return

    const handleMouseMove = (event: MouseEvent) => {
      // 新宽度 = 窗口宽度 - 鼠标 X 坐标（从右向左计算）
      setAskWidth(clampAskWidth(window.innerWidth - event.clientX, tocOpen))
    }
    const handleMouseUp = () => setResizingAsk(false)

    // 拖拽过程中：光标变为 col-resize，禁止文本选择
    document.body.style.cursor = 'col-resize'
    document.body.style.userSelect = 'none'
    window.addEventListener('mousemove', handleMouseMove)
    window.addEventListener('mouseup', handleMouseUp)

    return () => {
      // 恢复默认样式
      document.body.style.cursor = ''
      document.body.style.userSelect = ''
      window.removeEventListener('mousemove', handleMouseMove)
      window.removeEventListener('mouseup', handleMouseUp)
    }
  }, [resizingAsk, tocOpen])

  // === 交互处理 ===

  /** 选择资料：更新选中 ID，重置片段索引为 0，关闭移动端面板 */
  const handleSelectMaterial = (id: string) => {
    setSelectedMaterialId(id)
    setSelectedChunkIndex(0)
    setMobilePanel(null)
    // 更新 URL 参数（push 模式，产生浏览器历史记录，支持后退）
    setSearchParams({ materialId: id }, { replace: false })
  }

  /** 选择片段：更新片段索引，关闭移动端面板，更新 URL */
  const handleSelectChunk = (index: number) => {
    setSelectedChunkIndex(index)
    setMobilePanel(null)
    const targetChunk = chunks[index]
    if (selectedMaterialId && targetChunk) {
      // 用户主动点目录时使用 push，保留可后退的阅读路径。
      setSearchParams({ materialId: selectedMaterialId, chunkId: String(targetChunk.id) }, { replace: false })
    }
  }

  /** 向前翻片段 */
  const handlePrev = () => {
    handleSelectChunk(Math.max(0, selectedChunkIndex - 1))
  }

  /** 向后翻片段 */
  const handleNext = () => {
    handleSelectChunk(Math.min(chunks.length - 1, selectedChunkIndex + 1))
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
      {/* ============================================ */}
      {/* ---- 左侧：目录/分块列表（ReaderToc） ---- */}
      {/* ============================================ */}
      {tocOpen ? (
        /* 目录展开状态 */
        <aside className="relative hidden w-72 shrink-0 lg:block">
          <ReaderToc
            materials={materials}
            chunks={chunks}
            selectedMaterialId={selectedMaterialId}
            selectedChunkIndex={selectedChunkIndex}
            onSelectMaterial={handleSelectMaterial}
            onSelectChunk={handleSelectChunk}
          />
          {/* 收起目录的按钮（右边缘中间位置） */}
          <Button
            type="button"
            variant="ghost"
            size="icon"
            className="absolute right-[-13px] top-5 z-20 h-9 w-6 rounded-full border border-slate-200 bg-white text-slate-500 shadow-sm transition hover:bg-slate-50 hover:text-slate-900 dark:border-slate-800 dark:bg-slate-900 dark:text-slate-300 dark:hover:bg-slate-800 dark:hover:text-white"
            title="隐藏资料栏"
            onClick={() => setTocOpen(false)}
          >
            <PanelLeftClose className="h-4 w-4" />
          </Button>
        </aside>
      ) : (
        /* 目录收起状态：只显示一个展开按钮 */
        <div className="hidden w-8 shrink-0 border-r border-slate-200 bg-white lg:flex dark:border-slate-800 dark:bg-[#171a21]">
          <button
            type="button"
            className="mt-5 flex h-9 w-7 translate-x-[-1px] items-center justify-center rounded-r-full border border-l-0 border-slate-200 bg-white text-slate-500 shadow-sm transition-colors hover:bg-slate-50 hover:text-slate-900 dark:border-slate-800 dark:bg-slate-900 dark:text-slate-300 dark:hover:bg-slate-800 dark:hover:text-white"
            title="展开资料栏"
            onClick={() => setTocOpen(true)}
          >
            <PanelLeftOpen className="h-4 w-4" />
          </button>
        </div>
      )}

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
            progress={chunks.length > 0 ? (selectedChunkIndex + 1) / chunks.length : 0}
            canPrev={selectedChunkIndex > 0}
            canNext={selectedChunkIndex < chunks.length - 1}
            onPrev={handlePrev}
            onNext={handleNext}
            onSelectChunk={handleSelectChunk}
            onOpenFile={selectedMaterial ? handleOpenFile : undefined}
          />
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
      {askOpen ? (
        <>
          {/* ---- 可拖拽的分隔条 ---- */}
          <div
            role="separator"
            aria-orientation="vertical"
            aria-label="调整问答栏宽度"
            className={cn(
              'relative hidden w-3 shrink-0 cursor-col-resize items-center justify-center border-l border-slate-200 bg-white text-slate-400 transition-colors hover:bg-slate-50 hover:text-slate-700 lg:flex dark:border-slate-800 dark:bg-[#171a21] dark:hover:bg-slate-900 dark:hover:text-slate-200',
              resizingAsk && 'bg-cyan-50 text-cyan-700 dark:bg-cyan-950/30 dark:text-cyan-200',
            )}
            onMouseDown={(event) => {
              event.preventDefault()
              setResizingAsk(true)  // 开始拖拽
            }}
          >
            <GripVertical className="h-5 w-5" />
            {/* 收起问答栏的按钮（分隔条左侧） */}
            <button
              type="button"
              className="absolute left-[-13px] top-5 flex h-9 w-6 cursor-pointer items-center justify-center rounded-full border border-slate-200 bg-white text-slate-500 shadow-sm transition hover:bg-slate-50 hover:text-slate-900 dark:border-slate-800 dark:bg-slate-900 dark:text-slate-300 dark:hover:bg-slate-800 dark:hover:text-white"
              title="隐藏问答栏"
              onMouseDown={(event) => event.stopPropagation()}  // 防止触发拖拽
              onClick={() => setAskOpen(false)}
            >
              <PanelRightClose className="h-4 w-4" />
            </button>
          </div>

          {/* 问答面板（宽度由 askWidth 状态控制） */}
          <aside className="hidden min-h-0 shrink-0 lg:block" style={{ width: askWidth }}>
            <ReaderAsk
              material={selectedMaterial}
              chunk={currentChunk}
              chunks={chunks}
              currentPageNo={currentPage?.pageNo ?? currentChunk?.pageNo ?? null}
              currentPageChunkIds={currentPage?.chunkIds || []}
              onNavigateToChunk={handleSelectChunk}
              className="lg:border-l-0"
            />
          </aside>
        </>
      ) : (
        /* 问答栏收起状态：只显示一个展开按钮 */
        <div className="hidden w-8 shrink-0 border-l border-slate-200 bg-white lg:flex dark:border-slate-800 dark:bg-[#171a21]">
          <button
            type="button"
            className="mt-5 flex h-9 w-7 translate-x-[1px] items-center justify-center rounded-l-full border border-r-0 border-slate-200 bg-white text-slate-500 shadow-sm transition-colors hover:bg-slate-50 hover:text-slate-900 dark:border-slate-800 dark:bg-slate-900 dark:text-slate-300 dark:hover:bg-slate-800 dark:hover:text-white"
            title="展开问答栏"
            onClick={() => setAskOpen(true)}
          >
            <PanelRightOpen className="h-4 w-4" />
          </button>
        </div>
      )}

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
                chunk={currentChunk}
                chunks={chunks}
                currentPageNo={currentPage?.pageNo ?? currentChunk?.pageNo ?? null}
                currentPageChunkIds={currentPage?.chunkIds || []}
                onNavigateToChunk={handleSelectChunk}
              />
            )}
          </div>
        </div>
      )}
    </motion.div>
  )
}

/**
 * clampAskWidth -- 限制问答栏宽度在合理范围内
 *
 * @param width - 期望宽度
 * @param tocOpen - 左侧目录是否展开（影响可用空间）
 * @returns 限制后的宽度（像素）
 *
 * 计算逻辑：
 * - reservedWidth：左侧目录 + 中间内容区的最小保留宽度
 * - maxWidth：最大不超过 760px，且不能小于 340px
 * - minWidth：最小 360px 或 maxWidth（取较小者）
 */
function clampAskWidth(width: number, tocOpen: boolean) {
  const reservedWidth = tocOpen ? 560 : 440
  const maxWidth = Math.min(760, Math.max(340, window.innerWidth - reservedWidth))
  const minWidth = Math.min(360, maxWidth)
  return Math.max(minWidth, Math.min(maxWidth, Math.round(width)))
}
