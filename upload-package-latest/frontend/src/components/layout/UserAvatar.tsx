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
 * 2. 预设渐变头像（preset:xxx 格式）：渲染为带渐变背景的圆形容器，中间显示用户名首字符
 * 3. 无头像：默认使用第一个预设头像（晨光）
 *
 * 【导出内容】
 * - DEFAULT_AVATARS：6 种预设头像的配置数组（在 ProfileDialog 中也使用）
 * - getAvatarInitial()：从会话信息中提取头像首字符的工具函数
 * - UserAvatar：头像显示组件
 */

import type { Session } from '@/types'
import { cn } from '@/lib/utils'

/**
 * 6 种预设渐变头像配置
 * 每种包含：
 * @property id - 唯一标识符（如 'preset:aurora'）
 * @property label - 显示名称（如 '晨光'）
 * @property className - 渐变背景的 Tailwind CSS 类名
 */
export const DEFAULT_AVATARS = [
  { id: 'preset:aurora', label: '晨光', className: 'bg-[linear-gradient(135deg,#0ea5e9,#f59e0b)]' },
  { id: 'preset:forest', label: '松林', className: 'bg-[linear-gradient(135deg,#15803d,#84cc16)]' },
  { id: 'preset:ink', label: '墨蓝', className: 'bg-[linear-gradient(135deg,#0f172a,#38bdf8)]' },
  { id: 'preset:rose', label: '蔷薇', className: 'bg-[linear-gradient(135deg,#be123c,#f9a8d4)]' },
  { id: 'preset:amber', label: '琥珀', className: 'bg-[linear-gradient(135deg,#92400e,#fde047)]' },
  { id: 'preset:violet', label: '藤紫', className: 'bg-[linear-gradient(135deg,#6d28d9,#c4b5fd)]' },
]

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

  // 情况 2：预设渐变头像（找不到匹配 ID 时使用第一个预设）
  const preset = DEFAULT_AVATARS.find((item) => item.id === value) || DEFAULT_AVATARS[0]
  return (
    <div
      className={cn(
        'flex h-8 w-8 items-center justify-center rounded-full text-sm font-semibold text-white shadow-sm',
        preset.className,
        className,
      )}
    >
      {/* 在渐变背景上显示用户名/昵称的首字符 */}
      {getAvatarInitial(session)}
    </div>
  )
}
