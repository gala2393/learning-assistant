import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { CheckCircle, Loader2, XCircle } from 'lucide-react'
import { z } from 'zod'
import { useCheckUsername } from '@/api/auth'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { useAuth } from '@/context/AuthContext'
import { useDebounce } from '@/hooks/useDebounce'

const registerSchema = z
  .object({
    username: z.string().min(3, '用户名至少 3 位').max(32, '用户名最多 32 位'),
    nickname: z.string().min(1, '请输入昵称').max(32, '昵称最多 32 位'),
    password: z.string().min(8, '密码至少 8 位').max(64, '密码最多 64 位'),
    confirmPassword: z.string(),
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

  const {
    register,
    handleSubmit,
    watch,
    formState: { errors },
  } = useForm<RegisterFormValues>({
    resolver: zodResolver(registerSchema),
    defaultValues: { username: '', nickname: '', password: '', confirmPassword: '' },
  })

  const username = watch('username')
  const debouncedUsername = useDebounce(username, 500)
  const shouldCheck = debouncedUsername.length >= 3
  const { data: usernameStatus, isLoading: checkingUsername } = useCheckUsername(debouncedUsername, shouldCheck)

  const onSubmit = async (data: RegisterFormValues) => {
    setError('')
    setLoading(true)
    try {
      await registerUser(data)
      sessionStorage.removeItem('learning-assistant.chat.current')
      navigate('/workspace/chat?new=1', { replace: true })
    } catch (err: unknown) {
      const e = err as { response?: { status?: number }; message?: string }
      if (e.response?.status === 400) {
        setError('注册信息有误，请检查用户名、昵称、密码和确认密码。')
      } else {
        setError(e.message || '注册请求失败，请稍后再试。')
      }
    } finally {
      setLoading(false)
    }
  }

  return (
    <section className="mx-auto grid h-[600px] w-full max-w-[920px] overflow-hidden rounded-[6px] bg-[#eef3f7] shadow-[18px_18px_38px_rgba(172,184,196,0.75),-18px_-18px_38px_rgba(255,255,255,0.95)] md:grid-cols-[0.9fr_1.1fr]">
      <aside className="relative flex flex-col items-center justify-center overflow-hidden border-r border-white/70 px-10 text-center">
        <div className="absolute -left-24 -top-24 h-56 w-56 rounded-full border border-slate-300/60" />
        <div className="absolute -bottom-28 -right-20 h-64 w-64 rounded-full border border-slate-300/60" />
        <div className="relative z-10">
          <div className="mx-auto mb-6 flex h-14 w-14 items-center justify-center rounded-2xl bg-[#e7edf3] text-lg font-black text-[#222833] shadow-[8px_8px_18px_rgba(174,185,197,0.8),-8px_-8px_18px_rgba(255,255,255,0.9)]">
            智学
          </div>
          <h1 className="text-3xl font-black tracking-normal">加入学习空间</h1>
          <p className="mx-auto mt-5 max-w-[260px] text-sm leading-6 text-slate-400">
            创建账号后即可整理资料、追踪问答记录和收藏重点内容。
          </p>
          <Link to="/login">
            <Button className="mt-8 h-11 rounded-full bg-[#4f73e8] px-12 text-xs font-bold tracking-wide text-white shadow-[0_10px_22px_rgba(79,115,232,0.35)] hover:bg-[#4269df]">
              去登录
            </Button>
          </Link>
        </div>
      </aside>

      <div className="flex items-center justify-center px-10 py-12">
        <form onSubmit={handleSubmit(onSubmit)} className="w-full max-w-[340px]">
          <h2 className="text-center text-3xl font-black tracking-normal">创建账号</h2>
          <p className="mt-4 text-center text-xs text-slate-400">注册后开启资料问答和收藏复习</p>

          {error && <div className="mt-5 rounded-md bg-red-500/10 px-3 py-2 text-sm text-red-500">{error}</div>}

          <div className="mt-7 space-y-4">
            <div>
              <Input
                placeholder="用户名"
                autoComplete="username"
                className="h-10 rounded-none border-0 border-b border-slate-300 bg-transparent px-3 text-[#222833] shadow-none placeholder:text-slate-400 focus-visible:border-[#4f73e8] focus-visible:ring-0 focus-visible:ring-offset-0"
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
                placeholder="昵称"
                autoComplete="name"
                className="h-10 rounded-none border-0 border-b border-slate-300 bg-transparent px-3 text-[#222833] shadow-none placeholder:text-slate-400 focus-visible:border-[#4f73e8] focus-visible:ring-0 focus-visible:ring-offset-0"
                {...register('nickname')}
              />
              {errors.nickname && <p className="mt-1 text-xs text-red-500">{errors.nickname.message}</p>}
            </div>
            <div>
              <Input
                type="password"
                placeholder="密码"
                autoComplete="new-password"
                className="h-10 rounded-none border-0 border-b border-slate-300 bg-transparent px-3 text-[#222833] shadow-none placeholder:text-slate-400 focus-visible:border-[#4f73e8] focus-visible:ring-0 focus-visible:ring-offset-0"
                {...register('password')}
              />
              {errors.password && <p className="mt-1 text-xs text-red-500">{errors.password.message}</p>}
            </div>
            <div>
              <Input
                type="password"
                placeholder="确认密码"
                autoComplete="new-password"
                className="h-10 rounded-none border-0 border-b border-slate-300 bg-transparent px-3 text-[#222833] shadow-none placeholder:text-slate-400 focus-visible:border-[#4f73e8] focus-visible:ring-0 focus-visible:ring-offset-0"
                {...register('confirmPassword')}
              />
              {errors.confirmPassword && <p className="mt-1 text-xs text-red-500">{errors.confirmPassword.message}</p>}
            </div>
          </div>

          <Button
            type="submit"
            className="mx-auto mt-8 flex h-11 rounded-full bg-[#4f73e8] px-14 text-xs font-bold tracking-wide text-white shadow-[0_10px_22px_rgba(79,115,232,0.35)] hover:bg-[#4269df]"
            disabled={loading}
          >
            {loading ? '处理中...' : '注册'}
          </Button>
        </form>
      </div>
    </section>
  )
}
