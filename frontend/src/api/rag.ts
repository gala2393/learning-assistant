import api from '@/lib/axios'
import { useMutation, useQuery } from '@tanstack/react-query'
import { queryClient } from '@/lib/query-client'
import { hasStoredSession } from '@/lib/auth-gate'
import { SESSION_KEY } from '@/constants'
import type {
  ChatPayload, HistoryItem, RagEvaluationSuiteDetail, RagEvaluationSuitePayload,
  RagEvaluationSuiteResult, RagEvaluationSuiteRun, RagEvaluationSuiteSavePayload,
  RagEvaluationSuiteSummary, StreamChatPayload, SummaryResult, RagSource, RagUsage,
  SummaryType,
} from '@/types'

/**
 * RAG 问答 API 模块 — 封装所有与智能问答相关的接口。
 *
 * 包含：
 * - 普通聊天（POST /rag/chat）
 * - 流式聊天 SSE（POST /rag/chat/stream）— 逐字输出 AI 回答
 * - 问答历史管理（列表、详情、删除、重命名、置顶）
 * - 资料摘要生成和历史
 * - RAG 评估套件管理（CRUD、运行、定时调度）
 * - 推荐问题生成
 * - 使用量查询
 */

const CHAT_HISTORY_CONVERSATION_KEY = 'learning-assistant.chat.history-conversations'

// ===== 普通聊天 =====

/** 普通聊天（非流式） — 一次性返回完整回答 */
export async function chat(payload: ChatPayload): Promise<HistoryItem> {
  const { data } = await api.post('/rag/chat', payload)
  return data
}

/** 获取用户的 RAG 使用量统计（剩余问答次数等） */
export async function getRagUsage(): Promise<RagUsage> {
  const { data } = await api.get('/rag/usage')
  return data
}

/**
 * SSE 流式聊天回调接口 — 定义了流式输出过程中各个阶段的回调函数。
 */
export interface StreamCallbacks {
  onSources?: (sources: RagSource[]) => void    // 收到检索到的资料来源
  onChunk?: (delta: string) => void              // 收到 AI 回答的文本增量
  onStatus?: (status: { stage?: string; message?: string }) => void  // 状态变化（检索中/思考中）
  onDone?: (result: {
    questionId: number | string
    conversationId?: number | string
    answer?: string
    answerIncluded?: boolean
    continuable?: boolean
    continuationHint?: string | null
  }) => void  // 流结束
  onError?: (message: string) => void            // 发生错误
}

/**
 * 流式聊天 — 通过 SSE (Server-Sent Events) 实现逐字输出。
 *
 * 为什么不直接用 Axios？因为 SSE 需要读取 response body 的 ReadableStream，
 * Axios 不支持流式读取，所以用原生 fetch API。
 *
 * SSE 协议格式（每帧由 event + data 组成，帧间用 \n\n 分隔）：
 * ```
 * event: status
 * data: {"stage":"searching"}
 *
 * event: chunk
 * data: {"delta":"TCP"}
 *
 * event: chunk
 * data: {"delta":"是"}
 *
 * event: sources
 * data: {"sources":[...]}
 *
 * event: done
 * data: {"questionId":123,"answer":"完整回答..."}
 * ```
 *
 * @param payload   聊天请求参数（问题、模式、资料ID、历史等）
 * @param callbacks 各阶段的回调函数
 * @returns AbortController，可调用 controller.abort() 取消流
 */
export function chatStream(payload: StreamChatPayload, callbacks: StreamCallbacks): AbortController {
  const controller = new AbortController()
  const base = ((import.meta.env.VITE_API_BASE as string) || '/api').replace(/\/$/, '')
  const session = localStorage.getItem(SESSION_KEY)
  // 流式接口绕过 Axios，需要和普通接口一样从统一 SESSION_KEY 读取 Token。
  const token = session ? JSON.parse(session).token : ''

  fetch(`${base}/rag/chat/stream`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: JSON.stringify(payload),
    signal: controller.signal,
  })
    .then(async (response) => {
      if (!response.ok) {
        callbacks.onError?.(normalizeStreamErrorMessage(await readStreamErrorMessage(response), response.status))
        return
      }
      const reader = response.body?.getReader()
      if (!reader) { callbacks.onError?.('无法读取响应流'); return }

      const decoder = new TextDecoder()
      let buffer = ''           // 缓冲区（处理跨 chunk 的不完整帧）
      let receivedTerminalEvent = false

      // 解析单个 SSE 帧（event + data 组合）
      const processFrame = (frame: string) => {
        let currentEvent = ''
        const dataLines: string[] = []
        for (const rawLine of frame.split('\n')) {
          const line = rawLine.trimEnd()
          if (line.startsWith('event:')) currentEvent = line.slice(6).trim()
          // data 可能跨多行，先收集后用换行拼回 JSON 字符串。
          else if (line.startsWith('data:')) dataLines.push(line.slice(5).trimStart())
        }
        if (!currentEvent || dataLines.length === 0) return
        try {
          const data = JSON.parse(dataLines.join('\n'))
          if (currentEvent === 'sources') callbacks.onSources?.(data.sources || [])
          else if (currentEvent === 'chunk') callbacks.onChunk?.(data.delta || '')
          else if (currentEvent === 'status') callbacks.onStatus?.(data)
          else if (currentEvent === 'done') { receivedTerminalEvent = true; callbacks.onDone?.(data) }
          else if (currentEvent === 'error') { receivedTerminalEvent = true; callbacks.onError?.(normalizeStreamErrorMessage(data.message || '未知错误')) }
        } catch { /* 跳过格式错误的 SSE 数据，不中断整个流 */ }
      }

      // 循环读取流数据
      while (true) {
        const { done, value } = await reader.read()
        if (done) break
        buffer += decoder.decode(value, { stream: true }).replace(/\r\n/g, '\n')
        // SSE 帧以 \n\n 分隔
        let frameEnd = buffer.indexOf('\n\n')
        while (frameEnd >= 0) {
          const frame = buffer.slice(0, frameEnd)
          // 保留未完整到达的尾部数据，下一次 reader.read 后继续拼接。
          buffer = buffer.slice(frameEnd + 2)
          processFrame(frame)
          frameEnd = buffer.indexOf('\n\n')
        }
      }
      // 处理缓冲区中剩余的最后一个帧
      if (buffer.trim()) processFrame(buffer)
      if (!receivedTerminalEvent) callbacks.onError?.('回答连接已中断，请重新提问。')
    })
    .catch((err) => {
      if (err.name !== 'AbortError') callbacks.onError?.(normalizeStreamErrorMessage(err.message || '网络错误'))
    })
  return controller
}

async function readStreamErrorMessage(response: Response) {
  try {
    const data = await response.clone().json()
    return data?.message || data?.error || `请求失败 (${response.status})`
  } catch {
    try {
      const text = await response.text()
      return text.trim() || `请求失败 (${response.status})`
    } catch {
      return `请求失败 (${response.status})`
    }
  }
}

function normalizeStreamErrorMessage(message: string, status?: number) {
  const raw = String(message || '').trim()
  const lower = raw.toLowerCase()
  if (status === 401 || lower.includes('unauthorized')) return '登录状态已失效，请重新登录后再提问。'
  if (status === 429 || lower.includes('rate limit')) return '当前请求过于频繁，请稍后再试。'
  if (status === 502 || status === 503 || lower.includes('502') || lower.includes('503')) {
    return '模型服务暂时不可用，请稍后重试。'
  }
  if (status === 504 || lower.includes('timeout') || lower.includes('timed out') || lower.includes('超时')) {
    return '模型服务响应超时，请缩短问题或稍后重试。'
  }
  if (lower.includes('failed to fetch') || lower.includes('networkerror') || lower.includes('network error')) {
    return '网络连接异常，未能连接到问答服务。'
  }
  return raw || '回答生成失败，请稍后重试。'
}

// ===== 问答历史管理 =====

/** 获取问答历史列表（按对话分组，置顶优先） */
export async function listHistory(): Promise<HistoryItem[]> {
  const { data } = await api.get('/rag/history')
  return groupHistoryByConversation(data || [])
}
/** 获取单条历史详情（含多轮消息） */
export async function getHistory(id: string): Promise<HistoryItem> { const { data } = await api.get(`/rag/history/${id}`); return data }
/** 获取指定资料最近一段边读边问会话；没有历史时返回 null */
export async function getLatestMaterialHistory(materialId: string): Promise<HistoryItem | null> {
  const { data } = await api.get(`/rag/history/materials/${materialId}/latest`)
  return data || null
}
/** 获取指定资料的边读边问历史会话列表 */
export async function listMaterialHistory(materialId: string): Promise<HistoryItem[]> {
  const { data } = await api.get(`/rag/history/materials/${materialId}`)
  return data || []
}
/** 删除单条历史 */
export async function deleteHistory(id: string): Promise<void> { await api.delete(`/rag/history/${id}`) }
/** 重命名历史记录标题 */
export async function renameHistory(id: string, title: string): Promise<HistoryItem> { const { data } = await api.patch(`/rag/history/${id}/title`, { title }); return data }
/** 切换置顶状态 */
export async function togglePinHistory(id: string): Promise<HistoryItem> { const { data } = await api.patch(`/rag/history/${id}/pin`); return data }
/** 清空所有历史 */
export async function clearHistory(): Promise<void> { await api.delete('/rag/history') }

// ===== 资料摘要 =====

/** 为资料生成 AI 摘要；可传入 AbortSignal，让页面上的“暂停生成”真正取消当前请求。 */
export async function summarizeMaterial(
  payload: { materialId: string; summaryType?: SummaryType },
  options?: { signal?: AbortSignal },
): Promise<SummaryResult> {
  const { data } = await api.post('/rag/summarize', payload, { signal: options?.signal })
  return normalizeSummary(data)
}
/** 获取资料的最新摘要 */
export async function getMaterialSummary(materialId: string): Promise<SummaryResult> { const { data } = await api.get(`/rag/summaries/${materialId}`); return normalizeSummary(data) }
/** 获取资料的所有历史摘要版本 */
export async function listMaterialSummaries(materialId: string): Promise<SummaryResult[]> { const { data } = await api.get(`/rag/summaries/${materialId}/history`); return (data || []).map(normalizeSummary) }
/** 更新用户整理版 */
export async function updateSummaryNote(summaryId: string, userNote: string): Promise<SummaryResult> {
  const { data } = await api.patch(`/rag/summaries/${summaryId}/note`, { userNote })
  return normalizeSummary(data)
}

// ===== RAG 评估套件 =====

/** 即时运行评估（不保存套件） */
export async function runEvaluationSuite(payload: RagEvaluationSuitePayload): Promise<RagEvaluationSuiteResult> { const { data } = await api.post('/rag/evaluation-suite', payload); return data }
function normalizeEvaluationSuiteSummary(item: RagEvaluationSuiteSummary): RagEvaluationSuiteSummary { return { ...item, id: String(item.id) } }
function normalizeEvaluationSuiteRun(item: RagEvaluationSuiteRun): RagEvaluationSuiteRun { return { ...item, id: String(item.id), suiteId: String(item.suiteId) } }
function normalizeEvaluationSuiteDetail(item: RagEvaluationSuiteDetail): RagEvaluationSuiteDetail { return { ...item, id: String(item.id), latestRun: item.latestRun ? normalizeEvaluationSuiteRun(item.latestRun) : null } }
function normalizeSummary(item: SummaryResult): SummaryResult {
  return {
    ...item,
    summaryId: String(item.summaryId),
    materialId: String(item.materialId),
    sources: (item.sources || []).map((source) => ({
      ...source,
      materialId: String(source.materialId),
      chunkId: String(source.chunkId),
      pageNo: source.pageNo ?? null,
      chunkIndex: source.chunkIndex ?? null,
    })),
    sections: (item.sections || []).map((section) => ({
      ...section,
      sources: (section.sources || []).map((source) => ({
        ...source,
        materialId: String(source.materialId),
        chunkId: String(source.chunkId),
        pageNo: source.pageNo ?? null,
        chunkIndex: source.chunkIndex ?? null,
      })),
    })),
  }
}
/** 获取已保存的评估套件列表 */
export async function listEvaluationSuites(): Promise<RagEvaluationSuiteSummary[]> { const { data } = await api.get('/rag/evaluation-suites'); return (data || []).map(normalizeEvaluationSuiteSummary) }
/** 获取单个评估套件详情（含用例） */
export async function getEvaluationSuite(id: string): Promise<RagEvaluationSuiteDetail> { const { data } = await api.get(`/rag/evaluation-suites/${id}`); return normalizeEvaluationSuiteDetail(data) }
/** 新建评估套件 */
export async function saveEvaluationSuite(payload: RagEvaluationSuiteSavePayload): Promise<RagEvaluationSuiteDetail> { const { data } = await api.post('/rag/evaluation-suites', payload); return normalizeEvaluationSuiteDetail(data) }
/** 更新评估套件 */
export async function updateEvaluationSuite(id: string, payload: RagEvaluationSuiteSavePayload): Promise<RagEvaluationSuiteDetail> { const { data } = await api.put(`/rag/evaluation-suites/${id}`, payload); return normalizeEvaluationSuiteDetail(data) }
/** 删除评估套件 */
export async function deleteEvaluationSuite(id: string): Promise<void> { await api.delete(`/rag/evaluation-suites/${id}`) }
/** 运行已保存的评估套件 */
export async function runSavedEvaluationSuite(id: string): Promise<RagEvaluationSuiteRun> { const { data } = await api.post(`/rag/evaluation-suites/${id}/runs`); return normalizeEvaluationSuiteRun(data) }
/** 获取评估套件的运行历史 */
export async function listEvaluationSuiteRuns(id: string): Promise<RagEvaluationSuiteRun[]> { const { data } = await api.get(`/rag/evaluation-suites/${id}/runs`); return (data || []).map(normalizeEvaluationSuiteRun) }
/** 更新评估套件的定时调度设置 */
export async function updateEvaluationSuiteSchedule(id: string, payload: { scheduled: boolean; intervalHours: number }): Promise<RagEvaluationSuiteDetail> { const { data } = await api.patch(`/rag/evaluation-suites/${id}/schedule`, payload); return normalizeEvaluationSuiteDetail(data) }

/** 根据当前分块内容生成推荐问题（阅读器中使用） */
export async function suggestQuestions(materialId: string, chunkId?: string): Promise<string[]> {
  const params = new URLSearchParams({ materialId })
  if (chunkId) params.set('chunkId', chunkId)
  const { data } = await api.get(`/rag/suggest-questions?${params}`)
  return data || []
}

// ===== 历史分组逻辑 =====

/**
 * 将历史记录按 conversationId 分组。
 * 同一组对话只保留最新的一条（用于列表展示）。
 * 置顶的对话排在最前面。
 */
function groupHistoryByConversation(items: HistoryItem[]): HistoryItem[] {
  const grouped = new Map<string, HistoryItem>()
  const localConversationMap = readLocalConversationMap()
  for (const item of items) {
    const itemId = String(item.id)
    // 旧记录可能没有 conversationId；前端在流式完成时会额外记录 questionId -> conversationId，
    // 用这个本地映射兜底，避免同一轮对话在历史侧边栏里被拆成多条。
    const key = String(item.conversationId || localConversationMap[itemId] || itemId)
    const current = grouped.get(key)
    // 列表只展示每个会话的最新一条问答；点击后再通过详情接口拿完整 messages。
    if (!current || new Date(item.createdAt).getTime() > new Date(current.createdAt).getTime()) {
      grouped.set(key, { ...item, conversationId: key })
    }
  }
  return Array.from(grouped.values()).sort((left, right) => {
    if (left.pinned !== right.pinned) return left.pinned ? -1 : 1  // 置顶优先
    return new Date(right.createdAt).getTime() - new Date(left.createdAt).getTime()  // 时间倒序
  })
}
function readLocalConversationMap() {
  if (typeof window === 'undefined') return {}
  try { return JSON.parse(localStorage.getItem(CHAT_HISTORY_CONVERSATION_KEY) || '{}') }
  // 本地对话映射损坏时清除，避免历史列表分组一直失败。
  catch { localStorage.removeItem(CHAT_HISTORY_CONVERSATION_KEY); return {} }
}

// ===== React Hooks =====
export function useChat() { return useMutation({ mutationFn: chat, onSuccess: () => {
  // 新问答会新增历史、消耗额度，并影响管理端使用流水。
  queryClient.invalidateQueries({ queryKey: ['history'] }); queryClient.invalidateQueries({ queryKey: ['rag-usage'] }); queryClient.invalidateQueries({ queryKey: ['admin', 'usage-records'] })
} }) }
export function useRagUsage() { return useQuery({ queryKey: ['rag-usage'], queryFn: getRagUsage, enabled: hasStoredSession() }) }
export function useHistory() { return useQuery({ queryKey: ['history'], queryFn: listHistory, enabled: hasStoredSession() }) }
export function useHistoryDetail(id: string | null) { return useQuery({ queryKey: ['history', id], queryFn: () => getHistory(id!), enabled: !!id && hasStoredSession() }) }
export function useLatestMaterialHistory(materialId: string | null) { return useQuery({ queryKey: ['history', 'materials', materialId, 'latest'], queryFn: () => getLatestMaterialHistory(materialId!), enabled: !!materialId && hasStoredSession() }) }
export function useMaterialHistory(materialId: string | null) { return useQuery({ queryKey: ['history', 'materials', materialId], queryFn: () => listMaterialHistory(materialId!), enabled: !!materialId && hasStoredSession() }) }
export function useDeleteHistory() { return useMutation({ mutationFn: deleteHistory, onSuccess: () => queryClient.invalidateQueries({ queryKey: ['history'] }) }) }
export function useRenameHistory() { return useMutation({ mutationFn: ({ id, title }: { id: string; title: string }) => renameHistory(id, title), onSuccess: () => queryClient.invalidateQueries({ queryKey: ['history'] }) }) }
export function useTogglePinHistory() { return useMutation({ mutationFn: togglePinHistory, onSuccess: () => queryClient.invalidateQueries({ queryKey: ['history'] }) }) }
export function useClearHistory() { return useMutation({ mutationFn: clearHistory, onSuccess: () => {
  // 清空历史后收藏列表中可能还有指向历史的问题，需要一并刷新。
  queryClient.invalidateQueries({ queryKey: ['history'] }); queryClient.invalidateQueries({ queryKey: ['favorites'] })
} }) }
export function useSummarizeMaterial() { return useMutation({ mutationFn: (payload: { materialId: string; summaryType?: SummaryType }) => summarizeMaterial(payload), onSuccess: (_d, payload) => { queryClient.invalidateQueries({ queryKey: ['summaries', payload.materialId] }) } }) }
export function useMaterialSummary(materialId: string | null) { return useQuery({ queryKey: ['summaries', materialId], queryFn: () => getMaterialSummary(materialId!), enabled: !!materialId && hasStoredSession() }) }
export function useMaterialSummaryHistory(materialId: string | null) { return useQuery({ queryKey: ['summaries', materialId, 'history'], queryFn: () => listMaterialSummaries(materialId!), enabled: !!materialId && hasStoredSession() }) }
export function useUpdateSummaryNote() { return useMutation({ mutationFn: ({ summaryId, userNote }: { summaryId: string; userNote: string }) => updateSummaryNote(summaryId, userNote), onSuccess: (summary) => {
  queryClient.invalidateQueries({ queryKey: ['summaries', summary.materialId] })
} }) }
export function useRunEvaluationSuite() { return useMutation({ mutationFn: runEvaluationSuite }) }
export function useEvaluationSuites() { return useQuery({ queryKey: ['rag-evaluation-suites'], queryFn: listEvaluationSuites }) }
export function useEvaluationSuiteDetail(id: string | null) { return useQuery({ queryKey: ['rag-evaluation-suites', id], queryFn: () => getEvaluationSuite(id!), enabled: !!id }) }
export function useEvaluationSuiteRuns(id: string | null) { return useQuery({ queryKey: ['rag-evaluation-suites', id, 'runs'], queryFn: () => listEvaluationSuiteRuns(id!), enabled: !!id }) }
export function useSaveEvaluationSuite() { return useMutation({ mutationFn: saveEvaluationSuite, onSuccess: () => queryClient.invalidateQueries({ queryKey: ['rag-evaluation-suites'] }) }) }
export function useUpdateEvaluationSuite() { return useMutation({ mutationFn: ({ id, payload }: { id: string; payload: RagEvaluationSuiteSavePayload }) => updateEvaluationSuite(id, payload), onSuccess: (_d, v) => {
  // 套件更新同时影响列表摘要和当前详情页，两个 queryKey 都要失效。
  queryClient.invalidateQueries({ queryKey: ['rag-evaluation-suites'] }); queryClient.invalidateQueries({ queryKey: ['rag-evaluation-suites', v.id] })
} }) }
export function useDeleteEvaluationSuite() { return useMutation({ mutationFn: deleteEvaluationSuite, onSuccess: () => queryClient.invalidateQueries({ queryKey: ['rag-evaluation-suites'] }) }) }
export function useRunSavedEvaluationSuite() { return useMutation({ mutationFn: runSavedEvaluationSuite, onSuccess: (_d, id) => { queryClient.invalidateQueries({ queryKey: ['rag-evaluation-suites'] }); queryClient.invalidateQueries({ queryKey: ['rag-evaluation-suites', id] }); queryClient.invalidateQueries({ queryKey: ['rag-evaluation-suites', id, 'runs'] }) } }) }
export function useUpdateEvaluationSuiteSchedule() { return useMutation({ mutationFn: ({ id, payload }: { id: string; payload: { scheduled: boolean; intervalHours: number } }) => updateEvaluationSuiteSchedule(id, payload), onSuccess: (_d, v) => { queryClient.invalidateQueries({ queryKey: ['rag-evaluation-suites'] }); queryClient.invalidateQueries({ queryKey: ['rag-evaluation-suites', v.id] }) } }) }
