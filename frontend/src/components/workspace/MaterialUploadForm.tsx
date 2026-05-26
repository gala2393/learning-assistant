import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { Input } from '@/components/ui/input'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { FileUp, Sparkles, Upload } from 'lucide-react'
import type { UploadProgress } from '@/api/materials'
import { MAX_UPLOAD_BYTES } from '@/api/materials'
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

export function MaterialUploadForm({ onSubmit, loading, progress }: MaterialUploadFormProps) {
  const { register, handleSubmit, watch, reset, formState: { errors } } = useForm<UploadFormValues>({
    resolver: zodResolver(uploadSchema),
  })

  const fileWatch = watch('file')
  const selectedFile = fileWatch?.[0] as File | undefined
  const fileTooLarge = !!selectedFile && selectedFile.size > MAX_UPLOAD_BYTES

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
    <Card className="overflow-hidden border-[#dce2ea] bg-gradient-to-b from-white to-[#f8fafc] shadow-[0_12px_32px_rgba(15,23,42,0.07)]">
      <CardHeader className="border-b border-[#edf1f6] bg-gradient-to-r from-[#f8fbff] to-[#eef6ff] pb-4">
        <CardTitle className="flex items-center gap-3 text-base">
          <span className="flex h-9 w-9 items-center justify-center rounded-2xl bg-white text-[#1f8fd6] shadow-sm ring-1 ring-[#e6edf6]">
            <Upload className="h-4 w-4" />
          </span>
          <span>
            <span className="flex items-center gap-1.5">
              导入资料
              <Sparkles className="h-3.5 w-3.5 text-[#8aa6d6]" />
            </span>
            <span className="mt-0.5 block text-xs font-normal text-muted-foreground">上传文件后自动解析</span>
          </span>
        </CardTitle>
      </CardHeader>
      <CardContent className="pt-4">
        <form onSubmit={handleSubmit(handleFormSubmit)} className="space-y-4">
          <div>
            <label className="mb-1.5 block text-sm font-medium">标题（可选）</label>
            <Input className="h-11 bg-white" placeholder="自动生成或手动输入" {...register('title')} />
          </div>

          <div>
            <label className="mb-1.5 block text-sm font-medium">
              <FileUp className="mr-1 inline h-3.5 w-3.5" />上传文件
            </label>
            <Input
              type="file"
              accept=".pdf,.docx,.pptx,.txt,.md,.html,.htm"
              className="h-11 cursor-pointer bg-white file:mr-3 file:rounded-lg file:border-0 file:bg-[#eef6ff] file:px-3 file:py-1.5 file:text-sm file:text-[#1f8fd6]"
              {...register('file')}
            />
            {selectedFile && (
              <p className="mt-1.5 text-xs text-muted-foreground">
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
            <div className="space-y-2 rounded-2xl border border-[#e6ebf2] bg-white/80 px-3 py-3 shadow-sm">
              <div className="flex items-center justify-between text-xs text-muted-foreground">
                <span>{progress.phase === 'uploading' ? '上传中' : '解析中'}</span>
                <span>
                  {progress.phase === 'uploading'
                    ? `${progress.uploadedChunks}/${progress.totalChunks}`
                    : '后台处理中'}
                </span>
              </div>
              <div className="h-2 overflow-hidden rounded-full bg-slate-100">
                <div
                  className="h-full rounded-full bg-gradient-to-r from-[#1f8fd6] to-[#68b6ea] transition-all"
                  style={{ width: `${progress.percent}%` }}
                />
              </div>
            </div>
          )}

          <Button
            type="submit"
            className="h-11 w-full rounded-xl bg-[#1f8fd6] text-white shadow-[0_12px_22px_rgba(31,143,214,0.24)] transition-all hover:-translate-y-0.5 hover:bg-[#197fbe] hover:shadow-[0_16px_28px_rgba(31,143,214,0.28)]"
            disabled={loading || fileTooLarge}
          >
            {loading ? '导入中...' : '开始导入'}
          </Button>
        </form>
      </CardContent>
    </Card>
  )
}
