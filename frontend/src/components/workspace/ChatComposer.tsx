import { useRef, useEffect } from 'react'
import { Textarea } from '@/components/ui/textarea'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { actionButtonBase, actionButtonIdle, actionButtonReady } from '@/lib/action-button-styles'
import { Bot, ImagePlus, Lock, Send, X } from 'lucide-react'
import type { ChatImagePayload } from '@/types'

/**
 * ChatComposer — 聊天输入框组件。
 *
 * 功能：
 * - 多行文本输入（自动调整高度）
 * - 快捷提示词芯片（点击直接填入输入框）
 * - 图片上传（点击按钮或粘贴截图，最多 4 张）
 * - 模型切换按钮（显示当前使用的模型名）
 * - 发送按钮（有内容时高亮，loading 时显示旋转动画）
 * - Enter 发送，Shift+Enter 换行
 * - 使用量提示（显示剩余问答次数）
 *
 * 使用场景：ChatPage 聊天主页 和 ReaderPage 阅读器内的问答面板
 */

/** 组件属性 */
interface ChatComposerProps {
  value: string                                    // 输入框中的文本
  onChange: (val: string) => void                   // 文本变化回调
  onSubmit: () => void                             // 发送消息回调
  loading?: boolean                                // 是否正在等待 AI 回答
  mode: 'GENERAL' | 'MATERIAL'                    // 当前问答模式
  onModeChange: (mode: 'GENERAL' | 'MATERIAL') => void  // 切换模式回调
  quickPrompts: string[]                           // 快捷提示词列表
  disabled?: boolean                               // 是否禁用（如超出使用限制）
  disabledHint?: string                            // 禁用时的提示文字
  centered?: boolean                               // 是否居中布局（首页空状态用）
  usageLabel?: string                              // 使用量提示文字
  modelLabel?: string                              // 当前模型名称
  customModelEnabled?: boolean                     // 是否启用了自定义模型
  onOpenModelSettings?: () => void                 // 打开模型设置弹窗
  images?: ChatImagePayload[]                      // 待发送的图片列表
  onImagesChange?: (images: ChatImagePayload[]) => void  // 图片列表变化回调
}

/** 图片限制常量 */
const MAX_IMAGES = 4        // 最多 4 张图片
const MAX_IMAGE_EDGE = 1280 // 图片最大边长 1280px（超过会压缩）
const JPEG_QUALITY = 0.82   // JPEG 压缩质量

export function ChatComposer({
  value, onChange, onSubmit, loading, mode, quickPrompts, disabled, disabledHint,
  centered, usageLabel, modelLabel, customModelEnabled, onOpenModelSettings,
  images = [], onImagesChange,
}: ChatComposerProps) {
  const textareaRef = useRef<HTMLTextAreaElement>(null)
  const fileInputRef = useRef<HTMLInputElement>(null)
  /** 是否可以发送（有文本或有图片，且不在 loading 和 disabled 状态） */
  const canSend = (value.trim().length > 0 || images.length > 0) && !loading && !disabled

  /** 键盘事件：Enter 发送，Shift+Enter 换行 */
  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      if (canSend) onSubmit()
    }
  }

  /** 自动调整输入框高度（随内容增长，最大 180px） */
  useEffect(() => {
    if (textareaRef.current) {
      textareaRef.current.style.height = 'auto'
      textareaRef.current.style.height = Math.min(textareaRef.current.scrollHeight, 180) + 'px'
    }
  }, [value])

  /** 添加图片文件（过滤非图片，压缩后追加到列表） */
  const addFiles = async (files: FileList | File[]) => {
    if (!onImagesChange || disabled) return
    const imageFiles = Array.from(files).filter((f) => f.type.startsWith('image/'))
    if (imageFiles.length === 0) return
    const remainingSlots = Math.max(0, MAX_IMAGES - images.length)
    const nextImages: ChatImagePayload[] = []
    for (const file of imageFiles.slice(0, remainingSlots)) {
      nextImages.push(await compressImage(file))  // 压缩图片（限制尺寸和质量）
    }
    if (nextImages.length > 0) onImagesChange([...images, ...nextImages])
  }

  /** 粘贴事件：如果粘贴的是图片，自动添加到图片列表 */
  const handlePaste = (event: React.ClipboardEvent<HTMLTextAreaElement>) => {
    const files = Array.from(event.clipboardData.files).filter((f) => f.type.startsWith('image/'))
    if (files.length === 0) return
    event.preventDefault()
    void addFiles(files)
  }

  /** 移除已添加的图片 */
  const removeImage = (index: number) => {
    onImagesChange?.(images.filter((_, i) => i !== index))
  }

  return (
    <div className={centered ? 'w-full' : 'border-t bg-background p-3 dark:border-slate-800 dark:bg-[#171a21] md:p-4'}>
      <div className={centered ? 'mx-auto w-full max-w-[760px] space-y-2 md:space-y-3' : 'mx-auto max-w-3xl space-y-2'}>

        {/* 使用量提示（如 "今日剩余 88 次"） */}
        {usageLabel && (
          <div className="flex justify-end">
            <Badge variant="secondary" className="rounded-full px-2.5 py-0.5 text-[11px] font-medium">{usageLabel}</Badge>
          </div>
        )}

        {/* 快捷提示词芯片（点击直接填入输入框） */}
        <div className={centered ? 'flex max-h-14 flex-wrap justify-center gap-1.5 overflow-hidden md:max-h-none md:gap-2' : 'flex max-h-8 flex-wrap gap-1.5 overflow-hidden md:max-h-none'}>
          {quickPrompts.map((p, i) => (
            <Badge key={i} variant="outline" className={centered ? 'cursor-pointer rounded-full ...' : 'cursor-pointer text-[11px] ...'}
              onClick={() => !disabled && onChange(p)}>
              {p.length > 18 ? p.slice(0, 18) + '...' : p}
            </Badge>
          ))}
        </div>

        {/* 已添加的图片预览（带移除按钮） */}
        {images.length > 0 && (
          <div className="flex flex-wrap gap-2 ...">
            {images.map((image, index) => (
              <div key={`${image.dataUrl.slice(0, 32)}-${index}`} className="group relative h-16 w-16 ...">
                <img src={image.dataUrl} alt={`待发送图片 ${index + 1}`} ... />
                <button onClick={() => removeImage(index)} aria-label="移除图片"><X className="h-3.5 w-3.5" /></button>
              </div>
            ))}
          </div>
        )}

        {/* 输入区域 */}
        <div className={centered ? 'min-h-[112px] rounded-[18px] border ...' : 'rounded-2xl border ...'}>
          {disabled ? (
            /* 禁用状态：显示锁定提示 */
            <div className="flex flex-1 items-center justify-center gap-2 ...">
              <Lock className="h-4 w-4" />
              <span>{disabledHint || '暂不可用'}</span>
            </div>
          ) : (
            <div className="flex min-h-[96px] flex-col">
              {/* 隐藏的文件选择器 */}
              <input ref={fileInputRef} type="file" accept="image/*" multiple className="hidden"
                onChange={(e) => { if (e.target.files) void addFiles(e.target.files); e.currentTarget.value = '' }} />
              {/* 文本输入框 */}
              <Textarea ref={textareaRef} value={value} onChange={(e) => onChange(e.target.value)}
                onKeyDown={handleKeyDown} onPaste={handlePaste}
                placeholder={mode === 'GENERAL' ? '描述你的问题，或粘贴图片后提问' : '基于当前资料提问，可附加图片...'}
                className={centered ? 'min-h-[58px] ...' : 'min-h-[54px] ...'} rows={1} />
              {/* 底部工具栏：图片按钮 + 模型切换 + 发送按钮 */}
              <div className="mt-2 flex items-center justify-between gap-2">
                <div className="flex min-w-0 items-center gap-2">
                  {/* 上传图片按钮 */}
                  <Button type="button" variant="outline" size="icon" className="h-10 w-10 shrink-0 ..."
                    onClick={() => fileInputRef.current?.click()} disabled={images.length >= MAX_IMAGES}
                    title="上传图片，也可以直接粘贴截图">
                    <ImagePlus className="h-4 w-4" />
                  </Button>
                  {/* 模型切换按钮 */}
                  {onOpenModelSettings && (
                    <Button type="button" variant="outline" className="h-10 shrink-0 ..." onClick={onOpenModelSettings} title="切换大模型">
                      <Bot className="mr-1.5 h-4 w-4" />
                      <span className="max-w-[128px] truncate">{customModelEnabled ? (modelLabel || '自定义模型') : 'gpt5.5模型'}</span>
                    </Button>
                  )}
                </div>
                {/* 发送按钮（有内容时高亮，loading 时旋转） */}
                <Button size="icon" className={`h-11 w-11 ... ${canSend ? actionButtonReady : actionButtonIdle}`}
                  onClick={onSubmit} disabled={!canSend}>
                  {loading ? <span className="animate-spin h-4 w-4 border-2 ..." /> : <Send className="h-4 w-4" />}
                </Button>
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  )
}

/**
 * 压缩图片 — 限制最大边长为 1280px，JPEG 质量 82%。
 * 上传到后端前先压缩，减少传输大小和 LLM 处理时间。
 */
async function compressImage(file: File): Promise<ChatImagePayload> {
  const sourceUrl = await readFileAsDataUrl(file)       // 读取为 Data URL
  const image = await loadImage(sourceUrl)               // 加载为 Image 对象
  const scale = Math.min(1, MAX_IMAGE_EDGE / Math.max(image.width, image.height))  // 计算缩放比例
  const width = Math.max(1, Math.round(image.width * scale))
  const height = Math.max(1, Math.round(image.height * scale))
  const canvas = document.createElement('canvas')        // 用 Canvas 绘制缩放后的图片
  canvas.width = width; canvas.height = height
  const context = canvas.getContext('2d')
  if (!context) return { dataUrl: sourceUrl, mediaType: file.type || 'image/png' }
  context.drawImage(image, 0, 0, width, height)
  const mediaType = file.type === 'image/png' ? 'image/png' : 'image/jpeg'
  const dataUrl = canvas.toDataURL(mediaType, mediaType === 'image/jpeg' ? JPEG_QUALITY : undefined)
  return { dataUrl, mediaType }
}

/** 读取文件为 Base64 Data URL */
function readFileAsDataUrl(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(String(reader.result || ''))
    reader.onerror = () => reject(reader.error)
    reader.readAsDataURL(file)
  })
}

/** 加载图片为 HTMLImageElement */
function loadImage(src: string): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const image = new Image()
    image.onload = () => resolve(image)
    image.onerror = reject
    image.src = src
  })
}
