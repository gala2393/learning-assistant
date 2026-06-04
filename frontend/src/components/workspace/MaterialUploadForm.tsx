/**
 * MaterialUploadForm - 资料上传表单组件
 *
 * 功能说明：
 * - 提供文件选择和标题输入的上传表单
 * - 使用 react-hook-form + zod 进行表单校验
 * - 文件大小校验（超过 MAX_UPLOAD_BYTES 时阻止上传）
 * - 上传过程中展示分片进度条和状态文字
 *
 * 表单校验规则：
 * - title：可选
 * - file：必填，至少选择一个文件
 */
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { Input } from '@/components/ui/input'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { FileUp, Sparkles, Upload } from 'lucide-react'
import type { UploadProgress } from '@/api/materials'
import { MAX_UPLOAD_BYTES } from '@/api/materials'
import { actionButtonBase, actionButtonIdle, actionButtonReady } from '@/lib/action-button-styles'
import { formatBytes } from '@/lib/utils'

// Zod 表单校验 schema
const uploadSchema = z.object({
  title: z.string().optional(),
  file: z.any().optional(),
}).refine(
  (data) => data.file?.length > 0,  // 必须选择了文件
  { message: '请上传文件', path: ['file'] },
)

type UploadFormValues = z.infer<typeof uploadSchema>

interface MaterialUploadFormProps {
  onSubmit: (data: { title?: string; file?: File }) => void  // 表单提交回调
  loading?: boolean     // 是否正在上传中
  progress?: UploadProgress | null  // 上传进度信息（含阶段、百分比、分片数等）
}

// 统一的输入框标签和输入框样式类名
const fieldLabelClass = 'mb-1.5 block text-sm font-medium text-slate-600 dark:text-slate-300'
const fieldClass =
  'h-11 border-[#c8d0da] bg-white text-slate-800 placeholder:text-slate-400 focus-visible:ring-[#6b7280]/35 dark:border-slate-700 dark:bg-[#141922] dark:text-slate-100 dark:placeholder:text-slate-500 dark:focus-visible:ring-slate-500/35'

export function MaterialUploadForm({ onSubmit, loading, progress }: MaterialUploadFormProps) {
  // react-hook-form 初始化，使用 zod 校验
  const { register, handleSubmit, watch, reset, formState: { errors } } = useForm<UploadFormValues>({
    resolver: zodResolver(uploadSchema),
  })

  // 监听文件输入变化，获取选中的文件
  const fileWatch = watch('file')
  const selectedFile = fileWatch?.[0] as File | undefined
  // 检查文件大小是否超限
  const fileTooLarge = !!selectedFile && selectedFile.size > MAX_UPLOAD_BYTES
  // 是否可以提交上传
  const canUpload = !!selectedFile && !fileTooLarge && !loading

  // ---- 进度条相关计算 ----
  const progressPercent = progress ? Math.max(0, Math.min(100, Math.round(progress.percent))) : 0
  // 进度标题：区分上传阶段和解析阶段
  const progressTitle = progress?.phase === 'uploading'
    ? `上传中 ${progressPercent}%`
    : `后台解析中 ${progressPercent}%`
  // 进度详情：上传阶段显示分片进度，解析阶段显示解析阶段名
  const progressDetail = progress?.phase === 'uploading'
    ? `${progress.uploadedChunks}/${progress.totalChunks} 个分片已上传`
    : progress?.stage || '正在解析文件'

  /** 表单提交处理 */
  const handleFormSubmit = (values: UploadFormValues) => {
    const file = values.file?.[0] as File | undefined
    // 再次校验文件大小
    if (file && file.size > MAX_UPLOAD_BYTES) return
    onSubmit({
      title: values.title || undefined,
      file,
    })
    reset()  // 重置表单
  }

  return (
    <Card className="overflow-hidden border-[#dce2ea] bg-gradient-to-b from-white to-[#f8fafc] shadow-[0_12px_32px_rgba(15,23,42,0.07)] dark:border-slate-800 dark:bg-gradient-to-b dark:from-[#1b2029] dark:to-[#121720] dark:text-slate-100 dark:shadow-[0_18px_42px_rgba(0,0,0,0.32)]">
      {/* 表单标题区域 */}
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
          {/* 标题输入（可选） */}
          <div>
            <label className={fieldLabelClass}>标题（可选）</label>
            <Input className={fieldClass} placeholder="自动生成或手动输入" {...register('title')} />
          </div>

          {/* 文件选择 */}
          <div>
            <label className={fieldLabelClass}>
              <FileUp className="mr-1 inline h-3.5 w-3.5" />上传文件
            </label>
            <Input
              type="file"
              accept=".pdf,.docx,.pptx,.txt,.md,.html,.htm"  // 支持的文件类型
              className={`${fieldClass} cursor-pointer file:mr-3 file:rounded-lg file:border-0 file:bg-[#eef0f2] file:px-3 file:py-1.5 file:text-sm file:text-[#4b5563] hover:file:bg-[#e3e6e9] dark:file:bg-[#262d38] dark:file:text-slate-200 dark:hover:file:bg-[#303846]`}
              {...register('file')}
            />
            {/* 已选文件信息 */}
            {selectedFile && (
              <p className="mt-1.5 text-xs text-slate-500 dark:text-slate-400">
                已选择：{selectedFile.name} ({formatBytes(selectedFile.size)})
              </p>
            )}
            {/* 文件过大的错误提示 */}
            {fileTooLarge && (
              <p className="mt-1.5 text-xs text-destructive">
                文件超过 {formatBytes(MAX_UPLOAD_BYTES)}，请压缩或拆分后再上传。
              </p>
            )}
          </div>

          {/* zod 校验错误提示 */}
          {errors.file && <p className="text-xs text-destructive">{errors.file.message as string}</p>}

          {/* 上传进度条区域（仅在上传中显示） */}
          {progress && (
            <div className="space-y-2 rounded-xl border border-[#d9e2ec] bg-white px-3 py-3 shadow-sm dark:border-slate-800 dark:bg-[#171d26] dark:shadow-none">
              <div className="flex items-center justify-between gap-3 text-xs">
                <span className="font-medium text-slate-700 dark:text-slate-200">{progressTitle}</span>
                <span className="shrink-0 tabular-nums text-slate-500 dark:text-slate-400">{progressPercent}%</span>
              </div>
              {/* 渐变进度条 */}
              <div className="h-2.5 overflow-hidden rounded-full bg-slate-100 ring-1 ring-slate-200/70 dark:bg-slate-800 dark:ring-slate-700">
                <div
                  className="h-full rounded-full bg-gradient-to-r from-[#2563eb] via-[#0f766e] to-[#65a30d] transition-all duration-500"
                  style={{ width: `${progressPercent}%` }}
                />
              </div>
              {/* 进度详情文字 */}
              <p className="line-clamp-2 text-xs text-slate-500 dark:text-slate-400">
                {progressDetail}
                {progress.message ? `：${progress.message}` : ''}
              </p>
            </div>
          )}

          {/* 提交按钮（根据表单状态切换样式） */}
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
