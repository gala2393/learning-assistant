import api from '@/lib/axios'
import { useMutation, useQuery } from '@tanstack/react-query'
import { queryClient } from '@/lib/query-client'
import type { ChatPayload, HistoryItem, StreamChatPayload, SummaryResult, RagSource } from '@/types'

export async function chat(payload: ChatPayload): Promise<HistoryItem> {
  const { data } = await api.post('/rag/chat', payload)
  return data
}

export interface StreamCallbacks {
  onSources?: (sources: RagSource[]) => void
  onChunk?: (delta: string) => void
  onDone?: (result: { questionId: number; answer: string }) => void
  onError?: (message: string) => void
}

export function chatStream(
  payload: StreamChatPayload,
  callbacks: StreamCallbacks,
): AbortController {
  const controller = new AbortController()
  const base = (import.meta.env.VITE_API_BASE as string) || '/api'

  const session = localStorage.getItem('learning-assistant.frontend.session')
  const token = session ? JSON.parse(session).token : ''

  fetch(`${base}/rag/chat/stream`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: JSON.stringify(payload),
    signal: controller.signal,
  })
    .then(async (response) => {
      if (!response.ok) {
        callbacks.onError?.(`请求失败 (${response.status})`)
        return
      }
      const reader = response.body?.getReader()
      if (!reader) {
        callbacks.onError?.('无法读取响应流')
        return
      }
      const decoder = new TextDecoder()
      let buffer = ''

      while (true) {
        const { done, value } = await reader.read()
        if (done) break
        buffer += decoder.decode(value, { stream: true })

        const lines = buffer.split('\n')
        buffer = lines.pop() || ''

        let currentEvent = ''
        for (const line of lines) {
          if (line.startsWith('event:')) {
            currentEvent = line.slice(6).trim()
          } else if (line.startsWith('data:')) {
            const jsonStr = line.slice(5).trim()
            if (!jsonStr) continue
            try {
              const data = JSON.parse(jsonStr)
              if (currentEvent === 'sources') {
                callbacks.onSources?.(data.sources || [])
              } else if (currentEvent === 'chunk') {
                callbacks.onChunk?.(data.delta || '')
              } else if (currentEvent === 'done') {
                callbacks.onDone?.(data)
              } else if (currentEvent === 'error') {
                callbacks.onError?.(data.message || '未知错误')
              }
            } catch {
              // skip malformed JSON
            }
            currentEvent = ''
          }
        }
      }
    })
    .catch((err) => {
      if (err.name !== 'AbortError') {
        callbacks.onError?.(err.message || '网络错误')
      }
    })

  return controller
}

export async function listHistory(): Promise<HistoryItem[]> {
  const { data } = await api.get('/rag/history')
  return data
}

export async function getHistory(id: string): Promise<HistoryItem> {
  const { data } = await api.get(`/rag/history/${id}`)
  return data
}

export async function deleteHistory(id: string): Promise<void> {
  await api.delete(`/rag/history/${id}`)
}

export async function renameHistory(id: string, title: string): Promise<HistoryItem> {
  const { data } = await api.patch(`/rag/history/${id}/title`, { title })
  return data
}

export async function togglePinHistory(id: string): Promise<HistoryItem> {
  const { data } = await api.patch(`/rag/history/${id}/pin`)
  return data
}

export async function clearHistory(): Promise<void> {
  await api.delete('/rag/history')
}

export async function summarizeMaterial(materialId: string): Promise<SummaryResult> {
  const { data } = await api.post('/rag/summarize', { materialId })
  return data
}

export async function getMaterialSummary(materialId: string): Promise<SummaryResult> {
  const { data } = await api.get(`/rag/summaries/${materialId}`)
  return data
}

export async function listMaterialSummaries(materialId: string): Promise<SummaryResult[]> {
  const { data } = await api.get(`/rag/summaries/${materialId}/history`)
  return data
}

export async function suggestQuestions(materialId: string, chunkId?: string): Promise<string[]> {
  const params = new URLSearchParams({ materialId })
  if (chunkId) params.set('chunkId', chunkId)
  const { data } = await api.get(`/rag/suggest-questions?${params}`)
  return data || []
}

export function useChat() {
  return useMutation({ mutationFn: chat })
}

export function useHistory() {
  return useQuery({
    queryKey: ['history'],
    queryFn: listHistory,
  })
}

export function useHistoryDetail(id: string | null) {
  return useQuery({
    queryKey: ['history', id],
    queryFn: () => getHistory(id!),
    enabled: !!id,
  })
}

export function useDeleteHistory() {
  return useMutation({
    mutationFn: deleteHistory,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['history'] }),
  })
}

export function useRenameHistory() {
  return useMutation({
    mutationFn: ({ id, title }: { id: string; title: string }) => renameHistory(id, title),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['history'] }),
  })
}

export function useTogglePinHistory() {
  return useMutation({
    mutationFn: togglePinHistory,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['history'] }),
  })
}

export function useClearHistory() {
  return useMutation({
    mutationFn: clearHistory,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['history'] })
      queryClient.invalidateQueries({ queryKey: ['favorites'] })
    },
  })
}

export function useSummarizeMaterial() {
  return useMutation({
    mutationFn: summarizeMaterial,
    onSuccess: (_data, materialId) => {
      queryClient.invalidateQueries({ queryKey: ['summaries', materialId] })
    },
  })
}

export function useMaterialSummary(materialId: string | null) {
  return useQuery({
    queryKey: ['summaries', materialId],
    queryFn: () => getMaterialSummary(materialId!),
    enabled: !!materialId,
  })
}

export function useMaterialSummaryHistory(materialId: string | null) {
  return useQuery({
    queryKey: ['summaries', materialId, 'history'],
    queryFn: () => listMaterialSummaries(materialId!),
    enabled: !!materialId,
  })
}
