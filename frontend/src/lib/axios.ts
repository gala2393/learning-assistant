import axios from 'axios'
import { SESSION_KEY } from '@/constants'

const api = axios.create({
  baseURL: (import.meta.env.VITE_API_BASE || '/api').replace(/\/$/, ''),
  timeout: 60000,
})

api.interceptors.request.use((config) => {
  const raw = localStorage.getItem(SESSION_KEY)
  if (raw) {
    try {
      const session = JSON.parse(raw)
      if (session.token) {
        config.headers.Authorization = `Bearer ${session.token}`
      }
    } catch {
      // invalid session
    }
  }
  return config
})

api.interceptors.response.use(
  (response) => {
    const data = response.data
    if (data && typeof data === 'object' && 'code' in data) {
      if (data.code !== 0) {
        const error = new Error(data.message || '请求失败') as Error & { code: number; data: unknown }
        error.code = data.code
        error.data = data.data
        throw error
      }
      return { ...response, data: data.data }
    }
    return response
  },
  (error) => {
    if (error.response?.status === 401) {
      localStorage.removeItem(SESSION_KEY)
      const path = window.location.pathname
      const isPublicAuthPage = path === '/login' || path === '/register' || path === '/forgot-password'
      if (!isPublicAuthPage) {
        window.location.href = '/login'
      }
    }
    if (!error.response) {
      const offlineError = new Error('后端未启动或无法访问，请先启动 8080 后端服务') as Error & { code?: number; data?: unknown }
      offlineError.code = 0
      return Promise.reject(offlineError)
    }
    const payload = error.response?.data
    if (payload && typeof payload === 'object' && 'message' in payload) {
      const apiError = new Error(payload.message || '请求失败') as Error & { code?: number; data?: unknown }
      apiError.code = payload.code
      apiError.data = payload.data
      return Promise.reject(apiError)
    }
    return Promise.reject(error)
  }
)

export default api
