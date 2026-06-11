/**
 * LoginForm 组件 —— 用户登录表单
 *
 * 【用途与使用场景】
 * 用户登录页面的核心表单组件，支持两种登录方式：
 * 1. 密码登录：输入用户名/邮箱 + 密码
 * 2. 邮箱验证码登录：输入邮箱 + 6 位数字验证码
 *
 * 访问路径通常为 /login。
 *
 * 【核心逻辑】
 * - 通过顶部切换标签在两种登录模式之间切换
 * - 密码登录支持"记住登录状态"复选框
 * - 验证码登录有 60 秒发送冷却
 * - 登录成功后跳转到工作台聊天页面
 * - 左侧面板提供品牌展示和"去注册"入口
 *
 * 【技术细节】
 * - 使用两套独立的 react-hook-form 实例分别管理密码登录和验证码登录表单
 * - 表单校验使用 zod schema
 * - 登录失败时显示后端提示或通用账号状态提示
 */

import { useEffect, useMemo, useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { Mail, KeyRound, RefreshCw } from 'lucide-react'
import { z } from 'zod'
import { createLoginCaptcha, sendEmailCode } from '@/api/auth'
import { useAuth } from '@/context/AuthContext'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Input } from '@/components/ui/input'
import { actionButtonBase, actionButtonIdle, actionButtonReady } from '@/lib/action-button-styles'
import { normalizeEmail, providerForEmail } from '@/lib/email'

/** 密码登录表单校验规则 */
const passwordLoginSchema = z.object({
  username: z.string().min(1, '请输入用户名或邮箱'),
  password: z.string().min(1, '请输入密码'),
  captchaCode: z.string().optional(),
})

/** 邮箱验证码登录表单校验规则 */
const codeLoginSchema = z.object({
  emailPrefix: z.string().min(1, '请输入邮箱地址').max(128, '邮箱地址过长'),
  emailDomain: z.enum(['qq.com', '163.com']),
  code: z.string().regex(/^\d{6}$/, '请输入 6 位数字验证码'),
})

type PasswordLoginValues = z.infer<typeof passwordLoginSchema>
type CodeLoginValues = z.infer<typeof codeLoginSchema>
/** 登录模式：'password' 为密码登录，'code' 为验证码登录 */
type LoginMode = 'password' | 'code'
type CaptchaState = {
  challengeId: string
  imageDataUrl: string
}
const REMEMBERED_LOGIN_KEY = 'learning-assistant.remembered-login'

function loadRememberedLogin() {
  if (typeof window === 'undefined') return { username: '', remember: true }
  try {
    const raw = localStorage.getItem(REMEMBERED_LOGIN_KEY)
    if (!raw) return { username: '', remember: true }
    const saved = JSON.parse(raw) as { username?: string; password?: string }
    // 旧版本曾把密码写入本地存储；读取时主动清理，只保留用户名。
    if (saved.password) {
      localStorage.setItem(REMEMBERED_LOGIN_KEY, JSON.stringify({ username: saved.username || '' }))
    }
    return { username: saved.username || '', remember: true }
  } catch {
    localStorage.removeItem(REMEMBERED_LOGIN_KEY)
    return { username: '', remember: true }
  }
}

export function LoginForm() {
  // 从认证上下文获取登录方法
  const { login, emailLogin } = useAuth()
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  // 当前登录模式，默认密码登录
  const [mode, setMode] = useState<LoginMode>('password')
  // 错误提示信息
  const [error, setError] = useState('')
  // 登录提交的加载状态
  const [loading, setLoading] = useState(false)
  // 发送验证码的加载状态
  const [codeLoading, setCodeLoading] = useState(false)
  // 验证码冷却倒计时（秒）
  const [cooldown, setCooldown] = useState(0)
  const [captcha, setCaptcha] = useState<CaptchaState | null>(null)
  const [captchaLoading, setCaptchaLoading] = useState(false)
  const rememberedLogin = useMemo(loadRememberedLogin, [])
  const [rememberLogin, setRememberLogin] = useState(rememberedLogin.remember)

  // 密码登录表单实例
  const passwordForm = useForm<PasswordLoginValues>({
    resolver: zodResolver(passwordLoginSchema),
    defaultValues: { username: rememberedLogin.username, password: '', captchaCode: '' },
  })

  // 验证码登录表单实例
  const codeForm = useForm<CodeLoginValues>({
    resolver: zodResolver(codeLoginSchema),
    defaultValues: { emailPrefix: '', emailDomain: 'qq.com', code: '' },
  })

  // 实时监听各字段值，用于动态控制按钮状态
  const username = passwordForm.watch('username')
  const password = passwordForm.watch('password')
  const captchaCode = passwordForm.watch('captchaCode') || ''
  const emailPrefix = codeForm.watch('emailPrefix')
  const emailDomain = codeForm.watch('emailDomain')
  const code = codeForm.watch('code')
  // 拼接完整邮箱地址
  const email = useMemo(() => normalizeEmail(emailPrefix, emailDomain), [emailPrefix, emailDomain])
  // 邮件服务商类型
  const provider = providerForEmail(emailPrefix, emailDomain)
  // 密码登录按钮是否可用：用户名和密码都已填写
  const canPasswordLogin = username.trim().length > 0
    && password.trim().length > 0
    && (!captcha || captchaCode.trim().length > 0)
    && !loading
  // 验证码登录按钮是否可用：邮箱和验证码都已填写
  const canCodeLogin = emailPrefix.trim().length > 0 && code.trim().length > 0 && !loading
  // 发送验证码按钮是否可用：邮箱已填写 + 不在加载中 + 冷却结束
  const canSendCode = emailPrefix.trim().length > 0 && !codeLoading && cooldown <= 0

  /**
   * 验证码冷却倒计时逻辑：
   * cooldown > 0 时每秒递减 1，直到归零
   */
  useEffect(() => {
    if (cooldown <= 0) return
    const timer = window.setTimeout(() => setCooldown((value) => value - 1), 1000)
    return () => window.clearTimeout(timer)
  }, [cooldown])

  /**
   * 登录成功后的统一跳转逻辑：
   * 清除缓存的聊天会话 ID，跳转到新建对话页面
   */
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
    localStorage.setItem(REMEMBERED_LOGIN_KEY, JSON.stringify({
      username: data.username,
    }))
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

  /**
   * 密码登录提交处理：
   * 调用 AuthContext 的 login 方法
   * 特殊处理验证码，其余失败使用后端提示或通用文案
   */
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
        setError(e.message || '登录失败，请检查账号状态或联系管理员。')
      } else {
        setError(e.message || '用户名、邮箱或密码错误，请检查后再试。')
      }
    } finally {
      setLoading(false)
    }
  }

  /**
   * 发送验证码处理函数：
   * 校验邮箱是否已填写，然后调用发送验证码 API
   */
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
      setCooldown(60) // 启动 60 秒冷却
    } catch (err: unknown) {
      const e = err as { message?: string }
      setError(e.message || '验证码发送失败，请稍后再试。')
    } finally {
      setCodeLoading(false)
    }
  }

  /**
   * 邮箱验证码登录提交处理：
   * 调用 AuthContext 的 emailLogin 方法
   */
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
      {/* 左侧面板：品牌展示 + 跳转注册入口 */}
      <aside className="relative flex flex-col items-center justify-center overflow-hidden border-r border-white/70 px-10 text-center">
        {/* 装饰性圆形边框 */}
        <div className="absolute -left-24 -top-24 h-56 w-56 rounded-full border border-slate-300/60" />
        <div className="absolute -bottom-28 -right-20 h-64 w-64 rounded-full border border-slate-300/60" />
        <div className="relative z-10">
          {/* 品牌 Logo */}
          <div className="mx-auto mb-6 flex h-20 w-20 flex-col items-center justify-center rounded-2xl bg-[#e7edf3] text-base font-black leading-tight text-[#222833] shadow-[8px_8px_18px_rgba(174,185,197,0.8),-8px_-8px_18px_rgba(255,255,255,0.9)]">
            <span>智学</span>
            <span>引擎</span>
          </div>
          <h1 className="text-3xl font-black tracking-normal">欢迎回来</h1>
          <p className="mx-auto mt-5 max-w-[260px] text-sm leading-6 text-slate-400">
            管理资料、边读边问、生成总结，让知识更好检索与复习。
          </p>
          {/* 注册入口按钮 */}
          <Link to="/register">
            <Button className={`mt-8 h-11 rounded-full px-12 text-xs font-bold tracking-wide ${actionButtonBase} ${actionButtonReady}`}>
              去注册
            </Button>
          </Link>
        </div>
      </aside>

      {/* 右侧面板：登录表单 */}
      <div className="flex items-center justify-center px-10 py-12">
        <div className="w-full max-w-[360px]">
          <h2 className="text-center text-3xl font-black tracking-normal">登录账号</h2>
          <p className="mt-4 text-center text-xs text-slate-400">密码登录和邮箱验证码登录均可进入学习工作台</p>

          {/* 登录模式切换标签（密码登录 / 验证码登录） */}
          <div className="mt-6 grid grid-cols-2 rounded-full bg-slate-200/60 p-1 text-xs font-bold text-slate-500">
            <button
              type="button"
              className={`flex h-9 items-center justify-center gap-2 rounded-full transition ${mode === 'password' ? 'bg-white text-[#222833] shadow-sm' : 'hover:text-slate-700'}`}
              onClick={() => {
                setMode('password')
                setError('') // 切换模式时清除错误信息
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

          {/* 错误提示区域 */}
          {error && <div className="mt-5 rounded-md bg-red-500/10 px-3 py-2 text-sm text-red-500">{error}</div>}

          {/* 根据当前模式渲染对应的表单 */}
          {mode === 'password' ? (
            /* ========== 密码登录表单 ========== */
            <form onSubmit={passwordForm.handleSubmit(onPasswordSubmit)}>
              <div className="mt-7 space-y-5">
                {/* 用户名/邮箱输入 */}
                <div>
                  <Input
                    placeholder="用户名或邮箱"
                    autoComplete="username"
                    className="h-11 rounded-none border-0 border-b border-slate-300 bg-transparent px-3 text-[#222833] shadow-none placeholder:text-slate-400 focus-visible:border-slate-500 focus-visible:ring-0 focus-visible:ring-offset-0"
                    {...passwordForm.register('username')}
                  />
                  {passwordForm.formState.errors.username && <p className="mt-1 text-xs text-red-500">{passwordForm.formState.errors.username.message}</p>}
                </div>
                {/* 密码输入 */}
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
                {captcha && (
                  <div>
                    <div className="flex items-end gap-3">
                      <div className="min-w-0 flex-1">
                        <Input
                          placeholder="图形验证码"
                          autoComplete="off"
                          maxLength={6}
                          className="h-11 rounded-none border-0 border-b border-slate-300 bg-transparent px-3 uppercase text-[#222833] shadow-none placeholder:normal-case placeholder:text-slate-400 focus-visible:border-slate-500 focus-visible:ring-0 focus-visible:ring-offset-0"
                          {...passwordForm.register('captchaCode')}
                        />
                      </div>
                      <button
                        type="button"
                        className="flex h-12 w-[148px] shrink-0 items-center justify-center overflow-hidden rounded-md border border-slate-200 bg-white shadow-sm"
                        onClick={() => refreshLoginCaptcha(username)}
                        disabled={captchaLoading}
                        title="刷新验证码"
                      >
                        {captchaLoading ? (
                          <RefreshCw className="h-5 w-5 animate-spin text-slate-400" />
                        ) : (
                          <img src={captcha.imageDataUrl} alt="图形验证码" className="h-12 w-[148px] object-cover" />
                        )}
                      </button>
                    </div>
                    {passwordForm.formState.errors.captchaCode && <p className="mt-1 text-xs text-red-500">{passwordForm.formState.errors.captchaCode.message}</p>}
                  </div>
                )}
              </div>

              {/* 记住登录状态 + 忘记密码链接 */}
              <div className="mt-5 flex items-center justify-between text-xs text-slate-400">
                <label className="flex items-center gap-2">
                  <Checkbox checked={rememberLogin} onCheckedChange={(checked) => setRememberLogin(checked === true)} />
                  记住登录状态
                </label>
                <Link to="/forgot-password" className="font-medium text-[#4b5563] hover:text-[#374151]">
                  忘记密码
                </Link>
              </div>

              {/* 登录提交按钮 */}
              <Button
                type="submit"
                className={`mx-auto mt-8 flex h-11 rounded-full px-14 text-xs font-bold tracking-wide ${actionButtonBase} ${canPasswordLogin ? actionButtonReady : actionButtonIdle}`}
                disabled={loading}
              >
                {loading ? '处理中...' : '登录'}
              </Button>
            </form>
          ) : (
            /* ========== 邮箱验证码登录表单 ========== */
            <form onSubmit={codeForm.handleSubmit(onCodeSubmit)}>
              <div className="mt-7 space-y-5">
                {/* 邮箱地址输入（前缀 + 域名选择器） */}
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

                {/* 验证码输入 + 发送验证码按钮 */}
                <div>
                  <div className="flex items-end gap-3">
                    <div className="min-w-0 flex-1">
                      <Input
                        inputMode="numeric"    // 移动端弹出数字键盘
                        maxLength={6}           // 最多 6 位
                        placeholder="邮箱验证码"
                        autoComplete="one-time-code"
                        className="h-11 rounded-none border-0 border-b border-slate-300 bg-transparent px-3 text-[#222833] shadow-none placeholder:text-slate-400 focus-visible:border-slate-500 focus-visible:ring-0 focus-visible:ring-offset-0"
                        {...codeForm.register('code')}
                      />
                      {codeForm.formState.errors.code && <p className="mt-1 text-xs text-red-500">{codeForm.formState.errors.code.message}</p>}
                    </div>
                    {/* 发送验证码按钮：冷却中显示倒计时秒数 */}
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
                  {/* 提示用户验证码将发送到哪个邮箱 */}
                  <p className="mt-2 text-xs text-slate-400">将使用 {provider === 'qq' ? 'QQ 邮箱' : '163 邮箱'} 通道发送到 {email}</p>
                </div>
              </div>

              {/* 忘记密码链接 */}
              <div className="mt-5 flex justify-end text-xs text-slate-400">
                <Link to="/forgot-password" className="font-medium text-[#4b5563] hover:text-[#374151]">
                  忘记密码
                </Link>
              </div>

              {/* 验证码登录提交按钮 */}
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
