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
  material: Material | null
  chunk: MaterialChunk | null
  chunks?: MaterialChunk[]
  currentPageNo?: number | null
  currentPageChunkIds?: Array<string | number>
  onNavigateToChunk?: (chunkIndex: number) => void
  className?: string
}

type ReaderMessage = ChatMessage

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
  const questionRef = useRef<HTMLTextAreaElement>(null)
  const abortRef = useRef<AbortController | null>(null)
  const answerBufferRef = useRef('')
  const selectionRef = useRef<string | null>(null)
  const sourcesRef = useRef<Record<string, RagSource[]>>({})
  const [question, setQuestion] = useState('')
  const [loading, setLoading] = useState(false)
  const [selectedText, setSelectedText] = useState<string | null>(null)
  const [suggestedQuestions, setSuggestedQuestions] = useState<string[]>([])
  const [messages, setMessages] = useState<ReaderMessage[]>([])
  const [sourcesByMessageId, setSourcesByMessageId] = useState<Record<string, RagSource[]>>({})
  const [errorByMessageId, setErrorByMessageId] = useState<Record<string, string>>({})
  const { data: ragUsage } = useRagUsage()
  const usageLabel = ragUsage
    ? ragUsage.unlimited
      ? '今日问答：不限'
      : `今日剩余：${ragUsage.remainingToday ?? 0}/${ragUsage.dailyLimit}`
    : ''
  const usageExhausted = !!ragUsage && !ragUsage.unlimited && (ragUsage.remainingToday ?? 0) <= 0
  const canAsk = question.trim().length > 0 && !loading && !!material && !usageExhausted

  const contextLabel = useMemo(
    () => buildContextLabel(selectedText, currentPageNo),
    [selectedText, currentPageNo],
  )

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

  useEffect(() => {
    return () => abortRef.current?.abort()
  }, [])

  useEffect(() => {
    if (!material || !chunk) {
      setSuggestedQuestions([])
      return
    }
    suggestQuestions(material.id, chunk.id)
      .then(setSuggestedQuestions)
      .catch(() => setSuggestedQuestions([]))
  }, [material?.id, chunk?.id])

  const openSource = useCallback(
    (source: RagSource) => {
      const idx = chunks?.findIndex((c) => c.id === source.chunkId)
      if (idx !== undefined && idx >= 0) {
        onNavigateToChunk?.(idx)
      }
    },
    [chunks, onNavigateToChunk],
  )

  const submitQuestion = useCallback((rawQuestion: string, selection?: string | null) => {
    const q = rawQuestion.trim()
    if (!q || loading || !material) return

    const userId = `user-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
    const assistantId = `assistant-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
    const history = messages
      .filter((msg) => !msg.thinking && !msg.error)
      .slice(-8)
      .map((msg) => ({
        role: msg.role,
        content: msg.text,
      }))

    setLoading(true)
    setQuestion('')
    setMessages((prev) => [
      ...prev,
      { id: userId, role: 'user', text: q },
      { id: assistantId, role: 'assistant', text: '', thinking: true },
    ])
    sourcesRef.current = { ...sourcesRef.current, [assistantId]: [] }
    setSourcesByMessageId((prev) => ({ ...prev, [assistantId]: [] }))
    setErrorByMessageId((prev) => ({ ...prev, [assistantId]: '' }))
    answerBufferRef.current = ''
    abortRef.current?.abort()

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
        onStatus: (status) => {
          if (answerBufferRef.current.trim()) return
          setMessages((prev) =>
            prev.map((msg) =>
              msg.id === assistantId
                ? { ...msg, text: streamStatusText(status), thinking: false }
                : msg,
            ),
          )
        },
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
        onSources: (sources) => {
          sourcesRef.current = { ...sourcesRef.current, [assistantId]: sources }
          setSourcesByMessageId((prev) => ({ ...prev, [assistantId]: sources }))
        },
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

  const handleSubmit = () => {
    const selection = selectionRef.current ?? selectedText
    submitQuestion(question, selection)
    setSelectedText(null)
    selectionRef.current = null
  }

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      handleSubmit()
    }
  }

  return (
    <div className={cn('flex h-full min-h-0 w-full shrink-0 flex-col overflow-hidden border-t bg-muted/10 lg:border-l lg:border-t-0', className)}>
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
        {contextLabel && (
          <div className="flex items-center gap-2 text-[11px] text-muted-foreground">
            <Badge variant="secondary" className="text-[10px]">
              <Sparkles className="mr-1 h-3 w-3" /> {contextLabel}
            </Badge>
          </div>
        )}
      </div>

      {chunk && (
        <div className="border-b px-3 py-2">
          <Badge variant="outline" className="mb-1 text-[10px]">当前上下文</Badge>
          <p className="text-[11px] leading-relaxed text-muted-foreground">
            {truncate(chunk.chunkText, 70)}
          </p>
        </div>
      )}

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

function streamStatusText(status: { stage?: string; message?: string }) {
  if (status.message?.trim()) return status.message.trim()
  if (status.stage === 'searching') return '正在检索相关资料...'
  if (status.stage === 'thinking') return '正在准备回答...'
  return '正在准备回答...'
}
