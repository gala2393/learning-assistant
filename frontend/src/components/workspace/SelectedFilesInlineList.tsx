import { MoreHorizontal } from 'lucide-react'
import { DropdownMenu, DropdownMenuContent, DropdownMenuTrigger } from '@/components/ui/dropdown-menu'
import { cn, formatBytes } from '@/lib/utils'

export interface SelectedFileListItem {
  name: string
  size?: number | null
  type?: string | null
}

interface SelectedFilesInlineListProps {
  files: SelectedFileListItem[]
  totalSize?: number | null
  className?: string
}

const INLINE_FILE_LIMIT = 2

export function SelectedFilesInlineList({ files, totalSize, className }: SelectedFilesInlineListProps) {
  if (files.length === 0) return null
  const inlineFiles = files.slice(-INLINE_FILE_LIMIT)
  const hiddenFileCount = Math.max(0, files.length - inlineFiles.length)
  const resolvedTotalSize = totalSize ?? files.reduce((sum, file) => sum + (file.size || 0), 0)

  return (
    <div className={cn('rounded-xl border border-slate-200 bg-slate-50/80 px-2 py-2 dark:border-slate-800 dark:bg-slate-900/60', className)}>
      <div className="mb-1.5 flex items-center justify-between gap-3 text-xs text-slate-500 dark:text-slate-400">
        <span>{files.length === 1 ? '已选择 1 份文件' : `已选择 ${files.length} 份文件`}</span>
        {resolvedTotalSize > 0 && <span className="shrink-0">{formatBytes(resolvedTotalSize)}</span>}
      </div>
      <div className="flex min-w-0 items-center justify-end gap-1.5 overflow-hidden">
        {hiddenFileCount > 0 && (
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <button
                type="button"
                data-selected-files-menu
                className="inline-flex h-7 w-8 shrink-0 items-center justify-center rounded-full bg-white text-slate-500 ring-1 ring-slate-200 transition hover:bg-slate-100 hover:text-slate-700 dark:bg-slate-950/60 dark:text-slate-300 dark:ring-slate-800 dark:hover:bg-slate-800"
                aria-label="查看全部已选文件"
                title="查看全部已选文件"
              >
                <MoreHorizontal className="h-4 w-4" />
              </button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end" sideOffset={6} className="max-h-72 w-80 overflow-auto p-2">
              <div className="mb-1 px-1 text-xs font-medium text-slate-500 dark:text-slate-400">
                全部已选文件
              </div>
              <div className="space-y-1">
                {files.map((file, index) => (
                  <div
                    key={`${file.name}-${file.size || 0}-menu-${index}`}
                    className="flex items-center justify-between gap-3 rounded-lg px-2 py-1.5 text-xs text-slate-700 hover:bg-slate-50 dark:text-slate-200 dark:hover:bg-slate-800"
                  >
                    <span className="min-w-0 truncate" title={file.name}>{file.name}</span>
                    {!!file.size && <span className="shrink-0 tabular-nums text-slate-500 dark:text-slate-400">{formatBytes(file.size)}</span>}
                  </div>
                ))}
              </div>
            </DropdownMenuContent>
          </DropdownMenu>
        )}
        {inlineFiles.map((file, index) => (
          <span
            key={`${file.name}-${file.size || 0}-${index}`}
            className="inline-flex h-7 min-w-0 max-w-[128px] shrink items-center rounded-full bg-white px-2.5 text-xs text-slate-700 ring-1 ring-slate-200 dark:bg-slate-950/60 dark:text-slate-200 dark:ring-slate-800"
            title={file.size ? `${file.name} (${formatBytes(file.size)})` : file.name}
          >
            <span className="truncate">{file.name}</span>
          </span>
        ))}
      </div>
    </div>
  )
}
