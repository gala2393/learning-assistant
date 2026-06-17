import { chatStream } from '@/api/rag'
import { queryClient } from '@/lib/query-client'
import type { ChatMessage } from '@/components/workspace/ChatThread'
import type { HistoryItem, RagSource } from '@/types'

type ReaderConversationMessage = { role: string; content: string }

export interface ReaderAskSnapshot {
  materialId: string | null
  question: string
  messages: ChatMessage[]
  sourcesByMessageId: Record<string, RagSource[]>
  errorByMessageId: Record<string, string>
  conversationId: string | number | null
  selectedText: string | null
  loading: boolean
  skipAutoRestoreForMaterialId: string | null
}

const defaultState: ReaderAskSnapshot = {
  materialId: null,
  question: '',
  messages: [],
  sourcesByMessageId: {},
  errorByMessageId: {},
  conversationId: null,
  selectedText: null,
  loading: false,
  skipAutoRestoreForMaterialId: null,
}

/** 边读边问直接提问上限；发送层兜底截断，防止绕过输入框限制后请求失败。 */
const MAX_READER_ASK_QUESTION_CHARS = 6000

let state: ReaderAskSnapshot = { ...defaultState }
let activeController: AbortController | null = null
let activeRunId: string | null = null
let activeAssistantId: string | null = null
let answerBuffer = ''
const listeners = new Set<() => void>()

/**
 * 边读边问会话使用模块级 External Store，而不是组件内 useState。
 * 这样 ReaderAsk 被路由切换卸载时，SSE 请求和消息状态仍留在模块内继续更新；
 * 用户重新进入阅读页后会重新订阅同一份快照，直接看到生成中的内容或最终回答。
 */
export function getReaderAskSnapshot() {
  return state
}

/** 供 useSyncExternalStore 订阅状态变化。 */
export function subscribeReaderAsk(listener: () => void) {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

/** 更新输入框文本；输入框也放进 store，避免切走再回来时正在编辑的问题丢失。 */
export function updateReaderAskQuestion(question: string) {
  setState({ question: question.slice(0, MAX_READER_ASK_QUESTION_CHARS) })
}

/** 更新阅读区选中文本；选区跟随当前资料保存，提交问题时作为补充上下文发送给后端。 */
export function updateReaderAskSelection(selectedText: string | null) {
  setState({ selectedText })
}

/**
 * 同步当前资料 ID。
 * 只有真的切换到另一份资料时才清空问答区；组件因为路由切换卸载再挂载时 materialId 不变，
 * 因此不会打断正在进行的流式回答，也不会覆盖已有消息。
 */
export function ensureReaderAskMaterial(materialId: string | null) {
  if (state.materialId === materialId) return
  abortActiveStream()
  state = {
    ...defaultState,
    materialId,
  }
  notify()
}

/**
 * 从后端历史恢复边读边问会话。
 * 该函数只在自动恢复最近历史或用户点击历史记录时调用；不会参与普通路由切换。
 */
export function restoreReaderAskHistory(history: HistoryItem, materialId: string | null) {
  state = {
    ...state,
    materialId,
    question: '',
    messages: historyToReaderMessages(history),
    sourcesByMessageId: {},
    errorByMessageId: {},
    conversationId: history.conversationId || history.id || null,
    selectedText: null,
    loading: false,
    skipAutoRestoreForMaterialId: null,
  }
  answerBuffer = ''
  notify()
}

/**
 * 开启当前资料的新会话。
 * 这是用户显式操作，所以会取消当前资料正在进行的流式任务，并阻止“最近历史”立即把旧会话填回来。
 */
export function startNewReaderAskConversation(materialId: string | null) {
  abortActiveStream()
  state = {
    ...defaultState,
    materialId,
    skipAutoRestoreForMaterialId: materialId,
  }
  answerBuffer = ''
  notify()
}

export function startReaderAskStream(params: {
  question: string
  materialId: string
  chunkId?: string
  currentPageNo?: number | null
  currentPageChunkIds?: Array<string | number>
  selectedText?: string | null
}) {
  const q = clampReaderAskQuestion(params.question)
  if (!q || state.loading) return

  abortActiveStream()

  const userId = `reader-user-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
  const assistantId = `reader-assistant-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
  const runId = `${Date.now()}-${Math.random().toString(36).slice(2)}`
  const history = buildRecentHistory(state.messages)
  const conversationIdBefore = state.conversationId

  activeRunId = runId
  activeAssistantId = assistantId
  answerBuffer = ''
  state = {
    ...state,
    materialId: params.materialId,
    question: '',
    selectedText: null,
    loading: true,
    skipAutoRestoreForMaterialId: null,
    messages: [
      ...state.messages,
      { id: userId, role: 'user', text: q },
      { id: assistantId, role: 'assistant', text: '', thinking: true },
    ],
    sourcesByMessageId: { ...state.sourcesByMessageId, [assistantId]: [] },
    errorByMessageId: { ...state.errorByMessageId, [assistantId]: '' },
  }
  notify()

  activeController = chatStream(
    {
      question: q,
      mode: 'MATERIAL',
      materialId: params.materialId,
      chunkId: params.chunkId,
      currentPageNo: params.currentPageNo || undefined,
      currentPageChunkIds: params.currentPageChunkIds?.length ? params.currentPageChunkIds : undefined,
      selectedText: params.selectedText || undefined,
      answerStyle: 'HOMEWORK',
      history,
      conversationId: conversationIdBefore,
    },
    {
      onStatus: (status) => {
        if (activeRunId !== runId || answerBuffer.trim()) return
        updateAssistantMessage(assistantId, {
          text: streamStatusText(status),
          thinking: false,
        })
      },
      onChunk: (delta) => {
        if (activeRunId !== runId) return
        answerBuffer += delta
        updateAssistantMessage(assistantId, {
          text: answerBuffer,
          thinking: false,
        })
      },
      onSources: (sources) => {
        if (activeRunId !== runId) return
        state = {
          ...state,
          sourcesByMessageId: { ...state.sourcesByMessageId, [assistantId]: sources },
          messages: state.messages.map((message) =>
            message.id === assistantId ? { ...message, sources } : message,
          ),
        }
        notify()
      },
      onDone: (result) => {
        if (activeRunId !== runId) return
        const sources = state.sourcesByMessageId[assistantId] || []
        state = {
          ...state,
          conversationId: result.conversationId || state.conversationId,
          loading: false,
          messages: state.messages.map((message) =>
            message.id === assistantId
              ? {
                  ...message,
                  id: String(result.questionId || message.id),
                  text: result.answer || answerBuffer,
                  thinking: false,
                  sources,
                }
              : message,
          ),
        }
        clearActiveStreamRuntime()
        notify()
        invalidateReaderAskQueries(params.materialId)
      },
      onError: (message) => {
        if (activeRunId !== runId) return
        state = {
          ...state,
          loading: false,
          errorByMessageId: { ...state.errorByMessageId, [assistantId]: message },
          messages: state.messages.map((item) =>
            item.id === assistantId
              ? { ...item, thinking: false, error: message, text: '' }
              : item,
          ),
        }
        clearActiveStreamRuntime()
        notify()
        invalidateReaderAskQueries(params.materialId)
      },
    },
  )
}

function setState(next: Partial<ReaderAskSnapshot>) {
  state = { ...state, ...next }
  notify()
}

function clampReaderAskQuestion(question: string) {
  return question.trim().slice(0, MAX_READER_ASK_QUESTION_CHARS)
}

function updateAssistantMessage(assistantId: string, patch: Partial<ChatMessage>) {
  state = {
    ...state,
    messages: state.messages.map((message) =>
      message.id === assistantId ? { ...message, ...patch } : message,
    ),
  }
  notify()
}

/**
 * 暂停边读边问的流式输出。
 *
 * fetch/SSE 不能可靠地“冻结后继续”同一条响应流，所以这里采用中止请求并保留已输出内容的方式。
 */
export function pauseReaderAskStream() {
  if (!activeController || !activeAssistantId || !state.loading) return
  const assistantId = activeAssistantId
  const cleanAnswer = answerBuffer.trim()
  const pausedText = cleanAnswer
    ? `${cleanAnswer}\n\n已暂停输出，以上为已收到的内容。`
    : '已暂停输出。'

  activeRunId = null
  activeController.abort()
  state = {
    ...state,
    loading: false,
    errorByMessageId: { ...state.errorByMessageId, [assistantId]: '' },
    messages: state.messages.map((message) =>
      message.id === assistantId
        ? { ...message, thinking: false, error: undefined, text: pausedText }
        : message,
    ),
  }
  clearActiveStreamRuntime()
  notify()
  queryClient.invalidateQueries({ queryKey: ['rag-usage'] })
}

function abortActiveStream() {
  activeRunId = null
  activeController?.abort()
  clearActiveStreamRuntime()
}

/** 清理当前流式任务引用，避免旧请求回调继续写入新会话。 */
function clearActiveStreamRuntime() {
  activeRunId = null
  activeController = null
  activeAssistantId = null
}

function notify() {
  listeners.forEach((listener) => listener())
}

function buildRecentHistory(messages: ChatMessage[]): ReaderConversationMessage[] {
  return messages
    .filter((message) => !message.thinking && !message.error)
    .slice(-8)
    .map((message) => ({
      role: message.role,
      content: message.text,
    }))
}

function historyToReaderMessages(history: HistoryItem): ChatMessage[] {
  const rawMessages = history.messages || []
  if (rawMessages.length === 0) {
    // 历史弹窗列表项通常只带最新一轮 question/answer，不带完整 messages。
    // 点击列表项时先用这一轮内容立即回填问答区，随后 ReaderAsk 会再请求详情补全多轮上下文。
    const fallbackMessages: ChatMessage[] = [
      { id: `history-user-${history.id}`, role: 'user', text: history.question || '' },
      { id: `history-assistant-${history.id}`, role: 'assistant', text: history.answer || '', sources: history.sources },
    ]
    return fallbackMessages.filter((message) => message.text.trim())
  }
  const messages = rawMessages.map((message, index) => ({
    id: `history-${message.role}-${message.id}-${index}`,
    role: message.role,
    text: message.text,
    images: message.images,
    temporaryMaterial: message.temporaryMaterial,
  })) as ChatMessage[]
  const latestAssistantIndex = [...messages].reverse().findIndex((message) => message.role === 'assistant')
  if (latestAssistantIndex >= 0 && history.sources?.length) {
    const index = messages.length - 1 - latestAssistantIndex
    messages[index] = { ...messages[index], sources: history.sources }
  }
  return messages
}

function streamStatusText(status: { stage?: string; message?: string }) {
  if (status.message?.trim()) return status.message.trim()
  if (status.stage === 'searching') return '正在检索相关资料...'
  if (status.stage === 'thinking') return '正在准备回答...'
  return '正在准备回答...'
}

function invalidateReaderAskQueries(materialId: string) {
  queryClient.invalidateQueries({ queryKey: ['rag-usage'] })
  queryClient.invalidateQueries({ queryKey: ['history'] })
  queryClient.invalidateQueries({ queryKey: ['history', 'materials', materialId] })
  queryClient.invalidateQueries({ queryKey: ['history', 'materials', materialId, 'latest'] })
  queryClient.invalidateQueries({ queryKey: ['admin', 'usage-records'] })
}
