/**
 * ProfileDialog 组件 —— 个人资料编辑弹窗
 *
 * 【用途与使用场景】
 * 以 Dialog 弹窗形式展示和编辑用户的个人资料信息。
 * 从 Sidebar 或 AppShell 的移动端菜单中打开。
 *
 * 【功能模块】
 * 1. 头像管理：
 *    - 6 种预设渐变头像供选择
 *    - 支持上传自定义图片（自动裁切为正方形并压缩到 192x192）
 *    - 上传限制：仅图片文件，最大 6MB
 * 2. 昵称修改：
 *    - 最长 64 字符
 * 3. 密码修改：
 *    - 需要输入当前密码、新密码、确认新密码
 *    - 后端会校验当前密码是否正确
 *    - 新密码长度 8-64 位，不能与当前密码相同
 *
 * 【技术细节】
 * - 头像压缩使用 Canvas API 进行客户端裁切和压缩
 * - 使用 loadimage + canvas.toDataURL 实现图片处理
 * - 使用 Object URL 读取文件，处理完成后及时释放
 * - 所有操作通过 Toast 组件给出反馈
 */

import { useEffect, useRef, useState } from 'react'
import { Camera, Check, ImagePlus, KeyRound, Loader2, ShieldCheck } from 'lucide-react'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { useToast } from '@/components/ui/toast'
import { useAuth } from '@/context/AuthContext'
import { actionButtonBase, actionButtonIdle, actionButtonReady } from '@/lib/action-button-styles'
import { cn } from '@/lib/utils'
import { DEFAULT_AVATARS, UserAvatar } from './UserAvatar'

/** 上传头像原图的最大字节数（6MB） */
const MAX_AVATAR_SOURCE_BYTES = 6 * 1024 * 1024
/** 头像压缩后的目标尺寸（像素，正方形） */
const AVATAR_TARGET_SIZE = 192
/** JPEG 压缩质量（0-1，0.82 在体积和质量之间取得平衡） */
const AVATAR_QUALITY = 0.82

/**
 * ProfileDialog 组件的 Props 接口
 * @property open - 弹窗是否打开
 * @property onOpenChange - 弹窗开关状态变化的回调
 */
interface ProfileDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
}

/**
 * 统一的错误信息提取函数
 * @param error - 捕获的异常对象
 * @returns 可读的错误提示文字
 */
function getErrorMessage(error: unknown) {
  if (error instanceof Error) return error.message
  return '保存失败，请稍后再试'
}

/**
 * 将图片地址加载为 HTMLImageElement
 * @param src - 图片地址（支持 data URL 和 Object URL）
 * @returns Promise<HTMLImageElement>
 */
function loadImage(src: string) {
  return new Promise<HTMLImageElement>((resolve, reject) => {
    const image = new Image()
    image.onload = () => resolve(image)
    image.onerror = () => reject(new Error('头像读取失败，请换一张图片'))
    image.src = src
  })
}

/**
 * 压缩头像图片
 * @param file - 用户选择的图片文件
 * @returns base64 格式的 JPEG data URL
 *
 * 处理流程：
 * 1. 创建 Object URL 读取图片
 * 2. 取图片中心的最大正方形区域（居中裁切）
 * 3. 绘制到 192x192 的 Canvas 上
 * 4. 导出为 JPEG（质量 0.82）
 * 5. 释放 Object URL 避免内存泄漏
 */
async function compressAvatar(file: File) {
  const objectUrl = URL.createObjectURL(file)
  try {
    const image = await loadImage(objectUrl)
    // 取宽高中较小的值作为裁切正方形的边长（居中裁切）
    const sourceSize = Math.min(image.naturalWidth || image.width, image.naturalHeight || image.height)
    const sourceX = ((image.naturalWidth || image.width) - sourceSize) / 2
    const sourceY = ((image.naturalHeight || image.height) - sourceSize) / 2
    const canvas = document.createElement('canvas')
    canvas.width = AVATAR_TARGET_SIZE
    canvas.height = AVATAR_TARGET_SIZE
    const ctx = canvas.getContext('2d')
    if (!ctx) throw new Error('当前浏览器不支持头像压缩')
    // 将裁切后的区域绘制到目标尺寸
    ctx.drawImage(image, sourceX, sourceY, sourceSize, sourceSize, 0, 0, AVATAR_TARGET_SIZE, AVATAR_TARGET_SIZE)
    return canvas.toDataURL('image/jpeg', AVATAR_QUALITY)
  } finally {
    // 无论成功或失败，都释放 Object URL
    URL.revokeObjectUrl(objectUrl)
  }
}

export function ProfileDialog({ open, onOpenChange }: ProfileDialogProps) {
  // 从认证上下文获取会话信息和更新方法
  const { session, updatePassword, updateProfile } = useAuth()
  const { showToast } = useToast()
  // 文件上传 input 的引用，用于触发点击
  const fileInputRef = useRef<HTMLInputElement | null>(null)
  // 昵称输入值
  const [nickname, setNickname] = useState('')
  // 当前选择的头像值（预设 ID 或 base64 data URL）
  const [avatar, setAvatar] = useState('')
  // 密码修改相关状态
  const [currentPassword, setCurrentPassword] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  // 个人资料保存的加载状态
  const [isSaving, setIsSaving] = useState(false)
  // 密码修改保存的加载状态
  const [isPasswordSaving, setIsPasswordSaving] = useState(false)

  // 个人资料保存按钮是否可用
  const canSaveProfile = nickname.trim().length > 0 && !isSaving
  // 密码保存按钮是否可用
  const canSavePassword = currentPassword.length > 0
    && newPassword.length > 0
    && confirmPassword.length > 0
    && !isPasswordSaving

  /**
   * 弹窗打开时，从当前会话信息初始化表单数据
   * 关闭时不需要重置，因为下次打开会重新初始化
   */
  useEffect(() => {
    if (!open) return
    setNickname(session?.nickname || session?.username || '')
    setAvatar(session?.avatar || DEFAULT_AVATARS[0].id)
    // 清空密码字段
    setCurrentPassword('')
    setNewPassword('')
    setConfirmPassword('')
  }, [open, session])

  /**
   * 处理头像图片上传：
   * 1. 校验文件类型（必须是图片）
   * 2. 校验文件大小（不超过 6MB）
   * 3. 调用 compressAvatar 进行客户端压缩
   * 4. 将压缩后的 base64 数据设为当前头像
   */
  const handleUpload = async (file?: File) => {
    if (!file) return
    if (!file.type.startsWith('image/')) {
      showToast('请选择图片文件')
      return
    }
    if (file.size > MAX_AVATAR_SOURCE_BYTES) {
      showToast('头像原图请控制在 6MB 以内')
      return
    }
    try {
      setAvatar(await compressAvatar(file))
      showToast('头像已压缩预览，记得保存修改')
      // 清空文件 input 的值，允许重新选择同一文件
      if (fileInputRef.current) fileInputRef.current.value = ''
    } catch (error) {
      showToast(getErrorMessage(error))
    }
  }

  /**
   * 保存个人资料（昵称 + 头像）
   * 调用 AuthContext 的 updateProfile 方法
   */
  const handleSave = async () => {
    const nextNickname = nickname.trim()
    if (!nextNickname) {
      showToast('昵称不能为空')
      return
    }
    setIsSaving(true)
    try {
      await updateProfile({ nickname: nextNickname, avatar })
      showToast('个人资料已更新')
      onOpenChange(false) // 保存成功后关闭弹窗
    } catch (error) {
      showToast(getErrorMessage(error))
    } finally {
      setIsSaving(false)
    }
  }

  /**
   * 保存密码修改：
   * 1. 前端校验（非空、长度、一致性）
   * 2. 调用 AuthContext 的 updatePassword 方法
   * 3. 成功后清空密码字段
   */
  const handlePasswordSave = async () => {
    if (!currentPassword || !newPassword || !confirmPassword) {
      showToast('请完整填写密码信息')
      return
    }
    if (newPassword.length < 8 || newPassword.length > 64) {
      showToast('新密码长度需在 8 到 64 位之间')
      return
    }
    if (newPassword !== confirmPassword) {
      showToast('两次输入的新密码不一致')
      return
    }
    setIsPasswordSaving(true)
    try {
      await updatePassword({ currentPassword, newPassword, confirmPassword })
      // 成功后清空所有密码字段
      setCurrentPassword('')
      setNewPassword('')
      setConfirmPassword('')
      showToast('密码已更新')
    } catch (error) {
      showToast(getErrorMessage(error))
    } finally {
      setIsPasswordSaving(false)
    }
  }

  // 统一的输入框样式
  const inputClass =
    'h-11 rounded-xl border-slate-200/80 bg-white/95 shadow-[0_1px_2px_rgba(15,23,42,0.04)] focus-visible:border-[#9db7ff] focus-visible:ring-[#c8d6ff] focus-visible:ring-offset-0'

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="flex max-h-[92vh] w-[min(940px,calc(100vw-28px))] max-w-none grid-rows-none flex-col overflow-hidden rounded-2xl border border-slate-200/80 bg-[#fbfcfe] p-0 shadow-[0_24px_70px_rgba(15,23,42,0.24)] dark:border-slate-800 dark:bg-slate-950">
        {/* 弹窗头部 */}
        <div className="flex shrink-0 items-start justify-between gap-4 border-b border-slate-100 bg-white/90 px-7 py-6 dark:border-slate-800 dark:bg-slate-950">
          <DialogHeader className="space-y-2">
            <DialogTitle className="flex items-center gap-3 text-xl">
              <span className="flex h-10 w-10 items-center justify-center rounded-xl bg-[#eef3f7] text-slate-500 dark:bg-slate-900 dark:text-slate-300">
                <Camera className="h-4 w-4" />
              </span>
              修改个人资料
            </DialogTitle>
            <DialogDescription className="text-slate-500">管理昵称、头像和账号密码。</DialogDescription>
          </DialogHeader>
        </div>

        {/* 弹窗内容区（可滚动） */}
        <div className="min-h-0 flex-1 overflow-y-auto">
          <div className="grid gap-0 lg:grid-cols-[260px_1fr]">
            {/* 左侧面板：头像预览和上传 */}
            <aside className="border-b border-slate-100 bg-[#f6f8fb] px-7 py-7 dark:border-slate-800 dark:bg-slate-900/50 lg:border-b-0 lg:border-r">
              <div className="flex flex-col items-center text-center">
                {/* 当前头像预览 */}
                <UserAvatar
                  session={{ username: session?.username || '', nickname, avatar }}
                  className="h-24 w-24 text-3xl shadow-[0_14px_32px_rgba(15,23,42,0.14)] ring-4 ring-white"
                />
                {/* 用户名和昵称展示 */}
                <div className="mt-4 max-w-full">
                  <p className="truncate text-base font-semibold text-slate-900 dark:text-slate-100">{nickname || session?.username}</p>
                  <p className="mt-1 truncate text-xs text-slate-500">{session?.username}</p>
                </div>
                {/* 上传图片按钮 */}
                <Button
                  type="button"
                  variant="outline"
                  className="mt-5 h-10 w-full rounded-xl border-slate-200/80 bg-white text-slate-700 shadow-sm hover:bg-slate-50 dark:border-slate-700 dark:bg-slate-950 dark:text-slate-200"
                  onClick={() => fileInputRef.current?.click()}
                >
                  <ImagePlus className="mr-2 h-4 w-4" />
                  上传图片
                </Button>
                {/* 隐藏的文件选择 input */}
                <input
                  ref={fileInputRef}
                  type="file"
                  accept="image/*"
                  className="hidden"
                  onChange={(event) => handleUpload(event.target.files?.[0])}
                />
                <p className="mt-3 text-xs leading-5 text-slate-500">图片会自动裁成方形并压缩，保存后同步到侧边栏和问答头像。</p>
              </div>
            </aside>

            {/* 右侧面板：表单内容 */}
            <div className="space-y-7 px-7 py-7">
              {/* 昵称修改区域 */}
              <section>
                <div className="mb-3">
                  <h3 className="text-sm font-semibold text-slate-900 dark:text-slate-100">基础信息</h3>
                  <p className="mt-1 text-xs text-slate-500">昵称会显示在个人资料和问答头像旁。</p>
                </div>
                <div className="space-y-2">
                  <Label htmlFor="profile-nickname" className="text-xs font-medium text-slate-500">
                    昵称
                  </Label>
                  <Input
                    id="profile-nickname"
                    value={nickname}
                    maxLength={64}
                    onChange={(event) => setNickname(event.target.value)}
                    placeholder="输入你的显示昵称"
                    className={inputClass}
                  />
                </div>
              </section>

              {/* 默认头像选择区域 */}
              <section>
                <div className="mb-3">
                  <h3 className="text-sm font-semibold text-slate-900 dark:text-slate-100">默认头像</h3>
                  <p className="mt-1 text-xs text-slate-500">选择一个预设头像，或上传自己的图片。</p>
                </div>
                <div className="flex flex-wrap gap-2">
                  {/* 渲染 6 种预设头像供选择 */}
                  {DEFAULT_AVATARS.map((item) => {
                    const selected = avatar === item.id
                    return (
                      <button
                        key={item.id}
                        type="button"
                        title={item.label}
                        className={cn(
                          'relative flex h-14 w-14 shrink-0 items-center justify-center rounded-xl border bg-white shadow-sm transition hover:-translate-y-0.5 hover:border-slate-300 hover:bg-slate-50 dark:bg-slate-900',
                          selected
                            ? 'border-[#9db7ff] ring-2 ring-[#c8d6ff] dark:border-slate-300 dark:ring-slate-300/20'
                            : 'border-slate-200/80 dark:border-slate-800',
                        )}
                        onClick={() => setAvatar(item.id)}
                      >
                        <UserAvatar session={{ username: session?.username || '', nickname, avatar: item.id }} className="h-9 w-9" />
                        {/* 选中状态显示勾号 */}
                        {selected && (
                          <span className="absolute -right-1 -top-1 rounded-full bg-slate-600 p-0.5 text-white dark:bg-slate-200 dark:text-slate-950">
                            <Check className="h-3 w-3" />
                          </span>
                        )}
                      </button>
                    )
                  })}
                </div>
              </section>

              {/* 密码修改区域 */}
              <section className="border-t border-slate-100 pt-6 dark:border-slate-800">
                <div className="mb-3 flex items-start gap-3">
                  <span className="flex h-9 w-9 items-center justify-center rounded-xl bg-[#eef3f7] text-slate-500 dark:bg-slate-900 dark:text-slate-300">
                    <KeyRound className="h-4 w-4" />
                  </span>
                  <div>
                    <h3 className="text-sm font-semibold text-slate-900 dark:text-slate-100">修改密码</h3>
                    <p className="mt-1 text-xs text-slate-500">密码长度 8-64 位，保存后可使用新密码登录。</p>
                  </div>
                </div>
                {/* 三个密码输入框并排显示（桌面端） */}
                <div className="grid gap-3 md:grid-cols-3">
                  <div className="space-y-2">
                    <Label htmlFor="profile-current-password" className="text-xs font-medium text-slate-500">当前密码</Label>
                    <Input
                      id="profile-current-password"
                      type="password"
                      value={currentPassword}
                      autoComplete="current-password"
                      onChange={(event) => setCurrentPassword(event.target.value)}
                      className={inputClass}
                    />
                  </div>
                  <div className="space-y-2">
                    <Label htmlFor="profile-new-password" className="text-xs font-medium text-slate-500">新密码</Label>
                    <Input
                      id="profile-new-password"
                      type="password"
                      value={newPassword}
                      autoComplete="new-password"
                      onChange={(event) => setNewPassword(event.target.value)}
                      className={inputClass}
                    />
                  </div>
                  <div className="space-y-2">
                    <Label htmlFor="profile-confirm-password" className="text-xs font-medium text-slate-500">确认新密码</Label>
                    <Input
                      id="profile-confirm-password"
                      type="password"
                      value={confirmPassword}
                      autoComplete="new-password"
                      onChange={(event) => setConfirmPassword(event.target.value)}
                      className={inputClass}
                    />
                  </div>
                </div>
                {/* 密码安全提示 + 保存密码按钮 */}
                <div className="mt-4 flex flex-col gap-3 rounded-xl bg-[#f6f8fb] px-4 py-3 dark:bg-slate-900 sm:flex-row sm:items-center sm:justify-between">
                  <div className="flex items-center gap-2 text-xs text-slate-500">
                    <ShieldCheck className="h-4 w-4 text-slate-600 dark:text-slate-300" />
                    后端会校验当前密码，且新密码不能与当前密码相同。
                  </div>
                  <Button
                    type="button"
                    size="sm"
                    className={`h-10 shrink-0 rounded-xl px-4 ${actionButtonBase} ${canSavePassword ? actionButtonReady : actionButtonIdle}`}
                    disabled={isPasswordSaving}
                    onClick={handlePasswordSave}
                  >
                    {isPasswordSaving && <Loader2 className="mr-1.5 h-4 w-4 animate-spin" />}
                    保存密码
                  </Button>
                </div>
              </section>
            </div>
          </div>
        </div>

        {/* 弹窗底部：取消和保存按钮 */}
        <DialogFooter className="shrink-0 border-t border-slate-100 bg-white/95 px-7 py-5 dark:border-slate-800 dark:bg-slate-950">
          <Button
            variant="outline"
            className="h-11 rounded-xl border-slate-200/80 bg-white px-6 text-slate-700 shadow-sm hover:bg-slate-50 dark:border-slate-700 dark:bg-slate-950 dark:text-slate-200"
            onClick={() => onOpenChange(false)}
            disabled={isSaving}
          >
            取消
          </Button>
          <Button
            onClick={handleSave}
            disabled={isSaving}
            className={`h-11 rounded-xl px-6 ${actionButtonBase} ${canSaveProfile ? actionButtonReady : actionButtonIdle}`}
          >
            {isSaving && <Loader2 className="mr-1.5 h-4 w-4 animate-spin" />}
            保存修改
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
