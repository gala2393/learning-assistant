import { useEffect, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { CheckCircle, Loader2, XCircle } from 'lucide-react'
import { z } from 'zod'
import { sendEmailCode, useCheckUsername } from '@/api/auth'
import { AuthScene, authInputClassName, authPrimaryButtonClassName, authSelectClassName } from '@/components/auth/AuthScene'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { useAuth } from '@/context/AuthContext'
import { useDebounce } from '@/hooks/useDebounce'
import { cn } from '@/lib/utils'
import { normalizeEmail, providerForEmail } from '@/lib/email'

const registerSchema = z
  .object({
    emailPrefix: z.string().min(1, '请输入邮箱地址').max(64, '邮箱地址过长'),
    emailDomain: z.enum(['qq.com', '163.com']),
    username: z.string().min(1, '请输入用户名').max(32, '用户名最多 32 位'),
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
  const shouldCheck = debouncedUsername.trim().length >= 1
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
    <AuthScene
      eyebrow="Access / Register"
      title="创建账号"
      description="先完成邮箱验证，再创建你的学习身份。"
      sideTitle="把资料整理成可持续提问的学习现场"
      sideDescription="完成注册后，你可以上传资料、围绕原文发问，并把结论整理成可复习的知识索引。"
      sideNotes={[
        '资料进入系统后会被持续索引和组织。',
        '问答保留来源引用，便于核对和回看。',
        '复习总结会围绕同一份资料继续累积。',
      ]}
      sideActionLabel="去登录"
      sideActionTo="/login"
    >
      {error ? <div className="rounded-2xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-600">{error}</div> : null}

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
            <Input
              placeholder="用户名"
              autoComplete="username"
              className={authInputClassName}
              {...register('username')}
            />
            <div className="mt-2 flex min-h-4 items-center gap-1 text-xs">
              {checkingUsername && shouldCheck ? (
                <>
                  <Loader2 className="h-3 w-3 animate-spin text-slate-400" />
                  <span className="text-slate-400">正在校验用户名...</span>
                </>
              ) : null}
              {!checkingUsername && usernameStatus ? (
                usernameStatus.available ? (
                  <>
                    <CheckCircle className="h-3 w-3 text-emerald-500" />
                    <span className="text-emerald-600">用户名可用</span>
                  </>
                ) : (
                  <>
                    <XCircle className="h-3 w-3 text-red-500" />
                    <span className="text-red-500">{usernameStatus.message || '用户名已存在'}</span>
                  </>
                )
              ) : null}
            </div>
            {errors.username ? <p className="text-xs text-red-500">{errors.username.message}</p> : null}
          </div>

          <div>
            <Input
              type="password"
              placeholder="设置密码"
              autoComplete="new-password"
              className={authInputClassName}
              {...register('password')}
            />
            {errors.password ? <p className="mt-2 text-xs text-red-500">{errors.password.message}</p> : null}
          </div>

          <div>
            <Input
              type="password"
              placeholder="再次输入密码"
              autoComplete="new-password"
              className={authInputClassName}
              {...register('confirmPassword')}
            />
            {errors.confirmPassword ? <p className="mt-2 text-xs text-red-500">{errors.confirmPassword.message}</p> : null}
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
                className="h-12 shrink-0 rounded-full border border-[#d6deea] bg-white/76 px-4 text-sm font-semibold text-[#111111] shadow-[0_10px_28px_rgba(15,23,42,0.06)] hover:border-[#1a2a3a] hover:bg-white"
                disabled={codeLoading || cooldown > 0 || !emailPrefix.trim()}
                onClick={handleSendCode}
              >
                {cooldown > 0 ? `${cooldown}s` : codeLoading ? '发送中' : '发送验证码'}
              </Button>
            </div>
            <p className="mt-3 text-xs text-slate-400">
              将使用 {provider === 'qq' ? 'QQ 邮箱' : '163 邮箱'} 通道发送到 {email}
            </p>
          </div>
        </div>

        <Button className={cn(authPrimaryButtonClassName, 'mt-8 w-full')} disabled={!canRegister} type="submit">
          {loading ? '处理中...' : '注册并登录'}
        </Button>
      </form>
    </AuthScene>
  )
}
