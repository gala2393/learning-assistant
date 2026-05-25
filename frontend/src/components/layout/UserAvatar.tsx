import type { Session } from '@/types'
import { cn } from '@/lib/utils'

export const DEFAULT_AVATARS = [
  { id: 'preset:aurora', label: '晨光', className: 'bg-[linear-gradient(135deg,#0ea5e9,#f59e0b)]' },
  { id: 'preset:forest', label: '松林', className: 'bg-[linear-gradient(135deg,#15803d,#84cc16)]' },
  { id: 'preset:ink', label: '墨蓝', className: 'bg-[linear-gradient(135deg,#0f172a,#38bdf8)]' },
  { id: 'preset:rose', label: '蔷薇', className: 'bg-[linear-gradient(135deg,#be123c,#f9a8d4)]' },
  { id: 'preset:amber', label: '琥珀', className: 'bg-[linear-gradient(135deg,#92400e,#fde047)]' },
  { id: 'preset:violet', label: '藤紫', className: 'bg-[linear-gradient(135deg,#6d28d9,#c4b5fd)]' },
]

interface UserAvatarProps {
  session?: Pick<Session, 'nickname' | 'username' | 'avatar'> | null
  avatar?: string
  className?: string
}

export function getAvatarInitial(session?: Pick<Session, 'nickname' | 'username'> | null) {
  return (session?.nickname || session?.username || '?').trim()[0] || '?'
}

export function UserAvatar({ session, avatar, className }: UserAvatarProps) {
  const value = avatar ?? session?.avatar ?? ''
  if (value.startsWith('data:image/')) {
    return (
      <img
        src={value}
        alt="用户头像"
        className={cn('h-8 w-8 rounded-full object-cover ring-1 ring-black/5 dark:ring-white/10', className)}
      />
    )
  }

  const preset = DEFAULT_AVATARS.find((item) => item.id === value) || DEFAULT_AVATARS[0]
  return (
    <div
      className={cn(
        'flex h-8 w-8 items-center justify-center rounded-full text-sm font-semibold text-white shadow-sm',
        preset.className,
        className,
      )}
    >
      {getAvatarInitial(session)}
    </div>
  )
}
