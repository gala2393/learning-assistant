/**
 * 统一解析前端 API 基础地址。
 *
 * 本地开发允许使用 http://localhost:8080/api；生产构建如果误带入本地地址，
 * 浏览器会访问用户设备自己的 localhost，导致线上登录、流式问答和阅读器资源不可用。
 * 因此生产环境固定回退到同源 /api，避免 .env.local 被误打包到服务器产物。
 */
export function apiBaseUrl() {
  if (import.meta.env.PROD) return '/api'
  const configured = ((import.meta.env.VITE_API_BASE as string | undefined) || '/api').trim()
  const normalized = configured.replace(/\/$/, '') || '/api'
  return isLocalApiBase(normalized) ? '/api' : normalized
}

function isLocalApiBase(value: string) {
  try {
    const url = new URL(value)
    return ['localhost', '127.0.0.1', '::1', '[::1]'].includes(url.hostname)
  } catch {
    return false
  }
}
