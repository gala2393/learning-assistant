import { useEffect, useRef, useState } from 'react'
import { Camera, Check, ImagePlus, KeyRound, Loader2 } from 'lucide-react'
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

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-h-[92vh] max-w-xl gap-5 overflow-y-auto rounded-xl border-slate-200 p-0 dark:border-slate-800">
        <div className="border-b bg-[#f7f9fc] px-6 py-5 dark:border-slate-800 dark:bg-slate-950/40">
          <DialogHeader>
            <DialogTitle className="flex items-center gap-2 text-base">
              <Camera className="h-4 w-4 text-sky-600" />
              修改个人资料
            </DialogTitle>
            <DialogDescription>设置昵称、头像和账号密码。</DialogDescription>
          </DialogHeader>
        </div>

        <div className="grid gap-5 px-6">
          <div className="flex items-center gap-4">
            <UserAvatar
              session={{ username: session?.username || '', nickname, avatar }}
              className="h-20 w-20 text-2xl"
            />
            <div className="min-w-0 flex-1 space-y-2">
              <Label htmlFor="profile-nickname">昵称</Label>
              <Input
                id="profile-nickname"
                value={nickname}
                maxLength={64}
                onChange={(event) => setNickname(event.target.value)}
                placeholder="输入你的显示昵称"
              />
            </div>
          </div>

          <div className="space-y-3">
            <div className="flex items-center justify-between">
              <Label>默认头像</Label>
              <Button
                type="button"
                variant="outline"
                size="sm"
                className="gap-2"
                onClick={() => fileInputRef.current?.click()}
              >
                <ImagePlus className="h-4 w-4" />
                上传图片
              </Button>
              <input
                ref={fileInputRef}
                type="file"
                accept="image/*"
                className="hidden"
                onChange={(event) => handleUpload(event.target.files?.[0])}
              />
            </div>
            <div className="grid grid-cols-6 gap-2">
              {DEFAULT_AVATARS.map((item) => {
                const selected = avatar === item.id
                return (
                  <button
                    key={item.id}
                    type="button"
                    title={item.label}
                    className={cn(
                      'relative flex aspect-square items-center justify-center rounded-lg border transition',
                      selected
                        ? 'border-sky-500 ring-2 ring-sky-500/20'
                        : 'border-slate-200 hover:border-slate-300 dark:border-slate-800 dark:hover:border-slate-700',
                    )}
                    onClick={() => setAvatar(item.id)}
                  >
                    <UserAvatar session={{ username: session?.username || '', nickname, avatar: item.id }} className="h-10 w-10" />
                    {selected && (
                      <span className="absolute right-1 top-1 rounded-full bg-sky-600 p-0.5 text-white">
                        <Check className="h-3 w-3" />
                      </span>
                    )}
                  </button>
                )
              })}
            </div>
            <p className="text-xs text-muted-foreground">上传图片会自动裁成方形并压缩，保存后会同步到侧边栏和问答头像。</p>
          </div>

          <div className="space-y-4 rounded-lg border border-slate-200 bg-slate-50/70 p-4 dark:border-slate-800 dark:bg-slate-950/30">
            <div className="flex items-center gap-2">
              <KeyRound className="h-4 w-4 text-sky-600" />
              <Label>修改密码</Label>
            </div>
            <div className="grid gap-3 sm:grid-cols-3">
              <div className="space-y-2">
                <Label htmlFor="profile-current-password" className="text-xs text-muted-foreground">当前密码</Label>
                <Input
                  id="profile-current-password"
                  type="password"
                  value={currentPassword}
                  autoComplete="current-password"
                  onChange={(event) => setCurrentPassword(event.target.value)}
                />
              </div>
              <div className="space-y-2">
                <Label htmlFor="profile-new-password" className="text-xs text-muted-foreground">新密码</Label>
                <Input
                  id="profile-new-password"
                  type="password"
                  value={newPassword}
                  autoComplete="new-password"
                  onChange={(event) => setNewPassword(event.target.value)}
                />
              </div>
              <div className="space-y-2">
                <Label htmlFor="profile-confirm-password" className="text-xs text-muted-foreground">确认新密码</Label>
                <Input
                  id="profile-confirm-password"
                  type="password"
                  value={confirmPassword}
                  autoComplete="new-password"
                  onChange={(event) => setConfirmPassword(event.target.value)}
                />
              </div>
            </div>
            <div className="flex items-center justify-between gap-3">
              <p className="text-xs text-muted-foreground">密码长度 8-64 位，保存后可用新密码登录。</p>
              <Button
                type="button"
                variant="outline"
                size="sm"
                className="shrink-0 gap-2"
                disabled={isPasswordSaving}
                onClick={handlePasswordSave}
              >
                {isPasswordSaving && <Loader2 className="h-4 w-4 animate-spin" />}
                保存密码
              </Button>
            </div>
          </div>
        </div>

        <DialogFooter className="border-t px-6 py-4 dark:border-slate-800">
          <Button variant="outline" onClick={() => onOpenChange(false)} disabled={isSaving}>
            取消
          </Button>
          <Button onClick={handleSave} disabled={isSaving} className="gap-2">
            {isSaving && <Loader2 className="h-4 w-4 animate-spin" />}
            保存修改
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
