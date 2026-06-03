import { useEffect, useRef } from 'react'
import { motion } from 'framer-motion'
import { ScrollArea } from '@/components/ui/scroll-area'
import { Badge } from '@/components/ui/badge'
import { cn, sanitizeAiText } from '@/lib/utils'
import { SourceCard } from './SourceCard'
import { Activity, AlertCircle, Layers3 } from 'lucide-react'
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
  images?: Array<{ dataUrl: string; mediaType: string }>
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

function InlineFormattedText({ text }: { text: string }) {
  const parts = text.split(/(\*\*[^*]+\*\*)/g).filter(Boolean)
  return (
    <>
      {parts.map((part, index) => {
        if (part.startsWith('**') && part.endsWith('**')) {
          return <strong key={index} className="font-semibold text-slate-950 dark:text-white">{part.slice(2, -2)}</strong>
        }
        return <span key={index}>{part}</span>
      })}
    </>
  )
}

type AssistantBlock =
  | { type: 'hr' }
  | { type: 'heading'; level: number; content: string }
  | { type: 'list'; ordered: boolean; items: string[] }
  | { type: 'table'; headers: string[]; rows: string[][] }
  | { type: 'paragraph'; lines: string[] }

function isHrLine(line: string) {
  return /^\s*([-_*])(?:\s*\1){2,}\s*$/.test(line)
}

function isTableSeparator(line: string) {
  if (!line.includes('|')) return false
  const cells = splitTableRow(line)
  return cells.length >= 2 && cells.every((cell) => /^:?-{3,}:?$/.test(cell.replace(/\s+/g, '')))
}

function splitTableRow(line: string) {
  return line
    .trim()
    .replace(/^\|/, '')
    .replace(/\|$/, '')
    .split('|')
    .map((cell) => cell.trim())
}

function isTableRow(line: string) {
  return line.includes('|') && splitTableRow(line).length >= 2
}

function parseAssistantBlocks(text: string): AssistantBlock[] {
  const lines = text.replace(/\r\n/g, '\n').split('\n')
  const blocks: AssistantBlock[] = []
  let index = 0

  while (index < lines.length) {
    const line = lines[index].trim()
    if (!line) {
      index += 1
      continue
    }

    const nextLine = lines[index + 1]?.trim() || ''
    if (isTableRow(line) && isTableSeparator(nextLine)) {
      const headers = splitTableRow(line)
      const rows: string[][] = []
      index += 2
      while (index < lines.length && isTableRow(lines[index])) {
        rows.push(splitTableRow(lines[index]))
        index += 1
      }
      blocks.push({ type: 'table', headers, rows })
      continue
    }

    if (isHrLine(line)) {
      blocks.push({ type: 'hr' })
      index += 1
      continue
    }

    const heading = line.match(/^(#{1,4})\s+(.+)$/)
    if (heading) {
      blocks.push({ type: 'heading', level: heading[1].length, content: heading[2] })
      index += 1
      continue
    }

    if (/^\d+[.、]\s+/.test(line) || /^[-•]\s+/.test(line)) {
      const ordered = /^\d+[.、]\s+/.test(line)
      const items: string[] = []
      while (index < lines.length) {
        const itemLine = lines[index].trim()
        if (ordered && /^\d+[.、]\s+/.test(itemLine)) {
          items.push(itemLine.replace(/^\d+[.、]\s+/, ''))
          index += 1
          continue
        }
        if (!ordered && /^[-•]\s+/.test(itemLine)) {
          items.push(itemLine.replace(/^[-•]\s+/, ''))
          index += 1
          continue
        }
        break
      }
      blocks.push({ type: 'list', ordered, items })
      continue
    }

    const paragraphLines: string[] = []
    while (index < lines.length) {
      const paragraphLine = lines[index].trim()
      const paragraphNextLine = lines[index + 1]?.trim() || ''
      if (!paragraphLine) break
      if (paragraphLines.length > 0 && (
        isHrLine(paragraphLine)
        || /^(#{1,4})\s+/.test(paragraphLine)
        || /^\d+[.、]\s+/.test(paragraphLine)
        || /^[-•]\s+/.test(paragraphLine)
        || (isTableRow(paragraphLine) && isTableSeparator(paragraphNextLine))
      )) {
        break
      }
      paragraphLines.push(paragraphLine.replace(/^#{1,4}\s+/, ''))
      index += 1
    }
    blocks.push({ type: 'paragraph', lines: paragraphLines })
  }

  return blocks
}

function AssistantContent({ text }: { text: string }) {
  const clean = sanitizeAiText(text).trim()
  if (!clean) return null

  const blocks = parseAssistantBlocks(clean)
  return (
    <div className="space-y-5 text-[15px] leading-8 text-slate-950 dark:text-slate-100 md:text-base md:leading-8">
      {blocks.map((block, blockIndex) => {
        if (block.type === 'hr') {
          return <hr key={blockIndex} className="my-7 border-t border-slate-200 dark:border-slate-800" />
        }

        if (block.type === 'heading') {
          const HeadingTag = block.level <= 2 ? 'h2' : 'h3'
          return (
            <HeadingTag
              key={blockIndex}
              className={cn(
                'pt-1 font-bold text-slate-950 dark:text-white',
                block.level <= 2 ? 'text-2xl leading-9' : 'text-xl leading-8',
              )}
            >
              <InlineFormattedText text={block.content} />
            </HeadingTag>
          )
        }

        if (block.type === 'list') {
          const ListTag = block.ordered ? 'ol' : 'ul'
          return (
            <ListTag key={blockIndex} className={cn('space-y-2 pl-6', block.ordered ? 'list-decimal' : 'list-disc')}>
              {block.items.map((item, index) => (
                <li key={index} className="pl-1"><InlineFormattedText text={item} /></li>
              ))}
            </ListTag>
          )
        }

        if (block.type === 'table') {
          return (
            <div key={blockIndex} className="my-6 overflow-x-auto border-y border-slate-200 py-3 dark:border-slate-800">
              <table className="w-full min-w-[560px] border-collapse text-left text-sm md:text-[15px]">
                <thead>
                  <tr className="border-b border-slate-200 dark:border-slate-800">
                    {block.headers.map((header, index) => (
                      <th key={index} className="px-4 py-3 font-medium text-slate-700 dark:text-slate-200">
                        <InlineFormattedText text={header} />
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {block.rows.map((row, rowIndex) => (
                    <tr key={rowIndex} className="border-b border-slate-100 last:border-b-0 dark:border-slate-800/80">
                      {block.headers.map((_, cellIndex) => (
                        <td key={cellIndex} className="px-4 py-3 align-top text-slate-950 dark:text-slate-100">
                          <InlineFormattedText text={row[cellIndex] || ''} />
                        </td>
                      ))}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )
        }

        return (
          <div key={blockIndex} className="space-y-2">
            {block.lines.map((line, index) => (
              <p key={index}><InlineFormattedText text={line} /></p>
            ))}
          </div>
        )
      })}
    </div>
  )
}

function RetrievalTrace({ sources, onOpenSource }: { sources: RagSource[]; onOpenSource?: (source: RagSource) => void }) {
  const maxScore = Math.max(...sources.map((source) => Number(source.score || 0)), 0)
  const pageCount = new Set(sources.map((source) => source.pageNo).filter(Boolean)).size

  return (
    <div className="mt-2 space-y-2 rounded-lg border border-slate-200/80 bg-slate-50/80 p-3 dark:border-slate-800 dark:bg-slate-950/40">
      <div className="flex flex-wrap items-center gap-2">
        <Badge variant="outline" className="gap-1 text-[10px]">
          <Activity className="h-3 w-3" />
          Retrieval trace
        </Badge>
        <span className="text-[11px] font-medium text-slate-600 dark:text-slate-300">
          {sources.length} chunks · top score {Math.round(maxScore * 100)}%
        </span>
        {pageCount > 0 && (
          <span className="inline-flex items-center gap-1 text-[11px] text-muted-foreground">
            <Layers3 className="h-3 w-3" />
            {pageCount} pages
          </span>
        )}
      </div>
      <div className="space-y-2">
        {sources.map((source, index) => (
          <SourceCard
            key={`${source.materialId}-${source.chunkId}-${index}`}
            source={source}
            rank={index + 1}
            maxScore={maxScore}
            onOpen={onOpenSource}
          />
        ))}
      </div>
    </div>
  )
}

export function ChatThread({ messages, onOpenSource }: ChatThreadProps) {
  const { session } = useAuth()
  const bottomRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth', block: 'end' })
  }, [messages])

  return (
    <ScrollArea className="h-full min-h-0 w-full overflow-hidden px-2 md:px-4">
      <div className="mx-auto max-w-5xl space-y-5 py-4 md:space-y-7 md:py-6">
        {messages.length === 0 && <div className="h-8" />}
        {messages.map((msg) => (
          <motion.div
            key={msg.id}
            initial={{ opacity: 0, y: 8 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.2 }}
            className={cn('flex gap-2 md:gap-3', msg.role === 'user' ? 'justify-end' : 'justify-start')}
          >
            <div className={cn('min-w-0', msg.role === 'user' ? 'max-w-[88%] md:max-w-[75%]' : 'w-full max-w-[940px]')}>
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
                    <div className="space-y-2">
                      {msg.images && msg.images.length > 0 && (
                        <div className="flex flex-wrap gap-2">
                          {msg.images.map((image, index) => (
                            <img
                              key={`${msg.id}-image-${index}`}
                              src={image.dataUrl}
                              alt={`上传图片 ${index + 1}`}
                              className="h-20 w-20 rounded-lg border border-slate-200 object-cover dark:border-slate-700"
                              draggable={false}
                            />
                          ))}
                        </div>
                      )}
                      <div className="whitespace-pre-wrap leading-relaxed">{msg.text}</div>
                    </div>
                  )}
                </div>
              ) : (
                <div className="space-y-2">
                  {msg.thinking ? (
                    <div className="px-1 py-3">
                      <ThinkingDots />
                    </div>
                  ) : msg.error ? (
                    <div className="rounded-lg border border-rose-200/60 bg-rose-50/80 px-4 py-3 text-sm text-rose-700 dark:border-rose-900/40 dark:bg-rose-950/30 dark:text-rose-200">
                      <div className="flex items-center gap-2">
                        <AlertCircle className="h-4 w-4" />
                        <span>{msg.error}</span>
                      </div>
                    </div>
                  ) : (
                    <>
                      <article className="px-1 py-1 md:px-2">
                        <AssistantContent text={msg.text} />
                      </article>

                      {msg.sources && msg.sources.length > 0 && (
                        <RetrievalTrace sources={msg.sources} onOpenSource={onOpenSource} />
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
