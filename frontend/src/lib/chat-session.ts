import { chatStream, getHistory } from '@/api/rag'
import { queryClient } from '@/lib/query-client'
import { sanitizeAiText } from '@/lib/utils'
import type { ChatMessage } from '@/components/workspace/ChatThread'
import type { ChatImagePayload, HistoryItem, RagSource } from '@/types'

export const CHAT_DRAFT_KEY = 'learning-assistant.chat.current'
const CHAT_DRAFT_BACKUP_KEY = 'learning-assistant.chat.current.backup'
export const CHAT_HISTORY_CONVERSATION_KEY = 'learning-assistant.chat.history-conversations'
const CHAT_CONVERSATION_ARCHIVE_KEY = 'learning-assistant.chat.conversation-archive'

type ChatMode = 'GENERAL' | 'MATERIAL'
type ConversationMessage = { role: string; content: string }

export interface ChatSessionSnapshot {
  selectedHistoryId: string | null
  conversationId: string | null
  currentQuestionId: string | null
  mode: ChatMode
  input: string
  materialId: string | null
  chunkId: string | null
  messages: ChatMessage[]
  conversationHistory: ConversationMessage[]
  streaming: boolean
  images: ChatImagePayload[]
}

interface PersistedChatDraft {
  lastQuestionId?: string | null
  historyId?: string | null
  conversationId?: string | null
  mode: ChatMode
  materialId: string | null
  chunkId: string | null
  messages: ChatMessage[]
  conversationHistory: ConversationMessage[]
  images?: ChatImagePayload[]
}

interface ConversationArchiveItem {
  conversationId: string
  currentQuestionId: string | null
  mode: ChatMode
  materialId: string | null
  chunkId: string | null
  messages: ChatMessage[]
  conversationHistory: ConversationMessage[]
  images?: ChatImagePayload[]
}

const defaultState: ChatSessionSnapshot = {
  selectedHistoryId: null,
  conversationId: null,
  currentQuestionId: null,
  mode: 'GENERAL',
  input: '',
  materialId: null,
  chunkId: null,
  messages: [],
  conversationHistory: [],
  streaming: false,
  images: [],
}

let activeController: AbortController | null = null
let activeRunId: string | null = null
const listeners = new Set<() => void>()

let state: ChatSessionSnapshot = restoreSnapshot()

export function getChatSessionSnapshot() {
  return state
}

export function subscribeChatSession(listener: () => void) {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

export function updateChatSession(
  updater: Partial<ChatSessionSnapshot> | ((current: ChatSessionSnapshot) => ChatSessionSnapshot),
) {
  state = typeof updater === 'function' ? updater(state) : { ...state, ...updater }
  persistSnapshot()
  notify()
}

export function resetChatSession(options?: {
  mode?: ChatMode
  materialId?: string | null
  chunkId?: string | null
  images?: ChatImagePayload[]
  abortActive?: boolean
}) {
  if (options?.abortActive !== false) {
    abortActiveStream()
  }
  state = {
    ...defaultState,
    mode: options?.mode || defaultState.mode,
    materialId: options?.materialId ?? null,
    chunkId: options?.chunkId ?? null,
    images: options?.images ?? [],
  }
  persistSnapshot()
  notify()
}

export async function selectHistorySession(item: HistoryItem) {
  abortActiveStream()
  const detail = await getHistory(String(item.id)).catch(() => item)
  applyHistorySession(detail)
}

export function applyHistorySession(item: HistoryItem) {
  const source = item.sources?.[0]
  const conversationId = String(item.conversationId || readHistoryConversationId(String(item.id)) || item.id)
  const archived = readConversationArchive(conversationId)
  const detailMessages = item.messages?.length
    ? item.messages.map((message, index) => ({
      id: `${message.id}-${message.role}-${index}`,
      role: message.role,
      text: message.text,
      sources: index === item.messages!.length - 1 && message.role === 'assistant' ? item.sources : undefined,
    }))
    : []
  const restoredMessages: ChatMessage[] = archived && archived.messages.length > detailMessages.length
    ? archived.messages
    : detailMessages.length
      ? detailMessages
    : [
      { id: item.id + '-user', role: 'user', text: item.question },
      { id: item.id + '-assistant', role: 'assistant', text: item.answer, sources: item.sources },
    ]
  const restoredHistory = restoredMessages
    .filter((message) => !message.error && message.text.trim())
    .map((message) => ({ role: message.role, content: message.text }))

  state = {
    ...state,
    selectedHistoryId: String(item.id),
    conversationId,
    currentQuestionId: null,
    mode: archived?.mode || (source ? 'MATERIAL' : 'GENERAL'),
    input: '',
    materialId: archived?.materialId ?? source?.materialId ?? null,
    chunkId: archived?.chunkId ?? source?.chunkId ?? null,
    messages: restoredMessages,
    conversationHistory: archived?.conversationHistory?.length ? archived.conversationHistory : restoredHistory,
    streaming: false,
  }
  persistSnapshot()
  notify()
}

export function startChatSessionStream(params: {
  question: string
  mode: ChatMode
  materialId: string | null
  chunkId: string | null
  images?: ChatImagePayload[]
}) {
  if (state.streaming) return

  const requestImages = params.images || state.images || []
  const userMsg: ChatMessage = { id: 'pending-user-' + Date.now(), role: 'user', text: params.question, images: requestImages }
  const assistantId = 'pending-assistant-' + Date.now()
  const thinkingMsg: ChatMessage = { id: assistantId, role: 'assistant', text: '', thinking: true }
  const historyBefore = state.conversationHistory
  const conversationIdBefore = state.conversationId
  const runId = `${Date.now()}-${Math.random().toString(36).slice(2)}`
  let answer = ''
  let firstChunk = true
  let sources: RagSource[] = []

  activeRunId = runId
  state = {
    ...state,
    selectedHistoryId: null,
    conversationId: conversationIdBefore,
    currentQuestionId: null,
    mode: params.mode,
    input: '',
    materialId: params.mode === 'MATERIAL' ? params.materialId : null,
    chunkId: params.mode === 'MATERIAL' ? params.chunkId : null,
    images: [],
    messages: state.messages.concat(userMsg, thinkingMsg),
    conversationHistory: historyBefore,
    streaming: true,
  }
  persistSnapshot()
  notify()

  activeController = chatStream(
    {
      question: params.question,
      mode: params.mode,
      materialId: params.mode === 'MATERIAL' ? (params.materialId || undefined) : undefined,
      chunkId: params.mode === 'MATERIAL' ? (params.chunkId || undefined) : undefined,
      history: historyBefore,
      conversationId: conversationIdBefore,
      images: requestImages,
    },
    {
      onStatus: (status) => {
        if (activeRunId !== runId || answer.trim()) return
        state = {
          ...state,
          messages: state.messages.map((message) =>
            message.id === assistantId
              ? { ...message, thinking: false, text: streamStatusText(status) }
              : message,
          ),
        }
        persistSnapshot()
        notify()
      },
      onChunk: (delta) => {
        if (activeRunId !== runId) return
        answer += delta
        const cleanText = sanitizeAiText(answer)
        state = {
          ...state,
          messages: state.messages.map((message) =>
            message.id === assistantId
              ? { ...message, thinking: firstChunk ? false : message.thinking, text: cleanText }
              : message,
          ),
        }
        firstChunk = false
        persistSnapshot()
        notify()
      },
      onSources: (nextSources) => {
        if (activeRunId !== runId) return
        sources = nextSources
        state = {
          ...state,
          messages: state.messages.map((message) => (message.id === assistantId ? { ...message, sources } : message)),
        }
        persistSnapshot()
        notify()
      },
      onDone: (result) => {
        if (activeRunId !== runId) return
        const questionId = String(result.questionId)
        const conversationId = String(result.conversationId || state.conversationId || questionId)
        const cleanAnswer = sanitizeAiText(result.answer)
        const nextConversationHistory = [
          ...historyBefore,
          { role: 'user', content: params.question },
          { role: 'assistant', content: cleanAnswer },
        ].slice(-10)

        activeRunId = null
        activeController = null
        state = {
          ...state,
          conversationId,
          currentQuestionId: questionId,
          messages: state.messages.map((message) =>
            message.id === assistantId
              ? { ...message, id: questionId, thinking: false, text: cleanAnswer, sources }
              : message,
          ),
          conversationHistory: nextConversationHistory,
          streaming: false,
        }
        persistSnapshot()
        rememberHistoryConversation(questionId, conversationId)
        rememberConversationArchive()
        notify()
        queryClient.invalidateQueries({ queryKey: ['history'] })
        queryClient.invalidateQueries({ queryKey: ['rag-usage'] })
        queryClient.invalidateQueries({ queryKey: ['admin', 'usage-records'] })
      },
      onError: (message) => {
        if (activeRunId !== runId) return
        activeRunId = null
        activeController = null
        state = {
          ...state,
          messages: state.messages.map((chatMessage) =>
            chatMessage.id === assistantId
              ? { ...chatMessage, thinking: false, error: message, text: '' }
              : chatMessage,
          ),
          streaming: false,
        }
        persistSnapshot()
        notify()
        queryClient.invalidateQueries({ queryKey: ['history'] })
        queryClient.invalidateQueries({ queryKey: ['rag-usage'] })
        queryClient.invalidateQueries({ queryKey: ['admin', 'usage-records'] })
      },
    },
  )
}

function streamStatusText(status: { stage?: string; message?: string }) {
  if (status.message?.trim()) return status.message.trim()
  if (status.stage === 'searching') return '正在检索相关资料...'
  if (status.stage === 'thinking') return '正在准备回答...'
  return '正在准备回答...'
}

function abortActiveStream() {
  activeRunId = null
  activeController?.abort()
  activeController = null
}

function restoreSnapshot(): ChatSessionSnapshot {
  if (typeof window === 'undefined') return defaultState
  try {
    const draft = JSON.parse(
      sessionStorage.getItem(CHAT_DRAFT_KEY) || localStorage.getItem(CHAT_DRAFT_BACKUP_KEY) || 'null',
    ) as PersistedChatDraft | null
    if (!draft?.messages?.length) return defaultState
    return {
      ...defaultState,
      currentQuestionId: draft.lastQuestionId || (draft.historyId !== 'pending' ? draft.historyId || null : null),
      conversationId: draft.conversationId || draft.lastQuestionId || (draft.historyId !== 'pending' ? draft.historyId || null : null),
      mode: draft.mode,
      materialId: draft.materialId,
      chunkId: draft.chunkId,
      messages: draft.messages.map((message) => {
        if (!message.thinking) return message
        if (message.text.trim()) {
          return { ...message, thinking: false, text: message.text.trim() + '\n\nStream interrupted by page refresh. Partial answer restored.' }
        }
        return { ...message, thinking: false, error: 'Stream interrupted by page refresh. Please send the question again.', text: '' }
      }),
      conversationHistory: draft.conversationHistory || [],
      images: draft.images || [],
    }
  } catch {
    sessionStorage.removeItem(CHAT_DRAFT_KEY)
    localStorage.removeItem(CHAT_DRAFT_BACKUP_KEY)
    return defaultState
  }
}

function persistSnapshot() {
  if (typeof window === 'undefined') return
  if (state.messages.length === 0) {
    sessionStorage.removeItem(CHAT_DRAFT_KEY)
    localStorage.removeItem(CHAT_DRAFT_BACKUP_KEY)
    return
  }
  const draft: PersistedChatDraft = {
    lastQuestionId: state.currentQuestionId,
    conversationId: state.conversationId,
    mode: state.mode,
    materialId: state.mode === 'MATERIAL' ? state.materialId : null,
    chunkId: state.mode === 'MATERIAL' ? state.chunkId : null,
    messages: state.messages,
    conversationHistory: state.conversationHistory,
    images: state.images,
  }
  sessionStorage.setItem(CHAT_DRAFT_KEY, JSON.stringify(draft))
  localStorage.setItem(CHAT_DRAFT_BACKUP_KEY, JSON.stringify(draft))
}

function rememberHistoryConversation(questionId: string, conversationId: string) {
  if (typeof window === 'undefined') return
  try {
    const raw = localStorage.getItem(CHAT_HISTORY_CONVERSATION_KEY)
    const map = raw ? JSON.parse(raw) as Record<string, string> : {}
    map[questionId] = conversationId
    map[conversationId] = conversationId
    localStorage.setItem(CHAT_HISTORY_CONVERSATION_KEY, JSON.stringify(map))
  } catch {
    localStorage.removeItem(CHAT_HISTORY_CONVERSATION_KEY)
  }
}

function readHistoryConversationId(questionId: string) {
  if (typeof window === 'undefined') return null
  try {
    const raw = localStorage.getItem(CHAT_HISTORY_CONVERSATION_KEY)
    const map = raw ? JSON.parse(raw) as Record<string, string> : {}
    return map[questionId] || null
  } catch {
    localStorage.removeItem(CHAT_HISTORY_CONVERSATION_KEY)
    return null
  }
}

function rememberConversationArchive() {
  if (typeof window === 'undefined' || !state.conversationId || state.messages.length === 0) return
  try {
    const archive = readConversationArchiveMap()
    archive[state.conversationId] = {
      conversationId: state.conversationId,
      currentQuestionId: state.currentQuestionId,
      mode: state.mode,
      materialId: state.materialId,
      chunkId: state.chunkId,
      messages: state.messages,
      conversationHistory: state.conversationHistory,
      images: state.images,
    }
    localStorage.setItem(CHAT_CONVERSATION_ARCHIVE_KEY, JSON.stringify(archive))
  } catch {
    localStorage.removeItem(CHAT_CONVERSATION_ARCHIVE_KEY)
  }
}

function readConversationArchive(conversationId: string): ConversationArchiveItem | null {
  return readConversationArchiveMap()[conversationId] || null
}

function readConversationArchiveMap(): Record<string, ConversationArchiveItem> {
  if (typeof window === 'undefined') return {}
  try {
    return JSON.parse(localStorage.getItem(CHAT_CONVERSATION_ARCHIVE_KEY) || '{}')
  } catch {
    localStorage.removeItem(CHAT_CONVERSATION_ARCHIVE_KEY)
    return {}
  }
}

function notify() {
  listeners.forEach((listener) => listener())
}
