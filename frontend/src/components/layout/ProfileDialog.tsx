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

const MAX_AVATAR_SOURCE_BYTES = 6 * 1024 * 1024
const AVATAR_TARGET_SIZE = 192
const AVATAR_QUALITY = 0.82

interface ProfileDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
}

function getErrorMessage(error: unknown) {
  if (error instanceof Error) return error.message
  return '保存失败，请稍后再试'
}

function loadImage(src: string) {
  return new Promise<HTMLImageElement>((resolve, reject) => {
    const image = new Image()
    image.onload = () => resolve(image)
    image.onerror = () => reject(new Error('头像读取失败，请换一张图片'))
    image.src = src
  })
}

async function compressAvatar(file: File) {
  const objectUrl = URL.createObjectURL(file)
  try {
    const image = await loadImage(objectUrl)
    const sourceSize = Math.min(image.naturalWidth || image.width, image.naturalHeight || image.height)
    const sourceX = ((image.naturalWidth || image.width) - sourceSize) / 2
    const sourceY = ((image.naturalHeight || image.height) - sourceSize) / 2
    const canvas = document.createElement('canvas')
    canvas.width = AVATAR_TARGET_SIZE
    canvas.height = AVATAR_TARGET_SIZE
    const ctx = canvas.getContext('2d')
    if (!ctx) throw new Error('当前浏览器不支持头像压缩')
    ctx.drawImage(image, sourceX, sourceY, sourceSize, sourceSize, 0, 0, AVATAR_TARGET_SIZE, AVATAR_TARGET_SIZE)
    return canvas.toDataURL('image/jpeg', AVATAR_QUALITY)
  } finally {
    URL.revokeObjectURL(objectUrl)
  }
}

export function ProfileDialog({ open, onOpenChange }: ProfileDialogProps) {
  const { session, updatePassword, updateProfile } = useAuth()
  const { showToast } = useToast()
  const fileInputRef = useRef<HTMLInputElement | null>(null)
  const [nickname, setNickname] = useState('')
  const [avatar, setAvatar] = useState('')
  const [currentPassword, setCurrentPassword] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [isSaving, setIsSaving] = useState(false)
  const [isPasswordSaving, setIsPasswordSaving] = useState(false)

  const canSaveProfile = nickname.trim().length > 0 && !isSaving
  const canSavePassword = currentPassword.length > 0
    && newPassword.length > 0
    && confirmPassword.length > 0
    && !isPasswordSaving

  useEffect(() => {
    if (!open) return
    setNickname(session?.nickname || session?.username || '')
    setAvatar(session?.avatar || DEFAULT_AVATARS[0].id)
    setCurrentPassword('')
    setNewPassword('')
    setConfirmPassword('')
  }, [open, session])

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
      if (fileInputRef.current) fileInputRef.current.value = ''
    } catch (error) {
      showToast(getErrorMessage(error))
    }
  }

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
      onOpenChange(false)
    } catch (error) {
      showToast(getErrorMessage(error))
    } finally {
      setIsSaving(false)
    }
  }

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

  const inputClass =
    'h-11 rounded-xl border-slate-200/80 bg-white/95 shadow-[0_1px_2px_rgba(15,23,42,0.04)] focus-visible:border-[#9db7ff] focus-visible:ring-[#c8d6ff] focus-visible:ring-offset-0'

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="flex max-h-[92vh] w-[min(940px,calc(100vw-28px))] max-w-none grid-rows-none flex-col overflow-hidden rounded-2xl border border-slate-200/80 bg-[#fbfcfe] p-0 shadow-[0_24px_70px_rgba(15,23,42,0.24)] dark:border-slate-800 dark:bg-slate-950">
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

        <div className="min-h-0 flex-1 overflow-y-auto">
          <div className="grid gap-0 lg:grid-cols-[260px_1fr]">
            <aside className="border-b border-slate-100 bg-[#f6f8fb] px-7 py-7 dark:border-slate-800 dark:bg-slate-900/50 lg:border-b-0 lg:border-r">
              <div className="flex flex-col items-center text-center">
                <UserAvatar
                  session={{ username: session?.username || '', nickname, avatar }}
                  className="h-24 w-24 text-3xl shadow-[0_14px_32px_rgba(15,23,42,0.14)] ring-4 ring-white"
                />
                <div className="mt-4 max-w-full">
                  <p className="truncate text-base font-semibold text-slate-900 dark:text-slate-100">{nickname || session?.username}</p>
                  <p className="mt-1 truncate text-xs text-slate-500">{session?.username}</p>
                </div>
                <Button
                  type="button"
                  variant="outline"
                  className="mt-5 h-10 w-full rounded-xl border-slate-200/80 bg-white text-slate-700 shadow-sm hover:bg-slate-50 dark:border-slate-700 dark:bg-slate-950 dark:text-slate-200"
                  onClick={() => fileInputRef.current?.click()}
                >
                  <ImagePlus className="mr-2 h-4 w-4" />
                  上传图片
                </Button>
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

            <div className="space-y-7 px-7 py-7">
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

              <section>
                <div className="mb-3">
                  <h3 className="text-sm font-semibold text-slate-900 dark:text-slate-100">默认头像</h3>
                  <p className="mt-1 text-xs text-slate-500">选择一个预设头像，或上传自己的图片。</p>
                </div>
                <div className="flex flex-wrap gap-2">
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
