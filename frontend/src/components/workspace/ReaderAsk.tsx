/**
 * ReaderAsk - 边读边问 AI 问答面板
 *
 * 功能说明：
 * - 在阅读器页面的右侧/底部展示 AI 问答功能
 * - 支持针对当前资料片段进行提问，AI 基于 RAG 检索回答
 * - 支持选中文本后围绕选中内容追问
 * - 支持流式回答（SSE），实时逐字展示 AI 回复
 * - 展示推荐问题（当没有对话记录时）
 * - 支持查看回答的检索来源，点击来源跳转到对应片段
 * - 支持查看今日使用额度
 * - 提供"在聊天中继续"按钮，跳转到独立聊天页面
 *
 * 数据流：
 * 1. suggestQuestions() 获取推荐问题
 * 2. chatStream() 发起流式问答，通过回调逐步更新消息
 * 3. useRagUsage() 获取今日使用额度
 * 4. 回答完成后刷新使用记录缓存
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
import type { Material, MaterialChunk, RagSource } from '@/types'

interface ReaderAskProps {
  material: Material | null                     // 当前选中的资料
  chunk: MaterialChunk | null                   // 当前正在阅读的片段
  chunks?: MaterialChunk[]                      // 所有片段列表（用于来源跳转）
  currentPageNo?: number | null                 // 当前页码
  currentPageChunkIds?: Array<string | number>  // 当前页包含的片段 ID 列表
  onNavigateToChunk?: (chunkIndex: number) => void  // 跳转到指定片段的回调
  className?: string
}

type ReaderMessage = ChatMessage

/**
 * 构建上下文标签文字
 * 用于在问答面板顶部显示当前上下文信息（选中文本、当前页码等）
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
  // 输入框引用（用于聚焦控制）
  const questionRef = useRef<HTMLTextAreaElement>(null)
  // 用于取消当前流式请求
  const abortRef = useRef<AbortController | null>(null)
  // 流式回答的缓冲区（逐步累积 delta 文本）
  const answerBufferRef = useRef('')
  // 用户选中文本的引用（不触发渲染的版本）
  const selectionRef = useRef<string | null>(null)
  // 各消息的检索来源缓存
  const sourcesRef = useRef<Record<string, RagSource[]>>({})

  const [question, setQuestion] = useState('')       // 用户输入的问题
  const [loading, setLoading] = useState(false)       // AI 是否正在回答
  const [selectedText, setSelectedText] = useState<string | null>(null)  // 用户选中的文本
  const [suggestedQuestions, setSuggestedQuestions] = useState<string[]>([])  // 推荐问题列表
  const [messages, setMessages] = useState<ReaderMessage[]>([])  // 对话消息列表
  const [sourcesByMessageId, setSourcesByMessageId] = useState<Record<string, RagSource[]>>({})  // 来源映射
  const [errorByMessageId, setErrorByMessageId] = useState<Record<string, string>>({})  // 错误映射

  // 获取今日使用额度
  const { data: ragUsage } = useRagUsage()
  const usageLabel = ragUsage
    ? ragUsage.unlimited
      ? '今日问答：不限'
      : `今日剩余：${ragUsage.remainingToday ?? 0}/${ragUsage.dailyLimit}`
    : ''
  const usageExhausted = !!ragUsage && !ragUsage.unlimited && (ragUsage.remainingToday ?? 0) <= 0
  // 是否可以提问
  const canAsk = question.trim().length > 0 && !loading && !!material && !usageExhausted

  // 构建上下文标签
  const contextLabel = useMemo(
    () => buildContextLabel(selectedText, currentPageNo),
    [selectedText, currentPageNo],
  )

  // 监听鼠标松开事件，捕获用户选中的文本（长度 5~500 字符才生效）
  useEffect(() => {
    const handleMouseUp = () => {
      const sel = window.getSelection()
      const text = sel?.toString().trim()
      if (text && text.length > 5 && text.length < 500) {
        setSelectedText(text)
        selectionRef.current = text
      }
    }
    document.addEventListener('mouseup', handleMouseUp)
    return () => document.removeEventListener('mouseup', handleMouseUp)
  }, [])

  // 组件卸载时取消未完成的流式请求
  useEffect(() => {
    return () => abortRef.current?.abort()
  }, [])

  // 当资料或片段变化时，请求推荐问题
  useEffect(() => {
    if (!material || !chunk) {
      setSuggestedQuestions([])
      return
    }
    suggestQuestions(material.id, chunk.id)
      .then(setSuggestedQuestions)
      .catch(() => setSuggestedQuestions([]))
  }, [material?.id, chunk?.id])

  /** 点击检索来源时，跳转到对应的片段 */
  const openSource = useCallback(
    (source: RagSource) => {
      const idx = chunks?.findIndex((c) => c.id === source.chunkId)
      if (idx !== undefined && idx >= 0) {
        onNavigateToChunk?.(idx)
      }
    },
    [chunks, onNavigateToChunk],
  )

  /**
   * 提交问题的核心逻辑：
   * 1. 创建用户消息和空的 AI 消息
   * 2. 调用 chatStream() 发起 SSE 流式请求
   * 3. 通过 onStatus/onChunk/onSources/onDone/onError 回调逐步更新消息
   * 4. 完成后刷新使用额度和管理员使用记录缓存
   */
  const submitQuestion = useCallback((rawQuestion: string, selection?: string | null) => {
    const q = rawQuestion.trim()
    if (!q || loading || !material) return

    // 生成唯一的消息 ID
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
    sourcesRef.current = { ...sourcesRef.current, [assistantId]: [] }
    setSourcesByMessageId((prev) => ({ ...prev, [assistantId]: [] }))
    setErrorByMessageId((prev) => ({ ...prev, [assistantId]: '' }))
    answerBufferRef.current = ''
    abortRef.current?.abort()  // 取消之前的请求

    // 发起流式请求
    abortRef.current = chatStream(
      {
        question: q,
        mode: 'MATERIAL',
        materialId: material.id,
        chunkId: chunk?.id,
        currentPageNo: currentPageNo || undefined,
        currentPageChunkIds: currentPageChunkIds && currentPageChunkIds.length > 0 ? currentPageChunkIds : undefined,
        selectedText: selection || undefined,
        answerStyle: 'HOMEWORK',
        history,
      },
      {
        // 收到状态更新（如"正在检索"）
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
        // 收到流式文本片段
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
        // 收到检索来源
        onSources: (sources) => {
          sourcesRef.current = { ...sourcesRef.current, [assistantId]: sources }
          setSourcesByMessageId((prev) => ({ ...prev, [assistantId]: sources }))
        },
        // 流式回答完成
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
          queryClient.invalidateQueries({ queryKey: ['rag-usage'] })
          queryClient.invalidateQueries({ queryKey: ['admin', 'usage-records'] })
        },
        // 请求出错
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
  }, [chunk?.id, currentPageChunkIds, currentPageNo, loading, material, messages])

  /** 提交问题（组合选中文本） */
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

  return (
    <div className={cn('flex h-full min-h-0 w-full shrink-0 flex-col overflow-hidden border-t bg-muted/10 lg:border-l lg:border-t-0', className)}>
      {/* 面板头部：标题 + 使用额度 */}
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
        {/* 上下文标签（选中文本/当前页） */}
        {contextLabel && (
          <div className="flex items-center gap-2 text-[11px] text-muted-foreground">
            <Badge variant="secondary" className="text-[10px]">
              <Sparkles className="mr-1 h-3 w-3" /> {contextLabel}
            </Badge>
          </div>
        )}
      </div>

      {/* 当前片段上下文预览 */}
      {chunk && (
        <div className="border-b px-3 py-2">
          <Badge variant="outline" className="mb-1 text-[10px]">当前上下文</Badge>
          <p className="text-[11px] leading-relaxed text-muted-foreground">
            {truncate(chunk.chunkText, 70)}
          </p>
        </div>
      )}

      {/* 选中文本面板（选中文本后显示） */}
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

      {/* 对话消息流 */}
      <div className="min-h-0 flex-1 overflow-hidden">
        <ChatThread
          messages={messages.map((msg) => ({
            ...msg,
            sources: msg.role === 'assistant' ? sourcesByMessageId[msg.id] || msg.sources : msg.sources,
            error: msg.role === 'assistant' ? errorByMessageId[msg.id] || msg.error : msg.error,
          }))}
          onOpenSource={openSource}
        />
      </div>

      {/* 推荐问题（仅在无对话记录时显示） */}
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

      {/* 底部输入区域 */}
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
        {/* 跳转到独立聊天页面的按钮 */}
        <Button
          variant="outline"
          size="sm"
          className="w-full text-xs"
          onClick={() => {
            const params = new URLSearchParams()
            if (material?.id) params.set('materialId', material.id)
            if (chunk?.id) params.set('chunkId', chunk.id)
            navigate(`/workspace/chat?${params.toString()}`)
          }}
        >
          在聊天中继续 <ArrowRight className="ml-1 h-3.5 w-3.5" />
        </Button>
      </div>
    </div>
  )
}

/** 将流式请求的状态阶段转换为中文提示文字 */
function streamStatusText(status: { stage?: string; message?: string }) {
  if (status.message?.trim()) return status.message.trim()
  if (status.stage === 'searching') return '正在检索相关资料...'
  if (status.stage === 'thinking') return '正在准备回答...'
  return '正在准备回答...'
}
