import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { Input } from '@/components/ui/input'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Upload, Link, FileUp } from 'lucide-react'
import type { UploadProgress } from '@/api/materials'
import { MAX_UPLOAD_BYTES } from '@/api/materials'
import { formatBytes } from '@/lib/utils'

const uploadSchema = z.object({
  title: z.string().optional(),
  url: z.string().url('请输入有效的 URL').optional().or(z.literal('')),
  file: z.any().optional(),
}).refine(
  (data) => data.file?.length > 0 || data.url,
  { message: '请上传文件或输入 URL', path: ['file'] }
)

type UploadFormValues = z.infer<typeof uploadSchema>

interface MaterialUploadFormProps {
  onSubmit: (data: { title?: string; url?: string; file?: File }) => void
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
      url: values.url || undefined,
      file,
    })
    reset()
  }

  return (
    <Card>
      <CardHeader className="pb-3">
        <CardTitle className="text-base flex items-center gap-2">
          <Upload className="h-4 w-4" /> 导入资料
        </CardTitle>
      </CardHeader>
      <CardContent>
        <form onSubmit={handleSubmit(handleFormSubmit)} className="space-y-3">
          <div>
            <label className="text-sm font-medium mb-1 block">标题（可选）</label>
            <Input placeholder="自动生成或手动输入" {...register('title')} />
          </div>

          <div>
            <label className="text-sm font-medium mb-1 block">
              <Link className="inline h-3.5 w-3.5 mr-1" />网页链接
            </label>
            <Input placeholder="https://example.com/article" {...register('url')} />
            {errors.url && <p className="text-xs text-destructive mt-1">{errors.url.message}</p>}
          </div>

          <div>
            <label className="text-sm font-medium mb-1 block">
              <FileUp className="inline h-3.5 w-3.5 mr-1" />上传文件
            </label>
            <Input type="file" accept=".pdf,.docx,.pptx,.txt,.md,.html,.htm"
              {...register('file')} />
            {selectedFile && (
              <p className="text-xs text-muted-foreground mt-1">
                已选择: {selectedFile.name} ({formatBytes(selectedFile.size)})
              </p>
            )}
            {fileTooLarge && (
              <p className="text-xs text-destructive mt-1">
                文件超过 {formatBytes(MAX_UPLOAD_BYTES)}，请压缩或拆分后再上传。
              </p>
            )}
          </div>

          {errors.file && <p className="text-xs text-destructive">{errors.file.message as string}</p>}

          {progress && (
            <div className="space-y-1 rounded-md bg-muted/50 px-3 py-2">
              <div className="flex items-center justify-between text-xs text-muted-foreground">
                <span>{progress.phase === 'uploading' ? '上传中' : '解析中'}</span>
                <span>
                  {progress.phase === 'uploading'
                    ? `${progress.uploadedChunks}/${progress.totalChunks}`
                    : '后台处理'}
                </span>
              </div>
              <div className="h-1.5 overflow-hidden rounded-full bg-background">
                <div
                  className="h-full rounded-full bg-primary transition-all"
                  style={{ width: `${progress.percent}%` }}
                />
              </div>
            </div>
          )}

          <Button type="submit" className="w-full" disabled={loading || fileTooLarge}>
            {loading ? '导入中...' : '开始导入'}
          </Button>
        </form>
      </CardContent>
    </Card>
  )
}
