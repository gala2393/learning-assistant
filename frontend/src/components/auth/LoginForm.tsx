import { useEffect, useMemo, useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { KeyRound, Mail, RefreshCw } from 'lucide-react'
import { z } from 'zod'
import { createLoginCaptcha, sendEmailCode } from '@/api/auth'
import { useAuth } from '@/context/AuthContext'
import { AuthScene, authInputClassName, authPrimaryButtonClassName, authSecondaryButtonClassName, authSelectClassName } from '@/components/auth/AuthScene'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Input } from '@/components/ui/input'
import { cn } from '@/lib/utils'
import { normalizeEmail, providerForEmail } from '@/lib/email'

const passwordLoginSchema = z.object({
  username: z.string().min(1, '请输入用户名或邮箱'),
  password: z.string().min(1, '请输入密码'),
  captchaCode: z.string().optional(),
})

const codeLoginSchema = z.object({
  emailPrefix: z.string().min(1, '请输入邮箱地址').max(128, '邮箱地址过长'),
  emailDomain: z.enum(['qq.com', '163.com']),
  code: z.string().regex(/^\d{6}$/, '请输入 6 位数字验证码'),
})

type PasswordLoginValues = z.infer<typeof passwordLoginSchema>
type CodeLoginValues = z.infer<typeof codeLoginSchema>
type LoginMode = 'password' | 'code'
type CaptchaState = {
  challengeId: string
  imageDataUrl: string
}

const REMEMBERED_LOGIN_KEY = 'learning-assistant.remembered-login'

/**
 * 只记住用户名，不再存储密码。
 * 这样既能减少重复输入，也避免把敏感信息写入本地存储。
 */
function loadRememberedLogin() {
  if (typeof window === 'undefined') return { username: '', remember: false }
  try {
    const raw = localStorage.getItem(REMEMBERED_LOGIN_KEY)
    if (!raw) return { username: '', remember: false }
    const saved = JSON.parse(raw) as { username?: string; password?: string }
    if (saved.password) {
      localStorage.setItem(REMEMBERED_LOGIN_KEY, JSON.stringify({ username: saved.username || '' }))
    }
    return { username: '', remember: false }
  } catch {
    localStorage.removeItem(REMEMBERED_LOGIN_KEY)
    return { username: '', remember: false }
  }
}

export function LoginForm() {
  const { login, emailLogin } = useAuth()
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const [mode, setMode] = useState<LoginMode>('password')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)
  const [codeLoading, setCodeLoading] = useState(false)
  const [cooldown, setCooldown] = useState(0)
  const [captcha, setCaptcha] = useState<CaptchaState | null>(null)
  const [captchaLoading, setCaptchaLoading] = useState(false)
  const rememberedLogin = useMemo(loadRememberedLogin, [])
  const [rememberLogin, setRememberLogin] = useState(rememberedLogin.remember)

  /**
   * 旧版本会把用户名写入 localStorage，导致登录页默认出现 admin。
   * 新视觉下登录框默认保持空白，这里在进入登录页时主动清掉旧缓存。
   */
  useEffect(() => {
    localStorage.removeItem(REMEMBERED_LOGIN_KEY)
  }, [])

  const passwordForm = useForm<PasswordLoginValues>({
    resolver: zodResolver(passwordLoginSchema),
    defaultValues: { username: rememberedLogin.username, password: '', captchaCode: '' },
  })

  const codeForm = useForm<CodeLoginValues>({
    resolver: zodResolver(codeLoginSchema),
    defaultValues: { emailPrefix: '', emailDomain: 'qq.com', code: '' },
  })

  const username = passwordForm.watch('username')
  const password = passwordForm.watch('password')
  const captchaCode = passwordForm.watch('captchaCode') || ''
  const emailPrefix = codeForm.watch('emailPrefix')
  const emailDomain = codeForm.watch('emailDomain')
  const code = codeForm.watch('code')
  const email = useMemo(() => normalizeEmail(emailPrefix, emailDomain), [emailPrefix, emailDomain])
  const provider = providerForEmail(emailPrefix, emailDomain)
  const canPasswordLogin = username.trim().length > 0
    && password.trim().length > 0
    && (!captcha || captchaCode.trim().length > 0)
    && !loading
  const canCodeLogin = emailPrefix.trim().length > 0 && code.trim().length > 0 && !loading
  const canSendCode = emailPrefix.trim().length > 0 && !codeLoading && cooldown <= 0

  useEffect(() => {
    if (cooldown <= 0) return
    const timer = window.setTimeout(() => setCooldown((value) => value - 1), 1000)
    return () => window.clearTimeout(timer)
  }, [cooldown])

  const finishLogin = () => {
    sessionStorage.removeItem('learning-assistant.chat.current')
    const redirect = searchParams.get('redirect')
    navigate(redirect?.startsWith('/') && !redirect.startsWith('//') ? redirect : '/workspace/chat?new=1', { replace: true })
  }

  const saveRememberedLogin = (data: PasswordLoginValues) => {
    if (!rememberLogin) {
      localStorage.removeItem(REMEMBERED_LOGIN_KEY)
      return
    }
    localStorage.setItem(REMEMBERED_LOGIN_KEY, JSON.stringify({ username: data.username }))
  }

  const refreshLoginCaptcha = async (targetUsername = passwordForm.getValues('username')) => {
    const normalizedUsername = targetUsername.trim()
    if (!normalizedUsername) {
      setError('请先填写用户名或邮箱。')
      return
    }
    setCaptchaLoading(true)
    try {
      const nextCaptcha = await createLoginCaptcha(normalizedUsername)
      setCaptcha({
        challengeId: nextCaptcha.challengeId,
        imageDataUrl: nextCaptcha.imageDataUrl,
      })
      passwordForm.setValue('captchaCode', '')
    } catch (err: unknown) {
      const e = err as { message?: string }
      setError(e.message || '图形验证码获取失败，请稍后再试。')
    } finally {
      setCaptchaLoading(false)
    }
  }

  const onPasswordSubmit = async (data: PasswordLoginValues) => {
    setError('')
    setLoading(true)
    try {
      await login({
        username: data.username,
        password: data.password,
        captchaChallengeId: captcha?.challengeId,
        captchaCode: data.captchaCode?.trim(),
      })
      saveRememberedLogin(data)
      setCaptcha(null)
      finishLogin()
    } catch (err: unknown) {
      const e = err as { code?: number; response?: { status?: number }; message?: string }
      if (e.code === 428 || e.response?.status === 428) {
        await refreshLoginCaptcha(data.username)
        setError(e.message || '请先完成图形验证码。')
        return
      }
      if (captcha) {
        await refreshLoginCaptcha(data.username)
      }
      if (e.code === 403 || e.response?.status === 403) {
        setError(e.message || '登录失败，请检查账户状态或联系管理员。')
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
    <AuthScene
      eyebrow="Access / Login"
      title="登录账号"
      description="使用密码或邮箱验证码进入学习工作台。"
      sideTitle="重新接上你的知识上下文"
      sideDescription="回到资料、问答与总结都在一起的工作台，让提问、引用和复习保持连续。"
      sideNotes={[
        '围绕同一份资料持续追问，保留上下文。',
        '回答附带来源页码，方便回看原文。',
        '上传、问答和总结在一个工作台内闭环。',
      ]}
      sideActionLabel="去注册"
      sideActionTo="/register"
    >
      <div className="grid grid-cols-2 rounded-full border border-[#dce4ee] bg-[#f7f8fb] p-1 shadow-[inset_0_1px_0_rgba(255,255,255,0.9)]">
        <button
          type="button"
          className={cn(
            'flex h-10 items-center justify-center gap-2 rounded-full text-sm font-medium transition',
            mode === 'password' ? 'bg-[#111318] text-white shadow-[0_10px_24px_rgba(15,23,42,0.14)]' : 'text-slate-500 hover:text-[#111111]'
          )}
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
          className={cn(
            'flex h-10 items-center justify-center gap-2 rounded-full text-sm font-medium transition',
            mode === 'code' ? 'bg-[#111318] text-white shadow-[0_10px_24px_rgba(15,23,42,0.14)]' : 'text-slate-500 hover:text-[#111111]'
          )}
          onClick={() => {
            setMode('code')
            setError('')
          }}
        >
          <Mail className="h-4 w-4" />
          验证码登录
        </button>
      </div>

      {error ? <div className="mt-5 rounded-2xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-600">{error}</div> : null}

      {mode === 'password' ? (
        <form className="mt-7" onSubmit={passwordForm.handleSubmit(onPasswordSubmit)}>
          <div className="space-y-5">
            <div>
              <Input
                placeholder="用户名或邮箱"
                autoComplete="username"
                className={authInputClassName}
                {...passwordForm.register('username')}
              />
              {passwordForm.formState.errors.username ? <p className="mt-2 text-xs text-red-500">{passwordForm.formState.errors.username.message}</p> : null}
            </div>

            <div>
              <Input
                type="password"
                placeholder="密码"
                autoComplete="current-password"
                className={authInputClassName}
                {...passwordForm.register('password')}
              />
              {passwordForm.formState.errors.password ? <p className="mt-2 text-xs text-red-500">{passwordForm.formState.errors.password.message}</p> : null}
            </div>

            {captcha ? (
              <div>
                <div className="flex items-end gap-3">
                  <div className="min-w-0 flex-1">
                    <Input
                      placeholder="图形验证码"
                      autoComplete="off"
                      maxLength={6}
                      className={authInputClassName}
                      {...passwordForm.register('captchaCode')}
                    />
                  </div>
                  <button
                    type="button"
                    className="flex h-12 w-[148px] shrink-0 items-center justify-center overflow-hidden rounded-2xl border border-[#dce4ee] bg-white shadow-[0_10px_24px_rgba(15,23,42,0.05)]"
                    onClick={() => refreshLoginCaptcha(username)}
                    disabled={captchaLoading}
                    title="刷新验证码"
                  >
                    {captchaLoading ? (
                      <RefreshCw className="h-5 w-5 animate-spin text-slate-400" />
                    ) : (
                      <img src={captcha.imageDataUrl} alt="图形验证码" className="h-11 w-[148px] object-cover" />
                    )}
                  </button>
                </div>
              </div>
            ) : null}
          </div>

          <div className="mt-6 flex items-center justify-between text-sm text-slate-500">
            <label className="flex items-center gap-2">
              <Checkbox
                checked={rememberLogin}
                onCheckedChange={(checked) => setRememberLogin(checked === true)}
                className="border-[#cbd8e8] data-[state=checked]:border-[#111318] data-[state=checked]:bg-[#111318]"
              />
              记住用户名
            </label>
            <Link className="transition hover:text-[#111111]" to="/forgot-password">
              忘记密码
            </Link>
          </div>

          <Button className={cn(authPrimaryButtonClassName, 'mt-8 w-full')} disabled={!canPasswordLogin} type="submit">
            {loading ? '处理中...' : '登录'}
          </Button>
        </form>
      ) : (
        <form className="mt-7" onSubmit={codeForm.handleSubmit(onCodeSubmit)}>
          <div className="space-y-5">
            <div>
              <div className="flex items-end gap-2">
                <Input
                  placeholder="邮箱地址"
                  autoComplete="email"
                  className={authInputClassName}
                  {...codeForm.register('emailPrefix')}
                />
                <select className={authSelectClassName} {...codeForm.register('emailDomain')}>
                  <option value="qq.com">@qq.com</option>
                  <option value="163.com">@163.com</option>
                </select>
              </div>
              {codeForm.formState.errors.emailPrefix ? <p className="mt-2 text-xs text-red-500">{codeForm.formState.errors.emailPrefix.message}</p> : null}
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
                    {...codeForm.register('code')}
                  />
                  {codeForm.formState.errors.code ? <p className="mt-2 text-xs text-red-500">{codeForm.formState.errors.code.message}</p> : null}
                </div>
                <Button
                  type="button"
                  variant="outline"
                  className={cn(authSecondaryButtonClassName, 'shrink-0')}
                  disabled={!canSendCode}
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

          <div className="mt-6 flex justify-end text-sm text-slate-500">
            <Link className="transition hover:text-[#111111]" to="/forgot-password">
              忘记密码
            </Link>
          </div>

          <Button className={cn(authPrimaryButtonClassName, 'mt-8 w-full')} disabled={!canCodeLogin} type="submit">
            {loading ? '处理中...' : '验证码登录'}
          </Button>
        </form>
      )}
    </AuthScene>
  )
}
