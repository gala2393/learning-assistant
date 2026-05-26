import { useRef, useEffect } from 'react'
import { Textarea } from '@/components/ui/textarea'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { actionButtonBase, actionButtonIdle, actionButtonReady } from '@/lib/action-button-styles'
import { Send, Lock } from 'lucide-react'

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
}

export function ChatComposer({
  value, onChange, onSubmit, loading, mode, quickPrompts, disabled, disabledHint, centered,
}: ChatComposerProps) {
  const textareaRef = useRef<HTMLTextAreaElement>(null)
  const canSend = value.trim().length > 0 && !loading && !disabled

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

  return (
    <div className={centered ? 'w-full' : 'border-t bg-background p-4 dark:border-slate-800 dark:bg-[#171a21]'}>
      <div className={centered ? 'mx-auto w-full max-w-[760px] space-y-3' : 'max-w-3xl mx-auto space-y-2'}>
        {/* Quick prompt chips */}
        <div className={centered ? 'flex flex-wrap justify-center gap-2' : 'flex flex-wrap gap-1.5'}>
          {quickPrompts.map((p, i) => (
            <Badge
              key={i}
              variant="outline"
              className={centered
                ? 'cursor-pointer rounded-full border-[#d9dde3] bg-white px-3 py-1 text-[12px] font-normal text-slate-600 hover:bg-[#f5f7fb] dark:bg-slate-900 dark:text-slate-300'
                : 'cursor-pointer text-[11px] font-normal hover:bg-primary/10'}
              onClick={() => !disabled && onChange(p)}
            >
              {p.length > 18 ? p.slice(0, 18) + '...' : p}
            </Badge>
          ))}
        </div>

        {/* Input area */}
        <div className={centered ? 'flex min-h-[156px] items-end gap-3 rounded-[24px] border border-[#d7d9df] bg-white p-4 shadow-[0_10px_28px_rgba(15,23,42,0.08)] dark:border-slate-700 dark:bg-slate-900' : 'flex items-end gap-2'}>
          {disabled ? (
            <div className="flex flex-1 items-center justify-center gap-2 rounded-md border border-dashed bg-muted/50 py-4 text-sm text-muted-foreground dark:border-slate-700 dark:bg-slate-900/60">
              <Lock className="h-4 w-4" />
              <span>{disabledHint || '暂不可用'}</span>
            </div>
          ) : (
            <Textarea
              ref={textareaRef}
              value={value}
              onChange={(e) => onChange(e.target.value)}
              onKeyDown={handleKeyDown}
              placeholder={mode === 'GENERAL' ? '描述你的问题，我会帮你整理思路' : '基于当前资料提问...'}
              className={centered
                ? 'min-h-[112px] max-h-[180px] resize-none border-0 bg-transparent px-1 text-base shadow-none focus-visible:ring-0 focus-visible:ring-offset-0'
                : 'min-h-[72px] max-h-[180px] resize-none dark:border-slate-700 dark:bg-slate-900 dark:text-slate-100'}
              rows={1}
            />
          )}
          <Button
            size="icon"
            className={centered
              ? `h-11 w-11 shrink-0 rounded-full ${actionButtonBase} ${canSend ? actionButtonReady : actionButtonIdle}`
              : `h-12 w-12 shrink-0 rounded-xl ${actionButtonBase} ${canSend ? actionButtonReady : actionButtonIdle}`}
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
    </div>
  )
}
