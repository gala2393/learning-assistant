/**
 * RegisterForm 组件 —— 用户注册表单
 *
 * 【用途与使用场景】
 * 新用户注册页面的核心表单组件。
 * 访问路径通常为 /register。
 *
 * 【核心流程】
 * 1. 用户填写邮箱地址（QQ 邮箱或 163 邮箱）
 * 2. 填写用户名（3-32 位，实时校验是否可用）
 * 3. 设置密码并确认密码
 * 4. 发送并填写邮箱验证码
 * 5. 提交注册，成功后自动跳转到工作台
 *
 * 【特色功能】
 * - 用户名可用性实时校验：输入时自动防抖 500ms 后调用后端检查接口
 *   - 校验中显示加载动画
 *   - 可用显示绿色勾号
 *   - 不可用显示红色叉号和原因
 * - 验证码发送后有 60 秒冷却倒计时
 *
 * 【技术细节】
 * - 使用 react-hook-form + zod 进行表单管理和校验
 * - 用户名校验使用自定义 useDebounce hook + useCheckUsername 查询
 * - 通过 SWR 进行数据请求和缓存管理
 */

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

/**
 * 注册表单校验规则（Zod schema）
 * - emailPrefix: 邮箱前缀，1-64 字符
 * - emailDomain: 邮箱域名，只允许 qq.com 和 163.com
 * - username: 用户名，3-32 位
 * - password: 密码，8-64 位
 * - confirmPassword: 确认密码，必须与 password 一致
 * - code: 6 位数字邮箱验证码
 */
const registerSchema = z
  .object({
    emailPrefix: z.string().min(1, '请输入邮箱地址').max(64, '邮箱地址过长'),
    emailDomain: z.enum(['qq.com', '163.com']),
    username: z.string().min(3, '用户名至少 3 位').max(32, '用户名最多 32 位'),
    password: z.string().min(8, '密码至少 8 位').max(64, '密码最多 64 位'),
    confirmPassword: z.string(),
    code: z.string().regex(/^\d{6}$/, '请输入 6 位数字验证码'),
  })
  // 自定义校验：两次密码必须一致
  .refine((data) => data.password === data.confirmPassword, {
    message: '两次输入的密码不一致',
    path: ['confirmPassword'], // 错误信息显示在 confirmPassword 字段
  })

type RegisterFormValues = z.infer<typeof registerSchema>

export function RegisterForm() {
  // 从认证上下文获取注册方法（重命名为 registerUser 避免与 useForm 的 register 冲突）
  const { register: registerUser } = useAuth()
  const navigate = useNavigate()
  // 错误提示信息
  const [error, setError] = useState('')
  // 注册提交的加载状态
  const [loading, setLoading] = useState(false)
  // 发送验证码的加载状态
  const [codeLoading, setCodeLoading] = useState(false)
  // 验证码冷却倒计时（秒）
  const [cooldown, setCooldown] = useState(0)

  // 初始化 react-hook-form，绑定 zod 校验器
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

  // 实时监听各字段值
  const username = watch('username')
  const emailPrefix = watch('emailPrefix')
  const emailDomain = watch('emailDomain')
  const password = watch('password')
  const confirmPassword = watch('confirmPassword')
  const code = watch('code')

  // 用户名防抖：输入停止 500ms 后才发起校验请求，避免频繁请求
  const debouncedUsername = useDebounce(username, 500)
  // 用户名至少 3 位时才触发校验
  const shouldCheck = debouncedUsername.length >= 3
  // 调用后端接口检查用户名是否可用
  const { data: usernameStatus, isLoading: checkingUsername } = useCheckUsername(debouncedUsername, shouldCheck)

  // 将前缀 + 域名拼接为完整邮箱地址
  const email = useMemo(() => normalizeEmail(emailPrefix, emailDomain), [emailPrefix, emailDomain])
  // 邮件服务商类型
  const provider = providerForEmail(emailPrefix, emailDomain)
  // 注册按钮是否可用：所有字段非空 + 不在加载中
  const canRegister = emailPrefix.trim().length > 0
    && username.trim().length > 0
    && password.trim().length > 0
    && confirmPassword.trim().length > 0
    && code.trim().length > 0
    && !loading

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
   * 发送验证码处理函数：
   * 1. 校验邮箱是否已填写
   * 2. 调用发送验证码 API
   * 3. 成功后启动 60 秒冷却倒计时
   */
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
      setCooldown(60) // 启动 60 秒冷却
    } catch (err: unknown) {
      const e = err as { message?: string }
      setError(e.message || '验证码发送失败，请稍后再试。')
    } finally {
      setCodeLoading(false)
    }
  }

  /**
   * 注册表单提交处理函数：
   * 调用注册 API，成功后清除缓存并跳转到工作台
   * 特殊处理 400 状态码（注册信息有误）
   */
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
      // 注册成功后清除缓存的聊天会话 ID
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
      {/* 左侧面板：品牌展示 + 跳转登录入口 */}
      <aside className="relative flex flex-col items-center justify-center overflow-hidden border-r border-white/70 px-10 text-center">
        {/* 装饰性圆形边框 */}
        <div className="absolute -left-24 -top-24 h-56 w-56 rounded-full border border-slate-300/60" />
        <div className="absolute -bottom-28 -right-20 h-64 w-64 rounded-full border border-slate-300/60" />
        <div className="relative z-10">
          {/* 品牌 Logo */}
          <div className="mx-auto mb-6 flex h-14 w-14 items-center justify-center rounded-2xl bg-[#e7edf3] text-lg font-black text-[#222833] shadow-[8px_8px_18px_rgba(174,185,197,0.8),-8px_-8px_18px_rgba(255,255,255,0.9)]">
            智学
          </div>
          <h1 className="text-3xl font-black tracking-normal">加入学习空间</h1>
          <p className="mx-auto mt-5 max-w-[260px] text-sm leading-6 text-slate-400">
            先验证邮箱，再创建你的学习身份。之后可用用户名或邮箱登录。
          </p>
          {/* 登录入口按钮 */}
          <Link to="/login">
            <Button className={`mt-8 h-11 rounded-full px-12 text-xs font-bold tracking-wide ${actionButtonBase} ${actionButtonReady}`}>
              去登录
            </Button>
          </Link>
        </div>
      </aside>

      {/* 右侧面板：注册表单 */}
      <div className="flex items-center justify-center px-10 py-12">
        <form onSubmit={handleSubmit(onSubmit)} className="w-full max-w-[360px]">
          <h2 className="text-center text-3xl font-black tracking-normal">创建账号</h2>
          <p className="mt-4 text-center text-xs text-slate-400">邮箱用于验证和登录，用户名用于展示和登录</p>

          {/* 错误提示区域 */}
          {error && <div className="mt-5 rounded-md bg-red-500/10 px-3 py-2 text-sm text-red-500">{error}</div>}

          <div className="mt-7 space-y-4">
            {/* 邮箱地址输入（前缀 + 域名选择器） */}
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

            {/* 用户名输入（带实时可用性校验） */}
            <div>
              <Input
                placeholder="用户名"
                autoComplete="username"
                className="h-10 rounded-none border-0 border-b border-slate-300 bg-transparent px-3 text-[#222833] shadow-none placeholder:text-slate-400 focus-visible:border-slate-500 focus-visible:ring-0 focus-visible:ring-offset-0"
                {...register('username')}
              />
              {/* 用户名校验状态提示区 */}
              <div className="mt-1 flex min-h-4 items-center gap-1 text-xs">
                {/* 校验中：显示加载动画 */}
                {checkingUsername && shouldCheck && (
                  <>
                    <Loader2 className="h-3 w-3 animate-spin text-slate-400" />
                    <span className="text-slate-400">正在校验用户名...</span>
                  </>
                )}
                {/* 校验完成：根据结果显示可用/不可用 */}
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

            {/* 密码输入 */}
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

            {/* 确认密码输入 */}
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

            {/* 验证码输入 + 发送验证码按钮 */}
            <div>
              <div className="flex items-end gap-3">
                <div className="min-w-0 flex-1">
                  <Input
                    inputMode="numeric"    // 移动端弹出数字键盘
                    maxLength={6}           // 最多 6 位
                    placeholder="邮箱验证码"
                    autoComplete="one-time-code"
                    className="h-10 rounded-none border-0 border-b border-slate-300 bg-transparent px-3 text-[#222833] shadow-none placeholder:text-slate-400 focus-visible:border-slate-500 focus-visible:ring-0 focus-visible:ring-offset-0"
                    {...register('code')}
                  />
                  {errors.code && <p className="mt-1 text-xs text-red-500">{errors.code.message}</p>}
                </div>
                {/* 发送验证码按钮：冷却中显示倒计时秒数 */}
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
              {/* 提示用户验证码将发送到哪个邮箱 */}
              <p className="mt-2 text-xs text-slate-400">将使用 {provider === 'qq' ? 'QQ 邮箱' : '163 邮箱'} 通道发送到 {email}</p>
            </div>
          </div>

          {/* 注册提交按钮：所有字段填写完毕后亮起 */}
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
