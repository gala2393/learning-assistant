import { type ClassValue, clsx } from 'clsx'
import { twMerge } from 'tailwind-merge'

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs))
}

export function formatBytes(bytes: number): string {
  if (bytes === 0) return '0 B'
  const k = 1024
  const sizes = ['B', 'KB', 'MB', 'GB']
  const i = Math.floor(Math.log(bytes) / Math.log(k))
  return `${parseFloat((bytes / Math.pow(k, i)).toFixed(1))} ${sizes[i]}`
}

export function formatDate(value: string | Date): string {
  if (typeof value === 'string') {
    const plainDateTime = value.match(/^(\d{4}-\d{2}-\d{2})[ T](\d{2}:\d{2}:\d{2})/)
    if (plainDateTime && !/[zZ]|[+-]\d{2}:?\d{2}$/.test(value)) {
      return `${plainDateTime[1]} ${plainDateTime[2]}`
    }
  }
  const date = value instanceof Date ? value : new Date(value)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}

export function truncate(text: string, maxLen: number): string {
  if (!text) return ''
  return text.length > maxLen ? text.slice(0, maxLen) + '...' : text
}

export function sanitizeAiText(text: string): string {
  if (!text) return ''
  return text
    .replace(/```[a-zA-Z0-9_-]*\n?/g, '')
    .replace(/```/g, '')
    .replace(/<br\s*\/?>/gi, '\n')
    .replace(/[ \t]+\n/g, '\n')
    .trim()
}

export function buildUploadChunks(file: File, chunkSize = 5 * 1024 * 1024): Blob[] {
  const chunks: Blob[] = []
  for (let offset = 0; offset < file.size; offset += chunkSize) {
    chunks.push(file.slice(offset, Math.min(file.size, offset + chunkSize)))
  }
  return chunks
}

export function inferSourceType(fileName: string): string {
  const lower = fileName.toLowerCase()
  if (lower.endsWith('.pdf')) return 'PDF'
  if (lower.endsWith('.docx')) return 'WORD'
  if (lower.endsWith('.pptx')) return 'PPT'
  if (lower.endsWith('.md')) return 'MD'
  if (lower.endsWith('.txt')) return 'TXT'
  if (lower.endsWith('.html') || lower.endsWith('.htm')) return 'WEB'
  return 'PDF'
}
