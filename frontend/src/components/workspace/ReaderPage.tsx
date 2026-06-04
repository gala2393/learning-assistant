/**
 * ReaderPage — 文档阅读器页面。
 *
 * 路由：/workspace/reader
 *
 * 三栏布局：
 * - 左侧：目录/分块列表（ReaderToc），可折叠
 * - 中间：文档内容展示区（ReaderPaper），支持 PDF 预览和纯文本模式
 * - 右侧：AI 问答面板（ReaderAsk），支持选中文本提问
 *
 * 功能：
 * 1. 资料选择：从 URL 参数 materialId 读取当前资料
 * 2. 分块导航：点击左侧目录项跳转到对应分块
 * 3. PDF 预览：显示页面图片，支持缩放和翻页
 * 4. 纯文本模式：直接显示分块文本，支持内嵌图片
 * 5. 选中文本提问：在文档中选中 5-500 字符的文字，自动填入问答面板
 * 6. 推荐问题：首次加载时 AI 根据当前分块生成推荐问题
 * 7. URL 同步：materialId 和 chunkId 通过 URL 参数保持
 * 8. 右侧面板宽度可拖拽调整（保存到 localStorage）
 * 9. 移动端：底部 Tab 切换目录和问答面板
 */
import { useEffect, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { motion } from 'framer-motion'
import { ReaderToc } from './ReaderToc'
import { ReaderPaper } from './ReaderPaper'
import { ReaderAsk } from './ReaderAsk'
import { useMaterials, useMaterialChunks, useMaterialPages, createMaterialFileTicket } from '@/api/materials'
import { Bot, FileText, GripVertical, ListTree, PanelLeftClose, PanelLeftOpen, X } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'

const DEFAULT_ASK_WIDTH = 480

export function ReaderPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const { data: materials = [] } = useMaterials()
  const [selectedMaterialId, setSelectedMaterialId] = useState<string | null>(() => searchParams.get('materialId'))
  const [selectedChunkIndex, setSelectedChunkIndex] = useState(0)
  const [mobilePanel, setMobilePanel] = useState<'toc' | 'ask' | null>(null)
  const [tocOpen, setTocOpen] = useState(true)
  const [askWidth, setAskWidth] = useState(() => {
    const saved = Number(window.localStorage.getItem('reader-ask-width'))
    return Number.isFinite(saved) && saved > 0 ? saved : DEFAULT_ASK_WIDTH
  })
  const [resizingAsk, setResizingAsk] = useState(false)

  const { data: chunks = [] } = useMaterialChunks(selectedMaterialId)
  const { data: pages = [] } = useMaterialPages(selectedMaterialId)

  const selectedMaterial = materials.find((m) => m.id === selectedMaterialId) || null
  const currentChunk = chunks[selectedChunkIndex] || null
  const currentPage = pages.find((page) => page.pageNo === currentChunk?.pageNo)
    || pages.find((page) => page.chunkIds.map(String).includes(String(currentChunk?.id)))
    || pages[0]

  useEffect(() => {
    const materialId = searchParams.get('materialId')
    if (materialId && materialId !== selectedMaterialId) {
      setSelectedMaterialId(materialId)
      setSelectedChunkIndex(0)
    }
  }, [searchParams, selectedMaterialId])

  useEffect(() => {
    const chunkId = searchParams.get('chunkId')
    if (!chunkId || chunks.length === 0) return
    const index = chunks.findIndex((chunk) => String(chunk.id) === chunkId)
    if (index >= 0 && index !== selectedChunkIndex) {
      setSelectedChunkIndex(index)
    }
  }, [chunks, searchParams, selectedChunkIndex])

  useEffect(() => {
    if (!selectedMaterialId || !currentChunk) return
    const currentMaterialParam = searchParams.get('materialId')
    const currentChunkParam = searchParams.get('chunkId')
    if (currentMaterialParam === selectedMaterialId && currentChunkParam === String(currentChunk.id)) return
    setSearchParams({ materialId: selectedMaterialId, chunkId: String(currentChunk.id) }, { replace: true })
  }, [currentChunk, searchParams, selectedMaterialId, setSearchParams])

  useEffect(() => {
    const handleResize = () => {
      setAskWidth((width) => clampAskWidth(width, tocOpen))
    }
    handleResize()
    window.addEventListener('resize', handleResize)
    return () => window.removeEventListener('resize', handleResize)
  }, [tocOpen])

  useEffect(() => {
    window.localStorage.setItem('reader-ask-width', String(Math.round(askWidth)))
  }, [askWidth])

  useEffect(() => {
    if (!resizingAsk) return

    const handleMouseMove = (event: MouseEvent) => {
      setAskWidth(clampAskWidth(window.innerWidth - event.clientX, tocOpen))
    }
    const handleMouseUp = () => setResizingAsk(false)

    document.body.style.cursor = 'col-resize'
    document.body.style.userSelect = 'none'
    window.addEventListener('mousemove', handleMouseMove)
    window.addEventListener('mouseup', handleMouseUp)

    return () => {
      document.body.style.cursor = ''
      document.body.style.userSelect = ''
      window.removeEventListener('mousemove', handleMouseMove)
      window.removeEventListener('mouseup', handleMouseUp)
    }
  }, [resizingAsk, tocOpen])

  const handleSelectMaterial = (id: string) => {
    setSelectedMaterialId(id)
    setSelectedChunkIndex(0)
    setMobilePanel(null)
    setSearchParams({ materialId: id }, { replace: false })
  }

  const handleSelectChunk = (index: number) => {
    setSelectedChunkIndex(index)
    setMobilePanel(null)
    const targetChunk = chunks[index]
    if (selectedMaterialId && targetChunk) {
      setSearchParams({ materialId: selectedMaterialId, chunkId: String(targetChunk.id) }, { replace: false })
    }
  }

  const handlePrev = () => {
    handleSelectChunk(Math.max(0, selectedChunkIndex - 1))
  }

  const handleNext = () => {
    handleSelectChunk(Math.min(chunks.length - 1, selectedChunkIndex + 1))
  }

  const handleOpenFile = async () => {
    if (!selectedMaterial) return
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
      // Keep reader focused if the browser blocks the new tab.
    }
  }

  return (
    <motion.div
      className="flex h-full min-h-0 flex-col overflow-hidden lg:flex-row"
      initial={{ opacity: 0 }}
      animate={{ opacity: 1 }}
      transition={{ duration: 0.3 }}
    >
      {tocOpen ? (
        <aside className="relative hidden w-72 shrink-0 lg:block">
          <ReaderToc
            materials={materials}
            chunks={chunks}
            selectedMaterialId={selectedMaterialId}
            selectedChunkIndex={selectedChunkIndex}
            onSelectMaterial={handleSelectMaterial}
            onSelectChunk={handleSelectChunk}
          />
          <Button
            type="button"
            variant="outline"
            size="icon"
            className="absolute right-[-14px] top-4 z-20 h-8 w-7 rounded-full border bg-background shadow-sm"
            title="隐藏资料栏"
            onClick={() => setTocOpen(false)}
          >
            <PanelLeftClose className="h-4 w-4" />
          </Button>
        </aside>
      ) : (
        <div className="hidden w-4 shrink-0 border-r bg-muted/20 lg:flex">
          <button
            type="button"
            className="mt-4 flex h-10 w-7 translate-x-[-1px] items-center justify-center rounded-r-lg border border-l-0 bg-background text-muted-foreground shadow-sm transition-colors hover:bg-muted hover:text-foreground"
            title="展开资料栏"
            onClick={() => setTocOpen(true)}
          >
            <PanelLeftOpen className="h-4 w-4" />
          </button>
        </div>
      )}

      <div className="flex min-h-0 min-w-0 flex-1 flex-col overflow-hidden">
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

      <div
        role="separator"
        aria-orientation="vertical"
        aria-label="调整问答栏宽度"
        className={cn(
          'hidden w-2 shrink-0 cursor-col-resize items-center justify-center border-l border-r bg-background/80 text-muted-foreground transition-colors hover:bg-primary/10 hover:text-primary lg:flex',
          resizingAsk && 'bg-primary/10 text-primary',
        )}
        onMouseDown={(event) => {
          event.preventDefault()
          setResizingAsk(true)
        }}
      >
        <GripVertical className="h-5 w-5" />
      </div>

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

      {mobilePanel && (
        <div className="fixed inset-0 z-50 bg-black/35 lg:hidden" onClick={() => setMobilePanel(null)}>
          <div
            className={cn(
              'absolute bottom-0 left-0 right-0 flex max-h-[82dvh] min-h-[45dvh] flex-col overflow-hidden rounded-t-2xl bg-background shadow-2xl',
              mobilePanel === 'toc' && 'top-auto',
            )}
            onClick={(event) => event.stopPropagation()}
          >
            <div className="flex h-12 shrink-0 items-center justify-between border-b px-4">
              <span className="text-sm font-semibold">{mobilePanel === 'toc' ? '资料目录' : '资料问答'}</span>
              <Button variant="ghost" size="icon" className="h-8 w-8" onClick={() => setMobilePanel(null)}>
                <X className="h-4 w-4" />
              </Button>
            </div>
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

function clampAskWidth(width: number, tocOpen: boolean) {
  const reservedWidth = tocOpen ? 560 : 440
  const maxWidth = Math.min(760, Math.max(340, window.innerWidth - reservedWidth))
  const minWidth = Math.min(360, maxWidth)
  return Math.max(minWidth, Math.min(maxWidth, Math.round(width)))
}
