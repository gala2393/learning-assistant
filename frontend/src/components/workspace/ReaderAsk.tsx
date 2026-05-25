import { useState, useRef, useEffect, useCallback } from 'react'
import { useNavigate } from 'react-router-dom'
import { Textarea } from '@/components/ui/textarea'
import { Button } from '@/components/ui/button'
import { ScrollArea } from '@/components/ui/scroll-area'
import { Badge } from '@/components/ui/badge'
import { truncate } from '@/lib/utils'
import { chatStream, suggestQuestions } from '@/api/rag'
import { Send, Bot, ArrowRight, MousePointer, Copy } from 'lucide-react'
import type { Material, MaterialChunk, RagSource } from '@/types'

interface ReaderAskProps {
  material: Material | null
  chunk: MaterialChunk | null
  chunks?: MaterialChunk[]
  onNavigateToChunk?: (chunkIndex: number) => void
}

export function ReaderAsk({ material, chunk, chunks, onNavigateToChunk }: ReaderAskProps) {
  const navigate = useNavigate()
  const [question, setQuestion] = useState('')
  const [loading, setLoading] = useState(false)
  const [answer, setAnswer] = useState<string | null>(null)
  const [sources, setSources] = useState<RagSource[]>([])
  const [error, setError] = useState<string | null>(null)
  const [selectedText, setSelectedText] = useState<string | null>(null)
  const [suggestedQuestions, setSuggestedQuestions] = useState<string[]>([])
  const abortRef = useRef<AbortController | null>(null)
  const answerRef = useRef('')

  useEffect(() => {
    const handleMouseUp = () => {
      const sel = window.getSelection()
      const text = sel?.toString().trim()
      if (text && text.length > 5 && text.length < 500) {
        setSelectedText(text)
      }
    }
    document.addEventListener('mouseup', handleMouseUp)
    return () => document.removeEventListener('mouseup', handleMouseUp)
  }, [])

  const handleAsk = useCallback((q: string, selection?: string) => {
    if (!q.trim() || loading || !material) return

    setLoading(true)
    setAnswer(null)
    setSources([])
    setError(null)
    answerRef.current = ''
    abortRef.current?.abort()

    abortRef.current = chatStream(
      {
        question: q,
        mode: 'MATERIAL',
        materialId: material.id,
        chunkId: chunk?.id,
        selectedText: selection || undefined,
        answerStyle: 'HOMEWORK',
      },
      {
        onChunk: (delta) => {
          answerRef.current += delta
          setAnswer(answerRef.current)
        },
        onSources: (s) => setSources(s),
        onDone: (result) => {
          setAnswer(result.answer || answerRef.current)
          setLoading(false)
        },
        onError: (msg) => {
          setError(msg)
          setLoading(false)
        },
      },
    )
  }, [material, chunk, loading])

  const handleSubmit = () => {
    const q = question.trim()
    if (!q) return
    handleAsk(q, selectedText || undefined)
    setQuestion('')
    setSelectedText(null)
  }

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      handleSubmit()
    }
  }

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

  return (
    <div className="w-72 border-l flex flex-col h-full bg-muted/10">
      <div className="p-3 border-b">
        <p className="text-xs font-semibold text-muted-foreground flex items-center gap-1">
          <Bot className="h-3.5 w-3.5" /> AI 问答
        </p>
      </div>

      {chunk && (
        <div className="px-3 py-2 border-b">
          <Badge variant="outline" className="text-[10px] mb-1">当前上下文</Badge>
          <p className="text-[11px] text-muted-foreground leading-relaxed">
            {truncate(chunk.chunkText, 70)}
          </p>
        </div>
      )}

      {selectedText && (
        <div className="px-3 py-2 border-b bg-primary/5 space-y-2">
          <div className="flex items-center justify-between gap-2">
            <Badge variant="secondary" className="text-[10px]">
              <MousePointer className="h-3 w-3 mr-0.5" /> 选中内容
            </Badge>
            <button
              className="text-[10px] text-muted-foreground hover:text-foreground"
              onClick={() => setSelectedText(null)}
            >
              清除
            </button>
          </div>
          <p className="rounded-md bg-background/70 px-2 py-1.5 text-[11px] leading-relaxed text-foreground/80 line-clamp-2">
            {truncate(selectedText, 90)}
          </p>
          <Button
            variant="outline"
            size="sm"
            className="w-full text-xs h-7"
            onClick={() => {
              handleAsk('请解释这段内容', selectedText)
              setSelectedText(null)
            }}
          >
            对此提问
          </Button>
        </div>
      )}

      <ScrollArea className="flex-1 px-3 py-2">
        {answer && (
          <>
            <div className="mb-3 space-y-2">
              {answer
                .split(/\n{2,}/)
                .map((part) => part.trim())
                .filter(Boolean)
                .map((part, index) => (
                  <div
                    key={index}
                    className="rounded-xl border border-border/60 bg-background/80 px-3 py-2 text-sm leading-relaxed"
                  >
                    {part}
                  </div>
                ))}
            </div>
            {sources.length > 0 && (
              <div className="mb-3 space-y-1">
                <p className="text-[10px] text-muted-foreground font-medium">参考来源</p>
                {sources.map((s, i) => {
                  const idx = chunks?.findIndex((c) => c.id === s.chunkId)
                  const scorePercent = Math.round((s.score || 0) * 100)
                  return (
                    <button
                      key={i}
                      className="flex w-full items-center gap-2 rounded-md bg-muted/30 px-2.5 py-1.5 text-left text-[11px] text-muted-foreground transition-colors hover:bg-muted/60 hover:text-foreground"
                      onClick={() => {
                        if (idx !== undefined && idx >= 0) onNavigateToChunk?.(idx)
                      }}
                    >
                      <span className="min-w-0 flex-1 truncate">{s.materialTitle}</span>
                      {s.pageNo > 0 && (
                        <Badge variant="outline" className="h-5 px-1.5 text-[10px]">
                          P{s.pageNo}
                        </Badge>
                      )}
                      <span className="shrink-0 text-[10px] text-muted-foreground/80">{scorePercent}%</span>
                    </button>
                  )
                })}
              </div>
            )}
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
              在聊天中继续 <ArrowRight className="h-3.5 w-3.5 ml-1" />
            </Button>
          </>
        )}
        {error && <p className="text-xs text-destructive">{error}</p>}
        {!answer && !error && !loading && (
          <div className="py-3">
            {suggestedQuestions.length > 0 && (
              <div className="space-y-1.5 mb-3">
                <p className="text-[10px] text-muted-foreground font-medium">推荐问题</p>
                {suggestedQuestions.map((q, i) => (
                  <button
                    key={i}
                    className="block w-full text-left text-xs text-muted-foreground hover:text-foreground p-2 rounded bg-muted/50 hover:bg-muted transition-colors"
                    onClick={() => handleAsk(q)}
                  >
                    {q}
                  </button>
                ))}
              </div>
            )}
            <p className="text-xs text-muted-foreground text-center">
              {suggestedQuestions.length === 0 ? '输入问题，基于当前片段获取回答' : '或输入自定义问题'}
            </p>
          </div>
        )}
        {loading && !answer && (
          <div className="flex items-center gap-1 py-3 justify-center">
            {[0, 1, 2].map((i) => (
              <span
                key={i}
                className="inline-block h-2 w-2 rounded-full bg-muted-foreground/50 animate-pulse"
                style={{ animationDelay: `${i * 200}ms` }}
              />
            ))}
          </div>
        )}
      </ScrollArea>

      <div className="p-3 border-t space-y-2">
        <Textarea
          value={question}
          onChange={(e) => setQuestion(e.target.value)}
          onKeyDown={handleKeyDown}
          placeholder={material ? '针对当前片段提问...' : '请先选择一份资料'}
          className="min-h-[60px] resize-none text-sm"
          disabled={!material}
        />
        <Button
          size="sm"
          className="w-full"
          onClick={handleSubmit}
          disabled={!question.trim() || loading || !material}
        >
          {loading ? '思考中...' : (
            <>
              <Send className="h-3.5 w-3.5 mr-1" /> 提问
            </>
          )}
        </Button>
      </div>
    </div>
  )
}
