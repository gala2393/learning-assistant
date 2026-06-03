import { useRef, useEffect } from 'react'
import { Textarea } from '@/components/ui/textarea'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { actionButtonBase, actionButtonIdle, actionButtonReady } from '@/lib/action-button-styles'
import { Bot, ImagePlus, Lock, Send, X } from 'lucide-react'
import type { ChatImagePayload } from '@/types'

interface ChatComposerProps {
  value: string
  onChange: (val: string) => void
  onSubmit: () => void
  loading?: boolean
  mode: 'GENERAL' | 'MATERIAL'
  onModeChange: (mode: 'GENERAL' | 'MATERIAL') => void
  quickPrompts: string[]
  disabled?: boolean
  disabledHint?: string
  centered?: boolean
  usageLabel?: string
  modelLabel?: string
  customModelEnabled?: boolean
  onOpenModelSettings?: () => void
  images?: ChatImagePayload[]
  onImagesChange?: (images: ChatImagePayload[]) => void
}

const MAX_IMAGES = 4
const MAX_IMAGE_EDGE = 1280
const JPEG_QUALITY = 0.82

export function ChatComposer({
  value,
  onChange,
  onSubmit,
  loading,
  mode,
  quickPrompts,
  disabled,
  disabledHint,
  centered,
  usageLabel,
  modelLabel,
  customModelEnabled,
  onOpenModelSettings,
  images = [],
  onImagesChange,
}: ChatComposerProps) {
  const textareaRef = useRef<HTMLTextAreaElement>(null)
  const fileInputRef = useRef<HTMLInputElement>(null)
  const canSend = (value.trim().length > 0 || images.length > 0) && !loading && !disabled

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      if (canSend) onSubmit()
    }
  }

  useEffect(() => {
    if (textareaRef.current) {
      textareaRef.current.style.height = 'auto'
      textareaRef.current.style.height = Math.min(textareaRef.current.scrollHeight, 180) + 'px'
    }
  }, [value])

  const addFiles = async (files: FileList | File[]) => {
    if (!onImagesChange || disabled) return
    const imageFiles = Array.from(files).filter((file) => file.type.startsWith('image/'))
    if (imageFiles.length === 0) return
    const remainingSlots = Math.max(0, MAX_IMAGES - images.length)
    const nextImages: ChatImagePayload[] = []
    for (const file of imageFiles.slice(0, remainingSlots)) {
      nextImages.push(await compressImage(file))
    }
    if (nextImages.length > 0) {
      onImagesChange([...images, ...nextImages])
    }
  }

  const handlePaste = (event: React.ClipboardEvent<HTMLTextAreaElement>) => {
    const files = Array.from(event.clipboardData.files).filter((file) => file.type.startsWith('image/'))
    if (files.length === 0) return
    event.preventDefault()
    void addFiles(files)
  }

  const removeImage = (index: number) => {
    onImagesChange?.(images.filter((_, currentIndex) => currentIndex !== index))
  }

  return (
    <div className={centered ? 'w-full' : 'border-t bg-background p-3 dark:border-slate-800 dark:bg-[#171a21] md:p-4'}>
      <div className={centered ? 'mx-auto w-full max-w-[760px] space-y-2 md:space-y-3' : 'mx-auto max-w-3xl space-y-2'}>
        {usageLabel && (
          <div className="flex justify-end">
            <Badge variant="secondary" className="rounded-full px-2.5 py-0.5 text-[11px] font-medium">
              {usageLabel}
            </Badge>
          </div>
        )}

        <div className={centered ? 'flex max-h-14 flex-wrap justify-center gap-1.5 overflow-hidden md:max-h-none md:gap-2' : 'flex max-h-8 flex-wrap gap-1.5 overflow-hidden md:max-h-none'}>
          {quickPrompts.map((p, i) => (
            <Badge
              key={i}
              variant="outline"
              className={centered
                ? 'cursor-pointer rounded-full border-[#d9dde3] bg-white px-2.5 py-0.5 text-[11px] font-normal text-slate-600 hover:bg-[#f5f7fb] dark:bg-slate-900 dark:text-slate-300 md:px-3 md:py-1 md:text-[12px]'
                : 'cursor-pointer text-[11px] font-normal hover:bg-primary/10'}
              onClick={() => !disabled && onChange(p)}
            >
              {p.length > 18 ? p.slice(0, 18) + '...' : p}
            </Badge>
          ))}
        </div>

        {images.length > 0 && (
          <div className="flex flex-wrap gap-2 rounded-xl border border-slate-200 bg-white/80 p-2 dark:border-slate-700 dark:bg-slate-900/80">
            {images.map((image, index) => (
              <div key={`${image.dataUrl.slice(0, 32)}-${index}`} className="group relative h-16 w-16 overflow-hidden rounded-lg border border-slate-200 dark:border-slate-700">
                <img src={image.dataUrl} alt={`待发送图片 ${index + 1}`} className="h-full w-full object-cover" draggable={false} />
                <button
                  type="button"
                  className="absolute right-1 top-1 rounded-full bg-black/65 p-0.5 text-white opacity-100 transition-opacity md:opacity-0 md:group-hover:opacity-100"
                  onClick={() => removeImage(index)}
                  aria-label="移除图片"
                >
                  <X className="h-3.5 w-3.5" />
                </button>
              </div>
            ))}
          </div>
        )}

        <div className={centered ? 'min-h-[112px] rounded-[18px] border border-[#d7d9df] bg-white p-2.5 shadow-[0_10px_28px_rgba(15,23,42,0.08)] dark:border-slate-700 dark:bg-slate-900 sm:min-h-[132px] sm:rounded-[24px] sm:p-4' : 'rounded-2xl border border-slate-200 bg-white p-2 dark:border-slate-700 dark:bg-slate-900'}>
          {disabled ? (
            <div className="flex flex-1 items-center justify-center gap-2 rounded-md border border-dashed bg-muted/50 py-4 text-sm text-muted-foreground dark:border-slate-700 dark:bg-slate-900/60">
              <Lock className="h-4 w-4" />
              <span>{disabledHint || '暂不可用'}</span>
            </div>
          ) : (
            <div className="flex min-h-[96px] flex-col">
              <input
                ref={fileInputRef}
                type="file"
                accept="image/*"
                multiple
                className="hidden"
                onChange={(event) => {
                  if (event.target.files) void addFiles(event.target.files)
                  event.currentTarget.value = ''
                }}
              />
              <Textarea
                ref={textareaRef}
                value={value}
                onChange={(e) => onChange(e.target.value)}
                onKeyDown={handleKeyDown}
                onPaste={handlePaste}
                placeholder={mode === 'GENERAL' ? '描述你的问题，或粘贴图片后提问' : '基于当前资料提问，可附加图片...'}
                className={centered
                  ? 'min-h-[58px] max-h-[140px] flex-1 resize-none border-0 bg-transparent px-1 text-left text-sm shadow-none focus-visible:ring-0 focus-visible:ring-offset-0 placeholder:text-left sm:min-h-[76px] sm:text-base md:max-h-[180px]'
                  : 'min-h-[54px] max-h-[180px] flex-1 resize-none border-0 bg-transparent px-1 text-left shadow-none focus-visible:ring-0 focus-visible:ring-offset-0 placeholder:text-left dark:bg-slate-900 dark:text-slate-100'}
                rows={1}
              />
              <div className="mt-2 flex items-center justify-between gap-2">
                <div className="flex min-w-0 items-center gap-2">
                  <Button
                    type="button"
                    variant="outline"
                    size="icon"
                    className={centered ? 'h-10 w-10 shrink-0 rounded-full' : 'h-10 w-10 shrink-0 rounded-xl'}
                    onClick={() => fileInputRef.current?.click()}
                    disabled={images.length >= MAX_IMAGES}
                    title="上传图片，也可以直接粘贴截图"
                  >
                    <ImagePlus className="h-4 w-4" />
                  </Button>
                  {onOpenModelSettings && (
                    <Button
                      type="button"
                      variant="outline"
                      className={centered
                        ? 'h-10 shrink-0 rounded-full px-3 text-xs'
                        : 'h-10 shrink-0 rounded-xl px-3 text-xs'}
                      onClick={onOpenModelSettings}
                      title="切换大模型"
                    >
                      <Bot className="mr-1.5 h-4 w-4" />
                      <span className="max-w-[128px] truncate">
                        {customModelEnabled ? (modelLabel || '自定义模型') : 'gpt5.5模型'}
                      </span>
                    </Button>
                  )}
                </div>
                <Button
                  size="icon"
                  className={centered
                    ? `h-11 w-11 shrink-0 rounded-full ${actionButtonBase} ${canSend ? actionButtonReady : actionButtonIdle}`
                    : `h-10 w-10 shrink-0 rounded-xl ${actionButtonBase} ${canSend ? actionButtonReady : actionButtonIdle}`}
                  onClick={onSubmit}
                  disabled={!canSend}
                >
                  {loading ? (
                    <span className="animate-spin h-4 w-4 border-2 border-current border-t-transparent rounded-full" />
                  ) : (
                    <Send className="h-4 w-4" />
                  )}
                </Button>
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  )
}

async function compressImage(file: File): Promise<ChatImagePayload> {
  const sourceUrl = await readFileAsDataUrl(file)
  const image = await loadImage(sourceUrl)
  const scale = Math.min(1, MAX_IMAGE_EDGE / Math.max(image.width, image.height))
  const width = Math.max(1, Math.round(image.width * scale))
  const height = Math.max(1, Math.round(image.height * scale))
  const canvas = document.createElement('canvas')
  canvas.width = width
  canvas.height = height
  const context = canvas.getContext('2d')
  if (!context) {
    return { dataUrl: sourceUrl, mediaType: file.type || 'image/png' }
  }
  context.drawImage(image, 0, 0, width, height)
  const mediaType = file.type === 'image/png' ? 'image/png' : 'image/jpeg'
  const dataUrl = canvas.toDataURL(mediaType, mediaType === 'image/jpeg' ? JPEG_QUALITY : undefined)
  return { dataUrl, mediaType }
}

function readFileAsDataUrl(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(String(reader.result || ''))
    reader.onerror = () => reject(reader.error)
    reader.readAsDataURL(file)
  })
}

function loadImage(src: string): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const image = new Image()
    image.onload = () => resolve(image)
    image.onerror = reject
    image.src = src
  })
}
