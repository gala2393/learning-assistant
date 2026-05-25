import React, { createContext, useContext, useState, useEffect, useCallback } from 'react'
import { SESSION_KEY } from '@/constants'
import type { Session, LoginPayload, PasswordPayload, ProfilePayload, RegisterPayload } from '@/types'
import {
  login as loginApi,
  register as registerApi,
  getMe,
  logout as logoutApi,
  updatePassword as updatePasswordApi,
  updateProfile as updateProfileApi,
} from '@/api/auth'

interface AuthContextValue {
  session: Session | null
  isAuthenticated: boolean
  isAdmin: boolean
  isLoading: boolean
  login: (payload: LoginPayload) => Promise<void>
  register: (payload: RegisterPayload) => Promise<void>
  updateProfile: (payload: ProfilePayload) => Promise<void>
  updatePassword: (payload: PasswordPayload) => Promise<void>
  logout: () => Promise<void>
}

const AuthContext = createContext<AuthContextValue | null>(null)

function loadSession(): Session | null {
  try {
    const raw = localStorage.getItem(SESSION_KEY)
    return raw ? JSON.parse(raw) : null
  } catch {
    return null
  }
}

function saveSession(session: Session | null) {
  if (session) {
    localStorage.setItem(SESSION_KEY, JSON.stringify(session))
  } else {
    localStorage.removeItem(SESSION_KEY)
  }
}

type AuthUserPayload = Partial<Session> & { user?: Partial<Session> }

function normalizeSession(payload: AuthUserPayload, fallback: Pick<Session, 'username' | 'nickname'>, token = ''): Session {
  const user = payload.user || payload
  return {
    id: user.id ?? null,
    username: user.username || fallback.username,
    nickname: user.nickname || user.username || fallback.nickname,
    avatar: user.avatar || '',
    role: (user.role as Session['role']) || 'USER',
    token: payload.token || token,
  }
}

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [session, setSession] = useState<Session | null>(loadSession)
  const [isLoading, setIsLoading] = useState(true)

  useEffect(() => {
    const stored = loadSession()
    if (!stored?.token) {
      setIsLoading(false)
      return
    }
    getMe()
      .then((me) => {
        const merged: Session = { ...me, token: stored.token }
        saveSession(merged)
        setSession(merged)
      })
      .catch(() => {
        saveSession(null)
        setSession(null)
      })
      .finally(() => setIsLoading(false))
  }, [])

  const login = useCallback(async (payload: LoginPayload) => {
    const data = await loginApi(payload)
    const normalized = normalizeSession(data as AuthUserPayload, {
      username: payload.username,
      nickname: payload.username,
    })
    saveSession(normalized)
    setSession(normalized)
  }, [])

  const register = useCallback(async (payload: RegisterPayload) => {
    const data = await registerApi(payload)
    const loginData = await loginApi({ username: payload.username, password: payload.password })
    const normalized = normalizeSession(loginData as AuthUserPayload, {
      username: data.username || payload.username,
      nickname: data.nickname || payload.nickname,
    })
    saveSession(normalized)
    setSession(normalized)
  }, [])

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

  const updatePassword = useCallback(async (payload: PasswordPayload) => {
    await updatePasswordApi(payload)
  }, [])

  const logout = useCallback(async () => {
    try { await logoutApi() } catch { /* ignore */ }
    saveSession(null)
    setSession(null)
  }, [])

  return (
    <AuthContext.Provider
      value={{
        session,
        isAuthenticated: !!session?.token,
        isAdmin: session?.role === 'ADMIN',
        isLoading,
        login,
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

export function useAuth() {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth must be used within AuthProvider')
  return ctx
}
