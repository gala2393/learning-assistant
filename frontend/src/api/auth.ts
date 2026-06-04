import api from '@/lib/axios'
import { useMutation, useQuery } from '@tanstack/react-query'
import { queryClient } from '@/lib/query-client'
import type { EmailLoginPayload, LoginPayload, PasswordPayload, ProfilePayload, RegisterPayload, ResetPasswordPayload, Session, UsernameCheckResult } from '@/types'

/**
 * 认证 API 模块 — 封装所有与认证相关的 HTTP 请求。
 *
 * 每个函数都是一个纯异步函数，直接调用后端接口。
 * 同时提供对应的 React Hook（useXxx），封装了 useQuery/useMutation，
 * 组件中直接调用 Hook 即可享受缓存、加载状态、错误处理等能力。
 *
 * 后端接口映射：
 * - login           → POST /api/auth/login
 * - sendEmailCode   → POST /api/auth/email-code
 * - emailLogin      → POST /api/auth/email-login
 * - register        → POST /api/auth/register
 * - resetPassword   → POST /api/auth/reset-password
 * - checkUsername   → GET  /api/auth/check-username
 * - getMe           → GET  /api/auth/me
 * - updateProfile   → PUT  /api/auth/me
 * - updatePassword  → PUT  /api/auth/password
 * - logout          → POST /api/auth/logout
 */

/** 用户名密码登录 — 返回 Session（含 token 和用户信息） */
export async function login(payload: LoginPayload): Promise<Session> {
  const { data } = await api.post('/auth/login', payload)
  return data
}

/** 发送邮箱验证码 — 用于登录或重置密码 */
export async function sendEmailCode(email: string, provider = 'qq'): Promise<void> {
  await api.post('/auth/email-code', { email, provider })
}

/** 邮箱验证码登录 — 邮箱未注册时自动创建账号 */
export async function emailLogin(payload: EmailLoginPayload): Promise<Session> {
  const { data } = await api.post('/auth/email-login', payload)
  return data
}

/** 注册新用户 — 返回新用户信息（不含 token，需再调用 login） */
export async function register(payload: RegisterPayload): Promise<Session> {
  const { data } = await api.post('/auth/register', payload)
  return data
}

/** 通过邮箱验证码重置密码 */
export async function resetPassword(payload: ResetPasswordPayload): Promise<void> {
  await api.post('/auth/reset-password', payload)
}

/** 检查用户名是否可用 — 实时校验 */
export async function checkUsername(username: string): Promise<UsernameCheckResult> {
  const { data } = await api.get('/auth/check-username', { params: { username } })
  return data
}

/** 获取当前登录用户信息 — 验证 token 是否有效 */
export async function getMe(): Promise<Session> {
  const { data } = await api.get('/auth/me')
  return data
}

/** 修改个人资料（昵称、头像） */
export async function updateProfile(payload: ProfilePayload): Promise<Session> {
  const { data } = await api.put('/auth/me', payload)
  return data
}

/** 修改密码（需提供当前密码） */
export async function updatePassword(payload: PasswordPayload): Promise<void> {
  await api.put('/auth/password', payload)
}

/** 登出（后端无需处理，前端清除 session 即可） */
export async function logout(): Promise<void> {
  await api.post('/auth/logout')
}

// ===== React Hooks =====

/** 登录 mutation — 在登录表单中使用 */
export function useLogin() {
  return useMutation({ mutationFn: login })
}

/** 发送验证码 mutation — 在邮箱登录表单中使用 */
export function useSendEmailCode() {
  return useMutation({
    mutationFn: ({ email, provider }: { email: string; provider?: string }) => sendEmailCode(email, provider),
  })
}

/** 注册 mutation */
export function useRegister() {
  return useMutation({ mutationFn: register })
}

/** 重置密码 mutation */
export function useResetPassword() {
  return useMutation({ mutationFn: resetPassword })
}

/** 检查用户名可用性 query — 防抖后触发，10 秒内缓存 */
export function useCheckUsername(username: string, enabled: boolean) {
  return useQuery({
    queryKey: ['check-username', username],
    queryFn: () => checkUsername(username),
    enabled,        // 只在 enabled=true 时发请求
    staleTime: 10_000,  // 10 秒缓存
  })
}

/** 获取当前用户信息 query */
export function useMe(enabled: boolean) {
  return useQuery({
    queryKey: ['me'],
    queryFn: getMe,
    enabled,
    retry: false,  // 不重试（token 无效时重试也没用）
  })
}

/** 登出 mutation — 登出后清除所有缓存 */
export function useLogout() {
  return useMutation({
    mutationFn: logout,
    onSuccess: () => {
      localStorage.removeItem('learning-assistant.frontend.session')
      queryClient.clear()  // 清除所有 React Query 缓存
    },
  })
}
