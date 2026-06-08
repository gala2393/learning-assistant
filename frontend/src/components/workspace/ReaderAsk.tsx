/**
 * ReaderAsk -- 边读边问 AI 问答面板
 *
 * 【用途】
 * 在阅读器页面（ReaderPage）的右侧/底部展示 AI 问答功能，
 * 让用户可以针对当前正在阅读的资料片段直接向 AI 提问。
 *
 * 【主要功能】
 * 1. 针对当前资料片段提问：AI 基于 RAG 检索回答
 * 2. 选中文本追问：在文档中选中 5-500 字符的文字后，可围绕选中内容提问
 * 3. 流式回答（SSE）：实时逐字展示 AI 回复，支持中断取消
 * 4. 推荐问题：首次加载时 AI 根据当前片段生成推荐问题
 * 5. 检索来源查看：回答完成后可查看引用的资料片段，点击跳转
 * 6. 使用额度：显示今日剩余问答次数
 * 7. "在聊天中继续"按钮：跳转到独立聊天页面继续对话
 * 8. 清空对话：清除所有消息和选中文本
 *
 * 【SSE 流式回答流程】
 * 1. 用户输入问题 -> 创建用户消息 + AI 占位消息（thinking 状态）
 * 2. 调用 chatStream() 发起 SSE 连接
 * 3. onStatus: 收到状态更新（如"正在检索"）-> 显示状态文字
 * 4. onChunk: 收到文本片段 -> 累积到缓冲区 -> 逐字更新 UI
 * 5. onSources: 收到检索来源 -> 存入 sourcesRef
 * 6. onDone: 流式完成 -> 最终设置完整回答和来源
 * 7. onError: 出错 -> 显示错误信息
 * 8. 完成后刷新使用额度缓存
 */
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Textarea } from '@/components/ui/textarea'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { chatStream, suggestQuestions, useRagUsage } from '@/api/rag'
import { ChatThread, type ChatMessage } from './ChatThread'
import { Send, Bot, ArrowRight, MousePointer, Sparkles, Trash2 } from 'lucide-react'
import { actionButtonBase, actionButtonIdle, actionButtonReady } from '@/lib/action-button-styles'
import { queryClient } from '@/lib/query-client'
import { cn, truncate } from '@/lib/utils'
import { useAuth } from '@/context/AuthContext'
import { useToast } from '@/components/ui/toast'
import { LOGIN_REQUIRED_MESSAGE, redirectToLogin } from '@/lib/auth-gate'
import type { Material, MaterialChunk, RagSource } from '@/types'

/**
 * ReaderAsk 组件属性
 *
 * @property material - 当前选中的资料对象
 * @property chunk - 当前正在阅读的片段
 * @property chunks - 所有片段列表（用于来源跳转时定位片段索引）
 * @property currentPageNo - 当前页码（用于问答上下文）
 * @property currentPageChunkIds - 当前页包含的片段 ID 列表（用于限定 RAG 检索范围）
 * @property onNavigateToChunk - 跳转到指定片段的回调（点击来源卡片时触发）
 * @property className - 额外的 CSS 类名
 */
interface ReaderAskProps {
  material: Material | null
  chunk: MaterialChunk | null
  chunks?: MaterialChunk[]
  currentPageNo?: number | null
  currentPageChunkIds?: Array<string | number>
  onNavigateToChunk?: (chunkIndex: number, options?: { view?: 'smart' | 'original' }) => void
  className?: string
}

/** 消息类型别名（复用 ChatMessage） */
type ReaderMessage = ChatMessage

/**
 * buildContextLabel -- 构建上下文标签文字
 * 用于在问答面板顶部显示当前上下文信息
 * 例如："已选中文本 · 当前页 P3"
 */
function buildContextLabel(selectedText?: string | null, currentPageNo?: number | null) {
  const labels: string[] = []
  if (selectedText?.trim()) labels.push('已选中文本')
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

  // === Refs ===
  /** 输入框引用（用于聚焦控制） */
  const questionRef = useRef<HTMLTextAreaElement>(null)
  /** AbortController 引用（用于取消当前流式请求） */
  const abortRef = useRef<AbortController | null>(null)
  /**
   * 流式回答缓冲区
   * 在 SSE 流式接收过程中，delta 文本片段逐步累积到此引用中。
   * 使用 ref 而非 state 避免频繁重渲染。
   */
  const answerBufferRef = useRef('')
  /** 用户选中文本的引用版本（不触发渲染，提交时使用） */
  const selectionRef = useRef<string | null>(null)
  /** 各消息的检索来源缓存（ref 版本，避免闭包问题） */
  const sourcesRef = useRef<Record<string, RagSource[]>>({})

  // === 状态 ===
  /** 用户输入的问题文本 */
  const [question, setQuestion] = useState('')
  /** AI 是否正在回答（流式进行中） */
  const [loading, setLoading] = useState(false)
  /** 用户在文档中选中的文本（5-500 字符才生效） */
  const [selectedText, setSelectedText] = useState<string | null>(null)
  /** AI 生成的推荐问题列表 */
  const [suggestedQuestions, setSuggestedQuestions] = useState<string[]>([])
  /** 对话消息列表 */
  const [messages, setMessages] = useState<ReaderMessage[]>([])
  /** 每条 AI 消息对应的检索来源映射（消息 ID -> 来源列表） */
  const [sourcesByMessageId, setSourcesByMessageId] = useState<Record<string, RagSource[]>>({})
  /** 每条 AI 消息对应的错误信息映射（消息 ID -> 错误文字） */
  const [errorByMessageId, setErrorByMessageId] = useState<Record<string, string>>({})

  // === 数据获取 ===
  /** 获取今日使用额度 */
  const { data: ragUsage } = useRagUsage()
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

  /** 上下文标签（"已选中文本 · 当前页 P3"） */
  const contextLabel = useMemo(
    () => buildContextLabel(selectedText, currentPageNo),
    [selectedText, currentPageNo],
  )
  /** 片段 ID -> 片段索引的映射（用于点击来源时跳转） */
  const chunkIndexById = useMemo(() => {
    const map = new Map<string, number>()
    ;(chunks || []).forEach((candidate, index) => {
      // 来源里的 chunkId 可能是 number 或 string，统一转 string 做稳定匹配。
      map.set(String(candidate.id), index)
    })
    return map
  }, [chunks])

  // === 副作用 ===

  /**
   * 监听鼠标松开事件，捕获用户选中的文本
   * 选中文字长度在 5~500 字符之间时生效
   * 这样用户可以在文档中选中一段文字，然后在问答面板中围绕它提问
   */
  useEffect(() => {
    const handleMouseUp = () => {
      const sel = window.getSelection()
      const text = sel?.toString().trim()
      if (text && text.length > 5 && text.length < 500) {
        // 只接收短选区，避免整页误选导致 prompt 过长或覆盖用户当前问题。
        setSelectedText(text)
        selectionRef.current = text
      }
    }
    document.addEventListener('mouseup', handleMouseUp)
    return () => document.removeEventListener('mouseup', handleMouseUp)
  }, [])

  /** 组件卸载时取消未完成的流式请求 */
  useEffect(() => {
    return () => abortRef.current?.abort()
  }, [])

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
   * 点击检索来源时，跳转到对应的片段
   * 通过 chunkIndexById 查找片段索引，然后调用 onNavigateToChunk 回调
   */
  const openSource = useCallback(
    (source: RagSource) => {
      const idx = chunkIndexById.get(String(source.chunkId))
      if (idx !== undefined) {
        onNavigateToChunk?.(idx, { view: 'smart' })
      }
    },
    [chunkIndexById, onNavigateToChunk],
  )

  /**
   * submitQuestion -- 提交问题的核心逻辑（SSE 流式请求）
   *
   * 完整流程：
   * 1. 校验输入有效性
   * 2. 生成唯一消息 ID
   * 3. 构建对话历史（最近 8 轮，排除错误和思考中的消息）
   * 4. 创建用户消息 + AI 占位消息（thinking 状态）
   * 5. 调用 chatStream() 发起 SSE 连接
   * 6. 通过回调逐步更新消息：
   *    - onStatus: 显示状态文字（如"正在检索相关资料..."）
   *    - onChunk: 累积文本片段到缓冲区并更新 UI
   *    - onSources: 存储检索来源
   *    - onDone: 设置最终回答，清除 loading 状态
   *    - onError: 显示错误信息
   * 7. 完成后刷新使用额度缓存
   */
  const submitQuestion = useCallback((rawQuestion: string, selection?: string | null) => {
    if (!requireLogin()) return
    const q = rawQuestion.trim()
    if (!q || loading || !material) return

    // 生成唯一的消息 ID（时间戳 + 随机字符串）
    const userId = `user-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
    const assistantId = `assistant-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`

    // 构建对话历史（最近 8 轮，排除错误和思考中的消息）
    const history = messages
      .filter((msg) => !msg.thinking && !msg.error)
      .slice(-8)
      .map((msg) => ({
        role: msg.role,
        content: msg.text,
      }))

    setLoading(true)
    setQuestion('')
    // 添加用户消息和 AI 思考中占位消息
    setMessages((prev) => [
      ...prev,
      { id: userId, role: 'user', text: q },
      { id: assistantId, role: 'assistant', text: '', thinking: true },
    ])
    // 初始化来源缓存
    sourcesRef.current = { ...sourcesRef.current, [assistantId]: [] }
    setSourcesByMessageId((prev) => ({ ...prev, [assistantId]: [] }))
    setErrorByMessageId((prev) => ({ ...prev, [assistantId]: '' }))
    answerBufferRef.current = ''
    // 新问题发起前取消旧流，防止旧 SSE 回调继续写入新的 assistant 消息。
    abortRef.current?.abort()

    // 发起 SSE 流式请求
    abortRef.current = chatStream(
      {
        question: q,
        mode: 'MATERIAL',
        materialId: material.id,
        chunkId: chunk?.id,
        currentPageNo: currentPageNo || undefined,
        currentPageChunkIds: currentPageChunkIds && currentPageChunkIds.length > 0 ? currentPageChunkIds : undefined,
        selectedText: selection || undefined,
        answerStyle: 'HOMEWORK',  // 作业风格回答
        history,
      },
      {
        /**
         * onStatus: 收到状态更新（如"正在检索"、"正在准备回答"）
         * 仅在还没有实际内容时显示，避免覆盖已有的回答文本
         */
        onStatus: (status) => {
          if (answerBufferRef.current.trim()) return  // 已有内容时忽略状态消息
          setMessages((prev) =>
            prev.map((msg) =>
              msg.id === assistantId
                ? { ...msg, text: streamStatusText(status), thinking: false }
                : msg,
            ),
          )
        },
        /**
         * onChunk: 收到流式文本片段（delta）
         * 累积到缓冲区，然后更新 AI 消息的文本
         * 这是 SSE 流式输出的核心回调，每秒可能触发多次
         */
        onChunk: (delta) => {
          answerBufferRef.current += delta
          setMessages((prev) =>
            prev.map((msg) =>
              msg.id === assistantId
                ? { ...msg, text: answerBufferRef.current, thinking: false }
                : msg,
            ),
          )
        },
        /** onSources: 收到 RAG 检索来源 */
        onSources: (sources) => {
          // 来源先写 ref，再写 state；onDone 闭包读取 ref，避免拿到过期 sources。
          sourcesRef.current = { ...sourcesRef.current, [assistantId]: sources }
          setSourcesByMessageId((prev) => ({ ...prev, [assistantId]: sources }))
        },
        /**
         * onDone: 流式回答完成
         * 设置最终的完整回答和来源，清除 loading 状态
         * 刷新使用额度缓存
         */
        onDone: (result) => {
          setMessages((prev) =>
            prev.map((msg) =>
              msg.id === assistantId
                ? {
                    ...msg,
                    text: result.answer || answerBufferRef.current,
                    thinking: false,
                    sources: sourcesRef.current[assistantId] || [],
                  }
                : msg,
            ),
          )
          setLoading(false)
          // 刷新使用额度缓存
          queryClient.invalidateQueries({ queryKey: ['rag-usage'] })
          queryClient.invalidateQueries({ queryKey: ['admin', 'usage-records'] })
        },
        /** onError: 请求出错时显示错误信息 */
        onError: (msg) => {
          setErrorByMessageId((prev) => ({ ...prev, [assistantId]: msg }))
          setMessages((prev) =>
            prev.map((item) =>
              item.id === assistantId
                ? { ...item, thinking: false, error: msg, text: '' }
                : item,
            ),
          )
          setLoading(false)
          queryClient.invalidateQueries({ queryKey: ['rag-usage'] })
          queryClient.invalidateQueries({ queryKey: ['admin', 'usage-records'] })
        },
      },
    )
  }, [chunk?.id, currentPageChunkIds, currentPageNo, loading, material, messages, requireLogin])

  /** 提交问题（组合选中文本一起发送） */
  const handleSubmit = () => {
    const selection = selectionRef.current ?? selectedText
    submitQuestion(question, selection)
    setSelectedText(null)
    selectionRef.current = null
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

      {/* ---- 面板头部：标题 + 使用额度 ---- */}
      <div className="space-y-2 border-b p-3">
        <div className="flex items-center justify-between gap-2">
          <p className="flex items-center gap-1 text-xs font-semibold text-muted-foreground">
            <Bot className="h-3.5 w-3.5" /> AI 问答
          </p>
          {usageLabel && (
            <Badge variant="secondary" className="rounded-full px-2 py-0.5 text-[10px] font-medium">
              {usageLabel}
            </Badge>
          )}
        </div>
        {/* 上下文标签（"已选中文本 · 当前页 P3"） */}
        {contextLabel && (
          <div className="flex items-center gap-2 text-[11px] text-muted-foreground">
            <Badge variant="secondary" className="text-[10px]">
              <Sparkles className="mr-1 h-3 w-3" /> {contextLabel}
            </Badge>
          </div>
        )}
      </div>

      {/* ---- 当前片段上下文预览 ---- */}
      {chunk && (
        <div className="border-b px-3 py-2">
          <Badge variant="outline" className="mb-1 text-[10px]">当前上下文</Badge>
          <p className="text-[11px] leading-relaxed text-muted-foreground">
            {truncate(chunk.chunkText, 70)}
          </p>
        </div>
      )}

      {/* ---- 选中文本面板（用户在文档中选中文字后显示） ---- */}
      {selectedText && (
        <div className="space-y-2 border-b bg-primary/5 px-3 py-2">
          <div className="flex items-center justify-between gap-2">
            <Badge variant="secondary" className="text-[10px]">
              <MousePointer className="mr-0.5 h-3 w-3" /> 选中文本
            </Badge>
            <button
              className="text-[10px] text-muted-foreground hover:text-foreground"
              onClick={() => {
                setSelectedText(null)
                selectionRef.current = null
              }}
            >
              清除
            </button>
          </div>
          <p className="line-clamp-2 rounded-md bg-background/70 px-2 py-1.5 text-[11px] leading-relaxed text-foreground/80">
            {truncate(selectedText, 90)}
          </p>
          <Button
            variant="outline"
            size="sm"
            className="h-7 w-full text-xs"
            onClick={() => questionRef.current?.focus()}
          >
            在下方提问
          </Button>
        </div>
      )}

      {/* ---- 对话消息流（复用 ChatThread 组件） ---- */}
      <div className="min-h-0 flex-1 overflow-hidden">
        <ChatThread
          messages={messages.map((msg) => ({
            ...msg,
            // 将状态中的来源和错误合并到消息对象中
            sources: msg.role === 'assistant' ? sourcesByMessageId[msg.id] || msg.sources : msg.sources,
            error: msg.role === 'assistant' ? errorByMessageId[msg.id] || msg.error : msg.error,
          }))}
          onOpenSource={openSource}
        />
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
                  onClick={() => submitQuestion(q, selectedText)}
                >
                  {q}
                </button>
              ))}
            </div>
          )}
        </div>
      )}

      {/* ---- 底部输入区域 ---- */}
      <div className="shrink-0 space-y-2 border-t p-3">
        <Textarea
          ref={questionRef}
          value={question}
          onChange={(e) => setQuestion(e.target.value)}
          onKeyDown={handleKeyDown}
          placeholder={material
            ? usageExhausted
              ? '今日问答次数已用完'
              : (selectedText ? '围绕选中文本继续追问...' : '针对当前资料继续提问...')
            : '请先选择一份资料'}
          className="min-h-[64px] resize-none text-sm"
          disabled={!material || usageExhausted}
        />
        {/* 提交和清空按钮 */}
        <div className="flex items-center gap-2">
          <Button
            size="sm"
            className={`h-9 flex-1 rounded-xl ${actionButtonBase} ${canAsk ? actionButtonReady : actionButtonIdle}`}
            onClick={handleSubmit}
            disabled={!canAsk}
          >
            {loading ? '思考中...' : (
              <>
                <Send className="mr-1 h-3.5 w-3.5" /> 提问
              </>
            )}
          </Button>
          {/* 清空按钮：清除所有消息、来源、选中文本 */}
          <Button
            variant="outline"
            size="sm"
            onClick={() => {
              setMessages([])
              setSourcesByMessageId({})
              setErrorByMessageId({})
              sourcesRef.current = {}
              setSelectedText(null)
              selectionRef.current = null
            }}
            disabled={messages.length === 0 && !selectedText}
          >
            <Trash2 className="mr-1 h-3.5 w-3.5" /> 清空
          </Button>
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
    </div>
  )
}

/**
 * streamStatusText -- 将流式请求的状态阶段转换为中文提示文字
 * 用于在 AI 回答尚未开始时显示"正在检索相关资料..."等状态信息
 */
function streamStatusText(status: { stage?: string; message?: string }) {
  if (status.message?.trim()) return status.message.trim()
  if (status.stage === 'searching') return '正在检索相关资料...'
  if (status.stage === 'thinking') return '正在准备回答...'
  return '正在准备回答...'
}
