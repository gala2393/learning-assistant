import { useRef, useEffect, useState } from 'react'
import { Textarea } from '@/components/ui/textarea'
import { Button } from '@/components/ui/button'
import { actionButtonBase, actionButtonIdle, actionButtonReady } from '@/lib/action-button-styles'
import { BookOpen, Bot, FileText, Lock, Paperclip, Pause, Send, X } from 'lucide-react'
import { cn, formatBytes } from '@/lib/utils'
import type { ChatImagePayload, TemporaryMaterial } from '@/types'
import { ImagePreviewDialog, type PreviewImage } from './ImagePreviewDialog'
import { TemporaryMaterialPreviewDialog } from './TemporaryMaterialPreviewDialog'
import type { SelectedFileListItem } from './SelectedFilesInlineList'
import { ChatAttachmentCards } from './ChatAttachmentCards'

/**
 * ChatComposer -- 聊天输入框组件
 *
 * 【用途】
 * 提供用户输入问题、上传图片/附件、切换模型的交互界面。
 * 是聊天页面和阅读器问答面板共用的核心输入组件。
 *
 * 【使用场景】
 * 1. ChatPage 聊天主页（/workspace/chat）—— 居中布局（centered=true）或底部布局
 * 2. ReaderPage 阅读器内的问答面板（/workspace/reader）—— 底部布局
 *
 * 【主要功能】
 * - 多行文本输入（自动调整高度，最大 180px）
 * - 快捷提示词芯片（点击直接填入输入框）
 * - 图片上传（点击按钮或粘贴截图，最多 8 张，自动压缩）
 * - 文件附件上传（PDF/Word/PPT/TXT/MD/HTML）
 * - 模型切换按钮（显示当前使用的模型名）
 * - 发送/暂停按钮（未生成时发送，生成中暂停当前输出）
 * - Enter 发送，Shift+Enter 换行
 * - 使用量提示（显示剩余问答次数）
 */

/** 常量：最多 8 张图片 */
const MAX_IMAGES = 8
/** 常量：图片最大边长 1280px（超过会自动压缩） */
const MAX_IMAGE_EDGE = 1280
/** 常量：JPEG 压缩质量 0.82（平衡画质与文件大小） */
const JPEG_QUALITY = 0.82
/** 单次直接输入上限：实测 8000 字左右容易卡住，因此限制为 6000 字；更长内容请通过资料上传进入上下文。 */
const MAX_CHAT_INPUT_CHARS = 6000
/** 临时资料请求正文上限与 chat-session.ts 保持一致，超过后只会带入开头和结尾。 */
const TEMPORARY_MATERIAL_REQUEST_TEXT_LIMIT = 120_000

/**
 * 临时附件项类型
 * 继承自 SelectedFileListItem，额外携带 previewMaterial 以便点击时预览
 */
type TemporaryAttachmentItem = SelectedFileListItem & {
  previewMaterial?: TemporaryMaterial
}

/**
 * ChatComposer 组件属性
 *
 * @property value - 输入框中的文本
 * @property onChange - 文本变化回调，由父组件同步到全局状态
 * @property onSubmit - 发送消息回调（点击发送按钮或按 Enter）
 * @property onPauseOutput - 暂停当前流式输出的回调（生成中点击按钮触发）
 * @property loading - 是否正在等待 AI 回答（true 时主按钮切换为暂停输出）
 * @property mode - 当前问答模式：GENERAL（通用/智能问答）或 MATERIAL（资料问答）
 * @property onModeChange - 切换问答模式的回调
 * @property quickPrompts - 快捷提示词列表（显示在输入框下方的芯片）
 * @property disabled - 是否禁用输入框（如超出每日使用限制）
 * @property disabledHint - 禁用时的提示文字
 * @property centered - 是否居中布局（ChatPage 首页空状态使用大卡片样式）
 * @property usageLabel - 使用量提示文字（如"今日剩余：5/10"）
 * @property modelLabel - 当前模型名称（显示在切换按钮上）
 * @property boundMaterialLabel - 资料问答当前绑定资料名，显示在输入框上方作为轻量上下文提示
 * @property customModelEnabled - 是否启用了自定义模型
 * @property onOpenModelSettings - 打开模型设置弹窗的回调
 * @property images - 待发送的图片列表（Base64 DataURL 格式）
 * @property onImagesChange - 图片列表变化回调
 * @property onOpenUploadMaterial - 资料模式下打开资料上传弹窗
 * @property onUploadMaterialFile - 资料模式下单文件上传
 * @property onUploadMaterialFiles - 资料模式下多文件上传
 * @property onUploadTemporaryMaterial - 通用模式下单个临时资料上传
 * @property onUploadTemporaryMaterials - 通用模式下多个临时资料上传
 * @property temporaryMaterialUploading - 临时资料是否正在解析中
 * @property temporaryMaterial - 当前已加载的临时资料对象
 * @property temporaryUploadFile - 正在上传/解析的临时资料文件信息
 * @property temporaryUploadProgress - 临时资料上传/解析进度
 * @property temporaryUploadFiles - 智能问答临时上传的文件列表
 * @property temporaryUploadError - 临时资料上传/解析错误信息
 * @property onClearTemporaryMaterial - 清除临时资料
 * @property onRemoveTemporaryMaterialFile - 移除临时资料中的某个文件
 */
interface ChatComposerProps {
  value: string
  onChange: (val: string) => void
  onSubmit: () => void
  onPauseOutput?: () => void
  loading?: boolean
  mode: 'GENERAL' | 'MATERIAL'
  onModeChange: (mode: 'GENERAL' | 'MATERIAL') => void
  quickPrompts: string[]
  disabled?: boolean
  disabledHint?: string
  centered?: boolean
  usageLabel?: string
  modelLabel?: string
  boundMaterialLabel?: string
  customModelEnabled?: boolean
  onOpenModelSettings?: () => void
  images?: ChatImagePayload[]
  onImagesChange?: (images: ChatImagePayload[]) => void
  onOpenUploadMaterial?: () => void
  onUploadMaterialFile?: (file: File) => void
  onUploadMaterialFiles?: (files: File[]) => void
  onUploadTemporaryMaterial?: (file: File) => void
  onUploadTemporaryMaterials?: (files: File[]) => void
  temporaryMaterialUploading?: boolean
  temporaryMaterial?: TemporaryMaterial | null
  temporaryUploadFile?: { name: string; size: number; sourceType: string } | null
  temporaryUploadProgress?: { phase: 'uploading' | 'processing'; percent: number; message?: string } | null
  temporaryUploadFiles?: SelectedFileListItem[]
  temporaryUploadError?: string | null
  onClearTemporaryMaterial?: () => void
  onRemoveTemporaryMaterialFile?: (index: number) => void
}

export function ChatComposer({
  value, onChange, onSubmit, onPauseOutput, loading, mode, quickPrompts, disabled, disabledHint,
  centered, usageLabel, modelLabel, boundMaterialLabel, customModelEnabled, onOpenModelSettings,
  images = [], onImagesChange, onOpenUploadMaterial, onUploadMaterialFile, onUploadMaterialFiles, onUploadTemporaryMaterial, onUploadTemporaryMaterials,
  temporaryMaterialUploading, temporaryMaterial, temporaryUploadFile, temporaryUploadProgress, temporaryUploadFiles = [], temporaryUploadError, onClearTemporaryMaterial, onRemoveTemporaryMaterialFile,
}: ChatComposerProps) {
  // === Refs ===
  /** 输入框 DOM 引用（用于自动调整高度） */
  const textareaRef = useRef<HTMLTextAreaElement>(null)
  /** 隐藏的文件选择器引用（点击附件按钮时触发） */
  const attachmentInputRef = useRef<HTMLInputElement>(null)

  // === 状态 ===
  /** 图片预览弹窗状态 */
  const [previewImage, setPreviewImage] = useState<PreviewImage | null>(null)
  /** 临时资料预览弹窗状态 */
  const [previewMaterial, setPreviewMaterial] = useState<TemporaryMaterial | null>(null)
  /** 附件相关错误提示 */
  const [attachmentError, setAttachmentError] = useState('')

  // === 派生值 ===
  /** 是否可以发送（有文本或有图片，且不在 loading 和 disabled 状态，且临时资料不在解析中） */
  const canSend = (value.slice(0, MAX_CHAT_INPUT_CHARS).trim().length > 0 || images.length > 0)
    && !loading
    && !disabled
    && !temporaryMaterialUploading
  /** 生成中时同一个主按钮切换为暂停输出，避免用户找不到停止入口。 */
  const canPauseOutput = Boolean(loading && onPauseOutput)
  const inputCounterTone = value.length > MAX_CHAT_INPUT_CHARS * 0.9
    ? 'text-amber-600 dark:text-amber-300'
    : 'text-slate-400 dark:text-slate-500'
  /** 可见的快捷提示词（居中模式和非居中模式都显示 2 个） */
  const visiblePrompts = quickPrompts.slice(0, centered ? 2 : 2)
  /** 附件按钮的提示文字（通用模式和资料模式不同） */
  const attachmentTooltip = mode === 'GENERAL'
    ? '可上传图片和文件；智能问答临时文件最大 100MB'
    : '可上传图片和文件；资料文件最大 2GB'
  /** 临时文件附件列表（用于在输入框上方显示已上传的临时文件卡片） */
  const temporaryFileItems: TemporaryAttachmentItem[] = temporaryUploadFiles.length > 0
    ? temporaryUploadFiles
    : temporaryMaterialAttachmentItems(temporaryMaterial)
  /** 临时资料太长时提醒用户：普通智能问答不会建立完整索引。 */
  const temporaryMaterialTooLong = mode === 'GENERAL'
    && Boolean(temporaryMaterial)
    && temporaryMaterialTextLength(temporaryMaterial) > TEMPORARY_MATERIAL_REQUEST_TEXT_LIMIT

  // === 事件处理 ===

  /**
   * 键盘事件处理
   * - Enter（不按 Shift）：发送消息
   * - Shift+Enter：换行（默认行为，不阻止）
   */
  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      // 发送前复用 canSend，统一拦截 loading、disabled 和临时资料解析中的状态。
      if (canSend) onSubmit()
    }
  }

  // === 副作用 ===

  /**
   * 自动调整输入框高度
   * 每次 value 变化时，重置为 auto 再设为 scrollHeight（最大 180px）
   * 实现"随内容增长而变高"的效果
   */
  useEffect(() => {
    if (textareaRef.current) {
      textareaRef.current.style.height = 'auto'
      textareaRef.current.style.height = Math.min(textareaRef.current.scrollHeight, 180) + 'px'
    }
  }, [value])

  // === 图片处理 ===

  /**
   * 添加图片文件到待发送列表
   * 1. 过滤出图片类型文件
   * 2. 检查剩余槽位（最多 MAX_IMAGES 张）
   * 3. 对每张图片进行压缩（限制尺寸和质量）
   * 4. 追加到现有图片列表
   */
  const addFiles = async (files: FileList | File[]) => {
    if (!onImagesChange || disabled) return
    const imageFiles = Array.from(files).filter((f) => f.type.startsWith('image/'))
    if (imageFiles.length === 0) return
    setAttachmentError('')
    const remainingSlots = Math.max(0, MAX_IMAGES - images.length)
    const nextImages: ChatImagePayload[] = []
    // 只处理剩余槽位内的图片，避免一次粘贴多张图突破后端限制。
    for (const file of imageFiles.slice(0, remainingSlots)) {
      nextImages.push(await compressImage(file))  // 压缩图片（限制尺寸和质量）
    }
    if (nextImages.length > 0) onImagesChange([...images, ...nextImages])
  }

  /**
   * 粘贴事件处理
   * 如果用户粘贴的是图片（如截图），自动添加到图片列表
   */
  const handlePaste = (event: React.ClipboardEvent<HTMLTextAreaElement>) => {
    const files = Array.from(event.clipboardData.files).filter((f) => f.type.startsWith('image/'))
    if (files.length === 0) return
    event.preventDefault()
    void addFiles(files)
  }

  /** 移除已添加的图片（按索引） */
  const removeImage = (index: number) => {
    onImagesChange?.(images.filter((_, i) => i !== index))
  }

  /**
   * 附件文件选择处理
   * 将选中的文件分为"图片"和"文档"两类分别处理：
   * - 图片：走 addFiles 压缩后添加到图片列表
   * - 文档：根据模式调用不同的上传接口
   *   - GENERAL 模式：上传为临时资料（用于智能问答）
   *   - MATERIAL 模式：上传为持久资料（用于资料问答）
   */
  const handleAttachmentFiles = (files: FileList | null) => {
    if (!files || files.length === 0) return
    setAttachmentError('')
    const selectedFiles = Array.from(files)
    // 同一个 input 同时承载图片和文档，先拆分类型再分别进入图片压缩或资料上传流程。
    const imageFiles = selectedFiles.filter((file) => file.type.startsWith('image/'))
    const documentFiles = selectedFiles.filter((file) => !file.type.startsWith('image/'))
    if (imageFiles.length > 0) {
      void addFiles(imageFiles)
    }
    if (documentFiles.length === 0) return
    // 根据模式调用不同的上传接口
    if (mode === 'GENERAL') {
      // 通用模式：上传为临时资料
      if (onUploadTemporaryMaterials) onUploadTemporaryMaterials(documentFiles)
      else documentFiles.forEach((file) => onUploadTemporaryMaterial?.(file))
    } else if (onUploadMaterialFiles) {
      // 资料模式：批量上传为持久资料
      onUploadMaterialFiles(documentFiles)
    } else if (onUploadMaterialFile) {
      // 资料模式：逐个上传
      documentFiles.forEach((file) => onUploadMaterialFile(file))
    } else {
      // 没有直接上传回调时降级打开上传弹窗，让父组件接管持久资料上传。
      onOpenUploadMaterial?.()
    }
  }

  // === 渲染 ===
  return (
    <div className={centered ? 'w-full' : 'bg-white px-3 pb-3 pt-2 dark:bg-[#171a21] md:px-4 md:py-2.5'}>
      <div className={centered ? 'mx-auto w-full max-w-[760px] space-y-2' : 'mx-auto max-w-3xl space-y-1'}>

        {/* ---- 已添加的图片预览区域 ---- */}
        {images.length > 0 && (
          <div className="flex flex-wrap gap-2">
            {images.map((image, index) => (
              <div
                key={`${image.dataUrl.slice(0, 32)}-${index}`}
                className="group relative h-16 w-16 overflow-hidden rounded-lg border border-slate-200 bg-slate-100 shadow-sm dark:border-slate-700 dark:bg-slate-800"
              >
                {/* 点击缩略图可放大预览 */}
                <button
                  type="button"
                  className="h-full w-full focus:outline-none focus:ring-2 focus:ring-cyan-400"
                  onClick={() => setPreviewImage({ src: image.dataUrl, alt: `待发送图片 ${index + 1}` })}
                  title="点击放大查看"
                  aria-label={`放大查看待发送图片 ${index + 1}`}
                >
                  <img
                    src={image.dataUrl}
                    alt={`待发送图片 ${index + 1}`}
                    className="h-full w-full object-cover transition group-hover:scale-105"
                  />
                </button>
                {/* 悬浮时显示的删除按钮 */}
                <button
                  type="button"
                  onClick={() => removeImage(index)}
                  aria-label="移除图片"
                  className="absolute right-1 top-1 flex h-5 w-5 items-center justify-center rounded-full bg-slate-950/75 text-white opacity-0 transition group-hover:opacity-100"
                >
                  <X className="h-3.5 w-3.5" />
                </button>
              </div>
            ))}
          </div>
        )}

        {/* ---- 临时资料上传进度提示（仅通用模式） ---- */}
        {mode === 'GENERAL' && temporaryUploadProgress && (
          <div className="max-w-[520px] rounded-xl border border-blue-200 bg-blue-50 px-3 py-2 text-xs leading-5 text-blue-700 dark:border-blue-900 dark:bg-blue-950/30 dark:text-blue-300">
            {temporaryUploadProgress.message || (temporaryUploadProgress.phase === 'uploading' ? '正在上传资料' : '正在解析资料')} · {Math.round(temporaryUploadProgress.percent)}%
          </div>
        )}
        {/* ---- 临时资料上传错误提示 ---- */}
        {mode === 'GENERAL' && temporaryUploadError && (
          <div className="max-w-[520px] rounded-xl border border-red-200 bg-red-50 px-3 py-2 text-xs leading-5 text-red-700 dark:border-red-900 dark:bg-red-950/30 dark:text-red-300">
            {temporaryUploadError}
          </div>
        )}
        {/* ---- 附件通用错误提示 ---- */}
        {attachmentError && (
          <div className="max-w-[520px] rounded-xl border border-amber-200 bg-amber-50 px-3 py-2 text-xs leading-5 text-amber-800 dark:border-amber-900/60 dark:bg-amber-950/30 dark:text-amber-200">
            {attachmentError}
          </div>
        )}
        {/* ---- 资料问答绑定提示：贴近输入框，用户提问前能看见当前上下文。 ---- */}
        {mode === 'MATERIAL' && boundMaterialLabel && (
          <div className="flex max-w-full items-center gap-2 rounded-full border border-[#dfe7f2] bg-[#f4f7fb] px-3 py-1.5 text-xs text-[#5d6b82] shadow-[0_4px_16px_rgba(15,23,42,0.03)] dark:border-cyan-900/50 dark:bg-cyan-950/25 dark:text-cyan-100">
            <BookOpen className="h-3.5 w-3.5 shrink-0" />
            <span className="shrink-0 font-medium">已绑定</span>
            <span className="min-w-0 truncate text-[#6b7890] dark:text-cyan-100/90" title={boundMaterialLabel}>
              {boundMaterialLabel}
            </span>
          </div>
        )}

        {/* ---- 输入区域主体 ---- */}
        <div
          className={
            centered
              ? 'min-h-[112px] rounded-[22px] border border-[#e5e9ef] bg-white px-4 py-3 shadow-[0_18px_48px_rgba(15,23,42,0.06)] dark:border-slate-700 dark:bg-slate-900'
              : 'rounded-[22px] border border-[#e5e9ef] bg-white px-4 py-3 shadow-[0_6px_20px_rgba(15,23,42,0.04)] dark:border-slate-800 dark:bg-slate-900'
          }
        >
          {disabled ? (
            /* ---- 禁用状态：显示锁定图标和提示文字 ---- */
            <div className="flex min-h-[86px] flex-1 items-center justify-center gap-2 text-sm text-slate-500 dark:text-slate-400">
              <Lock className="h-4 w-4" />
              <span>{disabledHint || '暂不可用'}</span>
            </div>
          ) : (
            <div className="flex min-h-[86px] flex-col">
              {/* 隐藏的文件选择器（accept 限制文件类型，multiple 允许多选） */}
              <input
                ref={attachmentInputRef}
                type="file"
                accept="image/*,.pdf,.doc,.docx,.pptx,.txt,.md,.html,.htm"
                multiple
                className="hidden"
                data-testid="chat-attachment-input"
                onChange={(e) => {
                  handleAttachmentFiles(e.target.files)
                  e.currentTarget.value = ''  // 重置 input 以便重复选择同一文件
                }}
              />

        {/* ---- 已上传的临时资料附件卡片（仅通用模式，显示在输入框上方） ---- */}
        {mode === 'GENERAL' && temporaryFileItems.length > 0 && (
          <ChatAttachmentCards
                  files={temporaryFileItems}
                  onOpen={(file) => setPreviewMaterial((file as TemporaryAttachmentItem).previewMaterial || temporaryMaterial || null)}
                  onRemove={(_, index) => onRemoveTemporaryMaterialFile?.(index)}
          />
        )}
        {temporaryMaterialTooLong && (
          <div className="mb-2 rounded-xl border border-amber-200 bg-amber-50 px-3 py-2 text-xs leading-5 text-amber-800 dark:border-amber-900/60 dark:bg-amber-950/30 dark:text-amber-200">
            临时资料较长，本轮智能问答会优先带入开头和结尾；如果需要完整检索每一页/每一段，请切换到资料问答上传。
          </div>
        )}

              {/* ---- 文本输入框 ---- */}
              <Textarea ref={textareaRef} value={value} onChange={(e) => onChange(e.target.value.slice(0, MAX_CHAT_INPUT_CHARS))}
                onKeyDown={handleKeyDown} onPaste={handlePaste}
                maxLength={MAX_CHAT_INPUT_CHARS}
                data-testid="chat-input"
                placeholder={mode === 'GENERAL' ? '描述你的问题，或粘贴图片后提问' : '基于当前资料提问，可附加图片...'}
                className={
                  centered
                    ? 'min-h-[50px] resize-none border-0 bg-transparent px-0 py-0 text-base shadow-none outline-none focus:outline-none focus-visible:outline-none focus-visible:ring-0 focus-visible:ring-offset-0 md:text-[15px]'
                    : 'min-h-[42px] resize-none border-0 bg-transparent px-0 py-0 text-base shadow-none outline-none focus:outline-none focus-visible:outline-none focus-visible:ring-0 focus-visible:ring-offset-0 md:text-sm'
                }
                rows={1}
              />

              {/* ---- 底部工具栏：模型按钮 + 快捷提示词 + 附件按钮 + 发送按钮 ---- */}
              <div className="mt-auto flex items-end justify-between gap-2 pt-2">
                <div className="flex min-w-0 flex-1 items-center gap-1.5 overflow-hidden">
                  {/* 模型切换按钮（显示当前模型名） */}
                  {onOpenModelSettings && (
                    <Button type="button" variant="ghost" className="h-8 shrink-0 rounded-full bg-[#f5f7fa] px-2.5 text-xs text-slate-700 hover:bg-[#edf1f5] dark:bg-slate-800/60 dark:text-slate-200 dark:hover:bg-slate-800" onClick={onOpenModelSettings} title="切换大模型">
                      <Bot className="mr-1.5 h-3.5 w-3.5" />
                      <span className="max-w-[88px] truncate">{customModelEnabled ? (modelLabel || '自定义模型') : 'gpt5.5模型'}</span>
                    </Button>
                  )}
                  {/* 快捷提示词芯片（点击直接填入输入框） */}
                  {visiblePrompts.map((prompt, index) => (
                    <button
                      key={`${prompt}-${index}`}
                      type="button"
                      className="hidden h-8 max-w-[168px] shrink truncate rounded-full px-2.5 text-xs font-medium text-slate-500 transition hover:bg-[#f5f7fa] hover:text-slate-700 dark:text-slate-400 dark:hover:bg-slate-800 dark:hover:text-slate-200 sm:inline-block"
                      onClick={() => !disabled && onChange(prompt.slice(0, MAX_CHAT_INPUT_CHARS))}
                      title={prompt}
                    >
                      {prompt}
                    </button>
                  ))}
                </div>
                <div className="flex shrink-0 items-center gap-1.5">
                  {/* 附件/文件上传按钮（临时资料解析中时显示旋转动画） */}
                  <Button
                    type="button"
                    variant="ghost"
                    size="icon"
                    className="h-8 w-8 rounded-full text-slate-500 hover:bg-[#edf1f5] dark:text-slate-300 dark:hover:bg-slate-800"
                    onClick={() => attachmentInputRef.current?.click()}
                    disabled={temporaryMaterialUploading}
                    title={attachmentTooltip}
                    aria-label={attachmentTooltip}
                  >
                    {temporaryMaterialUploading
                      ? <span className="h-4 w-4 animate-spin rounded-full border-2 border-slate-300 border-t-slate-600 dark:border-slate-600 dark:border-t-slate-100" />
                      : <Paperclip className="h-4 w-4" />}
                  </Button>
                  {/* 发送/暂停按钮：未生成时发送，生成中切换为暂停，保留已输出内容。 */}
                  <Button
                    type="button"
                    size="icon"
                    className={`h-9 w-9 rounded-full ${actionButtonBase} ${canSend || canPauseOutput ? actionButtonReady : actionButtonIdle}`}
                    onClick={canPauseOutput ? onPauseOutput : onSubmit}
                    disabled={canPauseOutput ? false : !canSend}
                    data-testid="chat-submit-button"
                    aria-label={canPauseOutput ? '暂停输出' : '发送消息'}
                    title={canPauseOutput ? '暂停输出' : '发送消息'}
                  >
                    {canPauseOutput ? <Pause className="h-4 w-4" /> : <Send className="h-4 w-4" />}
                  </Button>
                </div>
              </div>
            </div>
          )}
        </div>

        {/* ---- 底部提示文字（AI 免责声明 + 使用量；手机端也显示次数，空间不足时自动换行） ---- */}
        <div className="flex min-h-4 flex-wrap items-center justify-center gap-x-2 gap-y-0.5 text-center text-[11px] text-slate-400 dark:text-slate-500">
          <span>内容由 AI 生成，请仔细甄别</span>
          <span className={cn('whitespace-nowrap', inputCounterTone)}>· {value.length}/{MAX_CHAT_INPUT_CHARS}</span>
          {usageLabel && <span className="whitespace-nowrap">· {usageLabel}</span>}
        </div>
      </div>

      {/* 图片放大预览弹窗 */}
      <ImagePreviewDialog image={previewImage} onClose={() => setPreviewImage(null)} />
      {/* 临时资料预览弹窗 */}
      <TemporaryMaterialPreviewDialog material={previewMaterial} onClose={() => setPreviewMaterial(null)} />
    </div>
  )
}

/**
 * TemporaryMaterialPreview -- 临时资料预览卡片组件（未被直接使用，保留供扩展）
 *
 * 展示临时资料的文件名、大小、类型，以及上传/解析进度。
 * 上传中时显示旋转动画和百分比，完成后显示文件信息。
 */
function TemporaryMaterialPreview({
  material,
  uploadingFile,
  uploading,
  progress,
  onPreview,
  onClear,
}: {
  material?: TemporaryMaterial | null
  uploadingFile?: { name: string; size: number; sourceType: string } | null
  uploading?: boolean
  progress?: { phase: 'uploading' | 'processing'; percent: number; message?: string } | null
  onPreview?: () => void
  onClear?: () => void
}) {
  const title = material?.title || material?.originalName || uploadingFile?.name || '临时资料'
  const sourceType = (material?.sourceType || uploadingFile?.sourceType || 'FILE').toUpperCase()
  const size = material?.fileSize ?? uploadingFile?.size
  const percent = Math.max(0, Math.min(100, Math.round(progress?.percent ?? (uploading ? 12 : 100))))
  const phaseLabel = progress?.message || (progress?.phase === 'uploading' ? '正在上传' : '正在解析资料')
  const concisePhaseLabel = uploading
    ? progress?.phase === 'uploading'
      ? '上传中'
      : phaseLabel.includes('OCR')
        ? 'OCR 解析中'
        : '解析中'
    : ''
  const detail = uploading ? `${phaseLabel} · ${percent}%` : size ? `${sourceType} ${formatBytes(size)}` : sourceType
  const iconTone = sourceType === 'PDF'
    ? 'bg-red-50 text-red-500 dark:bg-red-950/35 dark:text-red-300'
    : 'bg-blue-50 text-blue-600 dark:bg-blue-950/35 dark:text-blue-300'
  return (
    <div className="inline-flex w-full max-w-[320px] items-center gap-2.5 rounded-2xl border border-[#e5e9ef] bg-white px-3 py-2 text-left shadow-[0_6px_20px_rgba(15,23,42,0.04)] dark:border-slate-800 dark:bg-slate-900 sm:w-[320px]">
      <button
        type="button"
        className="flex min-w-0 flex-1 items-center gap-2.5 text-left outline-none focus-visible:ring-2 focus-visible:ring-blue-400"
        onClick={onPreview}
        disabled={!onPreview}
        title={onPreview ? '点击预览资料' : undefined}
      >
        <span className={cn('flex h-9 w-9 shrink-0 items-center justify-center rounded-xl', iconTone)}>
          {uploading
            ? <span className="h-[18px] w-[18px] animate-spin rounded-full border-2 border-current border-r-transparent opacity-80" />
            : <FileText className="h-[18px] w-[18px]" />}
        </span>
        <span className="min-w-0 flex-1">
          <span className="block truncate text-sm font-medium text-slate-900 dark:text-slate-100">{title}</span>
          {uploading ? (
            <span className="mt-0.5 flex min-w-0 items-center gap-2 text-xs text-blue-600 dark:text-blue-300">
              <span className="min-w-0 flex-1 truncate">{concisePhaseLabel}</span>
              <span className="shrink-0 tabular-nums">{percent}%</span>
            </span>
          ) : (
            <span className="mt-0.5 block truncate text-xs text-slate-500 dark:text-slate-400">{detail}</span>
          )}
        </span>
      </button>
      {onClear && (
        <button
          type="button"
          className="shrink-0 rounded-full p-1 text-slate-400 transition hover:bg-slate-100 hover:text-slate-700 dark:hover:bg-slate-800 dark:hover:text-slate-100"
          onClick={onClear}
          aria-label="移除临时资料"
          title="移除临时资料"
        >
          <X className="h-4 w-4" />
        </button>
      )}
    </div>
  )
}

/**
 * 压缩图片工具函数
 *
 * 步骤：
 * 1. 将文件读取为 DataURL（Base64）
 * 2. 加载为 HTMLImageElement
 * 3. 计算缩放比例（最大边长不超过 1280px）
 * 4. 用 Canvas 绘制缩放后的图片
 * 5. 导出为 JPEG（质量 82%）或 PNG 格式
 *
 * 目的：减少传输大小和 LLM 处理时间
 */
async function compressImage(file: File): Promise<ChatImagePayload> {
  const sourceUrl = await readFileAsDataUrl(file)       // 读取为 Data URL（Base64 编码）
  const image = await loadImage(sourceUrl)               // 加载为 Image 对象
  // 计算缩放比例：如果图片最大边长 <= 1280 则不缩放
  const scale = Math.min(1, MAX_IMAGE_EDGE / Math.max(image.width, image.height))
  const width = Math.max(1, Math.round(image.width * scale))
  const height = Math.max(1, Math.round(image.height * scale))
  // 用 Canvas 绘制缩放后的图片
  const canvas = document.createElement('canvas')
  canvas.width = width; canvas.height = height
  const context = canvas.getContext('2d')
  if (!context) return { dataUrl: sourceUrl, mediaType: file.type || 'image/png' }
  context.drawImage(image, 0, 0, width, height)
  // 导出：PNG 保持原格式，其他统一转 JPEG 并压缩质量
  const mediaType = file.type === 'image/png' ? 'image/png' : 'image/jpeg'
  const dataUrl = canvas.toDataURL(mediaType, mediaType === 'image/jpeg' ? JPEG_QUALITY : undefined)
  return { dataUrl, mediaType }
}

/** 读取文件为 Base64 Data URL（使用 FileReader API） */
function readFileAsDataUrl(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(String(reader.result || ''))
    reader.onerror = () => reject(reader.error)
    reader.readAsDataURL(file)
  })
}

/** 加载图片为 HTMLImageElement（异步，等 image.onload 触发） */
function loadImage(src: string): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const image = new Image()
    image.onload = () => resolve(image)
    image.onerror = reject
    image.src = src
  })
}

/**
 * 将 TemporaryMaterial 转换为附件列表项
 *
 * 处理多种情况：
 * - material.parts 存在时（多文件临时资料），展开为多个列表项
 * - material.files 存在时（文件信息数组），直接映射
 * - 其他情况，根据 sourceType 是否为 MULTI 来拆分文件名
 */
function temporaryMaterialAttachmentItems(material?: TemporaryMaterial | null): TemporaryAttachmentItem[] {
  if (!material) return []
  // 多文件临时资料：展开 parts 数组
  if (material.parts?.length) {
    return material.parts.map((part, index) => {
      const file = part.files?.[0]
      return {
        name: file?.name || part.originalName || part.title || `临时资料 ${index + 1}`,
        size: file?.size ?? part.fileSize,
        type: file?.type || part.sourceType || 'FILE',
        previewMaterial: part,
      }
    })
  }
  // 文件信息数组：直接映射
  if (material.files?.length) {
    return material.files.map((file) => ({
      ...file,
      previewMaterial: material,
    }))
  }
  // 兜底：单个资料项或按 MULTI 类型拆分文件名
  const sourceType = (material.sourceType || '').toUpperCase()
  const fallbackName = material.originalName || material.title || '临时资料'
  if (sourceType !== 'MULTI') {
    return [{ name: fallbackName, size: material.fileSize, type: sourceType || 'FILE', previewMaterial: material }]
  }
  // MULTI 类型：按逗号/顿号拆分文件名
  return fallbackName
    .split(/[、,]/)
    .map((name) => name.trim())
    .filter(Boolean)
    .map((name) => ({ name, type: 'FILE', previewMaterial: material }))
}

/** 统计临时资料正文长度；多文件资料按 parts 累加，用于判断是否需要展示截断提示。 */
function temporaryMaterialTextLength(material?: TemporaryMaterial | null): number {
  if (!material) return 0
  if (material.parts?.length) {
    return material.parts.reduce((sum, part) => sum + temporaryMaterialTextLength(part), 0)
  }
  return (material.text || '').length
}
