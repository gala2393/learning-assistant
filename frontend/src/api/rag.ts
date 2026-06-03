import api from '@/lib/axios'
import { useMutation, useQuery } from '@tanstack/react-query'
import { queryClient } from '@/lib/query-client'
import type {
  ChatPayload,
  HistoryItem,
  RagEvaluationSuiteDetail,
  RagEvaluationSuitePayload,
  RagEvaluationSuiteResult,
  RagEvaluationSuiteRun,
  RagEvaluationSuiteSavePayload,
  RagEvaluationSuiteSummary,
  StreamChatPayload,
  SummaryResult,
  RagSource,
  RagUsage,
} from '@/types'

const CHAT_HISTORY_CONVERSATION_KEY = 'learning-assistant.chat.history-conversations'

export async function chat(payload: ChatPayload): Promise<HistoryItem> {
  const { data } = await api.post('/rag/chat', payload)
  return data
}

export async function getRagUsage(): Promise<RagUsage> {
  const { data } = await api.get('/rag/usage')
  return data
}

export interface StreamCallbacks {
  onSources?: (sources: RagSource[]) => void
  onChunk?: (delta: string) => void
  onStatus?: (status: { stage?: string; message?: string }) => void
  onDone?: (result: { questionId: number | string; conversationId?: number | string; answer: string }) => void
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
      let receivedTerminalEvent = false

      const processFrame = (frame: string) => {
        let currentEvent = ''
        const dataLines: string[] = []

        for (const rawLine of frame.split('\n')) {
          const line = rawLine.trimEnd()
          if (line.startsWith('event:')) {
            currentEvent = line.slice(6).trim()
          } else if (line.startsWith('data:')) {
            dataLines.push(line.slice(5).trimStart())
          }
        }

        if (!currentEvent || dataLines.length === 0) return

        try {
          const data = JSON.parse(dataLines.join('\n'))
          if (currentEvent === 'sources') {
            callbacks.onSources?.(data.sources || [])
          } else if (currentEvent === 'chunk') {
            callbacks.onChunk?.(data.delta || '')
          } else if (currentEvent === 'status') {
            callbacks.onStatus?.(data)
          } else if (currentEvent === 'done') {
            receivedTerminalEvent = true
            callbacks.onDone?.(data)
          } else if (currentEvent === 'error') {
            receivedTerminalEvent = true
            callbacks.onError?.(data.message || '未知错误')
          }
        } catch {
          // Skip malformed SSE data instead of breaking the whole stream.
        }
      }

      while (true) {
        const { done, value } = await reader.read()
        if (done) break

        buffer += decoder.decode(value, { stream: true }).replace(/\r\n/g, '\n')
        let frameEnd = buffer.indexOf('\n\n')
        while (frameEnd >= 0) {
          const frame = buffer.slice(0, frameEnd)
          buffer = buffer.slice(frameEnd + 2)
          processFrame(frame)
          frameEnd = buffer.indexOf('\n\n')
        }

      }

      if (buffer.trim()) {
        processFrame(buffer)
      }

      if (!receivedTerminalEvent) {
        callbacks.onError?.('回答已中断，请重试')
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
  return groupHistoryByConversation(data || [])
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

export async function runEvaluationSuite(payload: RagEvaluationSuitePayload): Promise<RagEvaluationSuiteResult> {
  const { data } = await api.post('/rag/evaluation-suite', payload)
  return data
}

function normalizeEvaluationSuiteSummary(item: RagEvaluationSuiteSummary): RagEvaluationSuiteSummary {
  return {
    ...item,
    id: String(item.id),
  }
}

function normalizeEvaluationSuiteRun(item: RagEvaluationSuiteRun): RagEvaluationSuiteRun {
  return {
    ...item,
    id: String(item.id),
    suiteId: String(item.suiteId),
  }
}

function normalizeEvaluationSuiteDetail(item: RagEvaluationSuiteDetail): RagEvaluationSuiteDetail {
  return {
    ...item,
    id: String(item.id),
    latestRun: item.latestRun ? normalizeEvaluationSuiteRun(item.latestRun) : null,
  }
}

export async function listEvaluationSuites(): Promise<RagEvaluationSuiteSummary[]> {
  const { data } = await api.get('/rag/evaluation-suites')
  return (data || []).map(normalizeEvaluationSuiteSummary)
}

export async function getEvaluationSuite(id: string): Promise<RagEvaluationSuiteDetail> {
  const { data } = await api.get(`/rag/evaluation-suites/${id}`)
  return normalizeEvaluationSuiteDetail(data)
}

export async function saveEvaluationSuite(payload: RagEvaluationSuiteSavePayload): Promise<RagEvaluationSuiteDetail> {
  const { data } = await api.post('/rag/evaluation-suites', payload)
  return normalizeEvaluationSuiteDetail(data)
}

export async function updateEvaluationSuite(id: string, payload: RagEvaluationSuiteSavePayload): Promise<RagEvaluationSuiteDetail> {
  const { data } = await api.put(`/rag/evaluation-suites/${id}`, payload)
  return normalizeEvaluationSuiteDetail(data)
}

export async function deleteEvaluationSuite(id: string): Promise<void> {
  await api.delete(`/rag/evaluation-suites/${id}`)
}

export async function runSavedEvaluationSuite(id: string): Promise<RagEvaluationSuiteRun> {
  const { data } = await api.post(`/rag/evaluation-suites/${id}/runs`)
  return normalizeEvaluationSuiteRun(data)
}

export async function listEvaluationSuiteRuns(id: string): Promise<RagEvaluationSuiteRun[]> {
  const { data } = await api.get(`/rag/evaluation-suites/${id}/runs`)
  return (data || []).map(normalizeEvaluationSuiteRun)
}

export async function updateEvaluationSuiteSchedule(
  id: string,
  payload: { scheduled: boolean; intervalHours: number },
): Promise<RagEvaluationSuiteDetail> {
  const { data } = await api.patch(`/rag/evaluation-suites/${id}/schedule`, payload)
  return normalizeEvaluationSuiteDetail(data)
}

export async function suggestQuestions(materialId: string, chunkId?: string): Promise<string[]> {
  const params = new URLSearchParams({ materialId })
  if (chunkId) params.set('chunkId', chunkId)
  const { data } = await api.get(`/rag/suggest-questions?${params}`)
  return data || []
}

export function useChat() {
  return useMutation({
    mutationFn: chat,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['history'] })
      queryClient.invalidateQueries({ queryKey: ['rag-usage'] })
      queryClient.invalidateQueries({ queryKey: ['admin', 'usage-records'] })
    },
  })
}

export function useRagUsage() {
  return useQuery({
    queryKey: ['rag-usage'],
    queryFn: getRagUsage,
  })
}

export function useHistory() {
  return useQuery({
    queryKey: ['history'],
    queryFn: listHistory,
  })
}

function groupHistoryByConversation(items: HistoryItem[]): HistoryItem[] {
  const grouped = new Map<string, HistoryItem>()
  const localConversationMap = readLocalConversationMap()

  for (const item of items) {
    const itemId = String(item.id)
    const key = String(item.conversationId || localConversationMap[itemId] || itemId)
    const current = grouped.get(key)
    if (!current || new Date(item.createdAt).getTime() > new Date(current.createdAt).getTime()) {
      grouped.set(key, { ...item, conversationId: key })
    }
  }

  return Array.from(grouped.values()).sort((left, right) => {
    if (left.pinned !== right.pinned) return left.pinned ? -1 : 1
    return new Date(right.createdAt).getTime() - new Date(left.createdAt).getTime()
  })
}

function readLocalConversationMap(): Record<string, string> {
  if (typeof window === 'undefined') return {}
  try {
    return JSON.parse(localStorage.getItem(CHAT_HISTORY_CONVERSATION_KEY) || '{}')
  } catch {
    localStorage.removeItem(CHAT_HISTORY_CONVERSATION_KEY)
    return {}
  }
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

export function useRunEvaluationSuite() {
  return useMutation({ mutationFn: runEvaluationSuite })
}

export function useEvaluationSuites() {
  return useQuery({
    queryKey: ['rag-evaluation-suites'],
    queryFn: listEvaluationSuites,
  })
}

export function useEvaluationSuiteDetail(id: string | null) {
  return useQuery({
    queryKey: ['rag-evaluation-suites', id],
    queryFn: () => getEvaluationSuite(id!),
    enabled: !!id,
  })
}

export function useEvaluationSuiteRuns(id: string | null) {
  return useQuery({
    queryKey: ['rag-evaluation-suites', id, 'runs'],
    queryFn: () => listEvaluationSuiteRuns(id!),
    enabled: !!id,
  })
}

export function useSaveEvaluationSuite() {
  return useMutation({
    mutationFn: saveEvaluationSuite,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['rag-evaluation-suites'] }),
  })
}

export function useUpdateEvaluationSuite() {
  return useMutation({
    mutationFn: ({ id, payload }: { id: string; payload: RagEvaluationSuiteSavePayload }) => updateEvaluationSuite(id, payload),
    onSuccess: (_data, variables) => {
      queryClient.invalidateQueries({ queryKey: ['rag-evaluation-suites'] })
      queryClient.invalidateQueries({ queryKey: ['rag-evaluation-suites', variables.id] })
    },
  })
}

export function useDeleteEvaluationSuite() {
  return useMutation({
    mutationFn: deleteEvaluationSuite,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['rag-evaluation-suites'] }),
  })
}

export function useRunSavedEvaluationSuite() {
  return useMutation({
    mutationFn: runSavedEvaluationSuite,
    onSuccess: (_data, id) => {
      queryClient.invalidateQueries({ queryKey: ['rag-evaluation-suites'] })
      queryClient.invalidateQueries({ queryKey: ['rag-evaluation-suites', id] })
      queryClient.invalidateQueries({ queryKey: ['rag-evaluation-suites', id, 'runs'] })
    },
  })
}

export function useUpdateEvaluationSuiteSchedule() {
  return useMutation({
    mutationFn: ({ id, payload }: { id: string; payload: { scheduled: boolean; intervalHours: number } }) =>
      updateEvaluationSuiteSchedule(id, payload),
    onSuccess: (_data, variables) => {
      queryClient.invalidateQueries({ queryKey: ['rag-evaluation-suites'] })
      queryClient.invalidateQueries({ queryKey: ['rag-evaluation-suites', variables.id] })
    },
  })
}
