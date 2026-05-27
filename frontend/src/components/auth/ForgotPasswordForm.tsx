import { useEffect, useMemo, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { CheckCircle2 } from 'lucide-react'
import { z } from 'zod'
import { resetPassword, sendEmailCode } from '@/api/auth'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { actionButtonBase, actionButtonIdle, actionButtonReady } from '@/lib/action-button-styles'
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
    <section className="mx-auto grid min-h-[640px] w-full max-w-[920px] overflow-hidden rounded-[6px] bg-[#eef3f7] shadow-[18px_18px_38px_rgba(172,184,196,0.75),-18px_-18px_38px_rgba(255,255,255,0.95)] md:grid-cols-[0.9fr_1.1fr]">
      <aside className="relative flex flex-col items-center justify-center overflow-hidden border-r border-white/70 px-10 text-center">
        <div className="absolute -left-24 -top-24 h-56 w-56 rounded-full border border-slate-300/60" />
        <div className="absolute -bottom-28 -right-20 h-64 w-64 rounded-full border border-slate-300/60" />
        <div className="relative z-10">
          <div className="mx-auto mb-6 flex h-14 w-14 items-center justify-center rounded-2xl bg-[#e7edf3] text-lg font-black text-[#222833] shadow-[8px_8px_18px_rgba(174,185,197,0.8),-8px_-8px_18px_rgba(255,255,255,0.9)]">
            智学
          </div>
          <h1 className="text-3xl font-black tracking-normal">重设密码</h1>
          <p className="mx-auto mt-5 max-w-[260px] text-sm leading-6 text-slate-400">
            使用注册邮箱接收验证码，验证通过后即可设置新的登录密码。
          </p>
          <Link to="/login">
            <Button className={`mt-8 h-11 rounded-full px-12 text-xs font-bold tracking-wide ${actionButtonBase} ${actionButtonReady}`}>
              返回登录
            </Button>
          </Link>
        </div>
      </aside>

      <div className="flex items-center justify-center px-10 py-12">
        <form onSubmit={handleSubmit(onSubmit)} className="w-full max-w-[360px]">
          <h2 className="text-center text-3xl font-black tracking-normal">找回账号</h2>
          <p className="mt-4 text-center text-xs text-slate-400">邮箱验证通过后，新密码会立即生效</p>

          {error && <div className="mt-5 rounded-md bg-red-500/10 px-3 py-2 text-sm text-red-500">{error}</div>}
          {success && (
            <div className="mt-5 flex items-center gap-2 rounded-md bg-emerald-500/10 px-3 py-2 text-sm text-emerald-600">
              <CheckCircle2 className="h-4 w-4" />
              <span>{success}</span>
            </div>
          )}

          <div className="mt-7 space-y-4">
            <div>
              <div className="flex items-end gap-2">
                <Input
                  placeholder="邮箱地址"
                  autoComplete="email"
                  className="h-10 rounded-none border-0 border-b border-slate-300 bg-transparent px-3 text-[#222833] shadow-none placeholder:text-slate-400 focus-visible:border-slate-500 focus-visible:ring-0 focus-visible:ring-offset-0"
                  {...register('emailPrefix')}
                />
                <select
                  className="h-10 rounded-lg border border-slate-200 bg-[#f3f5f7] px-3 text-sm font-semibold text-slate-500 shadow-[inset_0_1px_0_rgba(255,255,255,0.7)] outline-none transition-colors hover:bg-[#eef1f4] focus:border-slate-300 focus:bg-white focus:text-slate-600"
                  {...register('emailDomain')}
                >
                  <option value="qq.com">@qq.com</option>
                  <option value="163.com">@163.com</option>
                </select>
              </div>
              {errors.emailPrefix && <p className="mt-1 text-xs text-red-500">{errors.emailPrefix.message}</p>}
            </div>

            <div>
              <div className="flex items-end gap-3">
                <div className="min-w-0 flex-1">
                  <Input
                    inputMode="numeric"
                    maxLength={6}
                    placeholder="邮箱验证码"
                    autoComplete="one-time-code"
                    className="h-10 rounded-none border-0 border-b border-slate-300 bg-transparent px-3 text-[#222833] shadow-none placeholder:text-slate-400 focus-visible:border-slate-500 focus-visible:ring-0 focus-visible:ring-offset-0"
                    {...register('code')}
                  />
                  {errors.code && <p className="mt-1 text-xs text-red-500">{errors.code.message}</p>}
                </div>
                <Button
                  type="button"
                  variant="outline"
                  className={`h-10 shrink-0 rounded-full border-0 px-4 text-xs font-bold ${actionButtonBase} ${canSendCode ? actionButtonReady : actionButtonIdle}`}
                  disabled={codeLoading || cooldown > 0}
                  onClick={handleSendCode}
                >
                  {cooldown > 0 ? `${cooldown}s` : codeLoading ? '发送中' : '发送验证码'}
                </Button>
              </div>
              <p className="mt-2 text-xs text-slate-400">将使用 {provider} 通道发送到 {email}</p>
            </div>

            <div>
              <Input
                type="password"
                placeholder="设置新密码"
                autoComplete="new-password"
                className="h-10 rounded-none border-0 border-b border-slate-300 bg-transparent px-3 text-[#222833] shadow-none placeholder:text-slate-400 focus-visible:border-slate-500 focus-visible:ring-0 focus-visible:ring-offset-0"
                {...register('newPassword')}
              />
              {errors.newPassword && <p className="mt-1 text-xs text-red-500">{errors.newPassword.message}</p>}
            </div>

            <div>
              <Input
                type="password"
                placeholder="再次输入新密码"
                autoComplete="new-password"
                className="h-10 rounded-none border-0 border-b border-slate-300 bg-transparent px-3 text-[#222833] shadow-none placeholder:text-slate-400 focus-visible:border-slate-500 focus-visible:ring-0 focus-visible:ring-offset-0"
                {...register('confirmPassword')}
              />
              {errors.confirmPassword && <p className="mt-1 text-xs text-red-500">{errors.confirmPassword.message}</p>}
            </div>
          </div>

          <Button
            type="submit"
            className={`mx-auto mt-8 flex h-11 rounded-full px-14 text-xs font-bold tracking-wide ${actionButtonBase} ${canReset ? actionButtonReady : actionButtonIdle}`}
            disabled={loading}
          >
            {loading ? '处理中...' : '重置密码'}
          </Button>
        </form>
      </div>
    </section>
  )
}
