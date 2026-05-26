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

const uploadSchema = z.object({
  title: z.string().optional(),
  file: z.any().optional(),
}).refine(
  (data) => data.file?.length > 0,
  { message: '请上传文件', path: ['file'] },
)

type UploadFormValues = z.infer<typeof uploadSchema>

interface MaterialUploadFormProps {
  onSubmit: (data: { title?: string; file?: File }) => void
  loading?: boolean
  progress?: UploadProgress | null
}

const fieldLabelClass = 'mb-1.5 block text-sm font-medium text-slate-600 dark:text-slate-300'
const fieldClass =
  'h-11 border-[#c8d0da] bg-white text-slate-800 placeholder:text-slate-400 focus-visible:ring-[#6b7280]/35 dark:border-slate-700 dark:bg-[#141922] dark:text-slate-100 dark:placeholder:text-slate-500 dark:focus-visible:ring-slate-500/35'

export function MaterialUploadForm({ onSubmit, loading, progress }: MaterialUploadFormProps) {
  const { register, handleSubmit, watch, reset, formState: { errors } } = useForm<UploadFormValues>({
    resolver: zodResolver(uploadSchema),
  })

  const fileWatch = watch('file')
  const selectedFile = fileWatch?.[0] as File | undefined
  const fileTooLarge = !!selectedFile && selectedFile.size > MAX_UPLOAD_BYTES
  const canUpload = !!selectedFile && !fileTooLarge && !loading

  const handleFormSubmit = (values: UploadFormValues) => {
    const file = values.file?.[0] as File | undefined
    if (file && file.size > MAX_UPLOAD_BYTES) return
    onSubmit({
      title: values.title || undefined,
      file,
    })
    reset()
  }

  return (
    <Card className="overflow-hidden border-[#dce2ea] bg-gradient-to-b from-white to-[#f8fafc] shadow-[0_12px_32px_rgba(15,23,42,0.07)] dark:border-slate-800 dark:bg-gradient-to-b dark:from-[#1b2029] dark:to-[#121720] dark:text-slate-100 dark:shadow-[0_18px_42px_rgba(0,0,0,0.32)]">
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
          <div>
            <label className={fieldLabelClass}>标题（可选）</label>
            <Input className={fieldClass} placeholder="自动生成或手动输入" {...register('title')} />
          </div>

          <div>
            <label className={fieldLabelClass}>
              <FileUp className="mr-1 inline h-3.5 w-3.5" />上传文件
            </label>
            <Input
              type="file"
              accept=".pdf,.docx,.pptx,.txt,.md,.html,.htm"
              className={`${fieldClass} cursor-pointer file:mr-3 file:rounded-lg file:border-0 file:bg-[#eef0f2] file:px-3 file:py-1.5 file:text-sm file:text-[#4b5563] hover:file:bg-[#e3e6e9] dark:file:bg-[#262d38] dark:file:text-slate-200 dark:hover:file:bg-[#303846]`}
              {...register('file')}
            />
            {selectedFile && (
              <p className="mt-1.5 text-xs text-slate-500 dark:text-slate-400">
                已选择：{selectedFile.name} ({formatBytes(selectedFile.size)})
              </p>
            )}
            {fileTooLarge && (
              <p className="mt-1.5 text-xs text-destructive">
                文件超过 {formatBytes(MAX_UPLOAD_BYTES)}，请压缩或拆分后再上传。
              </p>
            )}
          </div>

          {errors.file && <p className="text-xs text-destructive">{errors.file.message as string}</p>}

          {progress && (
            <div className="space-y-2 rounded-2xl border border-[#e6ebf2] bg-white/80 px-3 py-3 shadow-sm dark:border-slate-800 dark:bg-[#171d26] dark:shadow-none">
              <div className="flex items-center justify-between text-xs text-slate-500 dark:text-slate-400">
                <span>{progress.phase === 'uploading' ? '上传中' : '解析中'}</span>
                <span>
                  {progress.phase === 'uploading'
                    ? `${progress.uploadedChunks}/${progress.totalChunks}`
                    : '后台处理中'}
                </span>
              </div>
              <div className="h-2 overflow-hidden rounded-full bg-slate-100 dark:bg-slate-800">
                <div
                  className="h-full rounded-full bg-gradient-to-r from-[#6b7280] to-[#9ca3af] transition-all dark:from-[#4b5563] dark:to-[#9ca3af]"
                  style={{ width: `${progress.percent}%` }}
                />
              </div>
            </div>
          )}

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
