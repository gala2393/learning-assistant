/**
 * 阅读器里的“边读边问”面板。
 *
 * 面板状态托管在 reader-ask-session 的模块级 store 中。这样用户切换路由或阅读页重渲染时，
 * 正在进行的 SSE 流式回答不会被组件卸载打断，重新进入页面也能恢复同一段问答。
 */
import { useCallback, useEffect, useMemo, useRef, useState, useSyncExternalStore } from 'react'
import { useNavigate } from 'react-router-dom'
import { Textarea } from '@/components/ui/textarea'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { getHistory, suggestQuestions, useLatestMaterialHistory, useMaterialHistory, useRagUsage } from '@/api/rag'
import { ChatThread } from './ChatThread'
import { Send, Bot, ArrowRight, Sparkles, Trash2, History, Plus, MessagesSquare, Loader2, RefreshCw, Pause } from 'lucide-react'
import { actionButtonBase, actionButtonIdle, actionButtonReady } from '@/lib/action-button-styles'
import { cn, truncate } from '@/lib/utils'
import { useAuth } from '@/context/AuthContext'
import { useToast } from '@/components/ui/toast'
import { LOGIN_REQUIRED_MESSAGE, redirectToLogin } from '@/lib/auth-gate'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { ScrollArea } from '@/components/ui/scroll-area'
import {
  ensureReaderAskMaterial,
  getReaderAskSnapshot,
  pauseReaderAskStream,
  restoreReaderAskHistory,
  startNewReaderAskConversation,
  startReaderAskStream,
  subscribeReaderAsk,
  updateReaderAskQuestion,
} from '@/lib/reader-ask-session'
import type { HistoryItem, Material, MaterialChunk, RagSource } from '@/types'

/** 边读边问的单次直接输入上限，与主聊天和后端 ChatRequest 保持一致。 */
const READER_ASK_INPUT_MAX_CHARS = 6000

interface ReaderAskProps {
  material: Material | null
  chunk: MaterialChunk | null
  chunks?: MaterialChunk[]
  currentPageNo?: number | null
  currentPageChunkIds?: Array<string | number>
  onNavigateToChunk?: (chunkIndex: number, options?: { view?: 'smart' | 'original'; pageNo?: number | null }) => void
  className?: string
}

/** 构建面板顶部的当前阅读位置提示。 */
function buildContextLabel(currentPageNo?: number | null) {
  const labels: string[] = []
  if (typeof currentPageNo === 'number' && currentPageNo > 0) labels.push(`当前页 P${currentPageNo}`)
  return labels.join(' · ')
}

export function ReaderAsk({
  material,
  chunk,
  chunks,
  currentPageNo,
  currentPageChunkIds,
  onNavigateToChunk,
  className,
}: ReaderAskProps) {
  const navigate = useNavigate()
  const { isAuthenticated } = useAuth()
  const { showToast } = useToast()

  const questionRef = useRef<HTMLTextAreaElement>(null)

  const askSession = useSyncExternalStore(
    subscribeReaderAsk,
    getReaderAskSnapshot,
    getReaderAskSnapshot,
  )
  const {
    question,
    loading,
    messages,
    sourcesByMessageId,
    errorByMessageId,
    conversationId,
    skipAutoRestoreForMaterialId,
  } = askSession

  /** AI 生成的推荐问题列表 */
  const [suggestedQuestions, setSuggestedQuestions] = useState<string[]>([])
  /** 当前是否打开资料历史会话弹窗。 */
  const [historyDialogOpen, setHistoryDialogOpen] = useState(false)
  /** 点击历史记录后异步加载详情；加载期间禁用重复点击，避免多次恢复互相覆盖。 */
  const [loadingHistoryId, setLoadingHistoryId] = useState<string | null>(null)

  // === 数据获取 ===
  /** 获取今日使用额度 */
  const { data: ragUsage } = useRagUsage()
  /** 当前资料最近一段问答历史，阅读器打开资料时用于自动恢复。 */
  const { data: latestMaterialHistory } = useLatestMaterialHistory(isAuthenticated ? material?.id || null : null)
  /** 当前资料的历史会话列表，供“历史”弹窗选择。 */
  const {
    data: materialHistoryItems = [],
    isFetching: materialHistoryFetching,
    refetch: refetchMaterialHistory,
  } = useMaterialHistory(historyDialogOpen && isAuthenticated && material?.id ? material.id : null)
  const usageLabel = ragUsage
    ? ragUsage.unlimited
      ? '今日问答：不限'
      : `今日剩余：${ragUsage.remainingToday ?? 0}/${ragUsage.dailyLimit}`
    : ''
  const usageExhausted = !!ragUsage && !ragUsage.unlimited && (ragUsage.remainingToday ?? 0) <= 0
  const requireLogin = useCallback(() => {
    if (isAuthenticated) return true
    showToast(LOGIN_REQUIRED_MESSAGE, 2000)
    redirectToLogin()
    return false
  }, [isAuthenticated, showToast])

  // === 派生值 ===
  /** 是否可以提问（有输入、未在加载、有资料、未超出使用额度） */
  const canAsk = question.trim().length > 0 && !loading && !!material && !usageExhausted
  /** 生成中时主按钮切换为暂停输出，保留当前已经收到的回答。 */
  const canPauseOutput = loading

  /** 上下文标签（"当前页 P3"） */
  const contextLabel = useMemo(
    () => buildContextLabel(currentPageNo),
    [currentPageNo],
  )
  /** 片段 ID -> 片段索引的映射（用于点击来源时跳转） */
  const threadMessages = useMemo(
    () => messages.map((msg) => ({
      ...msg,
      sources: msg.role === 'assistant' ? sourcesByMessageId[msg.id] || msg.sources : msg.sources,
      error: msg.role === 'assistant' ? errorByMessageId[msg.id] || msg.error : msg.error,
    })),
    [errorByMessageId, messages, sourcesByMessageId],
  )
  const chunkIndexById = useMemo(() => buildChunkIndexById(chunks || []), [chunks])

  // === 副作用 ===

  /** 切换资料时同步 store 的资料归属；同一资料重新挂载不会清空或中断正在生成的回答。 */
  useEffect(() => {
    if (!material?.id) return
    ensureReaderAskMaterial(material?.id || null)
    setLoadingHistoryId(null)
  }, [material?.id])

  /**
   * 历史弹窗打开后刷新当前资料的历史列表。
   * 放在 effect 中执行，确保 useMaterialHistory 已经拿到“弹窗打开 + 当前资料 ID”的 queryKey，
   * 避免按钮点击瞬间调用到弹窗打开前的旧 refetch。
   */
  useEffect(() => {
    if (!historyDialogOpen || !material?.id || !isAuthenticated) return
    refetchMaterialHistory()
  }, [historyDialogOpen, isAuthenticated, material?.id, refetchMaterialHistory])

  /**
   * 自动恢复当前资料最近一段问答。
   * 只在没有本地消息且用户没有主动开启新对话时执行，避免覆盖正在进行的新会话。
   */
  useEffect(() => {
    if (!material?.id || !latestMaterialHistory || messages.length > 0) return
    if (skipAutoRestoreForMaterialId === material.id) return
    restoreHistory(latestMaterialHistory)
  }, [latestMaterialHistory, material?.id, messages.length, skipAutoRestoreForMaterialId])

  /**
   * 当资料或片段变化时，请求推荐问题
   * AI 会根据当前片段内容生成相关的推荐问题
   */
  useEffect(() => {
    if (!isAuthenticated) {
      setSuggestedQuestions([])
      return
    }
    if (!material || !chunk) {
      setSuggestedQuestions([])
      return
    }
    suggestQuestions(material.id, chunk.id)
      .then(setSuggestedQuestions)
      .catch(() => setSuggestedQuestions([]))
  }, [isAuthenticated, material?.id, chunk?.id])

  // === 核心交互逻辑 ===

  /**
   * 点击检索来源时定位阅读区。
   * 优先按 chunkId 精确跳转；PDF/旧数据里 chunkId 可能不在当前列表，则按 pageNo 和摘录文本兜底。
   */
  const openSource = useCallback(
    (source: RagSource) => {
      const idx = locateSourceChunkIndex(source, chunks || [], chunkIndexById)
      if (idx !== undefined) {
        onNavigateToChunk?.(idx, { pageNo: source.pageNo || null })
        return
      }
      const pageNo = Number(source.pageNo)
      if (Number.isFinite(pageNo) && pageNo > 0) {
        onNavigateToChunk?.(locatePageChunkIndex(pageNo, chunks || []), { pageNo, view: 'smart' })
      }
    },
    [chunkIndexById, chunks, onNavigateToChunk],
  )

  /** 将某段历史会话恢复到问答面板，并记录 conversationId 供后续追问续接。 */
  function restoreHistory(history: HistoryItem) {
    restoreReaderAskHistory(history, material?.id || null)
  }

  /** 开启当前资料的新问答会话，不再沿用最近历史的 conversationId。 */
  const startNewConversation = () => {
    startNewReaderAskConversation(material?.id || null)
  }

  /**
   * 点击历史记录后恢复到边读边问面板。
   * 先用列表项立即回填一轮问答，保证用户有即时反馈；再请求详情补全同一 conversationId 下的多轮消息。
   */
  const selectHistory = async (item: HistoryItem) => {
    restoreHistory(item)
    setLoadingHistoryId(item.id)
    try {
      const detail = await getHistory(String(item.id))
      restoreHistory(detail)
      setHistoryDialogOpen(false)
    } catch (error) {
      showToast(error instanceof Error ? error.message : '历史详情加载失败，已恢复列表中的最近一轮对话', 2000)
      setHistoryDialogOpen(false)
    } finally {
      setLoadingHistoryId(null)
    }
  }

  /**
   * submitQuestion -- 提交问题的核心逻辑（SSE 流式请求）
   *
   * 完整流程：
   * 1. 校验输入有效性
   * 2. 生成唯一消息 ID
   * 3. 构建对话历史（最近 8 轮，排除错误和思考中的消息）
   * 4. 创建用户消息 + AI 占位消息（thinking 状态）
   * 5. 调用模块级 reader-ask-session 发起 SSE 连接
   * 6. 通过回调逐步更新消息：
   *    - onStatus: 显示状态文字（如"正在检索相关资料..."）
   *    - onChunk: 累积文本片段到缓冲区并更新 UI
   *    - onSources: 存储检索来源
   *    - onDone: 设置最终回答，清除 loading 状态
   *    - onError: 显示错误信息
   * 7. 完成后刷新使用额度缓存
   */
  const submitQuestion = useCallback((rawQuestion: string) => {
    if (!requireLogin()) return
    const q = rawQuestion.trim().slice(0, READER_ASK_INPUT_MAX_CHARS)
    if (!q || loading || !material) return
    startReaderAskStream({
      question: q,
      materialId: material.id,
      chunkId: chunk?.id,
      currentPageNo,
      currentPageChunkIds,
    })
  }, [chunk?.id, currentPageChunkIds, currentPageNo, loading, material, requireLogin])

  /** 提交问题 */
  const handleSubmit = () => {
    submitQuestion(question)
  }

  /** 键盘快捷键：Enter 提交（Shift+Enter 换行） */
  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      handleSubmit()
    }
  }

  // === 渲染 ===
  return (
    <div className={cn('flex h-full min-h-0 w-full shrink-0 flex-col overflow-hidden border-t bg-muted/10 lg:border-l lg:border-t-0', className)}>

      {/* ---- 面板头部：资料名、会话状态、历史入口和使用额度，压缩为紧凑信息区。 ---- */}
      <div className="space-y-1.5 border-b px-3 py-2">
        <div className="flex items-center justify-between gap-2">
          <div className="min-w-0">
            <p className="flex items-center gap-1 text-xs font-semibold text-muted-foreground">
              <Bot className="h-3.5 w-3.5" /> AI 问答
            </p>
            <p className="mt-0.5 truncate text-[11px] text-muted-foreground">
              {material?.title || material?.originalName || '未选择资料'}
            </p>
          </div>
          {usageLabel && (
            <Badge variant="secondary" className="rounded-full px-2 py-0.5 text-[10px] font-medium">
              {usageLabel}
            </Badge>
          )}
        </div>
        <div className="flex items-center justify-between gap-2">
          <Badge variant="outline" className="max-w-[52%] truncate text-[10px]">
            <MessagesSquare className="mr-1 h-3 w-3" />
            {conversationId ? '已恢复资料会话' : '新对话'}
          </Badge>
          <div className="flex shrink-0 items-center gap-1">
            <Button variant="outline" size="sm" className="h-8 px-2 text-[11px] md:h-7" onClick={startNewConversation} disabled={!material || loading}>
              <Plus className="mr-1 h-3 w-3" /> 新对话
            </Button>
            <Button
              variant="outline"
              size="sm"
              className="h-8 px-2 text-[11px] md:h-7"
              onClick={() => setHistoryDialogOpen(true)}
              disabled={!material}
            >
              <History className="mr-1 h-3 w-3" /> 历史
            </Button>
          </div>
        </div>
        {/* 当前上下文只保留一行摘要，避免把聊天区顶得过低。 */}
        {(contextLabel || chunk) && (
          <div className="flex min-w-0 items-center gap-2 text-[11px] text-muted-foreground">
            {contextLabel && (
            <Badge variant="secondary" className="text-[10px]">
              <Sparkles className="mr-1 h-3 w-3" /> {contextLabel}
            </Badge>
            )}
            {chunk && <span className="min-w-0 truncate">{truncate(chunk.chunkText, 56)}</span>}
          </div>
        )}
      </div>

      {/* ---- 对话消息流（复用 ChatThread 组件） ---- */}
      <div className="min-h-0 flex-1 overflow-hidden">
        <ChatThread messages={threadMessages} onOpenSource={openSource} />
      </div>

      {/* ---- 推荐问题（仅在无对话记录时显示） ---- */}
      {!messages.length && !loading && (
        <div className="px-3 pb-2">
          {suggestedQuestions.length > 0 && (
            <div className="mb-3 space-y-1.5">
              <p className="text-[10px] font-medium text-muted-foreground">推荐问题</p>
              {suggestedQuestions.map((q, i) => (
                <button
                  key={i}
                  className="block w-full rounded bg-muted/50 p-2 text-left text-xs text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
                  onClick={() => submitQuestion(q)}
                >
                  {q}
                </button>
              ))}
            </div>
          )}
        </div>
      )}

      {/* ---- 底部输入区域 ---- */}
      <div className="shrink-0 space-y-2 border-t p-3 pb-[max(env(safe-area-inset-bottom),0.75rem)] lg:pb-3">
        <Textarea
          ref={questionRef}
          value={question}
          onChange={(e) => updateReaderAskQuestion(e.target.value.slice(0, READER_ASK_INPUT_MAX_CHARS))}
          onKeyDown={handleKeyDown}
          maxLength={READER_ASK_INPUT_MAX_CHARS}
          placeholder={material
            ? usageExhausted
              ? '今日问答次数已用完'
              : '针对当前资料继续提问...'
            : '请先选择一份资料'}
          className="max-h-28 min-h-[56px] resize-none text-base md:min-h-[64px] md:text-sm"
          disabled={!material || usageExhausted}
        />
        {/* 提交和清空按钮 */}
        <div className="flex items-center gap-2">
          <Button
            size="sm"
            className={`h-9 flex-1 rounded-xl ${actionButtonBase} ${canAsk || canPauseOutput ? actionButtonReady : actionButtonIdle}`}
            onClick={canPauseOutput ? pauseReaderAskStream : handleSubmit}
            disabled={canPauseOutput ? false : !canAsk}
          >
            {canPauseOutput ? (
              <>
                <Pause className="mr-1 h-3.5 w-3.5" /> 暂停输出
              </>
            ) : (
              <>
                <Send className="mr-1 h-3.5 w-3.5" /> 提问
              </>
            )}
          </Button>
          {/* 清空按钮：清除所有消息和来源 */}
          <Button
            variant="outline"
            size="sm"
            onClick={startNewConversation}
            disabled={messages.length === 0}
          >
            <Trash2 className="mr-1 h-3.5 w-3.5" /> 新对话
          </Button>
        </div>
        <div className="text-right text-[10px] text-slate-400 dark:text-slate-500">
          {question.length}/{READER_ASK_INPUT_MAX_CHARS}
        </div>
        {/* "在聊天中继续"按钮：跳转到独立聊天页面，携带当前资料和片段上下文 */}
        <Button
          variant="outline"
          size="sm"
          className="w-full text-xs"
          onClick={() => {
            const params = new URLSearchParams()
            if (material?.id) params.set('materialId', material.id)
            if (chunk?.id) params.set('chunkId', chunk.id)
            if (currentPageNo && currentPageNo > 0) params.set('pageNo', String(currentPageNo))
            navigate(`/workspace/chat?${params.toString()}`)
          }}
        >
          在聊天中继续 <ArrowRight className="ml-1 h-3.5 w-3.5" />
        </Button>
      </div>
      <Dialog open={historyDialogOpen} onOpenChange={setHistoryDialogOpen}>
        <DialogContent className="max-h-[76vh] max-w-md overflow-hidden p-0">
          <DialogHeader className="border-b bg-slate-50 px-5 py-4 dark:border-slate-800 dark:bg-slate-950/60">
            <DialogTitle className="flex items-center gap-2 text-base">
              <History className="h-4 w-4" />
              资料问答历史
            </DialogTitle>
            <DialogDescription>
              选择一段历史会话继续追问，或关闭后使用新对话。
            </DialogDescription>
            <Button
              variant="outline"
              size="sm"
              className="mt-3 h-8 w-fit text-xs"
              onClick={() => refetchMaterialHistory()}
              disabled={materialHistoryFetching}
            >
              {materialHistoryFetching ? <Loader2 className="mr-1 h-3.5 w-3.5 animate-spin" /> : <RefreshCw className="mr-1 h-3.5 w-3.5" />}
              刷新历史
            </Button>
          </DialogHeader>
          <ScrollArea className="max-h-[56vh]">
            <div className="space-y-2 p-4">
              {materialHistoryFetching && materialHistoryItems.length === 0 ? (
                <p className="rounded-lg border border-dashed px-3 py-8 text-center text-xs text-muted-foreground">
                  正在加载历史记录...
                </p>
              ) : materialHistoryItems.length === 0 ? (
                <p className="rounded-lg border border-dashed px-3 py-8 text-center text-xs text-muted-foreground">
                  当前资料还没有历史对话
                </p>
              ) : materialHistoryItems.map((item) => (
                <button
                  key={item.id}
                  type="button"
                  className="block w-full rounded-lg border border-slate-200 bg-white px-3 py-2.5 text-left transition-colors hover:border-cyan-200 hover:bg-cyan-50/50 dark:border-slate-800 dark:bg-slate-900 dark:hover:border-cyan-900 dark:hover:bg-cyan-950/30"
                  onClick={() => selectHistory(item)}
                  disabled={!!loadingHistoryId}
                >
                  <span className="flex items-center gap-2">
                    {loadingHistoryId === item.id && <Loader2 className="h-3.5 w-3.5 shrink-0 animate-spin text-cyan-600" />}
                    <span className="block min-w-0 truncate text-sm font-medium text-slate-900 dark:text-slate-100">
                      {item.title || item.question}
                    </span>
                  </span>
                  <span className="mt-1 block line-clamp-2 text-xs leading-5 text-slate-500 dark:text-slate-400">
                    {item.question}
                  </span>
                  <span className="mt-1 block text-[11px] text-slate-400">{item.createdAt}</span>
                </button>
              ))}
            </div>
          </ScrollArea>
        </DialogContent>
      </Dialog>
    </div>
  )
}

/** 构建来源定位用的片段 ID 索引，统一转成字符串以兼容后端 number/string 混用。 */
function buildChunkIndexById(chunks: MaterialChunk[]) {
  const map = new Map<string, number>()
  chunks.forEach((candidate, index) => {
    map.set(String(candidate.id), index)
  })
  return map
}

/**
 * 根据来源信息定位片段索引。
 * PDF 来源点击失败通常不是没有来源，而是来源只可靠带了页码/摘录；这里用多级兜底保证能跳到对应页。
 */
function locateSourceChunkIndex(source: RagSource, chunks: MaterialChunk[], chunkIndexById: Map<string, number>) {
  const byId = chunkIndexById.get(String(source.chunkId))
  if (byId !== undefined) return byId

  const pageNo = Number(source.pageNo)
  if (Number.isFinite(pageNo) && pageNo > 0) {
    const samePage = chunks
      .map((chunk, index) => ({ chunk, index }))
      .filter(({ chunk }) => Number(chunk.pageNo) === pageNo)
    const byExcerpt = bestSourceChunkMatch(source, samePage)
    if (byExcerpt !== undefined) return byExcerpt
    if (samePage[0]) return samePage[0].index
  }

  return bestSourceChunkMatch(
    source,
    chunks.map((chunk, index) => ({ chunk, index })),
  )
}

function locatePageChunkIndex(pageNo: number, chunks: MaterialChunk[]) {
  const samePageIndex = chunks.findIndex((chunk) => Number(chunk.pageNo) === pageNo)
  return samePageIndex >= 0 ? samePageIndex : 0
}

/** 用来源摘录匹配片段正文，处理空白、换行和 OCR 文本差异。 */
function sourceMatchesChunkText(source: RagSource, chunk: MaterialChunk) {
  return sourceChunkMatchScore(source, chunk) > 0
}

function bestSourceChunkMatch(
  source: RagSource,
  candidates: Array<{ chunk: MaterialChunk; index: number }>,
) {
  let bestIndex: number | undefined
  let bestScore = 0
  for (const candidate of candidates) {
    const score = sourceChunkMatchScore(source, candidate.chunk)
    if (score > bestScore) {
      bestScore = score
      bestIndex = candidate.index
    }
  }
  return bestIndex
}

function sourceChunkMatchScore(source: RagSource, chunk: MaterialChunk) {
  const excerpt = compactSourceText(source.excerpt || '')
  if (excerpt.length < 16) return 0
  const text = compactSourceText([chunk.chunkText, chunk.excerpt, chunk.summary].filter(Boolean).join('\n'))
  if (!text) return 0

  const fullExcerpt = excerpt.slice(0, Math.min(180, excerpt.length))
  if (text.includes(fullExcerpt)) return 1

  const mediumExcerpt = excerpt.slice(0, Math.min(120, excerpt.length))
  if (mediumExcerpt.length >= 24 && text.includes(mediumExcerpt)) return 0.88

  const shortExcerpt = excerpt.slice(0, Math.min(72, excerpt.length))
  if (shortExcerpt.length >= 20 && text.includes(shortExcerpt)) return 0.72

  const overlap = prefixOverlapLength(text, excerpt)
  if (overlap >= 32) {
    return Math.min(0.68, overlap / Math.max(64, excerpt.length))
  }
  return 0
}

function compactSourceText(value: string) {
  return value.replace(/\s+/g, '').toLowerCase()
}

function prefixOverlapLength(text: string, excerpt: string) {
  const maxWindow = Math.min(excerpt.length, 96)
  for (let size = maxWindow; size >= 16; size -= 4) {
    if (text.includes(excerpt.slice(0, size))) {
      return size
    }
  }
  return 0
}
