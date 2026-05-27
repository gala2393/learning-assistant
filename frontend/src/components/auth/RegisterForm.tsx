import { useEffect, useMemo, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { CheckCircle, Loader2, XCircle } from 'lucide-react'
import { z } from 'zod'
import { sendEmailCode, useCheckUsername } from '@/api/auth'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { useAuth } from '@/context/AuthContext'
import { useDebounce } from '@/hooks/useDebounce'
import { actionButtonBase, actionButtonIdle, actionButtonReady } from '@/lib/action-button-styles'
import { normalizeEmail, providerForEmail } from '@/lib/email'

const registerSchema = z
  .object({
    emailPrefix: z.string().min(1, '请输入邮箱地址').max(64, '邮箱地址过长'),
    emailDomain: z.enum(['qq.com', '163.com']),
    username: z.string().min(3, '用户名至少 3 位').max(32, '用户名最多 32 位'),
    password: z.string().min(8, '密码至少 8 位').max(64, '密码最多 64 位'),
    confirmPassword: z.string(),
    code: z.string().regex(/^\d{6}$/, '请输入 6 位数字验证码'),
  })
  .refine((data) => data.password === data.confirmPassword, {
    message: '两次输入的密码不一致',
    path: ['confirmPassword'],
  })

type RegisterFormValues = z.infer<typeof registerSchema>

export function RegisterForm() {
  const { register: registerUser } = useAuth()
  const navigate = useNavigate()
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)
  const [codeLoading, setCodeLoading] = useState(false)
  const [cooldown, setCooldown] = useState(0)

  const {
    register,
    handleSubmit,
    watch,
    getValues,
    formState: { errors },
  } = useForm<RegisterFormValues>({
    resolver: zodResolver(registerSchema),
    defaultValues: {
      emailPrefix: '',
      emailDomain: 'qq.com',
      username: '',
      password: '',
      confirmPassword: '',
      code: '',
    },
  })

  const username = watch('username')
  const emailPrefix = watch('emailPrefix')
  const emailDomain = watch('emailDomain')
  const password = watch('password')
  const confirmPassword = watch('confirmPassword')
  const code = watch('code')
  const debouncedUsername = useDebounce(username, 500)
  const shouldCheck = debouncedUsername.length >= 3
  const { data: usernameStatus, isLoading: checkingUsername } = useCheckUsername(debouncedUsername, shouldCheck)

  const email = useMemo(() => normalizeEmail(emailPrefix, emailDomain), [emailPrefix, emailDomain])
  const provider = providerForEmail(emailPrefix, emailDomain)
  const canRegister = emailPrefix.trim().length > 0
    && username.trim().length > 0
    && password.trim().length > 0
    && confirmPassword.trim().length > 0
    && code.trim().length > 0
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
    setCodeLoading(true)
    try {
      await sendEmailCode(targetEmail, providerForEmail(values.emailPrefix, values.emailDomain))
      setCooldown(60)
    } catch (err: unknown) {
      const e = err as { message?: string }
      setError(e.message || '验证码发送失败，请稍后再试。')
    } finally {
      setCodeLoading(false)
    }
  }

  const onSubmit = async (data: RegisterFormValues) => {
    setError('')
    setLoading(true)
    try {
      await registerUser({
        email: normalizeEmail(data.emailPrefix, data.emailDomain),
        username: data.username,
        password: data.password,
        confirmPassword: data.confirmPassword,
        code: data.code,
      })
      sessionStorage.removeItem('learning-assistant.chat.current')
      navigate('/workspace/chat?new=1', { replace: true })
    } catch (err: unknown) {
      const e = err as { response?: { status?: number }; message?: string }
      if (e.response?.status === 400) {
        setError('注册信息有误，请检查邮箱、用户名、密码和验证码。')
      } else {
        setError(e.message || '注册请求失败，请稍后再试。')
      }
    } finally {
      setLoading(false)
    }
  }

  return (
    <section className="mx-auto grid min-h-[680px] w-full max-w-[920px] overflow-hidden rounded-[6px] bg-[#eef3f7] shadow-[18px_18px_38px_rgba(172,184,196,0.75),-18px_-18px_38px_rgba(255,255,255,0.95)] md:grid-cols-[0.9fr_1.1fr]">
      <aside className="relative flex flex-col items-center justify-center overflow-hidden border-r border-white/70 px-10 text-center">
        <div className="absolute -left-24 -top-24 h-56 w-56 rounded-full border border-slate-300/60" />
        <div className="absolute -bottom-28 -right-20 h-64 w-64 rounded-full border border-slate-300/60" />
        <div className="relative z-10">
          <div className="mx-auto mb-6 flex h-14 w-14 items-center justify-center rounded-2xl bg-[#e7edf3] text-lg font-black text-[#222833] shadow-[8px_8px_18px_rgba(174,185,197,0.8),-8px_-8px_18px_rgba(255,255,255,0.9)]">
            智学
          </div>
          <h1 className="text-3xl font-black tracking-normal">加入学习空间</h1>
          <p className="mx-auto mt-5 max-w-[260px] text-sm leading-6 text-slate-400">
            先验证邮箱，再创建你的学习身份。之后可用用户名或邮箱登录。
          </p>
          <Link to="/login">
            <Button className={`mt-8 h-11 rounded-full px-12 text-xs font-bold tracking-wide ${actionButtonBase} ${actionButtonReady}`}>
              去登录
            </Button>
          </Link>
        </div>
      </aside>

      <div className="flex items-center justify-center px-10 py-12">
        <form onSubmit={handleSubmit(onSubmit)} className="w-full max-w-[360px]">
          <h2 className="text-center text-3xl font-black tracking-normal">创建账号</h2>
          <p className="mt-4 text-center text-xs text-slate-400">邮箱用于验证和登录，用户名用于展示和登录</p>

          {error && <div className="mt-5 rounded-md bg-red-500/10 px-3 py-2 text-sm text-red-500">{error}</div>}

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
              <Input
                placeholder="用户名"
                autoComplete="username"
                className="h-10 rounded-none border-0 border-b border-slate-300 bg-transparent px-3 text-[#222833] shadow-none placeholder:text-slate-400 focus-visible:border-slate-500 focus-visible:ring-0 focus-visible:ring-offset-0"
                {...register('username')}
              />
              <div className="mt-1 flex min-h-4 items-center gap-1 text-xs">
                {checkingUsername && shouldCheck && (
                  <>
                    <Loader2 className="h-3 w-3 animate-spin text-slate-400" />
                    <span className="text-slate-400">正在校验用户名...</span>
                  </>
                )}
                {!checkingUsername && usernameStatus && (
                  <>
                    {usernameStatus.available ? (
                      <>
                        <CheckCircle className="h-3 w-3 text-emerald-500" />
                        <span className="text-emerald-600">用户名可用</span>
                      </>
                    ) : (
                      <>
                        <XCircle className="h-3 w-3 text-red-500" />
                        <span className="text-red-500">{usernameStatus.message || '用户名已存在'}</span>
                      </>
                    )}
                  </>
                )}
              </div>
              {errors.username && <p className="text-xs text-red-500">{errors.username.message}</p>}
            </div>
            <div>
              <Input
                type="password"
                placeholder="设置密码"
                autoComplete="new-password"
                className="h-10 rounded-none border-0 border-b border-slate-300 bg-transparent px-3 text-[#222833] shadow-none placeholder:text-slate-400 focus-visible:border-slate-500 focus-visible:ring-0 focus-visible:ring-offset-0"
                {...register('password')}
              />
              {errors.password && <p className="mt-1 text-xs text-red-500">{errors.password.message}</p>}
            </div>
            <div>
              <Input
                type="password"
                placeholder="再次输入密码"
                autoComplete="new-password"
                className="h-10 rounded-none border-0 border-b border-slate-300 bg-transparent px-3 text-[#222833] shadow-none placeholder:text-slate-400 focus-visible:border-slate-500 focus-visible:ring-0 focus-visible:ring-offset-0"
                {...register('confirmPassword')}
              />
              {errors.confirmPassword && <p className="mt-1 text-xs text-red-500">{errors.confirmPassword.message}</p>}
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
                  className={`h-10 shrink-0 rounded-full border-0 px-4 text-xs font-bold ${actionButtonBase} ${emailPrefix.trim() && !codeLoading && cooldown <= 0 ? actionButtonReady : actionButtonIdle}`}
                  disabled={codeLoading || cooldown > 0}
                  onClick={handleSendCode}
                >
                  {cooldown > 0 ? `${cooldown}s` : codeLoading ? '发送中' : '发送验证码'}
                </Button>
              </div>
              <p className="mt-2 text-xs text-slate-400">将使用 {provider === 'qq' ? 'QQ 邮箱' : '163 邮箱'} 通道发送到 {email}</p>
            </div>
          </div>

          <Button
            type="submit"
            className={`mx-auto mt-8 flex h-11 rounded-full px-14 text-xs font-bold tracking-wide ${actionButtonBase} ${canRegister ? actionButtonReady : actionButtonIdle}`}
            disabled={loading}
          >
            {loading ? '处理中...' : '注册并登录'}
          </Button>
        </form>
      </div>
    </section>
  )
}
