import React, { createContext, useContext, useState, useEffect, useCallback } from 'react'
import { SESSION_KEY } from '@/constants'
import type { EmailLoginPayload, Session, LoginPayload, PasswordPayload, ProfilePayload, RegisterPayload } from '@/types'
import {
  emailLogin as emailLoginApi,
  login as loginApi,
  register as registerApi,
  getMe,
  logout as logoutApi,
  updatePassword as updatePasswordApi,
  updateProfile as updateProfileApi,
} from '@/api/auth'

/**
 * 认证上下文类型 — 定义了所有认证相关的状态和方法。
 */
interface AuthContextValue {
  session: Session | null     // 当前登录会话（null 表示未登录）
  isAuthenticated: boolean    // 是否已登录（有 token）
  isAdmin: boolean            // 是否是管理员
  isLoading: boolean          // 是否正在验证登录状态（页面首次加载时）
  login: (payload: LoginPayload) => Promise<void>       // 用户名密码登录
  emailLogin: (payload: EmailLoginPayload) => Promise<void> // 邮箱验证码登录
  register: (payload: RegisterPayload) => Promise<void>  // 注册新用户
  updateProfile: (payload: ProfilePayload) => Promise<void>  // 修改个人资料
  updatePassword: (payload: PasswordPayload) => Promise<void> // 修改密码
  logout: () => Promise<void>  // 登出
}

/** 创建认证上下文（初始值为 null，在 AuthProvider 中注入） */
const AuthContext = createContext<AuthContextValue | null>(null)

/** 从 localStorage 读取保存的 session（页面刷新后恢复登录状态） */
function loadSession(): Session | null {
  try {
    const raw = localStorage.getItem(SESSION_KEY)
    return raw ? JSON.parse(raw) : null
  } catch {
    return null  // JSON 解析失败
  }
}

/** 保存 session 到 localStorage（或清除） */
function saveSession(session: Session | null) {
  if (session) {
    localStorage.setItem(SESSION_KEY, JSON.stringify(session))
  } else {
    localStorage.removeItem(SESSION_KEY)
  }
}

type AuthUserPayload = Partial<Session> & { user?: Partial<Session> }

/**
 * 标准化 session 数据 — 后端返回的格式可能有两种形式：
 * 1. { token, user: { id, username, ... } } — 带 user 嵌套
 * 2. { id, username, ... } — 直接在顶层
 * 这个函数统一处理这两种情况。
 */
function normalizeSession(payload: AuthUserPayload, fallback: Pick<Session, 'username' | 'nickname'>, token = ''): Session {
  const user = payload.user || payload  // 优先取 user 嵌套，否则用顶层
  return {
    id: user.id ?? null,
    username: user.username || fallback.username,
    nickname: user.nickname || user.username || fallback.nickname,
    avatar: user.avatar || '',
    role: (user.role as Session['role']) || 'USER',
    token: payload.token || token,
  }
}

/**
 * AuthProvider — 认证状态管理的全局 Provider。
 *
 * 页面首次加载时：
 * 1. 从 localStorage 读取 session
 * 2. 如果有 token，调用 /api/auth/me 验证是否仍然有效
 * 3. 有效则更新 session，无效则清除并跳转登录页
 *
 * 登录流程：调用后端 API → 保存 session 到 state 和 localStorage
 * 登出流程：调用后端 API（可选）→ 清除 session
 */
export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [session, setSession] = useState<Session | null>(loadSession)
  const [isLoading, setIsLoading] = useState(true)  // 页面首次加载时为 true

  // 应用启动时验证 token 是否有效
  useEffect(() => {
    const stored = loadSession()
    if (!stored?.token) {
      setIsLoading(false)
      return
    }
    // 调用 /api/auth/me 验证 token
    getMe()
      .then((me) => {
        const merged: Session = { ...me, token: stored.token }
        saveSession(merged)
        setSession(merged)
      })
      .catch(() => {
        // token 无效（过期或被篡改），清除 session
        saveSession(null)
        setSession(null)
      })
      .finally(() => setIsLoading(false))
  }, [])

  /** 用户名密码登录 */
  const login = useCallback(async (payload: LoginPayload) => {
    const data = await loginApi(payload)
    const normalized = normalizeSession(data as AuthUserPayload, {
      username: payload.username,
      nickname: payload.username,
    })
    saveSession(normalized)
    setSession(normalized)
  }, [])

  /** 邮箱验证码登录 */
  const emailLogin = useCallback(async (payload: EmailLoginPayload) => {
    const data = await emailLoginApi(payload)
    const normalized = normalizeSession(data as AuthUserPayload, {
      username: payload.email,
      nickname: payload.email,
    })
    saveSession(normalized)
    setSession(normalized)
  }, [])

  /** 注册新用户 — 注册成功后自动登录 */
  const register = useCallback(async (payload: RegisterPayload) => {
    const data = await registerApi(payload)
    // 注册成功后立即登录（获取 token）
    const loginData = await loginApi({ username: payload.username, password: payload.password })
    const normalized = normalizeSession(loginData as AuthUserPayload, {
      username: data.username || payload.username,
      nickname: data.nickname || payload.username,
    })
    saveSession(normalized)
    setSession(normalized)
  }, [])

  /** 修改个人资料（昵称、头像）— 更新本地 session */
  const updateProfile = useCallback(async (payload: ProfilePayload) => {
    const data = await updateProfileApi(payload)
    setSession((current) => {
      const merged: Session = {
        ...current,
        ...data,
        token: current?.token || data.token || '',
      }
      saveSession(merged)
      return merged
    })
  }, [])

  /** 修改密码（只需调用 API，无需更新 session） */
  const updatePassword = useCallback(async (payload: PasswordPayload) => {
    await updatePasswordApi(payload)
  }, [])

  /** 登出 — 清除 session 并跳转到登录页 */
  const logout = useCallback(async () => {
    try { await logoutApi() } catch { /* 登出失败也忽略，本地清除即可 */ }
    saveSession(null)
    setSession(null)
  }, [])

  return (
    <AuthContext.Provider
      value={{
        session,
        isAuthenticated: !!session?.token,  // 有 token 就认为已登录
        isAdmin: session?.role === 'ADMIN',
        isLoading,
        login,
        emailLogin,
        register,
        updateProfile,
        updatePassword,
        logout,
      }}
    >
      {children}
    </AuthContext.Provider>
  )
}

/**
 * useAuth Hook — 在组件中获取认证状态和方法。
 * 必须在 AuthProvider 内部使用，否则抛出错误。
 */
export function useAuth() {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth must be used within AuthProvider')
  return ctx
}
