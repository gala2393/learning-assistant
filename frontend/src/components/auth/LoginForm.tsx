import { useEffect, useMemo, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { Mail, KeyRound } from 'lucide-react'
import { z } from 'zod'
import { sendEmailCode } from '@/api/auth'
import { useAuth } from '@/context/AuthContext'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Input } from '@/components/ui/input'
import { actionButtonBase, actionButtonIdle, actionButtonReady } from '@/lib/action-button-styles'
import { normalizeEmail, providerForEmail } from '@/lib/email'

const passwordLoginSchema = z.object({
  username: z.string().min(1, '请输入用户名或邮箱'),
  password: z.string().min(1, '请输入密码'),
})

const codeLoginSchema = z.object({
  emailPrefix: z.string().min(1, '请输入邮箱地址').max(128, '邮箱地址过长'),
  emailDomain: z.enum(['qq.com', '163.com']),
  code: z.string().regex(/^\d{6}$/, '请输入 6 位数字验证码'),
})

type PasswordLoginValues = z.infer<typeof passwordLoginSchema>
type CodeLoginValues = z.infer<typeof codeLoginSchema>
type LoginMode = 'password' | 'code'

export function LoginForm() {
  const { login, emailLogin } = useAuth()
  const navigate = useNavigate()
  const [mode, setMode] = useState<LoginMode>('password')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)
  const [codeLoading, setCodeLoading] = useState(false)
  const [cooldown, setCooldown] = useState(0)

  const passwordForm = useForm<PasswordLoginValues>({
    resolver: zodResolver(passwordLoginSchema),
    defaultValues: { username: '', password: '' },
  })

  const codeForm = useForm<CodeLoginValues>({
    resolver: zodResolver(codeLoginSchema),
    defaultValues: { emailPrefix: '', emailDomain: 'qq.com', code: '' },
  })

  const username = passwordForm.watch('username')
  const password = passwordForm.watch('password')
  const emailPrefix = codeForm.watch('emailPrefix')
  const emailDomain = codeForm.watch('emailDomain')
  const code = codeForm.watch('code')
  const email = useMemo(() => normalizeEmail(emailPrefix, emailDomain), [emailPrefix, emailDomain])
  const provider = providerForEmail(emailPrefix, emailDomain)
  const canPasswordLogin = username.trim().length > 0 && password.trim().length > 0 && !loading
  const canCodeLogin = emailPrefix.trim().length > 0 && code.trim().length > 0 && !loading
  const canSendCode = emailPrefix.trim().length > 0 && !codeLoading && cooldown <= 0

  useEffect(() => {
    if (cooldown <= 0) return
    const timer = window.setTimeout(() => setCooldown((value) => value - 1), 1000)
    return () => window.clearTimeout(timer)
  }, [cooldown])

  const finishLogin = () => {
    sessionStorage.removeItem('learning-assistant.chat.current')
    navigate('/workspace/chat?new=1', { replace: true })
  }

  const onPasswordSubmit = async (data: PasswordLoginValues) => {
    setError('')
    setLoading(true)
    try {
      await login(data)
      finishLogin()
    } catch (err: unknown) {
      const e = err as { response?: { status?: number }; message?: string }
      if (e.response?.status === 403) {
        setError('当前账号无权访问，请联系管理员确认权限。')
      } else {
        setError(e.message || '用户名、邮箱或密码错误，请检查后再试。')
      }
    } finally {
      setLoading(false)
    }
  }

  const handleSendCode = async () => {
    const values = codeForm.getValues()
    const targetEmail = normalizeEmail(values.emailPrefix, values.emailDomain)
    if (!targetEmail) {
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

  const onCodeSubmit = async (data: CodeLoginValues) => {
    setError('')
    setLoading(true)
    try {
      await emailLogin({
        email: normalizeEmail(data.emailPrefix, data.emailDomain),
        code: data.code,
      })
      finishLogin()
    } catch (err: unknown) {
      const e = err as { message?: string }
      setError(e.message || '邮箱验证码登录失败，请检查验证码后再试。')
    } finally {
      setLoading(false)
    }
  }

  return (
    <section className="mx-auto grid min-h-[600px] w-full max-w-[920px] overflow-hidden rounded-[6px] bg-[#eef3f7] shadow-[18px_18px_38px_rgba(172,184,196,0.75),-18px_-18px_38px_rgba(255,255,255,0.95)] md:grid-cols-[0.9fr_1.1fr]">
      <aside className="relative flex flex-col items-center justify-center overflow-hidden border-r border-white/70 px-10 text-center">
        <div className="absolute -left-24 -top-24 h-56 w-56 rounded-full border border-slate-300/60" />
        <div className="absolute -bottom-28 -right-20 h-64 w-64 rounded-full border border-slate-300/60" />
        <div className="relative z-10">
          <div className="mx-auto mb-6 flex h-14 w-14 items-center justify-center rounded-2xl bg-[#e7edf3] text-lg font-black text-[#222833] shadow-[8px_8px_18px_rgba(174,185,197,0.8),-8px_-8px_18px_rgba(255,255,255,0.9)]">
            智学
          </div>
          <h1 className="text-3xl font-black tracking-normal">欢迎回来</h1>
          <p className="mx-auto mt-5 max-w-[260px] text-sm leading-6 text-slate-400">
            用密码或邮箱验证码继续连接你的课程资料、问答历史和学习收藏。
          </p>
          <Link to="/register">
            <Button className={`mt-8 h-11 rounded-full px-12 text-xs font-bold tracking-wide ${actionButtonBase} ${actionButtonReady}`}>
              去注册
            </Button>
          </Link>
        </div>
      </aside>

      <div className="flex items-center justify-center px-10 py-12">
        <div className="w-full max-w-[360px]">
          <h2 className="text-center text-3xl font-black tracking-normal">登录账号</h2>
          <p className="mt-4 text-center text-xs text-slate-400">密码登录和邮箱验证码登录均可进入学习工作台</p>

          <div className="mt-6 grid grid-cols-2 rounded-full bg-slate-200/60 p-1 text-xs font-bold text-slate-500">
            <button
              type="button"
              className={`flex h-9 items-center justify-center gap-2 rounded-full transition ${mode === 'password' ? 'bg-white text-[#222833] shadow-sm' : 'hover:text-slate-700'}`}
              onClick={() => {
                setMode('password')
                setError('')
              }}
            >
              <KeyRound className="h-4 w-4" />
              密码登录
            </button>
            <button
              type="button"
              className={`flex h-9 items-center justify-center gap-2 rounded-full transition ${mode === 'code' ? 'bg-white text-[#222833] shadow-sm' : 'hover:text-slate-700'}`}
              onClick={() => {
                setMode('code')
                setError('')
              }}
            >
              <Mail className="h-4 w-4" />
              验证码登录
            </button>
          </div>

          {error && <div className="mt-5 rounded-md bg-red-500/10 px-3 py-2 text-sm text-red-500">{error}</div>}

          {mode === 'password' ? (
            <form onSubmit={passwordForm.handleSubmit(onPasswordSubmit)}>
              <div className="mt-7 space-y-5">
                <div>
                  <Input
                    placeholder="用户名或邮箱"
                    autoComplete="username"
                    className="h-11 rounded-none border-0 border-b border-slate-300 bg-transparent px-3 text-[#222833] shadow-none placeholder:text-slate-400 focus-visible:border-slate-500 focus-visible:ring-0 focus-visible:ring-offset-0"
                    {...passwordForm.register('username')}
                  />
                  {passwordForm.formState.errors.username && <p className="mt-1 text-xs text-red-500">{passwordForm.formState.errors.username.message}</p>}
                </div>
                <div>
                  <Input
                    type="password"
                    placeholder="密码"
                    autoComplete="current-password"
                    className="h-11 rounded-none border-0 border-b border-slate-300 bg-transparent px-3 text-[#222833] shadow-none placeholder:text-slate-400 focus-visible:border-slate-500 focus-visible:ring-0 focus-visible:ring-offset-0"
                    {...passwordForm.register('password')}
                  />
                  {passwordForm.formState.errors.password && <p className="mt-1 text-xs text-red-500">{passwordForm.formState.errors.password.message}</p>}
                </div>
              </div>

              <div className="mt-5 flex items-center justify-between text-xs text-slate-400">
                <label className="flex items-center gap-2">
                  <Checkbox defaultChecked />
                  记住登录状态
                </label>
                <Link to="/forgot-password" className="font-medium text-[#4b5563] hover:text-[#374151]">
                  忘记密码
                </Link>
              </div>

              <Button
                type="submit"
                className={`mx-auto mt-8 flex h-11 rounded-full px-14 text-xs font-bold tracking-wide ${actionButtonBase} ${canPasswordLogin ? actionButtonReady : actionButtonIdle}`}
                disabled={loading}
              >
                {loading ? '处理中...' : '登录'}
              </Button>
            </form>
          ) : (
            <form onSubmit={codeForm.handleSubmit(onCodeSubmit)}>
              <div className="mt-7 space-y-5">
                <div>
                  <div className="flex items-end gap-2">
                    <Input
                      placeholder="邮箱地址"
                      autoComplete="email"
                      className="h-11 rounded-none border-0 border-b border-slate-300 bg-transparent px-3 text-[#222833] shadow-none placeholder:text-slate-400 focus-visible:border-slate-500 focus-visible:ring-0 focus-visible:ring-offset-0"
                      {...codeForm.register('emailPrefix')}
                    />
                    <select
                      className="h-10 rounded-lg border border-slate-200 bg-[#f3f5f7] px-3 text-sm font-semibold text-slate-500 shadow-[inset_0_1px_0_rgba(255,255,255,0.7)] outline-none transition-colors hover:bg-[#eef1f4] focus:border-slate-300 focus:bg-white focus:text-slate-600"
                      {...codeForm.register('emailDomain')}
                    >
                      <option value="qq.com">@qq.com</option>
                      <option value="163.com">@163.com</option>
                    </select>
                  </div>
                  {codeForm.formState.errors.emailPrefix && <p className="mt-1 text-xs text-red-500">{codeForm.formState.errors.emailPrefix.message}</p>}
                </div>

                <div>
                  <div className="flex items-end gap-3">
                    <div className="min-w-0 flex-1">
                      <Input
                        inputMode="numeric"
                        maxLength={6}
                        placeholder="邮箱验证码"
                        autoComplete="one-time-code"
                        className="h-11 rounded-none border-0 border-b border-slate-300 bg-transparent px-3 text-[#222833] shadow-none placeholder:text-slate-400 focus-visible:border-slate-500 focus-visible:ring-0 focus-visible:ring-offset-0"
                        {...codeForm.register('code')}
                      />
                      {codeForm.formState.errors.code && <p className="mt-1 text-xs text-red-500">{codeForm.formState.errors.code.message}</p>}
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
                  <p className="mt-2 text-xs text-slate-400">将使用 {provider === 'qq' ? 'QQ 邮箱' : '163 邮箱'} 通道发送到 {email}</p>
                </div>
              </div>

              <div className="mt-5 flex justify-end text-xs text-slate-400">
                <Link to="/forgot-password" className="font-medium text-[#4b5563] hover:text-[#374151]">
                  忘记密码
                </Link>
              </div>

              <Button
                type="submit"
                className={`mx-auto mt-8 flex h-11 rounded-full px-14 text-xs font-bold tracking-wide ${actionButtonBase} ${canCodeLogin ? actionButtonReady : actionButtonIdle}`}
                disabled={loading}
              >
                {loading ? '处理中...' : '验证码登录'}
              </Button>
            </form>
          )}
        </div>
      </div>
    </section>
  )
}
