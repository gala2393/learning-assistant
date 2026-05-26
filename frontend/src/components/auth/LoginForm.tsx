import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { useAuth } from '@/context/AuthContext'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Input } from '@/components/ui/input'
import { actionButtonBase, actionButtonIdle, actionButtonReady } from '@/lib/action-button-styles'

const loginSchema = z.object({
  username: z.string().min(1, '请输入用户名或邮箱'),
  password: z.string().min(1, '请输入密码'),
})

type LoginFormValues = z.infer<typeof loginSchema>

export function LoginForm() {
  const { login } = useAuth()
  const navigate = useNavigate()
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)

  const {
    register,
    handleSubmit,
    watch,
    formState: { errors },
  } = useForm<LoginFormValues>({
    resolver: zodResolver(loginSchema),
    defaultValues: { username: '', password: '' },
  })

  const username = watch('username')
  const password = watch('password')
  const canLogin = username.trim().length > 0 && password.trim().length > 0 && !loading

  const onSubmit = async (data: LoginFormValues) => {
    setError('')
    setLoading(true)
    try {
      await login(data)
      sessionStorage.removeItem('learning-assistant.chat.current')
      navigate('/workspace/chat?new=1', { replace: true })
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

  return (
    <section className="mx-auto grid min-h-[560px] w-full max-w-[920px] overflow-hidden rounded-[6px] bg-[#eef3f7] shadow-[18px_18px_38px_rgba(172,184,196,0.75),-18px_-18px_38px_rgba(255,255,255,0.95)] md:grid-cols-[0.9fr_1.1fr]">
      <aside className="relative flex flex-col items-center justify-center overflow-hidden border-r border-white/70 px-10 text-center">
        <div className="absolute -left-24 -top-24 h-56 w-56 rounded-full border border-slate-300/60" />
        <div className="absolute -bottom-28 -right-20 h-64 w-64 rounded-full border border-slate-300/60" />
        <div className="relative z-10">
          <div className="mx-auto mb-6 flex h-14 w-14 items-center justify-center rounded-2xl bg-[#e7edf3] text-lg font-black text-[#222833] shadow-[8px_8px_18px_rgba(174,185,197,0.8),-8px_-8px_18px_rgba(255,255,255,0.9)]">
            智学
          </div>
          <h1 className="text-3xl font-black tracking-normal">欢迎回来</h1>
          <p className="mx-auto mt-5 max-w-[260px] text-sm leading-6 text-slate-400">
            用用户名或邮箱继续连接你的课程资料、问答历史和学习收藏。
          </p>
          <Link to="/register">
            <Button className={`mt-8 h-11 rounded-full px-12 text-xs font-bold tracking-wide ${actionButtonBase} ${actionButtonReady}`}>
              去注册
            </Button>
          </Link>
        </div>
      </aside>

      <div className="flex items-center justify-center px-10 py-12">
        <form onSubmit={handleSubmit(onSubmit)} className="w-full max-w-[340px]">
          <h2 className="text-center text-3xl font-black tracking-normal">登录账号</h2>
          <p className="mt-4 text-center text-xs text-slate-400">使用用户名或邮箱 + 密码进入学习工作台</p>

          {error && <div className="mt-5 rounded-md bg-red-500/10 px-3 py-2 text-sm text-red-500">{error}</div>}

          <div className="mt-7 space-y-5">
            <div>
              <Input
                placeholder="用户名或邮箱"
                autoComplete="username"
                className="h-11 rounded-none border-0 border-b border-slate-300 bg-transparent px-3 text-[#222833] shadow-none placeholder:text-slate-400 focus-visible:border-slate-500 focus-visible:ring-0 focus-visible:ring-offset-0"
                {...register('username')}
              />
              {errors.username && <p className="mt-1 text-xs text-red-500">{errors.username.message}</p>}
            </div>
            <div>
              <Input
                type="password"
                placeholder="密码"
                autoComplete="current-password"
                className="h-11 rounded-none border-0 border-b border-slate-300 bg-transparent px-3 text-[#222833] shadow-none placeholder:text-slate-400 focus-visible:border-slate-500 focus-visible:ring-0 focus-visible:ring-offset-0"
                {...register('password')}
              />
              {errors.password && <p className="mt-1 text-xs text-red-500">{errors.password.message}</p>}
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
            className={`mx-auto mt-8 flex h-11 rounded-full px-14 text-xs font-bold tracking-wide ${actionButtonBase} ${canLogin ? actionButtonReady : actionButtonIdle}`}
            disabled={loading}
          >
            {loading ? '处理中...' : '登录'}
          </Button>
        </form>
      </div>
    </section>
  )
}
