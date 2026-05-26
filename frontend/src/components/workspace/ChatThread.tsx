import { useEffect, useRef } from 'react'
import { motion } from 'framer-motion'
import { ScrollArea } from '@/components/ui/scroll-area'
import { Badge } from '@/components/ui/badge'
import { cn, sanitizeAiText } from '@/lib/utils'
import { SourceCard } from './SourceCard'
import { AlertCircle } from 'lucide-react'
import { useAuth } from '@/context/AuthContext'
import { UserAvatar } from '@/components/layout/UserAvatar'
import type { RagSource } from '@/types'

export interface ChatMessage {
  id: string
  role: 'user' | 'assistant'
  text: string
  thinking?: boolean
  error?: string
  sources?: RagSource[]
}

interface ChatThreadProps {
  messages: ChatMessage[]
  onOpenSource?: (source: RagSource) => void
}

function ThinkingDots() {
  return (
    <div className="flex items-center gap-1 py-1">
      {[0, 1, 2].map((i) => (
        <motion.span
          key={i}
          className="inline-block h-2 w-2 rounded-full bg-muted-foreground/50"
          animate={{ opacity: [0.3, 1, 0.3] }}
          transition={{ duration: 1.2, repeat: Infinity, delay: i * 0.2 }}
        />
      ))}
    </div>
  )
}

function AssistantAvatar() {
  return (
    <div
      className="relative mt-1 flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-[#fff4cf] shadow-[0_8px_18px_rgba(180,120,32,0.18)] ring-1 ring-amber-200/80 dark:bg-[#3a2b16] dark:ring-amber-300/20"
      title="AI 助手"
      aria-label="AI 助手头像"
    >
      <div className="relative h-7 w-7 rounded-[9px] border border-amber-400/70 bg-[#ffe7a6] shadow-inner dark:border-amber-300/40 dark:bg-[#f6c667]">
        <div className="absolute -left-1 top-1 h-5 w-3 rounded-l-[8px] border border-amber-400/70 bg-[#fff9e8] dark:border-amber-300/40 dark:bg-[#ffe5a1]" />
        <div className="absolute -right-1 top-1 h-5 w-3 rounded-r-[8px] border border-amber-400/70 bg-[#fff9e8] dark:border-amber-300/40 dark:bg-[#ffe5a1]" />
        <div className="absolute left-[8px] top-[7px] h-1.5 w-1.5 rounded-full bg-[#334155]" />
        <div className="absolute right-[8px] top-[7px] h-1.5 w-1.5 rounded-full bg-[#334155]" />
        <div className="absolute left-1/2 top-[15px] h-1.5 w-3 -translate-x-1/2 rounded-b-full border-b-2 border-[#334155]" />
        <div className="absolute left-1/2 top-0 h-full w-px -translate-x-1/2 bg-amber-400/50" />
        <div className="absolute right-1.5 top-0 h-2.5 w-1 rounded-b-full bg-cyan-500" />
      </div>
      <div className="absolute -right-0.5 -top-0.5 h-2 w-2 rounded-full bg-cyan-500 ring-2 ring-[#fff4cf] dark:ring-[#3a2b16]" />
    </div>
  )
}

function splitAssistantParagraphs(text: string) {
  const clean = sanitizeAiText(text).trim()
  if (!clean) return []
  return clean
    .split(/\n{2,}/)
    .map((block) => block.trim())
    .filter(Boolean)
}

export function ChatThread({ messages, onOpenSource }: ChatThreadProps) {
  const { session } = useAuth()
  const bottomRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth', block: 'end' })
  }, [messages])

  return (
    <ScrollArea className="h-full min-h-0 w-full overflow-hidden px-4">
      <div className="mx-auto max-w-3xl space-y-4 py-4">
        {messages.length === 0 && <div className="h-8" />}
        {messages.map((msg) => (
          <motion.div
            key={msg.id}
            initial={{ opacity: 0, y: 8 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.2 }}
            className={cn('flex gap-3', msg.role === 'user' ? 'justify-end' : 'justify-start')}
          >
            {msg.role === 'assistant' && <AssistantAvatar />}
            <div className={cn('max-w-[75%] min-w-0', msg.role === 'assistant' && 'space-y-2')}>
              {msg.role === 'assistant' && (
                <div className="inline-flex items-center rounded-full border border-amber-200 bg-[#fff8df] px-2 py-0.5 text-[11px] font-semibold text-amber-800 shadow-sm dark:border-amber-300/20 dark:bg-amber-300/10 dark:text-amber-200">
                  AI 助手
                </div>
              )}

              {msg.role === 'user' ? (
                <div className="rounded-2xl border border-slate-200/70 bg-slate-100/90 px-4 py-3 text-sm text-slate-900 shadow-[0_1px_2px_rgba(15,23,42,0.04)] dark:border-slate-700/60 dark:bg-slate-800/80 dark:text-slate-100">
                  {msg.thinking ? (
                    <ThinkingDots />
                  ) : msg.error ? (
                    <div className="flex items-center gap-2 text-destructive">
                      <AlertCircle className="h-4 w-4" />
                      <span>{msg.error}</span>
                    </div>
                  ) : (
                    <div className="whitespace-pre-wrap leading-relaxed">{msg.text}</div>
                  )}
                </div>
              ) : (
                <div className="space-y-2">
                  {msg.thinking ? (
                    <div className="rounded-2xl border border-amber-100/70 bg-white/90 px-4 py-3 shadow-[0_1px_2px_rgba(15,23,42,0.04)] dark:border-slate-700/60 dark:bg-slate-800/80">
                      <ThinkingDots />
                    </div>
                  ) : msg.error ? (
                    <div className="rounded-2xl border border-rose-200/60 bg-rose-50/80 px-4 py-3 text-sm text-rose-700 dark:border-rose-900/40 dark:bg-rose-950/30 dark:text-rose-200">
                      <div className="flex items-center gap-2">
                        <AlertCircle className="h-4 w-4" />
                        <span>{msg.error}</span>
                      </div>
                    </div>
                  ) : (
                    <>
                      {splitAssistantParagraphs(msg.text).length > 0 ? (
                        <div className="space-y-2">
                          {splitAssistantParagraphs(msg.text).map((block, index) => (
                            <div
                              key={`${msg.id}-${index}`}
                              className="rounded-2xl border border-amber-100/70 bg-white/90 px-4 py-3 text-sm leading-relaxed text-slate-800 shadow-[0_1px_2px_rgba(15,23,42,0.04)] dark:border-slate-700/60 dark:bg-slate-800/80 dark:text-slate-100"
                            >
                              {block}
                            </div>
                          ))}
                        </div>
                      ) : (
                        <div className="rounded-2xl border border-amber-100/70 bg-white/90 px-4 py-3 text-sm leading-relaxed text-slate-800 shadow-[0_1px_2px_rgba(15,23,42,0.04)] dark:border-slate-700/60 dark:bg-slate-800/80 dark:text-slate-100">
                          {sanitizeAiText(msg.text)}
                        </div>
                      )}

                      {msg.sources && msg.sources.length > 0 && (
                        <div className="mt-2 space-y-2">
                          <Badge variant="outline" className="text-[10px]">
                            参考来源({msg.sources.length})
                          </Badge>
                          {msg.sources.map((s, i) => (
                            <SourceCard key={i} source={s} onOpen={onOpenSource} />
                          ))}
                        </div>
                      )}
                    </>
                  )}
                </div>
              )}
            </div>
            {msg.role === 'user' && <UserAvatar session={session} className="mt-1 h-9 w-9 shrink-0 text-sm" />}
          </motion.div>
        ))}
        <div ref={bottomRef} />
      </div>
    </ScrollArea>
  )
}
