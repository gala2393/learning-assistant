import { SESSION_KEY } from '@/constants'

export const LOGIN_REQUIRED_MESSAGE = '请先完成登录哦！'

export function hasStoredSession() {
  if (typeof window === 'undefined') return false
  try {
    const raw = localStorage.getItem(SESSION_KEY)
    if (!raw) return false
    const session = JSON.parse(raw) as { token?: string }
    return Boolean(session.token)
  } catch {
    localStorage.removeItem(SESSION_KEY)
    return false
  }
}

export function redirectToLogin(delayMs = 2000) {
  if (typeof window === 'undefined') return
  const current = `${window.location.pathname}${window.location.search}`
  window.setTimeout(() => {
    window.location.href = `/login?redirect=${encodeURIComponent(current)}`
  }, delayMs)
}
