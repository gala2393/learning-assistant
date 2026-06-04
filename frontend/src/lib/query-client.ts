import QueryClient from '@tanstack/react-query'

/**
 * React Query 客户端配置 — 管理所有 API 请求的缓存和状态。
 *
 * React Query 的核心价值：
 * - 自动缓存：同一个请求在 staleTime 内不会重复发
 * - 自动重试：请求失败后自动重试 1 次
 * - 后台刷新：不打扰用户的情况下静默更新数据
 * - 统一管理：用 useQuery/useMutation 就能处理加载、错误、成功状态
 */
export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 30_000,         // 30 秒内的缓存数据视为"新鲜"，不会重新请求
      retry: 1,                  // 失败后重试 1 次（总共发 2 次请求）
      refetchOnWindowFocus: false, // 切换浏览器标签页时不自动刷新（避免不必要的请求）
    },
  },
})
