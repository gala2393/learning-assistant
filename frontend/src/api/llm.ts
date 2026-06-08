import api from '@/lib/axios'
import { useMutation, useQuery } from '@tanstack/react-query'
import { queryClient } from '@/lib/query-client'
import { hasStoredSession } from '@/lib/auth-gate'
import type { UserLlmConfig, UserLlmConfigPayload, UserLlmTestResult } from '@/types'

/**
 * LLM 配置 API — 用户自定义大模型配置的 CRUD 和连接测试。
 *
 * 用户可以保存多套自定义 LLM 配置（如 DeepSeek、Moonshot 等），
 * 在聊天时自由切换使用哪个模型。
 *
 * 后端接口映射：
 * - GET    /api/llm/user-config          → 获取所有配置
 * - PUT    /api/llm/user-config          → 新增或更新配置
 * - POST   /api/llm/user-config/test     → 测试连接
 * - DELETE /api/llm/user-config/{id}     → 删除指定配置
 */

/** 获取当前用户的所有 LLM 配置（含激活状态、是否有 API Key 等） */
export async function getUserLlmConfig(): Promise<UserLlmConfig> {
  const { data } = await api.get('/llm/user-config')
  return normalizeConfig(data)
}

/** 新增或更新 LLM 配置 — 后端自动处理"新增 vs 更新"逻辑 */
export async function saveUserLlmConfig(payload: UserLlmConfigPayload): Promise<UserLlmConfig> {
  const { data } = await api.put('/llm/user-config', payload)
  return normalizeConfig(data)
}

/** 测试 LLM 配置连接 — 发送一个简单请求验证 baseUrl/apiKey/model 是否正确 */
export async function testUserLlmConfig(payload: UserLlmConfigPayload): Promise<UserLlmTestResult> {
  const { data } = await api.post('/llm/user-config/test', payload)
  return data
}

/** 删除指定的 LLM 配置 */
export async function deleteUserLlmConfig(id: string | number): Promise<UserLlmConfig> {
  const { data } = await api.delete(`/llm/user-config/${id}`)
  return normalizeConfig(data)
}

/** 获取 LLM 配置 query */
export function useUserLlmConfig() {
  return useQuery({ queryKey: ['llm', 'user-config'], queryFn: getUserLlmConfig, enabled: hasStoredSession() })
}

/** 保存 LLM 配置 mutation — 保存后刷新配置列表和使用统计 */
export function useSaveUserLlmConfig() {
  return useMutation({ mutationFn: saveUserLlmConfig, onSuccess: () => {
    queryClient.invalidateQueries({ queryKey: ['llm', 'user-config'] })
    queryClient.invalidateQueries({ queryKey: ['rag-usage'] })
  } })
}

/** 测试 LLM 配置 mutation */
export function useTestUserLlmConfig() {
  return useMutation({ mutationFn: testUserLlmConfig })
}

/** 删除 LLM 配置 mutation */
export function useDeleteUserLlmConfig() {
  return useMutation({ mutationFn: deleteUserLlmConfig, onSuccess: () => {
    queryClient.invalidateQueries({ queryKey: ['llm', 'user-config'] })
    queryClient.invalidateQueries({ queryKey: ['rag-usage'] })
  } })
}

/** 标准化配置数据 — 确保 ID 为字符串类型 */
function normalizeConfig(config: UserLlmConfig): UserLlmConfig {
  return {
    ...config,
    activeConfigId: config.activeConfigId == null ? null : String(config.activeConfigId),
    configs: (config.configs || []).map((item) => ({ ...item, id: String(item.id) })),
  }
}
