import { useEffect, useRef, useState } from 'react'
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
          className="inline-block h-2 w-2 rounded-full bg-cyan-400/70 shadow-[0_0_8px_rgba(34,211,238,0.45)]"
          animate={{ opacity: [0.3, 1, 0.3] }}
          transition={{ duration: 1.2, repeat: Infinity, delay: i * 0.2 }}
        />
      ))}
    </div>
  )
}

function AssistantAvatarFallback() {
  return (
    <div
      className="relative mt-1 flex h-12 w-12 shrink-0 items-center justify-center rounded-full bg-[radial-gradient(circle_at_36%_24%,#ffffff_0%,#eff9ff_35%,#eaf3ff_72%,#fdf0fb_100%)] shadow-[0_12px_28px_rgba(56,189,248,0.22),0_0_24px_rgba(244,114,182,0.18)] ring-1 ring-cyan-200/80 dark:bg-[radial-gradient(circle_at_36%_24%,#243244_0%,#172033_68%,#2b1f35_100%)] dark:ring-cyan-300/25"
      title="AI 助手"
      aria-label="AI 助手头像"
    >
      <div className="absolute inset-0 rounded-full bg-[conic-gradient(from_210deg,rgba(56,189,248,0.28),rgba(244,114,182,0.28),rgba(255,255,255,0.45),rgba(56,189,248,0.28))] blur-[1px]" />
      <div className="relative h-9 w-9 overflow-hidden rounded-full border border-white/80 bg-[#fff7fb] shadow-inner dark:border-white/15 dark:bg-slate-900">
        <div className="absolute -left-1 top-0 h-8 w-5 rounded-br-[18px] rounded-tr-[18px] bg-gradient-to-b from-[#6fdcff] via-[#a5d8ff] to-[#f7c5ee]" />
        <div className="absolute -right-1 top-0 h-8 w-5 rounded-bl-[18px] rounded-tl-[18px] bg-gradient-to-b from-[#ffe9fb] via-[#f7a9dc] to-[#7dd3fc]" />
        <div className="absolute left-1/2 top-0 h-5 w-5 -translate-x-1/2 rounded-b-[18px] bg-gradient-to-b from-white via-[#cde9ff] to-[#ffc8ea]" />
        <div className="absolute left-1/2 top-[11px] h-[19px] w-[25px] -translate-x-1/2 rounded-[45%] bg-[#ffe8de]" />
        <div className="absolute left-[10px] top-[18px] h-[5px] w-[5px] rounded-full bg-[#2563eb] shadow-[0_0_0_2px_rgba(125,211,252,0.42),0_0_8px_rgba(56,189,248,0.65)]" />
        <div className="absolute right-[10px] top-[18px] h-[5px] w-[5px] rounded-full bg-[#2563eb] shadow-[0_0_0_2px_rgba(244,114,182,0.32),0_0_8px_rgba(244,114,182,0.6)]" />
        <div className="absolute left-1/2 top-[25px] h-1.5 w-3 -translate-x-1/2 rounded-b-full border-b-2 border-[#64748b]" />
        <div className="absolute left-[2px] top-[15px] h-3.5 w-1.5 rounded-full bg-cyan-300 shadow-[0_0_9px_rgba(34,211,238,0.75)]" />
        <div className="absolute right-[2px] top-[15px] h-3.5 w-1.5 rounded-full bg-pink-300 shadow-[0_0_9px_rgba(244,114,182,0.75)]" />
      </div>
      <div className="absolute -right-0.5 -top-0.5 h-2.5 w-2.5 rounded-full bg-cyan-400 ring-2 ring-white shadow-[0_0_10px_rgba(34,211,238,0.8)] dark:ring-slate-900" />
    </div>
  )
}

function AssistantAvatar() {
  const [imageFailed, setImageFailed] = useState(false)
  if (imageFailed) {
    return <AssistantAvatarFallback />
  }
  return (
    <div
      className="relative mt-1 h-12 w-12 shrink-0 overflow-hidden rounded-full bg-gradient-to-br from-cyan-50 via-white to-pink-50 p-[2px] shadow-[0_12px_28px_rgba(56,189,248,0.2),0_0_24px_rgba(244,114,182,0.18)] ring-1 ring-cyan-200/80 dark:from-cyan-300/10 dark:via-slate-900 dark:to-pink-300/10 dark:ring-cyan-300/25"
      title="AI 助手"
      aria-label="AI 助手头像"
    >
      <img
        src="/ai-avatar.png"
        alt="AI 助手头像"
        className="h-full w-full rounded-full object-cover object-[50%_30%]"
        draggable={false}
        onError={() => setImageFailed(true)}
      />
      <div className="pointer-events-none absolute inset-0 rounded-full ring-1 ring-white/70 dark:ring-white/10" />
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
                <div className="inline-flex items-center rounded-full border border-cyan-200/70 bg-gradient-to-r from-[#eefaff] to-[#fff0fb] px-2.5 py-0.5 text-[11px] font-semibold text-[#2f6f8f] shadow-sm dark:border-cyan-300/20 dark:from-cyan-300/10 dark:to-pink-300/10 dark:text-cyan-100">
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
                    <div className="rounded-2xl border border-cyan-100/80 bg-gradient-to-r from-white to-[#f8fdff] px-4 py-3 shadow-[0_1px_2px_rgba(15,23,42,0.04)] dark:border-cyan-300/20 dark:from-slate-900 dark:to-slate-800">
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
                              className="rounded-2xl border border-cyan-100/80 bg-gradient-to-r from-white to-[#fbfdff] px-4 py-3 text-sm leading-relaxed text-slate-800 shadow-[0_1px_2px_rgba(15,23,42,0.04),0_8px_24px_rgba(56,189,248,0.06)] dark:border-cyan-300/20 dark:from-slate-900 dark:to-slate-800 dark:text-slate-100"
                            >
                              {block}
                            </div>
                          ))}
                        </div>
                      ) : (
                        <div className="rounded-2xl border border-cyan-100/80 bg-gradient-to-r from-white to-[#fbfdff] px-4 py-3 text-sm leading-relaxed text-slate-800 shadow-[0_1px_2px_rgba(15,23,42,0.04),0_8px_24px_rgba(56,189,248,0.06)] dark:border-cyan-300/20 dark:from-slate-900 dark:to-slate-800 dark:text-slate-100">
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
