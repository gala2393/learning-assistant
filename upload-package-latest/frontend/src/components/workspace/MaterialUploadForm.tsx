/**
 * MaterialUploadForm -- 资料上传表单组件
 *
 * 【用途】
 * 提供文件选择和标题输入的上传表单，用于将学习资料导入系统。
 * 在 ChatPage（聊天主页的上传弹窗）和 MaterialsPage（资料管理页面）中使用。
 *
 * 【功能】
 * - 文件选择（支持多选）：PDF、Word、PPT、Markdown、TXT、HTML
 * - 可选标题输入（单文件时可自定义标题）
 * - 使用 react-hook-form + zod 进行表单校验
 * - 文件大小校验（超过 MAX_UPLOAD_BYTES 时阻止上传）
 * - 多文件上传进度展示（每个文件独立显示进度条、状态、分片信息）
 * - 已选文件列表展示（文件名 + 总大小）
 *
 * 【数据流】
 * 1. 用户选择文件 -> 表单校验 -> 显示文件信息
 * 2. 点击"开始导入" -> onSubmit 回调 -> 父组件执行分片上传
 * 3. 父组件通过 progressItems 回传进度 -> 组件渲染进度条
 */
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { Input } from '@/components/ui/input'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { FileUp, Sparkles, Upload } from 'lucide-react'
import type { UploadProgress, UploadProgressItem } from '@/api/materials'
import { MAX_UPLOAD_BYTES } from '@/api/materials'
import { actionButtonBase, actionButtonIdle, actionButtonReady } from '@/lib/action-button-styles'
import { formatBytes } from '@/lib/utils'
import { SelectedFilesInlineList } from './SelectedFilesInlineList'

// ========== 表单校验 Schema ==========

/**
 * Zod 表单校验 schema
 * - title：可选（自动生成或手动输入）
 * - file：必填（至少选择一个文件）
 */
const uploadSchema = z.object({
  title: z.string().optional(),
  file: z.any().optional(),
}).refine(
  (data) => data.file?.length > 0,  // 必须选择了至少一个文件
  { message: '请上传文件', path: ['file'] },
)

type UploadFormValues = z.infer<typeof uploadSchema>

// ========== 组件属性 ==========

/**
 * MaterialUploadForm 组件属性
 *
 * @property onSubmit - 表单提交回调，接收 { title, file, files }
 * @property loading - 是否正在上传中（true 时按钮显示"导入中..."并禁用）
 * @property progress - 单文件上传进度信息（兼容旧的单文件调用方式）
 * @property progressItems - 多文件上传进度信息数组（每个文件独立显示）
 */
interface MaterialUploadFormProps {
  onSubmit: (data: { title?: string; file?: File; files?: File[] }) => void
  loading?: boolean
  progress?: UploadProgress | null
  progressItems?: UploadProgressItem[]
}

// ========== 样式常量 ==========

/** 输入框标签统一样式 */
const fieldLabelClass = 'mb-1.5 block text-sm font-medium text-slate-600 dark:text-slate-300'
/** 输入框统一样式 */
const fieldClass =
  'h-11 border-[#c8d0da] bg-white text-slate-800 placeholder:text-slate-400 focus-visible:ring-[#6b7280]/35 dark:border-slate-700 dark:bg-[#141922] dark:text-slate-100 dark:placeholder:text-slate-500 dark:focus-visible:ring-slate-500/35'

// ========== 主组件 ==========

export function MaterialUploadForm({ onSubmit, loading, progress, progressItems = [] }: MaterialUploadFormProps) {
  // === 表单初始化 ===
  // 使用 react-hook-form，配合 zodResolver 进行自动校验
  const { register, handleSubmit, watch, reset, formState: { errors } } = useForm<UploadFormValues>({
    resolver: zodResolver(uploadSchema),
  })

  // === 派生值 ===
  /** 监听文件输入变化，获取用户选中的文件列表 */
  const fileWatch = watch('file')
  const selectedFiles = Array.from((fileWatch || []) as FileList) as File[]
  const selectedFile = selectedFiles[0]
  /** 检查是否有文件超过大小限制 */
  const tooLargeFile = selectedFiles.find((file) => file.size > MAX_UPLOAD_BYTES)
  const fileTooLarge = !!tooLargeFile
  /** 是否可以提交（有文件、不超限、不在上传中） */
  const canUpload = selectedFiles.length > 0 && !fileTooLarge && !loading

  /**
   * 构建可见的进度条列表
   * 优先使用 progressItems（多文件），否则从 progress（单文件）转换
   */
  const visibleProgressItems = progressItems.length > 0
    ? progressItems
    : progress
      ? [{
        ...progress,
        id: 'single-upload',
        fileName: selectedFile?.name || '当前文件',
        fileSize: selectedFile?.size || 0,
        status: progress.phase === 'processing' && progress.percent >= 100 ? 'success' as const : progress.phase,
      }]
      : []
  /** 是否显示进度条（正在上传中，或有文件上传失败） */
  const showProgressItems = visibleProgressItems.length > 0
    && (loading || visibleProgressItems.some((item) => item.status === 'error'))

  // === 事件处理 ===

  /**
   * 表单提交处理
   * 1. 提取文件列表
   * 2. 再次校验文件大小（防御性检查）
   * 3. 调用父组件的 onSubmit 回调
   * 4. 重置表单
   */
  const handleFormSubmit = (values: UploadFormValues) => {
    const files = Array.from((values.file || []) as FileList) as File[]
    const file = files[0]
    if (files.some((candidate) => candidate.size > MAX_UPLOAD_BYTES)) return
    onSubmit({
      title: files.length === 1 ? values.title || undefined : undefined,  // 单文件时传递标题
      file,
      files,
    })
    reset()  // 提交后重置表单（清空文件选择和标题）
  }

  // === 渲染 ===
  return (
    <Card className="overflow-hidden border-[#dce2ea] bg-gradient-to-b from-white to-[#f8fafc] shadow-[0_12px_32px_rgba(15,23,42,0.07)] dark:border-slate-800 dark:bg-gradient-to-b dark:from-[#1b2029] dark:to-[#121720] dark:text-slate-100 dark:shadow-[0_18px_42px_rgba(0,0,0,0.32)]">
      {/* ---- 表单标题区域 ---- */}
      <CardHeader className="border-b border-[#edf1f6] bg-gradient-to-r from-[#f8fafc] to-[#eef0f2] pb-4 dark:border-slate-800 dark:from-[#1f2530] dark:to-[#171d26]">
        <CardTitle className="flex items-center gap-3 text-base text-slate-800 dark:text-slate-100">
          <span className="flex h-9 w-9 items-center justify-center rounded-2xl bg-white text-[#4b5563] shadow-sm ring-1 ring-[#dce2ea] dark:bg-[#141922] dark:text-slate-200 dark:ring-slate-700/80">
            <Upload className="h-4 w-4" />
          </span>
          <span>
            <span className="flex items-center gap-1.5">
              导入资料
              <Sparkles className="h-3.5 w-3.5 text-[#9aa3af] dark:text-slate-500" />
            </span>
            <span className="mt-0.5 block text-xs font-normal text-slate-500 dark:text-slate-400">上传文件后自动解析</span>
          </span>
        </CardTitle>
      </CardHeader>

      <CardContent className="pt-4 dark:bg-[#121720]">
        <form onSubmit={handleSubmit(handleFormSubmit)} className="space-y-4">

          {/* ---- 标题输入（可选，单文件时可自定义） ---- */}
          <div>
            <label className={fieldLabelClass}>标题（可选）</label>
            <Input className={fieldClass} placeholder="自动生成或手动输入" {...register('title')} />
          </div>

          {/* ---- 文件选择区域 ---- */}
          <div>
            <label className={fieldLabelClass}>
              <FileUp className="mr-1 inline h-3.5 w-3.5" />上传文件
            </label>
            <Input
              type="file"
              accept=".pdf,.docx,.pptx,.txt,.md,.html,.htm"  // 支持的文件类型
              multiple  // 允许多选
              className={`${fieldClass} cursor-pointer file:mr-3 file:rounded-lg file:border-0 file:bg-[#eef0f2] file:px-3 file:py-1.5 file:text-sm file:text-[#4b5563] hover:file:bg-[#e3e6e9] dark:file:bg-[#262d38] dark:file:text-slate-200 dark:hover:file:bg-[#303846]`}
              {...register('file')}
            />
            {/* ---- 已选文件信息展示 ---- */}
            {selectedFiles.length > 0 && (
              <SelectedFilesInlineList
                className="mt-2"
                files={selectedFiles.map((file) => ({ name: file.name, size: file.size }))}
                totalSize={selectedFiles.reduce((sum, file) => sum + file.size, 0)}
              />
            )}
            {/* ---- 文件过大错误提示 ---- */}
            {fileTooLarge && (
              <p className="mt-1.5 text-xs text-destructive">
                {tooLargeFile?.name || '文件'} 超过 {formatBytes(MAX_UPLOAD_BYTES)}，请压缩或拆分后再上传。
              </p>
            )}
          </div>

          {/* ---- zod 校验错误提示 ---- */}
          {errors.file && <p className="text-xs text-destructive">{errors.file.message as string}</p>}

          {/* ---- 上传进度条区域（仅在上传中或有错误时显示） ---- */}
          {showProgressItems && (
            <div className="space-y-2 rounded-xl border border-[#d9e2ec] bg-white px-3 py-3 shadow-sm dark:border-slate-800 dark:bg-[#171d26] dark:shadow-none">
              {visibleProgressItems.map((item) => (
                <div key={item.id} className="space-y-1.5">
                  {/* 文件名 + 进度百分比 */}
                  <div className="flex items-center justify-between gap-3 text-xs">
                    <span className="min-w-0 truncate font-medium text-slate-700 dark:text-slate-200" title={item.fileName}>
                      {item.fileName}
                    </span>
                    <span className="shrink-0 tabular-nums text-slate-500 dark:text-slate-400">
                      {progressTitleFor(item)}
                    </span>
                  </div>
                  {/* 进度条（蓝色渐变，错误时变红） */}
                  <div className="h-2.5 overflow-hidden rounded-full bg-slate-100 ring-1 ring-slate-200/70 dark:bg-slate-800 dark:ring-slate-700">
                    <div
                      className={`h-full rounded-full transition-all duration-500 ${item.status === 'error' ? 'bg-red-500' : 'bg-gradient-to-r from-[#2563eb] via-[#0f766e] to-[#65a30d]'}`}
                      style={{ width: `${progressPercentFor(item)}%` }}
                    />
                  </div>
                  {/* 详细信息（分片上传进度或解析状态） */}
                  <p className={`line-clamp-2 text-xs ${item.status === 'error' ? 'text-red-600 dark:text-red-300' : 'text-slate-500 dark:text-slate-400'}`}>
                    {progressDetailFor(item)}
                  </p>
                </div>
              ))}
            </div>
          )}

          {/* ---- 提交按钮（根据状态切换样式和文字） ---- */}
          <Button
            type="submit"
            className={`h-11 w-full rounded-xl ${actionButtonBase} ${canUpload ? actionButtonReady : actionButtonIdle}`}
            disabled={loading || fileTooLarge}
          >
            {loading ? '导入中...' : '开始导入'}
          </Button>
        </form>
      </CardContent>
    </Card>
  )
}

// ========== 进度条辅助函数 ==========

/**
 * 计算进度条显示百分比
 * - error 状态：至少显示 5%（避免进度条不可见）
 * - success 状态：100%
 * - 其他：正常百分比
 */
function progressPercentFor(item: UploadProgressItem) {
  if (item.status === 'error') return Math.max(5, Math.min(100, Math.round(item.percent || 0)))
  if (item.status === 'success') return 100
  return Math.max(0, Math.min(100, Math.round(item.percent || 0)))
}

/** 进度条标题文字（"上传中 45%"、"解析中 80%"、"失败"、"完成"） */
function progressTitleFor(item: UploadProgressItem) {
  if (item.status === 'error') return '失败'
  if (item.status === 'success') return '完成'
  const percent = progressPercentFor(item)
  return item.phase === 'uploading' ? `上传中 ${percent}%` : `解析中 ${percent}%`
}

/** 进度条详细信息（分片数量或解析阶段说明） */
function progressDetailFor(item: UploadProgressItem) {
  if (item.status === 'error') return item.error || '上传失败，请重试'
  if (item.status === 'success') return item.message || '资料已上传并进入解析流程'
  if (item.phase === 'uploading') {
    return `${item.uploadedChunks}/${item.totalChunks} 个分片已上传${item.message ? `：${item.message}` : ''}`
  }
  return `${item.stage || '后台解析中'}${item.message ? `：${item.message}` : ''}`
}
