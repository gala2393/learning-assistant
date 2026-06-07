/**
 * ForgotPasswordForm 组件 —— 忘记密码/重置密码表单
 *
 * 【用途与使用场景】
 * 用户忘记密码时，通过邮箱验证码重置密码。
 * 访问路径通常为 /forgot-password。
 *
 * 【核心流程】
 * 1. 用户输入邮箱地址（支持 QQ 邮箱和 163 邮箱）
 * 2. 点击"发送验证码"，系统向该邮箱发送 6 位数字验证码
 * 3. 用户填写验证码 + 新密码 + 确认密码
 * 4. 提交后调用重置密码接口，成功后自动跳转到登录页
 *
 * 【技术细节】
 * - 使用 react-hook-form + zod 进行表单管理和校验
 * - 验证码发送后有 60 秒倒计时冷却
 * - 邮箱地址拆分为"前缀"+"域名"两个字段，方便用户选择邮箱服务商
 */

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

/**
 * 表单校验规则（Zod schema）
 * - emailPrefix: 邮箱地址前缀，1-64 个字符
 * - emailDomain: 邮箱域名，只允许 qq.com 和 163.com
 * - code: 6 位数字验证码
 * - newPassword: 新密码，8-64 位
 * - confirmPassword: 确认密码，必须与 newPassword 一致
 */
const forgotPasswordSchema = z
  .object({
    emailPrefix: z.string().min(1, '请输入邮箱地址').max(64, '邮箱地址过长'),
    emailDomain: z.enum(['qq.com', '163.com']),
    code: z.string().regex(/^\d{6}$/, '请输入 6 位数字验证码'),
    newPassword: z.string().min(8, '密码至少 8 位').max(64, '密码最多 64 位'),
    confirmPassword: z.string(),
  })
  // 自定义校验：两次密码必须一致
  .refine((data) => data.newPassword === data.confirmPassword, {
    message: '两次输入的密码不一致',
    path: ['confirmPassword'], // 错误信息显示在 confirmPassword 字段
  })

type ForgotPasswordFormValues = z.infer<typeof forgotPasswordSchema>

export function ForgotPasswordForm() {
  const navigate = useNavigate()
  // 错误提示信息
  const [error, setError] = useState('')
  // 成功提示信息
  const [success, setSuccess] = useState('')
  // 重置密码的加载状态
  const [loading, setLoading] = useState(false)
  // 发送验证码的加载状态
  const [codeLoading, setCodeLoading] = useState(false)
  // 验证码发送冷却倒计时（秒）
  const [cooldown, setCooldown] = useState(0)

  // 初始化 react-hook-form，绑定 zod 校验器
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

  // 实时监听各字段值，用于计算按钮状态和提示文案
  const emailPrefix = watch('emailPrefix')
  const emailDomain = watch('emailDomain')
  const code = watch('code')
  const newPassword = watch('newPassword')
  const confirmPassword = watch('confirmPassword')
  // 将前缀 + 域名拼接为完整邮箱地址
  const email = useMemo(() => normalizeEmail(emailPrefix, emailDomain), [emailPrefix, emailDomain])
  // 根据邮箱域名判断使用哪个邮件服务商
  const provider = providerForEmail(emailPrefix, emailDomain) === 'netease' ? '163 邮箱' : 'QQ 邮箱'
  // 是否可以发送验证码：邮箱非空 + 不在加载中 + 冷却结束
  const canSendCode = emailPrefix.trim().length > 0 && !codeLoading && cooldown <= 0
  // 是否可以提交重置：所有字段非空 + 不在加载中
  const canReset = emailPrefix.trim().length > 0
    && code.trim().length > 0
    && newPassword.trim().length > 0
    && confirmPassword.trim().length > 0
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
    setSuccess('')
    setCodeLoading(true)
    try {
      await sendEmailCode(targetEmail, providerForEmail(values.emailPrefix, values.emailDomain))
      setCooldown(60) // 启动 60 秒冷却
      setSuccess('验证码已发送，请在 5 分钟内完成密码重置。')
    } catch (err: unknown) {
      const e = err as { message?: string }
      setError(e.message || '验证码发送失败，请稍后再试。')
    } finally {
      setCodeLoading(false)
    }
  }

  /**
   * 表单提交处理函数：
   * 调用重置密码 API，成功后延迟 900ms 跳转到登录页
   */
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
      // 延迟跳转，让用户看到成功提示
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
      {/* 左侧面板：品牌展示 + 跳转登录链接 */}
      <aside className="relative flex flex-col items-center justify-center overflow-hidden border-r border-white/70 px-10 text-center">
        {/* 装饰性圆形边框 */}
        <div className="absolute -left-24 -top-24 h-56 w-56 rounded-full border border-slate-300/60" />
        <div className="absolute -bottom-28 -right-20 h-64 w-64 rounded-full border border-slate-300/60" />
        <div className="relative z-10">
          {/* 品牌 Logo */}
          <div className="mx-auto mb-6 flex h-14 w-24 items-center justify-center rounded-2xl bg-[#e7edf3] px-4 text-base font-black text-[#222833] shadow-[8px_8px_18px_rgba(174,185,197,0.8),-8px_-8px_18px_rgba(255,255,255,0.9)]">
            智学引擎
          </div>
          <h1 className="text-3xl font-black tracking-normal">重设密码</h1>
          <p className="mx-auto mt-5 max-w-[260px] text-sm leading-6 text-slate-400">
            使用注册邮箱接收验证码，验证通过后即可设置新的登录密码。
          </p>
          {/* 返回登录页按钮 */}
          <Link to="/login">
            <Button className={`mt-8 h-11 rounded-full px-12 text-xs font-bold tracking-wide ${actionButtonBase} ${actionButtonReady}`}>
              返回登录
            </Button>
          </Link>
        </div>
      </aside>

      {/* 右侧面板：重置密码表单 */}
      <div className="flex items-center justify-center px-10 py-12">
        <form onSubmit={handleSubmit(onSubmit)} className="w-full max-w-[360px]">
          <h2 className="text-center text-3xl font-black tracking-normal">找回账号</h2>
          <p className="mt-4 text-center text-xs text-slate-400">邮箱验证通过后，新密码会立即生效</p>

          {/* 错误提示 */}
          {error && <div className="mt-5 rounded-md bg-red-500/10 px-3 py-2 text-sm text-red-500">{error}</div>}
          {/* 成功提示 */}
          {success && (
            <div className="mt-5 flex items-center gap-2 rounded-md bg-emerald-500/10 px-3 py-2 text-sm text-emerald-600">
              <CheckCircle2 className="h-4 w-4" />
              <span>{success}</span>
            </div>
          )}

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
                  className={`h-10 shrink-0 rounded-full border-0 px-4 text-xs font-bold ${actionButtonBase} ${canSendCode ? actionButtonReady : actionButtonIdle}`}
                  disabled={codeLoading || cooldown > 0}
                  onClick={handleSendCode}
                >
                  {cooldown > 0 ? `${cooldown}s` : codeLoading ? '发送中' : '发送验证码'}
                </Button>
              </div>
              {/* 提示用户验证码将发送到哪个邮箱 */}
              <p className="mt-2 text-xs text-slate-400">将使用 {provider} 通道发送到 {email}</p>
            </div>

            {/* 新密码输入 */}
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

            {/* 确认新密码输入 */}
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

          {/* 提交按钮：所有字段填写完毕后亮起 */}
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
