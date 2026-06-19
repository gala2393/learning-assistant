import { useEffect, useRef, useState } from 'react'
import { motion } from 'framer-motion'
import { ScrollArea } from '@/components/ui/scroll-area'
import { Badge } from '@/components/ui/badge'
import { cn, formatBytes, sanitizeAiText } from '@/lib/utils'
import { SourceCard } from './SourceCard'
import { Activity, AlertCircle, ArrowDown, ArrowRight, Check, Copy, FileText, Layers3 } from 'lucide-react'
import { useAuth } from '@/context/AuthContext'
import { UserAvatar } from '@/components/layout/UserAvatar'
import type { RagSource, RetrievalDebugEntry, TemporaryMaterial } from '@/types'
import { ImagePreviewDialog, type PreviewImage } from './ImagePreviewDialog'
import { TemporaryMaterialPreviewDialog } from './TemporaryMaterialPreviewDialog'

/**
 * ChatThread -- 聊天消息列表组件
 *
 * 【用途】
 * 展示用户和 AI 之间的完整对话消息流。
 * 在 ChatPage（聊天主页）和 ReaderAsk（阅读器问答面板）中复用。
 *
 * 【主要功能】
 * 1. 用户消息：右侧对齐，显示用户头像，支持图片缩略图
 * 2. AI 回答：左侧对齐，支持富文本渲染（标题、列表、表格、代码块、段落）
 * 3. "思考中"动画：三个跳动的青色圆点
 * 4. 错误状态：红色边框 + 错误图标
 * 5. 图片消息：用户上传的图片以缩略图网格展示，点击可放大
 * 6. 来源引用卡片（RetrievalTrace）：展示 RAG 检索到的资料片段
 * 7. 自动滚动到底部：新消息到达时自动滚动
 *
 * 【AI 回答渲染流程】
 * 文本 -> sanitizeAiText 清理 -> parseAssistantBlocks 解析为结构化块 -> AssistantContent 渲染
 * 支持的 Markdown 子集：# 标题、有序/无序列表、表格、代码块（```）、分隔线（---）、段落
 */

// ========== 类型定义 ==========

/**
 * 单条聊天消息的数据结构
 *
 * @property id - 消息唯一 ID
 * @property role - 角色：'user'（用户）或 'assistant'（AI）
 * @property text - 消息文本内容
 * @property thinking - 是否正在"思考中"（显示加载动画，尚未有实际内容）
 * @property error - 错误信息（如果有，显示错误提示而非正常内容）
 * @property sources - AI 回答引用的资料来源（RAG 检索结果）
 * @property images - 附带的图片列表（用户上传的图片，Base64 格式）
 * @property temporaryMaterial - 附带的临时资料（通用模式下上传的资料）
 * @property continuable - 当前 AI 回答是否可以继续生成
 * @property continuationHint - 继续生成提示语
 */
export interface ChatMessage {
  id: string
  role: 'user' | 'assistant'
  text: string
  thinking?: boolean
  error?: string
  sources?: RagSource[]
  retrievalDebug?: RetrievalDebugEntry[]
  images?: Array<{ dataUrl: string; mediaType: string }>
  temporaryMaterial?: TemporaryMaterial | null
  continuable?: boolean
  continuationHint?: string | null
}

/** ChatThread 组件属性 */
interface ChatThreadProps {
  messages: ChatMessage[]                 // 消息列表
  onOpenSource?: (source: RagSource) => void  // 点击来源卡片的回调（跳转到阅读器对应片段）
  onContinueGeneration?: () => void        // 点击继续生成按钮的回调
}

// ========== 思考中动画 ==========

/**
 * ThinkingDots -- 思考中动画组件
 * 三个跳动的青色圆点，依次延迟 0.2s，循环播放
 */
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

// ========== 内联文本格式化 ==========

/**
 * InlineFormattedText -- 将 **加粗** 标记转为 <strong> 标签
 * 用正则按 **...** 分割文本，加粗部分用 <strong> 包裹
 */
function InlineFormattedText({ text }: { text: string }) {
  const parts = text.split(/(\*\*[^*]+\*\*)/g).filter(Boolean)
  return <>{parts.map((part, i) =>
    part.startsWith('**') && part.endsWith('**')
      ? <strong key={i} className="font-semibold text-slate-950 dark:text-white">{part.slice(2, -2)}</strong>
      : <span key={i}>{part}</span>
  )}</>
}

// ========== AI 回答的富文本解析 ==========

/**
 * AI 回答内容块类型
 * 将原始文本解析为以下结构化块之一：
 */
type AssistantBlock =
  | { type: 'hr' }                                               // 分隔线（---、***、___）
  | { type: 'heading'; level: number; content: string }         // 标题（#/##/###）
  | { type: 'list'; ordered: boolean; items: string[] }         // 列表（有序 1.2.3. 或无序 - •）
  | { type: 'table'; headers: string[]; rows: string[][] }     // 表格（| ... | ... |）
  | { type: 'code'; language: string; code: string }            // 代码块（```lang ... ```）
  | { type: 'paragraph'; lines: string[] }                      // 普通段落

/** 判断是否是分隔线（连续 3 个以上的 -、* 或 _） */
function isHrLine(line: string) { return /^\s*([-_*])(?:\s*\1){2,}\s*$/.test(line) }

/** 判断是否是 Markdown 表格的分隔行（如 |---|---|） */
function isTableSeparator(line: string) {
  if (!line.includes('|')) return false
  const cells = splitTableRow(line)
  return cells.length >= 2 && cells.every((c) => /^:?-{3,}:?$/.test(c.replace(/\s+/g, '')))
}

/** 分割表格行的单元格（去掉首尾的 | 后按 | 分割） */
function splitTableRow(line: string) {
  return line.trim().replace(/^\|/, '').replace(/\|$/, '').split('|').map((c) => c.trim())
}

/** 判断是否是表格行（包含 | 且至少 2 个单元格） */
function isTableRow(line: string) { return line.includes('|') && splitTableRow(line).length >= 2 }

/**
 * parseAssistantBlocks -- 将 AI 回答文本解析为结构化的内容块
 *
 * 解析顺序（按优先级）：
 * 1. 代码块：``` 开头到 ``` 结尾
 * 2. 表格：当前行是表格行 + 下一行是分隔行 -> 表格开始
 * 3. 分隔线：---、***、___
 * 4. 标题：#/##/###/#### 开头
 * 5. 列表：有序（1. 2.）或无序（- •）
 * 6. 段落：连续非空行
 */
function parseAssistantBlocks(text: string): AssistantBlock[] {
  const lines = text.replace(/\r\n/g, '\n').split('\n')
  const blocks: AssistantBlock[] = []
  let index = 0
  while (index < lines.length) {
    const rawLine = lines[index]
    const line = rawLine.trim()
    // 跳过空行
    if (!line) { index += 1; continue }

    // 1. 检测代码块（``` 开头）
    const codeFence = line.match(/^```([\w.+#-]*)\s*$/)
    if (codeFence) {
      const codeLines: string[] = []
      const language = codeFence[1] || ''
      index += 1
      // 收集代码块内容直到遇到闭合的 ```
      while (index < lines.length && !lines[index].trim().startsWith('```')) {
        codeLines.push(lines[index])
        index += 1
      }
      if (index < lines.length) index += 1  // 跳过闭合的 ```
      blocks.push({ type: 'code', language, code: codeLines.join('\n').replace(/\n+$/, '') })
      continue
    }

    const nextLine = lines[index + 1]?.trim() || ''

    // 2. 检测表格（当前行是表格行 + 下一行是分隔行）
    if (isTableRow(line) && isTableSeparator(nextLine)) {
      const headers = splitTableRow(line)
      const rows: string[][] = []; index += 2
      // 收集表格数据行
      while (index < lines.length && isTableRow(lines[index])) { rows.push(splitTableRow(lines[index])); index += 1 }
      blocks.push({ type: 'table', headers, rows }); continue
    }

    // 3. 检测分隔线
    if (isHrLine(line)) { blocks.push({ type: 'hr' }); index += 1; continue }

    // 4. 检测标题（# ## ### ####）
    const heading = line.match(/^(#{1,4})\s+(.+)$/)
    if (heading) { blocks.push({ type: 'heading', level: heading[1].length, content: heading[2] }); index += 1; continue }

    // 5. 检测列表（有序 1. 2. 或无序 - •）
    if (/^\d+[.、]\s+/.test(line) || /^[-•]\s+/.test(line)) {
      const ordered = /^\d+[.、]\s+/.test(line); const items: string[] = []
      // 收集连续的列表项
      while (index < lines.length) {
        const itemLine = lines[index].trim()
        if (ordered && /^\d+[.、]\s+/.test(itemLine)) { items.push(itemLine.replace(/^\d+[.、]\s+/, '')); index += 1; continue }
        if (!ordered && /^[-•]\s+/.test(itemLine)) { items.push(itemLine.replace(/^[-•]\s+/, '')); index += 1; continue }
        if (!itemLine) { index += 1; continue }
        break
      }
      blocks.push({ type: 'list', ordered, items }); continue
    }

    // 6. 段落：收集连续非空行（遇到其他块类型时停止）
    const paragraphLines: string[] = []
    while (index < lines.length) {
      const pLine = lines[index].trim()
      if (!pLine) break
      // 如果已经收集了内容，且当前行是新块的开始，则停止
      if (paragraphLines.length > 0 && (pLine.startsWith('```') || isHrLine(pLine) || /^(#{1,4})\s+/.test(pLine) || /^\d+[.、]\s+/.test(pLine) || /^[-•]\s+/.test(pLine) || (isTableRow(pLine) && isTableSeparator(lines[index + 1]?.trim() || '')))) break
      paragraphLines.push(pLine.replace(/^#{1,4}\s+/, '')); index += 1
    }
    blocks.push({ type: 'paragraph', lines: paragraphLines })
  }
  return blocks
}

/** 获取代码块的语言标签（如果没有语言标注则显示 "code"） */
function languageLabel(language: string) {
  const value = language.trim()
  return value ? value : 'code'
}

/**
 * 复制文本到剪贴板的兼容方案
 * 优先使用 Clipboard API，不支持时降级为 textarea + execCommand
 */
async function copyText(text: string) {
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text)
      return
    }
  } catch {
    // Clipboard API 权限被阻止时，降级到 textarea 方案
  }
  const textarea = document.createElement('textarea')
  textarea.value = text
  textarea.style.position = 'fixed'
  textarea.style.left = '-9999px'
  document.body.appendChild(textarea)
  textarea.focus()
  textarea.select()
  document.execCommand('copy')
  document.body.removeChild(textarea)
}

/**
 * CodeBlock -- 代码块渲染组件
 * 展示带语言标签和复制按钮的代码块
 * 点击"复制"后 1.2 秒内显示"已复制"
 */
function CodeBlock({ language, code }: { language: string; code: string }) {
  const [copied, setCopied] = useState(false)
  const handleCopy = async () => {
    try {
      await copyText(code)
      setCopied(true)
      window.setTimeout(() => setCopied(false), 1200)  // 1.2 秒后恢复
    } catch {
      setCopied(false)
    }
  }

  return (
    <div className="my-4 max-w-full overflow-hidden rounded-lg border border-slate-200 bg-slate-50 shadow-sm dark:border-slate-800 dark:bg-[#0b1020]">
      <div className="flex h-10 items-center justify-between border-b border-slate-200 bg-white px-3 dark:border-white/10 dark:bg-[#101827]">
        <span className="text-xs font-medium text-slate-600 dark:text-slate-300">{languageLabel(language)}</span>
        <button
          type="button"
          onClick={handleCopy}
          className="inline-flex h-7 items-center gap-1.5 rounded-md px-2 text-xs font-medium text-slate-600 transition hover:bg-slate-100 hover:text-slate-950 dark:text-slate-300 dark:hover:bg-white/10 dark:hover:text-white"
          aria-label="复制代码"
          title="复制代码"
        >
          {copied ? <Check className="h-3.5 w-3.5" /> : <Copy className="h-3.5 w-3.5" />}
          {copied ? '已复制' : '复制'}
        </button>
      </div>
      <pre className="max-h-[520px] overflow-auto bg-[#f8fafc] p-4 text-[13px] leading-6 text-slate-900 dark:bg-[#0b1020] dark:text-slate-100">
        <code className="font-mono">{code}</code>
      </pre>
    </div>
  )
}

// ========== AI 回答内容渲染器 ==========

/**
 * AssistantContent -- AI 回答内容渲染器
 *
 * 渲染流程：
 * 1. sanitizeAiText 清理 AI 输出的文本（去除异常字符等）
 * 2. parseAssistantBlocks 解析为结构化内容块
 * 3. 根据块类型分别渲染：标题、列表、表格、代码块、段落
 */
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
          return <Tag key={bi} className={cn('space-y-2', block.ordered ? 'pl-0' : 'list-disc pl-6')}>
            {block.items.map((item, i) => (
              <li key={i} className={block.ordered ? 'flex gap-2 pl-0' : 'pl-1'}>
                {block.ordered && <span className="min-w-6 shrink-0 text-right font-semibold">{i + 1}.</span>}
                <span className="min-w-0"><InlineFormattedText text={item} /></span>
              </li>
            ))}
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
        if (block.type === 'code') return <CodeBlock key={bi} language={block.language} code={block.code} />
        return <div key={bi} className="space-y-2">{block.lines.map((line, i) => <p key={i}><InlineFormattedText text={line} /></p>)}</div>
      })}
    </div>
  )
}

// ========== 检索追踪 ==========

/**
 * RetrievalTrace -- 检索追踪组件
 *
 * 展示 AI 回答引用的资料来源列表（RAG 检索结果）。
 * 包含统计信息（chunk 数量、最高分数、页数）和每个来源的 SourceCard。
 */
function RetrievalTrace({
  sources,
  retrievalDebug,
  onOpenSource,
}: {
  sources: RagSource[]
  retrievalDebug?: RetrievalDebugEntry[]
  onOpenSource?: (s: RagSource) => void
}) {
  const displaySources = dedupeRagSources(sources).slice(0, 5)
  const maxScore = Math.max(...displaySources.map((s) => Number(s.score || 0)), 0)
  const pageCount = new Set(displaySources.map((s) => s.pageNo).filter(Boolean)).size
  const scoreText = `最高匹配 ${Math.round(maxScore * 100)}%`
  return (
    <div className="mt-2 space-y-2 rounded-lg border ...">
      <div className="flex flex-wrap items-center gap-2">
        <Badge variant="outline" className="gap-1 text-[10px]"><Activity className="h-3 w-3" />检索诊断</Badge>
        <span className="text-[11px] ...">{displaySources.length} 个片段 · {scoreText}</span>
        {pageCount > 0 && <span className="text-[11px] ..."><Layers3 className="h-3 w-3" />{pageCount} 页</span>}
      </div>
      <div className="space-y-2">{displaySources.map((s, i) => <SourceCard key={`${s.materialId}-${s.chunkId}-${i}`} source={s} rank={i + 1} maxScore={maxScore} debugEntry={findDebugEntryForSource(s, retrievalDebug)} onOpen={onOpenSource} />)}</div>
    </div>
  )
}

function dedupeRagSources(sources: RagSource[]) {
  const seenChunkIds = new Set<string>()
  const seenContentKeys = new Set<string>()
  return sources.filter((source) => {
    const excerpt = String(source.excerpt || '').replace(/\s+/g, '').slice(0, 120)
    const chunkIdKey = String(source.chunkId || '')
    if (chunkIdKey && seenChunkIds.has(chunkIdKey)) return false
    const contentKey = `${source.materialId || ''}:${source.pageNo || ''}:${excerpt || chunkIdKey}`
    if (seenContentKeys.has(contentKey)) return false
    if (chunkIdKey) seenChunkIds.add(chunkIdKey)
    seenContentKeys.add(contentKey)
    return true
  })
}

function findDebugEntryForSource(source: RagSource, retrievalDebug?: RetrievalDebugEntry[]) {
  if (!retrievalDebug?.length) return undefined
  const targetChunkId = String(source.chunkId || '')
  const exact = retrievalDebug.find((entry) => String(entry.chunkId || '') === targetChunkId)
  if (exact) return exact
  const sourceExcerpt = compactSourceExcerpt(source.excerpt)
  if (sourceExcerpt) {
    const excerptMatched = retrievalDebug.find((entry) =>
      String(entry.materialId || '') === String(source.materialId || '')
      && compactSourceExcerpt(entry.excerpt).includes(sourceExcerpt),
    )
    if (excerptMatched) return excerptMatched
  }
  const materialId = String(source.materialId || '')
  const pageNo = Number(source.pageNo || 0)
  return retrievalDebug.find((entry) =>
    String(entry.materialId || '') === materialId && Number(entry.pageNo || 0) === pageNo,
  )
}

function compactSourceExcerpt(value?: string | null) {
  return String(value || '').replace(/\s+/g, '').slice(0, 120)
}

/** 判断回答文本里是否包含后端追加的续写提示。 */
function hasContinuationNotice(text: string) {
  return /输入[“"']继续[”"']/.test(text) || text.includes('点击“继续生成”')
}

// ========== 临时资料卡片 ==========

/**
 * TemporaryMaterialCard -- 临时资料卡片组件
 * 在用户消息中展示附带的临时资料（可点击预览）
 */
function TemporaryMaterialCard({
  material,
  compact = false,
  onPreview,
}: {
  material: TemporaryMaterial
  compact?: boolean
  onPreview?: () => void
}) {
  const title = material.title || material.originalName || '临时资料'
  const sourceType = (material.sourceType || 'FILE').toUpperCase()
  const detail = material.fileSize ? `${sourceType} ${formatBytes(material.fileSize)}` : sourceType
  return (
    <button
      type="button"
      onClick={onPreview}
      className={cn(
        'flex min-w-0 items-center gap-3 rounded-2xl border border-slate-200 bg-white text-left shadow-sm transition hover:border-blue-300 hover:shadow-md focus:outline-none focus:ring-2 focus:ring-blue-400 dark:border-slate-800 dark:bg-slate-900 dark:hover:border-blue-700',
        compact ? 'w-full max-w-[520px] px-3 py-2.5' : 'w-full max-w-[340px] px-4 py-3',
      )}
      title="点击预览资料"
    >
      <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-blue-50 text-blue-600 dark:bg-blue-950/40 dark:text-blue-300">
        <FileText className="h-5 w-5" />
      </span>
      <span className="min-w-0">
        <span className="block truncate text-sm font-medium text-slate-900 dark:text-slate-100">{title}</span>
        <span className="mt-0.5 block truncate text-xs text-slate-500 dark:text-slate-400">{detail}</span>
      </span>
    </button>
  )
}

// ========== 主组件 ==========

/**
 * ChatThread 主组件 -- 渲染完整的聊天消息列表
 *
 * 【消息布局】
 * - 用户消息：右侧对齐，显示用户头像
 *   - 附带临时资料时，先显示资料卡片，再显示文本
 *   - 附带图片时，显示图片缩略图网格（点击可放大）
 * - AI 回答：左侧对齐，使用 AssistantContent 渲染富文本
 *   - 思考中：显示 ThinkingDots 动画
 *   - 出错时：红色错误提示
 *   - 正常回答：富文本 + 来源引用（RetrievalTrace）
 *
 * 【自动滚动】
 * 使用 bottomRef 标记末尾，messages 变化时通过 useEffect 触发平滑滚动
 */
export function ChatThread({ messages, onOpenSource, onContinueGeneration }: ChatThreadProps) {
  const { session } = useAuth()
  /** ScrollArea 的真实滚动视口；Radix 的 Root 不是实际滚动元素，所以要拿 Viewport。 */
  const viewportRef = useRef<HTMLDivElement>(null)
  /** 列表底部引用（用于自动滚动） */
  const bottomRef = useRef<HTMLDivElement>(null)
  /** 记录用户是否仍在底部附近；离开底部后，流式内容继续生成但不再打断阅读。 */
  const shouldAutoScrollRef = useRef(true)
  /** 控制“回到底部”按钮显隐；高频 token 更新时不依赖它判断是否滚动，避免状态滞后。 */
  const [showJumpToBottom, setShowJumpToBottom] = useState(false)
  /** 图片预览弹窗状态 */
  const [previewImage, setPreviewImage] = useState<PreviewImage | null>(null)
  /** 临时资料预览弹窗状态 */
  const [previewMaterial, setPreviewMaterial] = useState<TemporaryMaterial | null>(null)

  /**
   * 判断视口是否接近底部。
   * 保留一点阈值是为了避免 1-2px 的浏览器舍入误差，让“贴底自动跟随”更稳定。
   */
  const isNearBottom = (element: HTMLDivElement) =>
    element.scrollHeight - element.scrollTop - element.clientHeight < 96

  /**
   * 监听用户滚动：
   * - 用户在底部附近：允许流式输出继续自动跟随。
   * - 用户向上查看历史：暂停自动滚动，只显示“回到底部”按钮。
   */
  useEffect(() => {
    const viewport = viewportRef.current
    if (!viewport) return

    const syncAutoScrollState = () => {
      const nearBottom = isNearBottom(viewport)
      shouldAutoScrollRef.current = nearBottom
      setShowJumpToBottom(!nearBottom)
    }

    syncAutoScrollState()
    viewport.addEventListener('scroll', syncAutoScrollState, { passive: true })
    return () => viewport.removeEventListener('scroll', syncAutoScrollState)
  }, [])

  /**
   * 消息变化时只在“贴底状态”自动滚动。
   * 这样 SSE 流式输出仍会持续写入 DOM，但用户上滑阅读已生成内容时不会被拉回底部。
   */
  useEffect(() => {
    if (!shouldAutoScrollRef.current) return
    bottomRef.current?.scrollIntoView({ block: 'end' })
  }, [messages])

  /** 用户主动点击后恢复自动跟随，并立即定位到最新回答。 */
  const jumpToBottom = () => {
    shouldAutoScrollRef.current = true
    setShowJumpToBottom(false)
    bottomRef.current?.scrollIntoView({ behavior: 'smooth', block: 'end' })
  }

  const openPreviewImage = (image: PreviewImage) => setPreviewImage(image)
  const closePreviewImage = () => setPreviewImage(null)

  return (
    <>
      <div className="relative h-full min-h-0">
        <ScrollArea viewportRef={viewportRef} className="h-full min-h-0 w-full overflow-hidden px-2 md:px-4">
        <div className="mx-auto max-w-5xl space-y-5 py-3 md:space-y-7 md:py-6">
          {/* 遍历所有消息 */}
          {messages.map((msg, index) => {
            const canContinueGeneration = msg.role === 'assistant'
              && index === messages.length - 1
              && !msg.thinking
              && !msg.error
              && Boolean(onContinueGeneration)
              && Boolean(msg.continuable || hasContinuationNotice(msg.text))
            return (
            <motion.div key={msg.id} initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.2 }}
              className={cn('flex gap-2 md:gap-3', msg.role === 'user' ? 'justify-end' : 'justify-start')}>
              <div className={cn('min-w-0', msg.role === 'user' ? 'max-w-[88%] md:max-w-[75%]' : 'w-full max-w-[940px]')}>
                {msg.role === 'user' ? (
                  /* ---- 用户消息（右侧） ---- */
                  <div className="flex flex-col items-end gap-2">
                    {/* 附带的临时资料卡片 */}
                    {msg.temporaryMaterial && (
                      <TemporaryMaterialCard
                        material={msg.temporaryMaterial}
                        onPreview={() => setPreviewMaterial(msg.temporaryMaterial || null)}
                      />
                    )}
                    <div className="rounded-2xl border ... px-4 py-3 text-sm ...">
                      {msg.thinking ? <ThinkingDots /> : msg.error ? (
                        /* 错误状态 */
                        <div className="flex items-center gap-2 text-destructive"><AlertCircle className="h-4 w-4" /><span>{msg.error}</span></div>
                      ) : (
                        <div className="space-y-2">
                          {/* 附带的图片缩略图网格 */}
                          {msg.images && msg.images.length > 0 && (
                            <div className="flex flex-wrap gap-2">
                              {msg.images.map((img, i) => {
                                const alt = `图片 ${i + 1}`
                                return (
                                  <button
                                    key={`${msg.id}-${i}`}
                                    type="button"
                                    className="group relative h-20 w-20 overflow-hidden rounded-lg border border-slate-200 bg-slate-100 shadow-sm transition hover:border-cyan-400 hover:shadow-md focus:outline-none focus:ring-2 focus:ring-cyan-400 dark:border-slate-700 dark:bg-slate-800"
                                    onClick={() => openPreviewImage({ src: img.dataUrl, alt })}
                                    title="点击放大查看"
                                    aria-label={`放大查看${alt}`}
                                  >
                                    <img src={img.dataUrl} alt={alt} className="h-full w-full object-cover transition group-hover:scale-105" />
                                    {/* 悬浮提示文字 */}
                                    <span className="pointer-events-none absolute inset-x-0 bottom-0 bg-slate-950/62 px-1.5 py-1 text-[10px] font-medium text-white opacity-0 transition group-hover:opacity-100">
                                      点击查看
                                    </span>
                                  </button>
                                )
                              })}
                            </div>
                          )}
                          {/* 消息文本（保留换行符） */}
                          <div className="whitespace-pre-wrap leading-relaxed">{msg.text}</div>
                        </div>
                      )}
                    </div>
                  </div>
                ) : (
                  /* ---- AI 回答（左侧） ---- */
                  <div className="space-y-2">
                    {msg.thinking ? <div className="px-1 py-3"><ThinkingDots /></div>
                      : msg.error ? <div className="rounded-lg border border-rose-200 ..."><AlertCircle className="h-4 w-4" /><span>{msg.error}</span></div>
                      : <>
                          {/* AI 回答富文本渲染 */}
                          <article className="px-1 py-1 md:px-2"><AssistantContent text={msg.text} /></article>
                          {canContinueGeneration && (
                            <button
                              type="button"
                              onClick={onContinueGeneration}
                              className="ml-1 inline-flex h-9 items-center gap-1.5 rounded-full border border-cyan-200 bg-cyan-50 px-3 text-xs font-medium text-cyan-800 transition hover:border-cyan-300 hover:bg-cyan-100 focus:outline-none focus:ring-2 focus:ring-cyan-400 dark:border-cyan-900/70 dark:bg-cyan-950/35 dark:text-cyan-100 dark:hover:border-cyan-700 dark:hover:bg-cyan-900/45"
                              title={msg.continuationHint || '继续生成后续内容'}
                              aria-label="继续生成后续内容"
                            >
                              <ArrowRight className="h-3.5 w-3.5" />
                              继续生成
                            </button>
                          )}
                          {/* 检索来源引用（RAG 结果） */}
                          {msg.sources && msg.sources.length > 0 && (
                            <RetrievalTrace
                              sources={msg.sources}
                              retrievalDebug={msg.retrievalDebug}
                              onOpenSource={onOpenSource}
                            />
                          )}
                        </>
                    }
                  </div>
                )}
              </div>
              {/* 用户头像（仅用户消息右侧显示） */}
              {msg.role === 'user' && <UserAvatar session={session} className="mt-1 h-9 w-9 shrink-0 text-sm" />}
            </motion.div>
          )})}
          {/* 滚动锚点 */}
          <div ref={bottomRef} />
        </div>
        </ScrollArea>
        {showJumpToBottom && (
          <div className="pointer-events-none absolute inset-x-0 bottom-3 flex justify-center px-4">
            <button
              type="button"
              onClick={jumpToBottom}
              className="pointer-events-auto inline-flex h-9 items-center gap-2 rounded-full border border-slate-200 bg-white/95 px-3 text-xs font-medium text-slate-700 shadow-lg shadow-slate-900/10 backdrop-blur transition hover:border-cyan-200 hover:bg-cyan-50 hover:text-cyan-700 focus:outline-none focus:ring-2 focus:ring-cyan-400 dark:border-slate-700 dark:bg-slate-900/95 dark:text-slate-200 dark:hover:border-cyan-800 dark:hover:bg-cyan-950/60 dark:hover:text-cyan-200"
              aria-label="回到底部查看最新回答"
              title="回到底部查看最新回答"
            >
              <ArrowDown className="h-4 w-4" />
              回到底部查看最新回答
            </button>
          </div>
        )}
      </div>
      {/* 图片放大预览弹窗 */}
      <ImagePreviewDialog
        image={previewImage}
        onClose={closePreviewImage}
      />
      {/* 临时资料预览弹窗 */}
      <TemporaryMaterialPreviewDialog
        material={previewMaterial}
        onClose={() => setPreviewMaterial(null)}
      />
    </>
  )
}
