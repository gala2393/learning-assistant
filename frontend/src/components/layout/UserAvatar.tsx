/**
 * UserAvatar 组件 —— 用户头像显示组件
 *
 * 【用途与使用场景】
 * 在应用各处显示用户头像，包括：
 * - 侧边栏底部的用户信息区
 * - 移动端菜单的用户信息区
 * - 个人资料弹窗中的头像预览
 * - 个人资料弹窗中的默认头像选择列表
 * - 聊天页面的问答头像
 *
 * 【头像显示策略】
 * 1. 自定义上传头像（base64 data URL）：直接渲染为 <img> 元素
 * 2. 预设卡通头像（preset:xxx 格式）：渲染为圆形卡通头像
 * 3. 无头像：默认使用第一个预设头像（小鹿）
 *
 * 【导出内容】
 * - DEFAULT_AVATARS：6 种预设头像的配置数组（在 ProfileDialog 中也使用）
 * - getAvatarInitial()：从会话信息中提取头像首字符的工具函数
 * - UserAvatar：头像显示组件
 */

import type { Session } from '@/types'
import { cn } from '@/lib/utils'

/**
 * 6 种预设卡通头像配置
 * 每种包含：
 * @property id - 唯一标识符（如 'preset:aurora'）
 * @property label - 显示名称（如 '小鹿'）
 */
export const DEFAULT_AVATARS = [
  { id: 'preset:aurora', label: '小鹿', bg: '#dff7ec', face: '#f4b36b', accent: '#7a4d2f', blush: '#f39aa5' },
  { id: 'preset:forest', label: '小熊', bg: '#f5eddc', face: '#b8794b', accent: '#5a3724', blush: '#f1a384' },
  { id: 'preset:ink', label: '小企鹅', bg: '#dceeff', face: '#27364a', accent: '#ffffff', blush: '#f7a6b6' },
  { id: 'preset:rose', label: '小兔', bg: '#ffe5ef', face: '#fff7f9', accent: '#d97999', blush: '#f5a4b8' },
  { id: 'preset:amber', label: '小虎', bg: '#fff0c9', face: '#f2a33c', accent: '#57321d', blush: '#f4b183' },
  { id: 'preset:violet', label: '小星', bg: '#ece7ff', face: '#f7d66b', accent: '#6a55a3', blush: '#f0a8c5' },
]

type AvatarPreset = (typeof DEFAULT_AVATARS)[number]

/**
 * UserAvatar 组件的 Props 接口
 * @property session - 会话信息（包含 nickname、username、avatar），用于获取头像值和首字符
 * @property avatar - 直接指定的头像值（优先级高于 session.avatar）
 * @property className - 自定义 CSS 类名（可覆盖默认尺寸等样式）
 */
interface UserAvatarProps {
  session?: Pick<Session, 'nickname' | 'username' | 'avatar'> | null
  avatar?: string
  className?: string
}

/**
 * 从会话信息中提取头像首字符
 * @param session - 会话信息
 * @returns 昵称或用户名的第一个字符，无信息时返回 '?'
 */
export function getAvatarInitial(session?: Pick<Session, 'nickname' | 'username'> | null) {
  return (session?.nickname || session?.username || '?').trim()[0] || '?'
}

/**
 * UserAvatar 头像组件
 *
 * 根据头像值的类型自动选择渲染方式：
 * - data:image/ 开头 → 自定义上传的图片，渲染为 <img>
 * - preset:xxx 格式 → 预设渐变头像，渲染为带渐变背景和首字符的圆形
 */
export function UserAvatar({ session, avatar, className }: UserAvatarProps) {
  // 优先使用直接传入的 avatar，其次使用 session 中的 avatar
  const value = avatar ?? session?.avatar ?? ''

  // 情况 1：自定义上传的 base64 图片
  if (value.startsWith('data:image/')) {
    return (
      <img
        src={value}
        alt="用户头像"
        className={cn('h-8 w-8 rounded-full object-cover ring-1 ring-black/5 dark:ring-white/10', className)}
      />
    )
  }

  // 情况 2：预设卡通头像（找不到匹配 ID 时使用第一个预设）
  const preset = DEFAULT_AVATARS.find((item) => item.id === value) || DEFAULT_AVATARS[0]
  return (
    <div
      className={cn(
        'flex h-8 w-8 items-center justify-center overflow-hidden rounded-full bg-white shadow-sm ring-1 ring-black/5 dark:ring-white/10',
        className,
      )}
      aria-label={`${preset.label}头像`}
    >
      <CartoonAvatar preset={preset} />
    </div>
  )
}

function CartoonAvatar({ preset }: { preset: AvatarPreset }) {
  if (preset.id === 'preset:ink') {
    return (
      <svg viewBox="0 0 64 64" className="h-full w-full" aria-hidden="true">
        <rect width="64" height="64" fill={preset.bg} />
        <circle cx="32" cy="34" r="22" fill={preset.face} />
        <ellipse cx="32" cy="39" rx="14" ry="15" fill={preset.accent} />
        <circle cx="24" cy="30" r="2.8" fill="#101827" />
        <circle cx="40" cy="30" r="2.8" fill="#101827" />
        <path d="M29 35h8l-4 4z" fill="#f2a33c" />
        <circle cx="20" cy="38" r="3" fill={preset.blush} opacity=".75" />
        <circle cx="44" cy="38" r="3" fill={preset.blush} opacity=".75" />
      </svg>
    )
  }
  if (preset.id === 'preset:rose') {
    return (
      <svg viewBox="0 0 64 64" className="h-full w-full" aria-hidden="true">
        <rect width="64" height="64" fill={preset.bg} />
        <ellipse cx="22" cy="16" rx="7" ry="15" fill={preset.face} />
        <ellipse cx="42" cy="16" rx="7" ry="15" fill={preset.face} />
        <ellipse cx="22" cy="17" rx="3" ry="10" fill={preset.accent} opacity=".45" />
        <ellipse cx="42" cy="17" rx="3" ry="10" fill={preset.accent} opacity=".45" />
        <circle cx="32" cy="38" r="21" fill={preset.face} />
        <circle cx="24" cy="35" r="2.6" fill="#334155" />
        <circle cx="40" cy="35" r="2.6" fill="#334155" />
        <path d="M29 41q3 3 6 0" fill="none" stroke="#334155" strokeWidth="2" strokeLinecap="round" />
        <circle cx="20" cy="41" r="3.5" fill={preset.blush} opacity=".8" />
        <circle cx="44" cy="41" r="3.5" fill={preset.blush} opacity=".8" />
      </svg>
    )
  }
  if (preset.id === 'preset:violet') {
    return (
      <svg viewBox="0 0 64 64" className="h-full w-full" aria-hidden="true">
        <rect width="64" height="64" fill={preset.bg} />
        <path d="M32 9l6.6 14.3L54 25.2 42.6 36l2.9 15.5L32 43.8 18.5 51.5 21.4 36 10 25.2l15.4-1.9z" fill={preset.face} />
        <circle cx="27" cy="31" r="2.4" fill={preset.accent} />
        <circle cx="37" cy="31" r="2.4" fill={preset.accent} />
        <path d="M28.5 38q3.5 2.8 7 0" fill="none" stroke={preset.accent} strokeWidth="2" strokeLinecap="round" />
        <circle cx="22" cy="36" r="3" fill={preset.blush} opacity=".7" />
        <circle cx="44" cy="36" r="3" fill={preset.blush} opacity=".7" />
      </svg>
    )
  }
  return (
    <svg viewBox="0 0 64 64" className="h-full w-full" aria-hidden="true">
      <rect width="64" height="64" fill={preset.bg} />
      {preset.id === 'preset:aurora' && (
        <>
          <path d="M18 24 12 10M46 24l6-14" stroke={preset.accent} strokeWidth="4" strokeLinecap="round" />
          <circle cx="12" cy="10" r="3" fill={preset.accent} />
          <circle cx="52" cy="10" r="3" fill={preset.accent} />
        </>
      )}
      {preset.id === 'preset:forest' && (
        <>
          <circle cx="19" cy="20" r="8" fill={preset.face} />
          <circle cx="45" cy="20" r="8" fill={preset.face} />
        </>
      )}
      {preset.id === 'preset:amber' && (
        <>
          <path d="M17 23 9 13l13 4zM47 23l8-10-13 4z" fill={preset.face} />
          <path d="M24 15h4M32 14v7M40 15h-4" stroke={preset.accent} strokeWidth="2.5" strokeLinecap="round" />
        </>
      )}
      <circle cx="32" cy="36" r="22" fill={preset.face} />
      {preset.id === 'preset:amber' && (
        <>
          <path d="M18 30h7M39 30h7M21 22l5 4M43 22l-5 4" stroke={preset.accent} strokeWidth="2.4" strokeLinecap="round" />
        </>
      )}
      <circle cx="24" cy="34" r="2.7" fill={preset.accent} />
      <circle cx="40" cy="34" r="2.7" fill={preset.accent} />
      <path d="M28.5 42q3.5 2.5 7 0" fill="none" stroke={preset.accent} strokeWidth="2" strokeLinecap="round" />
      <circle cx="20" cy="40" r="3.4" fill={preset.blush} opacity=".72" />
      <circle cx="44" cy="40" r="3.4" fill={preset.blush} opacity=".72" />
    </svg>
  )
}
