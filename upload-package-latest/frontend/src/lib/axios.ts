import axios from 'axios'
import { SESSION_KEY } from '@/constants'

/**
 * Axios 实例 — 封装了请求/响应拦截器，是前端与后端通信的核心。
 *
 * 功能：
 * 1. 请求拦截：自动从 localStorage 读取 Token 并附加到 Authorization 头
 * 2. 响应拦截：自动解包后端的 ApiResponse 格式，只返回 data 部分
 * 3. 401 处理：登录过期时自动清除 session 并跳转到登录页
 * 4. 网络错误处理：后端未启动时显示友好提示
 */

const api = axios.create({
  baseURL: (import.meta.env.VITE_API_BASE || '/api').replace(/\/$/, ''),  // API 基础地址，去掉尾部斜杠
  timeout: 60000,  // 60 秒超时（AI 问答可能需要较长时间）
})

/**
 * 请求拦截器 — 每个请求发出前自动执行。
 * 从 localStorage 中读取保存的登录 session，提取 token 附加到请求头。
 * 这样就不需要在每个 API 调用中手动写 Authorization 头。
 */
api.interceptors.request.use((config) => {
  const raw = localStorage.getItem(SESSION_KEY)
  if (raw) {
    try {
      const session = JSON.parse(raw)
      if (session.token) {
        config.headers.Authorization = `Bearer ${session.token}`
      }
    } catch {
      // session 数据损坏，忽略（后续 401 会处理）
    }
  }
  return config
})

/**
 * 响应拦截器 — 收到响应后自动执行。
 *
 * 成功响应：后端返回格式统一为 {code, message, data}。
 *   - code === 0 → 成功，只返回 data 字段
 *   - code !== 0 → 业务错误，抛出 Error（被 catch 处理）
 *
 * 错误响应：
 *   - 401 → 登录过期，清除 session 并跳转到登录页
 *   - 无 response → 后端未启动或网络断开
 *   - 其他 → 解析后端错误消息并抛出
 */
api.interceptors.response.use(
  (response) => {
    const data = response.data
    // 检查是否是标准 ApiResponse 格式
    if (data && typeof data === 'object' && 'code' in data) {
      if (data.code !== 0) {
        // 业务错误 — 创建 Error 并附带 code 和 data（方便上层做特殊处理）
        const error = new Error(data.message || '请求失败') as Error & { code: number; data: unknown }
        error.code = data.code
        error.data = data.data
        throw error
      }
      // 成功 — 只返回 data 部分，调用方不需要解包
      return { ...response, data: data.data }
    }
    return response  // 非 ApiResponse 格式直接返回（如健康检查接口）
  },
  (error) => {
    // 401 未授权 — 登录过期或 Token 无效
    if (error.response?.status === 401) {
      localStorage.removeItem(SESSION_KEY)
      const path = window.location.pathname
      const isPublicAuthPage = path === '/login' || path === '/register' || path === '/forgot-password'
      if (!isPublicAuthPage) {
        window.location.href = '/login'  // 跳转到登录页
      }
    }
    // 无 response — 后端未启动或网络断开
    if (!error.response) {
      if (error.code === 'ECONNABORTED' || String(error.message || '').includes('timeout')) {
        const timeoutError = new Error('请求处理时间过长；如果是大文件或扫描版 PDF，请切换到资料问答上传，系统会在后台解析并显示进度。') as Error & { code?: number; data?: unknown }
        timeoutError.code = 0
        return Promise.reject(timeoutError)
      }
      const offlineError = new Error('后端未启动或无法访问，请先启动 8080 后端服务') as Error & { code?: number; data?: unknown }
      offlineError.code = 0
      return Promise.reject(offlineError)
    }
    // 有响应体的错误 — 解析后端返回的错误信息
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
