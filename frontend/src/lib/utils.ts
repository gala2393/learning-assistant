import { type ClassValue, clsx } from 'clsx'
import { twMerge } from 'tailwind-merge'

/**
 * 合并 CSS 类名的工具函数 — 全项目通用，几乎每个组件都在用。
 *
 * 组合了两个库的能力：
 * - clsx：条件拼接 class，如 cn('base', isActive && 'active', undefined)
 * - tailwind-merge：智能合并冲突的 Tailwind class，如 cn('p-2 p-4') → 'p-4'（后者覆盖前者）
 *
 * @param inputs 任意数量的 class 字符串、对象、数组、布尔值
 * @returns 合并后的 class 字符串
 *
 * @example
 * cn('text-sm', 'p-2')              → 'text-sm p-2'
 * cn('p-2', isActive && 'bg-blue')  → 'p-2 bg-blue'（isActive 为 true 时）
 * cn('p-2', isActive && 'bg-blue')  → 'p-2'（isActive 为 false 时）
 * cn('p-2 p-4')                     → 'p-4'（冲突时后者胜出）
 */
export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs))
}

/**
 * 格式化字节大小为可读文本。
 * @example formatBytes(1024) → '1 KB', formatBytes(1048576) → '1 MB'
 */
export function formatBytes(bytes: number): string {
  if (bytes === 0) return '0 B'
  const k = 1024
  const sizes = ['B', 'KB', 'MB', 'GB']
  const i = Math.floor(Math.log(bytes) / Math.log(k))
  return `${parseFloat((bytes / Math.pow(k, i)).toFixed(1))} ${sizes[i]}`
}

/**
 * 格式化日期时间字符串。
 * 如果是纯日期时间字符串（无时区后缀），直接提取日期和时间部分显示，
 * 避免因时区转换导致显示错误。
 * @example formatDate('2024-01-15 14:30:00') → '2024-01-15 14:30:00'
 */
export function formatDate(value: string | Date): string {
  if (typeof value === 'string') {
    // 匹配纯日期时间格式（无时区后缀），如 '2024-01-15 14:30:00'
    const plainDateTime = value.match(/^(\d{4}-\d{2}-\d{2})[ T](\d{2}:\d{2}:\d{2})/)
    if (plainDateTime && !/[zZ]|[+-]\d{2}:?\d{2}$/.test(value)) {
      return `${plainDateTime[1]} ${plainDateTime[2]}`  // 直接返回，不做时区转换
    }
  }
  // 有时区信息的字符串或 Date 对象，格式化输出
  const date = value instanceof Date ? value : new Date(value)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}

/**
 * 截断文本，超过最大长度时添加省略号。
 * @example truncate('Hello World', 5) → 'Hello...'
 */
export function truncate(text: string, maxLen: number): string {
  if (!text) return ''
  return text.length > maxLen ? text.slice(0, maxLen) + '...' : text
}

/**
 * 清理 AI 回答中的 Markdown 格式标记。
 * 移除代码块标记、HTML <br> 标签、行尾空白等，保留纯文本内容。
 */
export function sanitizeAiText(text: string): string {
  if (!text) return ''
  return text
    .replace(/```[a-zA-Z0-9_-]*\n?/g, '')  // 去掉代码块开始标记 ```python 等
    .replace(/```/g, '')                      // 去掉代码块结束标记
    .replace(/<br\s*\/?>/gi, '\n')           // HTML 换行转为真实换行
    .replace(/[ \t]+\n/g, '\n')              // 去掉行尾空白
    .trim()
}

/**
 * 将文件切分为多个 chunk（用于分片上传）。
 * 大文件会被切成多个 5MB 的小块，逐片上传，支持断点续传。
 *
 * @param file 要切分的文件对象
 * @param chunkSize 每片大小（默认 5MB）
 * @returns Blob 数组（每片是一个 Blob）
 */
export function buildUploadChunks(file: File, chunkSize = 5 * 1024 * 1024): Blob[] {
  const chunks: Blob[] = []
  for (let offset = 0; offset < file.size; offset += chunkSize) {
    chunks.push(file.slice(offset, Math.min(file.size, offset + chunkSize)))
  }
  return chunks
}

/**
 * 根据文件扩展名推断资料类型。
 * @example inferSourceType('lecture.pdf') → 'PDF'
 */
export function inferSourceType(fileName: string): string {
  const lower = fileName.toLowerCase()
  if (lower.endsWith('.pdf')) return 'PDF'
  if (lower.endsWith('.docx')) return 'WORD'
  if (lower.endsWith('.pptx')) return 'PPT'
  if (lower.endsWith('.md')) return 'MD'
  if (lower.endsWith('.txt')) return 'TXT'
  if (lower.endsWith('.html') || lower.endsWith('.htm')) return 'WEB'
  return 'PDF'  // 默认当作 PDF 处理
}
