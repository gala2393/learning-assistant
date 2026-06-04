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

/**
 * ChatThread — 聊天消息列表组件。
 *
 * 展示用户和 AI 之间的对话消息，支持：
 * - 用户消息（右侧，带头像）
 * - AI 回答（左侧，支持富文本渲染：标题、列表、表格、段落）
 * - "思考中"动画（三个跳动的圆点）
 * - 错误状态显示
 * - 图片消息显示（用户上传的图片）
 * - 来源引用卡片（RetrievalTrace）
 * - 自动滚动到底部
 */

/** 单条聊天消息的数据结构 */
export interface ChatMessage {
  id: string                              // 消息唯一 ID
  role: 'user' | 'assistant'             // 角色：用户 或 AI
  text: string                            // 消息文本
  thinking?: boolean                      // 是否正在"思考中"（显示加载动画）
  error?: string                          // 错误信息（如果有）
  sources?: RagSource[]                   // AI 回答引用的资料来源
  images?: Array<{ dataUrl: string; mediaType: string }>  // 附带的图片
}

/** 组件属性 */
interface ChatThreadProps {
  messages: ChatMessage[]                 // 消息列表
  onOpenSource?: (source: RagSource) => void  // 点击来源卡片的回调（跳转到阅读器）
}

/** 思考中动画 — 三个跳动的青色圆点 */
function ThinkingDots() {
  return (
    <div className="flex items-center gap-1 py-1">
      {[0, 1, 2].map((i) => (
        <motion.span key={i} className="inline-block h-2 w-2 rounded-full bg-cyan-400/70 ..."
          animate={{ opacity: [0.3, 1, 0.3] }}
          transition={{ duration: 1.2, repeat: Infinity, delay: i * 0.2 }} />
      ))}
    </div>
  )
}

/** 内联格式化文本 — 将 **加粗** 标记转为 <strong> 标签 */
function InlineFormattedText({ text }: { text: string }) {
  const parts = text.split(/(\*\*[^*]+\*\*)/g).filter(Boolean)
  return <>{parts.map((part, i) =>
    part.startsWith('**') && part.endsWith('**')
      ? <strong key={i} className="font-semibold text-slate-950 dark:text-white">{part.slice(2, -2)}</strong>
      : <span key={i}>{part}</span>
  )}</>
}

// ===== AI 回答的富文本解析 =====

/** AI 回答内容块类型 — 将文本解析为结构化的块 */
type AssistantBlock =
  | { type: 'hr' }                        // 分隔线
  | { type: 'heading'; level: number; content: string }  // 标题（# ## ###）
  | { type: 'list'; ordered: boolean; items: string[] }   // 列表（有序/无序）
  | { type: 'table'; headers: string[]; rows: string[][] } // 表格
  | { type: 'paragraph'; lines: string[] }  // 段落

/** 判断是否是分隔线（---、***、___） */
function isHrLine(line: string) { return /^\s*([-_*])(?:\s*\1){2,}\s*$/.test(line) }

/** 判断是否是表格分隔行（|---|---|） */
function isTableSeparator(line: string) {
  if (!line.includes('|')) return false
  const cells = splitTableRow(line)
  return cells.length >= 2 && cells.every((c) => /^:?-{3,}:?$/.test(c.replace(/\s+/g, '')))
}

/** 分割表格行的单元格 */
function splitTableRow(line: string) {
  return line.trim().replace(/^\|/, '').replace(/\|$/, '').split('|').map((c) => c.trim())
}

/** 判断是否是表格行（包含 | 且至少 2 个单元格） */
function isTableRow(line: string) { return line.includes('|') && splitTableRow(line).length >= 2 }

/**
 * 将 AI 回答文本解析为结构化的内容块。
 * 支持：标题(#/##/###)、列表(1./2. 或 -/•)、表格(|...|)、分隔线(---)、段落。
 */
function parseAssistantBlocks(text: string): AssistantBlock[] {
  const lines = text.replace(/\r\n/g, '\n').split('\n')
  const blocks: AssistantBlock[] = []
  let index = 0
  while (index < lines.length) {
    const line = lines[index].trim()
    if (!line) { index += 1; continue }
    const nextLine = lines[index + 1]?.trim() || ''
    // 表格检测：当前行是表格行 + 下一行是分隔行
    if (isTableRow(line) && isTableSeparator(nextLine)) {
      const headers = splitTableRow(line)
      const rows: string[][] = []; index += 2
      while (index < lines.length && isTableRow(lines[index])) { rows.push(splitTableRow(lines[index])); index += 1 }
      blocks.push({ type: 'table', headers, rows }); continue
    }
    if (isHrLine(line)) { blocks.push({ type: 'hr' }); index += 1; continue }
    const heading = line.match(/^(#{1,4})\s+(.+)$/)
    if (heading) { blocks.push({ type: 'heading', level: heading[1].length, content: heading[2] }); index += 1; continue }
    // 列表检测：有序(1. 2.) 或无序(- •)
    if (/^\d+[.、]\s+/.test(line) || /^[-•]\s+/.test(line)) {
      const ordered = /^\d+[.、]\s+/.test(line); const items: string[] = []
      while (index < lines.length) {
        const itemLine = lines[index].trim()
        if (ordered && /^\d+[.、]\s+/.test(itemLine)) { items.push(itemLine.replace(/^\d+[.、]\s+/, '')); index += 1; continue }
        if (!ordered && /^[-•]\s+/.test(itemLine)) { items.push(itemLine.replace(/^[-•]\s+/, '')); index += 1; continue }
        break
      }
      blocks.push({ type: 'list', ordered, items }); continue
    }
    // 段落：收集连续非空行
    const paragraphLines: string[] = []
    while (index < lines.length) {
      const pLine = lines[index].trim()
      if (!pLine) break
      if (paragraphLines.length > 0 && (isHrLine(pLine) || /^(#{1,4})\s+/.test(pLine) || /^\d+[.、]\s+/.test(pLine) || /^[-•]\s+/.test(pLine) || (isTableRow(pLine) && isTableSeparator(lines[index + 1]?.trim() || '')))) break
      paragraphLines.push(pLine.replace(/^#{1,4}\s+/, '')); index += 1
    }
    blocks.push({ type: 'paragraph', lines: paragraphLines })
  }
  return blocks
}

/** AI 回答内容渲染器 — 将解析后的内容块渲染为 React 元素 */
function AssistantContent({ text }: { text: string }) {
  const clean = sanitizeAiText(text).trim()
  if (!clean) return null
  const blocks = parseAssistantBlocks(clean)
  return (
    <div className="space-y-5 text-[15px] leading-8 text-slate-950 dark:text-slate-100">
      {blocks.map((block, bi) => {
        if (block.type === 'hr') return <hr key={bi} className="my-7 border-t border-slate-200 dark:border-slate-800" />
        if (block.type === 'heading') {
          const Tag = block.level <= 2 ? 'h2' : 'h3'
          return <Tag key={bi} className={cn('pt-1 font-bold ...', block.level <= 2 ? 'text-2xl' : 'text-xl')}><InlineFormattedText text={block.content} /></Tag>
        }
        if (block.type === 'list') {
          const Tag = block.ordered ? 'ol' : 'ul'
          return <Tag key={bi} className={cn('space-y-2 pl-6', block.ordered ? 'list-decimal' : 'list-disc')}>
            {block.items.map((item, i) => <li key={i} className="pl-1"><InlineFormattedText text={item} /></li>)}
          </Tag>
        }
        if (block.type === 'table') {
          return <div key={bi} className="my-6 overflow-x-auto border-y ...">
            <table className="w-full border-collapse text-left text-sm">
              <thead><tr>{block.headers.map((h, i) => <th key={i} className="px-4 py-3 font-medium ..."><InlineFormattedText text={h} /></th>)}</tr></thead>
              <tbody>{block.rows.map((row, ri) => <tr key={ri}>{block.headers.map((_, ci) => <td key={ci} className="px-4 py-3 ..."><InlineFormattedText text={row[ci] || ''} /></td>)}</tr>)}</tbody>
            </table>
          </div>
        }
        return <div key={bi} className="space-y-2">{block.lines.map((line, i) => <p key={i}><InlineFormattedText text={line} /></p>)}</div>
      })}
    </div>
  )
}

/** 检索追踪 — 显示 AI 回答引用的资料来源列表 */
function RetrievalTrace({ sources, onOpenSource }: { sources: RagSource[]; onOpenSource?: (s: RagSource) => void }) {
  const maxScore = Math.max(...sources.map((s) => Number(s.score || 0)), 0)
  const pageCount = new Set(sources.map((s) => s.pageNo).filter(Boolean)).size
  return (
    <div className="mt-2 space-y-2 rounded-lg border ...">
      <div className="flex flex-wrap items-center gap-2">
        <Badge variant="outline" className="gap-1 text-[10px]"><Activity className="h-3 w-3" />Retrieval trace</Badge>
        <span className="text-[11px] ...">{sources.length} chunks · top score {Math.round(maxScore * 100)}%</span>
        {pageCount > 0 && <span className="text-[11px] ..."><Layers3 className="h-3 w-3" />{pageCount} pages</span>}
      </div>
      <div className="space-y-2">{sources.map((s, i) => <SourceCard key={`${s.materialId}-${s.chunkId}-${i}`} source={s} rank={i + 1} maxScore={maxScore} onOpen={onOpenSource} />)}</div>
    </div>
  )
}

/**
 * ChatThread 主组件 — 渲染完整的聊天消息列表。
 *
 * 消息布局：
 * - 用户消息：右侧对齐，显示用户头像
 * - AI 回答：左侧对齐，支持富文本渲染 + 来源引用
 * - 自动滚动到最新消息
 */
export function ChatThread({ messages, onOpenSource }: ChatThreadProps) {
  const { session } = useAuth()
  const bottomRef = useRef<HTMLDivElement>(null)
  // 新消息到达时自动滚动到底部
  useEffect(() => { bottomRef.current?.scrollIntoView({ behavior: 'smooth', block: 'end' }) }, [messages])

  return (
    <ScrollArea className="h-full min-h-0 w-full overflow-hidden px-2 md:px-4">
      <div className="mx-auto max-w-5xl space-y-5 py-4 md:space-y-7 md:py-6">
        {messages.map((msg) => (
          <motion.div key={msg.id} initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.2 }}
            className={cn('flex gap-2 md:gap-3', msg.role === 'user' ? 'justify-end' : 'justify-start')}>
            <div className={cn('min-w-0', msg.role === 'user' ? 'max-w-[88%] md:max-w-[75%]' : 'w-full max-w-[940px]')}>
              {msg.role === 'user' ? (
                /* 用户消息 */
                <div className="rounded-2xl border ... px-4 py-3 text-sm ...">
                  {msg.thinking ? <ThinkingDots /> : msg.error ? (
                    <div className="flex items-center gap-2 text-destructive"><AlertCircle className="h-4 w-4" /><span>{msg.error}</span></div>
                  ) : (
                    <div className="space-y-2">
                      {msg.images && msg.images.length > 0 && (
                        <div className="flex flex-wrap gap-2">{msg.images.map((img, i) => <img key={i} src={img.dataUrl} alt={`图片 ${i + 1}`} className="h-20 w-20 rounded-lg border ..." />)}</div>
                      )}
                      <div className="whitespace-pre-wrap leading-relaxed">{msg.text}</div>
                    </div>
                  )}
                </div>
              ) : (
                /* AI 回答 */
                <div className="space-y-2">
                  {msg.thinking ? <div className="px-1 py-3"><ThinkingDots /></div>
                    : msg.error ? <div className="rounded-lg border border-rose-200 ..."><AlertCircle className="h-4 w-4" /><span>{msg.error}</span></div>
                    : <>
                        <article className="px-1 py-1 md:px-2"><AssistantContent text={msg.text} /></article>
                        {msg.sources && msg.sources.length > 0 && <RetrievalTrace sources={msg.sources} onOpenSource={onOpenSource} />}
                      </>
                  }
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
