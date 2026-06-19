import { FileText, MoreHorizontal, X } from 'lucide-react'
import { DropdownMenu, DropdownMenuContent, DropdownMenuTrigger } from '@/components/ui/dropdown-menu'
import { formatBytes } from '@/lib/utils'
import type { SelectedFileListItem } from './SelectedFilesInlineList'

/**
 * ChatAttachmentCards -- 聊天输入框上方的附件卡片条。
 *
 * 这个组件服务于“智能问答临时资料”场景：用户在通用问答里上传 PDF、Word、PPT、
 * Markdown 等文件后，ChatComposer 会把已解析的文件整理成 SelectedFileListItem，
 * 再交给这里展示。
 *
 * 设计要点：
 * - 前 3 个附件以内联卡片展示，便于用户确认当前问题会携带哪些资料上下文。
 * - 超过 3 个的附件折叠到 More 菜单，避免输入框在多文件场景下被挤变形。
 * - onOpen 用于打开临时资料预览，onRemove 用于从当前会话上下文里移除某个文件。
 */
interface ChatAttachmentCardsProps {
  files: SelectedFileListItem[]
  onOpen?: (file: SelectedFileListItem, index: number) => void
  onRemove?: (file: SelectedFileListItem, index: number) => void
}

/** 输入框内联区域最多直接展示 3 个文件，其余文件统一收进下拉菜单。 */
const INLINE_ATTACHMENT_LIMIT = 3

export function ChatAttachmentCards({ files, onOpen, onRemove }: ChatAttachmentCardsProps) {
  if (files.length === 0) return null
  // visibleFiles 保持稳定的卡片宽度，hiddenFiles 则进入“全部附件”菜单。
  const visibleFiles = files.slice(0, INLINE_ATTACHMENT_LIMIT)
  const hiddenFiles = files.slice(INLINE_ATTACHMENT_LIMIT)

  return (
    <div
      className="mb-2 flex min-w-0 items-center gap-2 overflow-hidden"
      data-testid="chat-temporary-attachments"
    >
      {visibleFiles.map((file, index) => (
        <div
          key={`${file.name}-${file.size || 0}-${index}`}
          className="group relative h-[58px] min-w-0 max-w-[190px] flex-1 rounded-2xl border border-slate-200 bg-white shadow-sm transition hover:border-slate-300 hover:bg-slate-50 dark:border-slate-800 dark:bg-slate-900 dark:hover:border-slate-700 dark:hover:bg-slate-800"
          data-testid={`chat-temporary-attachment-${index}`}
          title={file.name}
        >
          <button
            type="button"
            onClick={() => onOpen?.(file, index)}
            disabled={!onOpen}
            className="flex h-full w-full min-w-0 items-center gap-2 rounded-2xl px-3 pr-8 text-left disabled:cursor-default"
          >
            <span className="flex h-8 w-8 shrink-0 items-center justify-center rounded-xl bg-blue-50 text-blue-600 dark:bg-blue-950/40 dark:text-blue-300">
              <FileText className="h-4 w-4" />
            </span>
            <span className="min-w-0 flex-1">
              <span className="block truncate text-sm font-medium text-slate-800 dark:text-slate-100">{file.name}</span>
              <span className="mt-0.5 block truncate text-xs text-slate-500 dark:text-slate-400">
                {file.type || 'FILE'}{file.size ? ` ${formatBytes(file.size)}` : ''}
              </span>
            </span>
          </button>
          {onRemove && (
            <button
              type="button"
              onClick={(event) => {
                event.stopPropagation()
                onRemove(file, index)
              }}
              className="absolute right-1.5 top-1.5 flex h-5 w-5 items-center justify-center rounded-full bg-slate-900/75 text-white opacity-0 shadow-sm transition hover:bg-slate-900 group-hover:opacity-100 focus:opacity-100 dark:bg-slate-100/90 dark:text-slate-900 dark:hover:bg-white"
              aria-label={`删除 ${file.name}`}
              title="删除"
            >
              <X className="h-3.5 w-3.5" />
            </button>
          )}
        </div>
      ))}
      {hiddenFiles.length > 0 && (
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <button
              type="button"
              data-chat-attachments-menu
              className="flex h-[58px] w-14 shrink-0 items-center justify-center rounded-2xl border border-slate-200 bg-white text-slate-500 shadow-sm transition hover:border-slate-300 hover:bg-slate-50 hover:text-slate-700 dark:border-slate-800 dark:bg-slate-900 dark:text-slate-300 dark:hover:border-slate-700 dark:hover:bg-slate-800"
              aria-label="查看全部附件"
              title="查看全部附件"
            >
              <MoreHorizontal className="h-5 w-5" />
            </button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end" sideOffset={8} className="max-h-80 w-80 overflow-auto p-2">
            <div className="mb-1 px-1 text-xs font-medium text-slate-500 dark:text-slate-400">
              全部附件
            </div>
            <div className="space-y-1">
              {files.map((file, index) => (
                <div
                  key={`${file.name}-${file.size || 0}-menu-${index}`}
                  className="group/menu flex w-full items-center gap-2 rounded-lg px-2 py-2 text-xs text-slate-700 hover:bg-slate-50 dark:text-slate-200 dark:hover:bg-slate-800"
                  title={file.name}
                >
                  <button
                    type="button"
                    onClick={() => onOpen?.(file, index)}
                    disabled={!onOpen}
                    className="flex min-w-0 flex-1 items-center justify-between gap-3 text-left disabled:cursor-default"
                  >
                    <span className="min-w-0 truncate">{file.name}</span>
                    <span className="shrink-0 tabular-nums text-slate-500 dark:text-slate-400">
                      {file.type || 'FILE'}{file.size ? ` ${formatBytes(file.size)}` : ''}
                    </span>
                  </button>
                  {onRemove && (
                    <button
                      type="button"
                      onClick={() => onRemove(file, index)}
                      className="flex h-6 w-6 shrink-0 items-center justify-center rounded-full text-slate-400 opacity-0 transition hover:bg-slate-100 hover:text-slate-700 group-hover/menu:opacity-100 focus:opacity-100 dark:hover:bg-slate-700 dark:hover:text-slate-100"
                      aria-label={`删除 ${file.name}`}
                      title="删除"
                    >
                      <X className="h-3.5 w-3.5" />
                    </button>
                  )}
                </div>
              ))}
            </div>
          </DropdownMenuContent>
        </DropdownMenu>
      )}
    </div>
  )
}
