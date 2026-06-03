import api from '@/lib/axios'
import { useMutation, useQuery } from '@tanstack/react-query'
import { queryClient } from '@/lib/query-client'
import type { UserLlmConfig, UserLlmConfigPayload, UserLlmTestResult } from '@/types'

export async function getUserLlmConfig(): Promise<UserLlmConfig> {
  const { data } = await api.get('/llm/user-config')
  return normalizeConfig(data)
}

export async function saveUserLlmConfig(payload: UserLlmConfigPayload): Promise<UserLlmConfig> {
  const { data } = await api.put('/llm/user-config', payload)
  return normalizeConfig(data)
}

export async function testUserLlmConfig(payload: UserLlmConfigPayload): Promise<UserLlmTestResult> {
  const { data } = await api.post('/llm/user-config/test', payload)
  return data
}

export async function deleteUserLlmConfig(id: string | number): Promise<UserLlmConfig> {
  const { data } = await api.delete(`/llm/user-config/${id}`)
  return normalizeConfig(data)
}

export function useUserLlmConfig() {
  return useQuery({
    queryKey: ['llm', 'user-config'],
    queryFn: getUserLlmConfig,
  })
}

export function useSaveUserLlmConfig() {
  return useMutation({
    mutationFn: saveUserLlmConfig,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['llm', 'user-config'] })
      queryClient.invalidateQueries({ queryKey: ['rag-usage'] })
    },
  })
}

export function useTestUserLlmConfig() {
  return useMutation({ mutationFn: testUserLlmConfig })
}

export function useDeleteUserLlmConfig() {
  return useMutation({
    mutationFn: deleteUserLlmConfig,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['llm', 'user-config'] })
      queryClient.invalidateQueries({ queryKey: ['rag-usage'] })
    },
  })
}

function normalizeConfig(config: UserLlmConfig): UserLlmConfig {
  return {
    ...config,
    activeConfigId: config.activeConfigId == null ? null : String(config.activeConfigId),
    configs: (config.configs || []).map((item) => ({ ...item, id: String(item.id) })),
  }
}
