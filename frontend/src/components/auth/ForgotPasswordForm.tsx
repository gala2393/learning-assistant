import { useEffect, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { CheckCircle2 } from 'lucide-react'
import { z } from 'zod'
import { resetPassword, sendEmailCode } from '@/api/auth'
import { AuthScene, authInputClassName, authPrimaryButtonClassName, authSelectClassName } from '@/components/auth/AuthScene'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { cn } from '@/lib/utils'
import { normalizeEmail, providerForEmail } from '@/lib/email'

const forgotPasswordSchema = z
  .object({
    emailPrefix: z.string().min(1, '请输入邮箱地址').max(64, '邮箱地址过长'),
    emailDomain: z.enum(['qq.com', '163.com']),
    code: z.string().regex(/^\d{6}$/, '请输入 6 位数字验证码'),
    newPassword: z.string().min(8, '密码至少 8 位').max(64, '密码最多 64 位'),
    confirmPassword: z.string(),
  })
  .refine((data) => data.newPassword === data.confirmPassword, {
    message: '两次输入的密码不一致',
    path: ['confirmPassword'],
  })

type ForgotPasswordFormValues = z.infer<typeof forgotPasswordSchema>

export function ForgotPasswordForm() {
  const navigate = useNavigate()
  const [error, setError] = useState('')
  const [success, setSuccess] = useState('')
  const [loading, setLoading] = useState(false)
  const [codeLoading, setCodeLoading] = useState(false)
  const [cooldown, setCooldown] = useState(0)

  const {
    register,
    handleSubmit,
    watch,
    getValues,
    formState: { errors },
  } = useForm<ForgotPasswordFormValues>({
    resolver: zodResolver(forgotPasswordSchema),
    defaultValues: {
      emailPrefix: '',
      emailDomain: 'qq.com',
      code: '',
      newPassword: '',
      confirmPassword: '',
    },
  })

  const emailPrefix = watch('emailPrefix')
  const emailDomain = watch('emailDomain')
  const code = watch('code')
  const newPassword = watch('newPassword')
  const confirmPassword = watch('confirmPassword')
  const email = useMemo(() => normalizeEmail(emailPrefix, emailDomain), [emailPrefix, emailDomain])
  const provider = providerForEmail(emailPrefix, emailDomain) === 'netease' ? '163 邮箱' : 'QQ 邮箱'
  const canSendCode = emailPrefix.trim().length > 0 && !codeLoading && cooldown <= 0
  const canReset = emailPrefix.trim().length > 0
    && code.trim().length > 0
    && newPassword.trim().length > 0
    && confirmPassword.trim().length > 0
    && !loading

  useEffect(() => {
    if (cooldown <= 0) return
    const timer = window.setTimeout(() => setCooldown((value) => value - 1), 1000)
    return () => window.clearTimeout(timer)
  }, [cooldown])

  const handleSendCode = async () => {
    const values = getValues()
    const targetEmail = normalizeEmail(values.emailPrefix, values.emailDomain)
    if (!values.emailPrefix.trim()) {
      setError('请先填写邮箱地址。')
      return
    }
    setError('')
    setSuccess('')
    setCodeLoading(true)
    try {
      await sendEmailCode(targetEmail, providerForEmail(values.emailPrefix, values.emailDomain))
      setCooldown(60)
      setSuccess('验证码已发送，请在 5 分钟内完成密码重置。')
    } catch (err: unknown) {
      const e = err as { message?: string }
      setError(e.message || '验证码发送失败，请稍后再试。')
    } finally {
      setCodeLoading(false)
    }
  }

  const onSubmit = async (data: ForgotPasswordFormValues) => {
    setError('')
    setSuccess('')
    setLoading(true)
    try {
      await resetPassword({
        email: normalizeEmail(data.emailPrefix, data.emailDomain),
        code: data.code,
        newPassword: data.newPassword,
        confirmPassword: data.confirmPassword,
      })
      setSuccess('密码已重置，请使用新密码登录。')
      window.setTimeout(() => navigate('/login', { replace: true }), 900)
    } catch (err: unknown) {
      const e = err as { message?: string }
      setError(e.message || '密码重置失败，请检查验证码和邮箱后再试。')
    } finally {
      setLoading(false)
    }
  }

  return (
    <AuthScene
      eyebrow="Access / Reset"
      title="找回账号"
      description="通过注册邮箱验证身份，然后设置一个新的登录密码。"
      sideTitle="密码重置之后，工作台会继续保留你的学习上下文"
      sideDescription="资料、问答记录和总结内容不会因为密码变更而丢失，你只是在重新获得访问权限。"
      sideNotes={[
        '验证码会发送到你的注册邮箱。',
        '新密码提交后立即生效。',
        '重置完成后可直接回到学习工作台。',
      ]}
      sideActionLabel="返回登录"
      sideActionTo="/login"
    >
      {error ? <div className="rounded-2xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-600">{error}</div> : null}
      {success ? (
        <div className="mt-4 flex items-center gap-2 rounded-2xl border border-emerald-200 bg-emerald-50 px-4 py-3 text-sm text-emerald-700">
          <CheckCircle2 className="h-4 w-4" />
          <span>{success}</span>
        </div>
      ) : null}

      <form className="mt-7" onSubmit={handleSubmit(onSubmit)}>
        <div className="space-y-5">
          <div>
            <div className="flex items-end gap-2">
              <Input
                placeholder="邮箱地址"
                autoComplete="email"
                className={authInputClassName}
                {...register('emailPrefix')}
              />
              <select className={authSelectClassName} {...register('emailDomain')}>
                <option value="qq.com">@qq.com</option>
                <option value="163.com">@163.com</option>
              </select>
            </div>
            {errors.emailPrefix ? <p className="mt-2 text-xs text-red-500">{errors.emailPrefix.message}</p> : null}
          </div>

          <div>
            <div className="flex items-end gap-3">
              <div className="min-w-0 flex-1">
                <Input
                  inputMode="numeric"
                  maxLength={6}
                  placeholder="邮箱验证码"
                  autoComplete="one-time-code"
                  className={authInputClassName}
                  {...register('code')}
                />
                {errors.code ? <p className="mt-2 text-xs text-red-500">{errors.code.message}</p> : null}
              </div>
              <Button
                type="button"
                variant="outline"
                className="h-11 shrink-0 rounded-full border border-black/12 bg-white px-4 text-sm font-semibold text-[#111111] hover:border-[#111111]"
                disabled={!canSendCode}
                onClick={handleSendCode}
              >
                {cooldown > 0 ? `${cooldown}s` : codeLoading ? '发送中' : '发送验证码'}
              </Button>
            </div>
            <p className="mt-3 text-xs text-slate-400">将使用 {provider} 通道发送到 {email}</p>
          </div>

          <div>
            <Input
              type="password"
              placeholder="设置新密码"
              autoComplete="new-password"
              className={authInputClassName}
              {...register('newPassword')}
            />
            {errors.newPassword ? <p className="mt-2 text-xs text-red-500">{errors.newPassword.message}</p> : null}
          </div>

          <div>
            <Input
              type="password"
              placeholder="再次输入新密码"
              autoComplete="new-password"
              className={authInputClassName}
              {...register('confirmPassword')}
            />
            {errors.confirmPassword ? <p className="mt-2 text-xs text-red-500">{errors.confirmPassword.message}</p> : null}
          </div>
        </div>

        <Button className={cn(authPrimaryButtonClassName, 'mt-8 w-full')} disabled={!canReset} type="submit">
          {loading ? '处理中...' : '重置密码'}
        </Button>
      </form>
    </AuthScene>
  )
}
