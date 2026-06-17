import { chatStream, getHistory } from '@/api/rag'
import { queryClient } from '@/lib/query-client'
import { sanitizeAiText } from '@/lib/utils'
import type { ChatMessage } from '@/components/workspace/ChatThread'
import type { ChatImagePayload, HistoryItem, RagSource, TemporaryMaterial } from '@/types'

/**
 * 聊天会话状态管理 — 使用 External Store 模式（类似 Redux 的轻量替代方案）。
 *
 * 为什么不用 React 的 useState？
 * 因为流式输出时每秒可能更新 10+ 次，useState 的批量更新机制会导致延迟。
 * External Store 通过 useSyncExternalStore 订阅，每次 state 变化立即触发重渲染。
 *
 * 状态持久化策略（三层）：
 * 1. sessionStorage（当前标签页刷新保持）
 * 2. localStorage（标签页关闭后恢复草稿）
 * 3. localStorage 对话归档（按 conversationId 存储完整对话历史）
 */

// ===== localStorage 键名 =====
export const CHAT_DRAFT_KEY = 'learning-assistant.chat.current'
const CHAT_DRAFT_BACKUP_KEY = 'learning-assistant.chat.current.backup'
export const CHAT_HISTORY_CONVERSATION_KEY = 'learning-assistant.chat.history-conversations'
const CHAT_CONVERSATION_ARCHIVE_KEY = 'learning-assistant.chat.conversation-archive'

// 前端只保留最近 24 条原文上下文给后端；更早内容由后端长期摘要记忆承接。
const RECENT_CONVERSATION_HISTORY_LIMIT = 24
/** 旧版本临时资料没有后端全文引用时，单次发送给后端的正文上限。 */
const TEMPORARY_MATERIAL_REQUEST_TEXT_LIMIT = 120_000
/** 有后端全文引用时，前端请求只携带少量预览，真正检索由后端按 ID 取全文。 */
const TEMPORARY_MATERIAL_REQUEST_PREVIEW_LIMIT = 2_000
/** 单次直接提问的真实可用上限；发送前统一截断，避免超长请求导致接口失败。 */
const MAX_CHAT_QUESTION_CHARS = 6000
/**
 * 临时资料持久化文本上限。
 *
 * 用户可能上传很大的 PDF/DOCX 作为临时资料。前端保存草稿和本地会话归档时，
 * 如果把完整解析文本原样写入 localStorage/sessionStorage，会很快触发浏览器
 * 存储配额限制，导致整个聊天状态无法恢复。因此只保留前 2 万字用于历史回显
 * 和预览提示；真正发给后端的内容在请求发起前已经完成。
 */
const TEMPORARY_MATERIAL_TEXT_LIMIT = 20_000

type ChatMode = 'GENERAL' | 'MATERIAL'  // 通用模式 / 资料模式
type ConversationMessage = { role: string; content: string }

/**
 * 聊天会话快照 — 包含聊天页面所需的所有状态。
 */
export interface ChatSessionSnapshot {
  selectedHistoryId: string | null   // 当前选中的历史记录 ID
  conversationId: string | null      // 当前对话 ID（同一组多轮对话共享）
  currentQuestionId: string | null   // 最后一个问题的 ID
  mode: ChatMode                     // 问答模式
  input: string                      // 输入框中的文字
  materialId: string | null          // 资料模式绑定的资料 ID
  chunkId: string | null             // 资料模式绑定的分块 ID
  messages: ChatMessage[]            // 消息列表（用户问题 + AI 回答）
  conversationHistory: ConversationMessage[]  // 多轮对话上下文（最近 24 条，更多内容由后端摘要承接）
  streaming: boolean                 // 是否正在流式输出
  images: ChatImagePayload[]         // 附带的图片（图片问答功能）
  temporaryMaterial: TemporaryMaterial | null  // 智能问答临时资料上下文
  temporaryMaterialPending: boolean   // 临时资料是否仍需随下一条消息提交到后端
}

interface PersistedChatDraft {
  historyId?: string | null
  lastQuestionId?: string | null
  conversationId?: string | null
  mode?: ChatMode
  materialId?: string | null
  chunkId?: string | null
  messages?: ChatMessage[]
  conversationHistory?: ConversationMessage[]
  images?: ChatImagePayload[]
  temporaryMaterial?: TemporaryMaterial | null
  temporaryMaterialPending?: boolean
}

interface ConversationArchiveItem {
  conversationId: string
  currentQuestionId: string | null
  mode: ChatMode
  materialId: string | null
  chunkId: string | null
  messages: ChatMessage[]
  conversationHistory: ConversationMessage[]
  images: ChatImagePayload[]
  temporaryMaterial?: TemporaryMaterial | null
}

/** 默认空状态 */
const defaultState: ChatSessionSnapshot = {
  selectedHistoryId: null, conversationId: null, currentQuestionId: null,
  mode: 'GENERAL', input: '', materialId: null, chunkId: null,
  messages: [], conversationHistory: [], streaming: false, images: [], temporaryMaterial: null, temporaryMaterialPending: false,
}

// ===== 全局状态 =====
let activeController: AbortController | null = null  // 当前活跃的 SSE 流（用于取消）
let activeRunId: string | null = null                 // 防止旧的回调污染新请求
let activeAssistantId: string | null = null            // 当前正在输出的 AI 消息 ID，用于暂停时精确更新
let activeAnswerText = ''                              // 当前流已经收到并清理后的回答文本
let activeSentTemporaryMaterialKey: string | null = null // 暂停首轮临时资料问答时，用于恢复“待发送”标记
let lastStreamPersistAt = 0                           // 流式长文输出期间的本地持久化节流时间戳
/** 当前会话中尚未提交给后端保存的临时资料标识；提交成功后清空，后续追问只传 conversationId。 */
let pendingTemporaryMaterialKey: string | null = null
const listeners = new Set<() => void>()               // 订阅者列表（React 组件）
let state: ChatSessionSnapshot = restoreSnapshot()    // 全局状态（从 localStorage 恢复）

/** 获取当前状态快照（useSyncExternalStore 的 getSnapshot 回调） */
export function getChatSessionSnapshot() { return state }

/** 订阅状态变化（useSyncExternalStore 的 subscribe 回调） */
export function subscribeChatSession(listener: () => void) {
  listeners.add(listener)
  return () => listeners.delete(listener)  // 返回取消订阅函数
}

/**
 * 更新聊天状态 — 支持部分更新或函数式更新。
 * 更新后自动持久化并通知所有订阅者重渲染。
 */
export function updateChatSession(
  updater: Partial<ChatSessionSnapshot> | ((current: ChatSessionSnapshot) => ChatSessionSnapshot),
) {
  const previousTemporaryKey = temporaryMaterialIdentity(state.temporaryMaterial)
  state = typeof updater === 'function' ? updater(state) : { ...state, ...updater }
  const nextTemporaryKey = temporaryMaterialIdentity(state.temporaryMaterial)
  if (nextTemporaryKey !== previousTemporaryKey) {
    pendingTemporaryMaterialKey = nextTemporaryKey
    state = { ...state, temporaryMaterialPending: Boolean(nextTemporaryKey) }
  }
  persistSnapshot()
  notify()
}

/**
 * 重置聊天状态（开始新对话）。
 * 会取消正在进行的流式输出。
 */
export function resetChatSession(options?: {
  mode?: ChatMode; materialId?: string | null; chunkId?: string | null;
  images?: ChatImagePayload[]; temporaryMaterial?: TemporaryMaterial | null; abortActive?: boolean
}) {
  if (options?.abortActive !== false) abortActiveStream()
  pendingTemporaryMaterialKey = temporaryMaterialIdentity(options?.temporaryMaterial ?? null)
  state = {
    ...defaultState,
    mode: options?.mode || defaultState.mode,
    materialId: options?.materialId ?? null,
    chunkId: options?.chunkId ?? null,
    images: options?.images ?? [],
    temporaryMaterial: options?.temporaryMaterial ?? null,
    temporaryMaterialPending: Boolean(pendingTemporaryMaterialKey),
  }
  persistSnapshot()
  notify()
}

/**
 * 选择历史记录 → 恢复该对话的完整上下文。
 * 从后端获取详情，结合本地归档恢复消息列表。
 */
export async function selectHistorySession(item: HistoryItem) {
  abortActiveStream()
  const detail = await getHistory(String(item.id)).catch(() => item)
  applyHistorySession(detail)
}

/** 从历史记录恢复对话状态 */
export function applyHistorySession(item: HistoryItem) {
  // 历史详情有两类来源：
  // 1. 后端返回的 messages，保证刷新、换设备后仍能看到完整会话。
  // 2. 本地 archive，保留前端即时产生的图片、临时资料预览和更完整的流式中间状态。
  // 当本地归档更完整时优先使用归档，否则以服务端详情为准。
  const source = item.sources?.[0]
  const conversationId = String(item.conversationId || readHistoryConversationId(String(item.id)) || item.id)
  const archived = readConversationArchive(conversationId)
  // 恢复消息列表（优先用归档，其次用后端详情，最后用单条记录）
  const detailMessages = item.messages?.length
    ? item.messages.map((message, index) => ({
      id: `${message.id}-${message.role}-${index}`, role: message.role, text: message.text,
      images: message.role === 'user' ? message.images || [] : undefined,
      temporaryMaterial: message.role === 'user' ? message.temporaryMaterial || null : null,
      sources: index === item.messages!.length - 1 && message.role === 'assistant' ? item.sources : undefined,
    }))
    : []
  const restoredMessages: ChatMessage[] = archived && archived.messages.length > detailMessages.length
    ? archived.messages : detailMessages.length ? detailMessages
    : [{ id: item.id + '-user', role: 'user', text: item.question },
       { id: item.id + '-assistant', role: 'assistant', text: item.answer, sources: item.sources }]
  const restoredHistory = restoredMessages
    .filter((m) => !m.error && m.text.trim())
    .map((m) => ({ role: m.role, content: m.text }))

  state = {
    ...state, selectedHistoryId: String(item.id), conversationId, currentQuestionId: null,
    mode: archived?.mode || (source ? 'MATERIAL' : 'GENERAL'), input: '',
    materialId: archived?.materialId ?? source?.materialId ?? null,
    chunkId: archived?.chunkId ?? source?.chunkId ?? null,
    messages: restoredMessages,
    conversationHistory: archived?.conversationHistory?.length ? archived.conversationHistory : restoredHistory,
    temporaryMaterial: archived?.temporaryMaterial ?? null,
    streaming: false,
    temporaryMaterialPending: false,
  }
  // 历史会话中的临时资料已经由后端保存过，恢复后继续追问不需要重新发送资料正文。
  pendingTemporaryMaterialKey = null
  persistSnapshot(); notify()
}

/**
 * 发起流式聊天请求 — 这是聊天功能的核心函数。
 *
 * 流程：
 * 1. 创建用户消息和"思考中"占位消息
 * 2. 调用 chatStream() 发起 SSE 流式请求
 * 3. onStatus → 显示状态提示（"正在检索相关资料..."）
 * 4. onChunk → 逐字追加 AI 回答文本（打字机效果）
 * 5. onSources → 收到引用来源后更新消息卡片
 * 6. onDone → 流结束，替换临时 ID 为真实 ID，更新对话历史
 * 7. onError → 显示错误信息
 *
 * 每次状态变化都通过 persistSnapshot() + notify() 实时更新 UI。
 */
export function startChatSessionStream(params: {
  question: string; mode: ChatMode; materialId: string | null;
  chunkId: string | null; images?: ChatImagePayload[]; selectedText?: string | null;
  temporaryMaterial?: TemporaryMaterial | null
}) {
  if (state.streaming) return  // 防止重复提交

  const question = clampChatQuestion(params.question)
  if (!question) return
  const historyBefore = state.conversationHistory  // 保存旧的对话历史
  const conversationIdBefore = state.conversationId
  const requestImages = params.images || state.images || []
  const fullTemporaryMaterial = params.temporaryMaterial ?? state.temporaryMaterial ?? null
  const fullTemporaryMaterialKey = temporaryMaterialIdentity(fullTemporaryMaterial)
  const shouldSendTemporaryMaterial = shouldSendTemporaryMaterialForRequest(
    params.mode,
    conversationIdBefore,
    fullTemporaryMaterialKey,
  )
  const requestTemporaryMaterial = shouldSendTemporaryMaterial
    ? temporaryMaterialForRequest(fullTemporaryMaterial)
    : null
  const requestSelectedText = shouldKeepSelectedTextForRequest(
    params.mode,
    fullTemporaryMaterial,
    shouldSendTemporaryMaterial,
    params.selectedText,
  )
  const displayTemporaryMaterial = shouldSendTemporaryMaterial
    ? compactTemporaryMaterial(fullTemporaryMaterial)
    : null
  const sentTemporaryMaterialKey = shouldSendTemporaryMaterial ? fullTemporaryMaterialKey : null
  const userMsg: ChatMessage = {
    id: 'pending-user-' + Date.now(),
    role: 'user',
    text: question,
    images: requestImages,
    temporaryMaterial: displayTemporaryMaterial,
  }
  const assistantId = 'pending-assistant-' + Date.now()
  const thinkingMsg: ChatMessage = { id: assistantId, role: 'assistant', text: '', thinking: true }
  const runId = `${Date.now()}-${Math.random().toString(36).slice(2)}`  // 唯一运行 ID
  let answer = ''           // 累积的 AI 回答
  let firstChunk = true     // 第一个 chunk 到达时关闭"思考中"动画
  let sources: RagSource[] = []

  activeRunId = runId
  activeAssistantId = assistantId
  activeAnswerText = ''
  activeSentTemporaryMaterialKey = sentTemporaryMaterialKey
  lastStreamPersistAt = 0
  // 立即更新 UI（显示用户消息 + 思考中占位）
  state = {
    ...state, selectedHistoryId: null, conversationId: conversationIdBefore,
    currentQuestionId: null, mode: params.mode, input: '',
    materialId: params.mode === 'MATERIAL' ? params.materialId : null,
    chunkId: params.mode === 'MATERIAL' ? params.chunkId : null,
    images: [], temporaryMaterial: fullTemporaryMaterial, temporaryMaterialPending: sentTemporaryMaterialKey ? false : state.temporaryMaterialPending,
    messages: state.messages.concat(userMsg, thinkingMsg),
    conversationHistory: historyBefore, streaming: true,
  }
  persistSnapshot(); notify()

  // 发起 SSE 流式请求
  activeController = chatStream(
    {
      question, mode: params.mode,
      materialId: params.mode === 'MATERIAL' ? (params.materialId || undefined) : undefined,
      chunkId: params.mode === 'MATERIAL' ? (params.chunkId || undefined) : undefined,
      selectedText: requestSelectedText || undefined,
      history: historyBefore, conversationId: conversationIdBefore,
      images: requestImages,
      temporaryMaterial: requestTemporaryMaterial || undefined,
    },
    {
      // 状态回调：显示"正在检索相关资料..."等提示
      onStatus: (status) => {
        if (activeRunId !== runId || answer.trim()) return
        state = { ...state, messages: state.messages.map((m) =>
          m.id === assistantId ? { ...m, thinking: false, text: streamStatusText(status) } : m) }
        persistSnapshot(); notify()
      },
      // 文本增量：逐字追加 AI 回答（打字机效果的核心）
      onChunk: (delta) => {
        if (activeRunId !== runId) return
        answer += delta  // 累积原始文本
        const cleanText = sanitizeAiText(answer)
        activeAnswerText = cleanText
        state = { ...state, messages: state.messages.map((m) =>
          m.id === assistantId ? { ...m, thinking: firstChunk ? false : m.thinking, text: cleanText } : m) }
        firstChunk = false
        persistStreamSnapshot()
        notify()
      },
      // 来源回调：收到检索到的资料来源
      onSources: (nextSources) => {
        if (activeRunId !== runId) return
        sources = nextSources
        state = { ...state, messages: state.messages.map((m) => (m.id === assistantId ? { ...m, sources } : m)) }
        persistSnapshot(); notify()
      },
      // 完成回调：流结束，更新对话 ID 和历史
      onDone: (result) => {
        if (activeRunId !== runId) return
        const questionId = String(result.questionId)
        const conversationId = String(result.conversationId || state.conversationId || questionId)
        // 长文流式时后端 done 事件不再重复携带完整正文，避免最后一帧 JSON 过大。
        // 这时直接使用前面 chunk 已经累积好的 answer 完成落盘和历史更新。
        const finalAnswer = typeof result.answer === 'string' && result.answer.trim() ? result.answer : answer
        const cleanAnswer = sanitizeAiText(finalAnswer)
        // 更新多轮对话上下文（保留最近 24 条，避免前端请求体无限增长）
        const nextConversationHistory = [
          ...historyBefore,
          { role: 'user', content: question },
          { role: 'assistant', content: cleanAnswer },
        ].slice(-RECENT_CONVERSATION_HISTORY_LIMIT)

        clearActiveStreamRuntime()
        if (sentTemporaryMaterialKey && pendingTemporaryMaterialKey === sentTemporaryMaterialKey) {
          pendingTemporaryMaterialKey = null
        }
        state = {
          ...state, conversationId, currentQuestionId: questionId,
          messages: state.messages.map((m) => m.id === assistantId
            ? {
                ...m,
                id: questionId,
                thinking: false,
                text: cleanAnswer,
                sources,
                continuable: Boolean(result.continuable),
                continuationHint: result.continuationHint || null,
              } : m),
          conversationHistory: nextConversationHistory, streaming: false, temporaryMaterialPending: false,
        }
        persistSnapshot()
        rememberHistoryConversation(questionId, conversationId)
        rememberConversationArchive()
        notify()
        // 刷新历史记录和使用统计缓存
        queryClient.invalidateQueries({ queryKey: ['history'] })
        queryClient.invalidateQueries({ queryKey: ['rag-usage'] })
        queryClient.invalidateQueries({ queryKey: ['admin', 'usage-records'] })
      },
      // 错误回调：显示错误信息
      onError: (message) => {
        if (activeRunId !== runId) return
        const cleanAnswer = sanitizeAiText(answer)
        clearActiveStreamRuntime()
        if (sentTemporaryMaterialKey) pendingTemporaryMaterialKey = sentTemporaryMaterialKey
        // 如果已经收到部分正文，说明模型确实在持续生成；此时保留已有内容并附加中断提示。
        // 注意这里不要设置 error 字段，否则消息组件会只渲染红色错误卡片，反而盖掉已生成正文。
        if (cleanAnswer.trim()) {
          const interruptedText = `${cleanAnswer}\n\n回答生成已中断，以上为已收到的内容。`
          state = {
            ...state, messages: state.messages.map((m) =>
              m.id === assistantId ? { ...m, thinking: false, error: undefined, text: interruptedText } : m),
            streaming: false, temporaryMaterialPending: sentTemporaryMaterialKey ? true : state.temporaryMaterialPending,
          }
          persistSnapshot(); notify()
          queryClient.invalidateQueries({ queryKey: ['history'] })
          clearActiveStreamRuntime()
          return
        }
        state = {
          ...state, messages: state.messages.map((m) =>
            m.id === assistantId ? { ...m, thinking: false, error: message, text: '' } : m),
          streaming: false, temporaryMaterialPending: sentTemporaryMaterialKey ? true : state.temporaryMaterialPending,
        }
        persistSnapshot(); notify()
        queryClient.invalidateQueries({ queryKey: ['history'] })
        clearActiveStreamRuntime()
      },
    },
  )
}

/**
 * 流式输出期间的持久化节流。
 * <p>
 * 1 万字长文会产生大量 chunk，如果每个 chunk 都把完整消息写入 sessionStorage/localStorage，
 * 浏览器容易卡顿，甚至因为存储写入失败导致页面恢复为"回答已中断"。这里保留页面刷新恢复能力，
 * 但把写入频率降到约每 1.5 秒一次；流结束时仍会通过 persistSnapshot() 保存最终全文。
 */
function clampChatQuestion(question: string) {
  return question.trim().slice(0, MAX_CHAT_QUESTION_CHARS)
}

function persistStreamSnapshot() {
  const now = Date.now()
  if (now - lastStreamPersistAt < 1500) return
  lastStreamPersistAt = now
  persistSnapshot()
}

/** 将后端返回的状态码转换为用户友好的中文提示 */
function streamStatusText(status: { stage?: string; message?: string }) {
  if (status.message?.trim()) return status.message.trim()
  if (status.stage === 'searching') return '正在检索相关资料...'
  if (status.stage === 'generating') return '正在生成回答...'
  return '正在准备回答...'
}

/**
 * 暂停当前流式输出。
 *
 * 这里的“暂停”采用中止当前 SSE 请求的实现：浏览器原生 fetch/SSE 不支持可靠地暂停后继续
 * 同一条 HTTP 流，所以暂停会停止继续接收 token，并保留已经输出到界面的内容。
 */
export function pauseActiveChatStream() {
  if (!activeController || !activeAssistantId || !state.streaming) return
  const assistantId = activeAssistantId
  const sentTemporaryMaterialKey = activeSentTemporaryMaterialKey
  const cleanAnswer = activeAnswerText.trim()
  const pausedText = cleanAnswer
    ? `${cleanAnswer}\n\n已暂停输出，以上为已收到的内容。`
    : '已暂停输出。'

  activeRunId = null
  activeController.abort()
  state = {
    ...state,
    messages: state.messages.map((message) =>
      message.id === assistantId
        ? { ...message, thinking: false, error: undefined, text: pausedText }
        : message,
    ),
    streaming: false,
    // 如果首轮临时资料还没有等到 done 保存会话，暂停后恢复待发送状态，下一次提问会重新提交资料正文。
    temporaryMaterialPending: sentTemporaryMaterialKey ? true : state.temporaryMaterialPending,
  }
  if (sentTemporaryMaterialKey) pendingTemporaryMaterialKey = sentTemporaryMaterialKey
  clearActiveStreamRuntime()
  persistSnapshot()
  notify()
  queryClient.invalidateQueries({ queryKey: ['rag-usage'] })
}

/** 取消当前活跃的 SSE 流 */
function abortActiveStream() {
  activeRunId = null
  activeController?.abort()
  clearActiveStreamRuntime()
}

/** 清理当前流式任务的运行时引用，避免旧请求回调污染下一次输出。 */
function clearActiveStreamRuntime() {
  activeRunId = null
  activeController = null
  activeAssistantId = null
  activeAnswerText = ''
  activeSentTemporaryMaterialKey = null
}

/**
 * 从 localStorage 恢复聊天状态。
 * 优先从 sessionStorage 读取（当前标签页），其次从 localStorage 备份读取。
 * 如果是流式输出中被中断的消息，标记为"中断"状态。
 */
function restoreSnapshot(): ChatSessionSnapshot {
  if (typeof window === 'undefined') return defaultState
  try {
    const draft = JSON.parse(
      sessionStorage.getItem(CHAT_DRAFT_KEY) || localStorage.getItem(CHAT_DRAFT_BACKUP_KEY) || 'null',
    ) as PersistedChatDraft | null
    if (!draft?.messages?.length) return defaultState
    const temporaryMaterial = draft.temporaryMaterial || null
    const temporaryMaterialPending = Boolean(draft.temporaryMaterialPending && temporaryMaterial)
    pendingTemporaryMaterialKey = temporaryMaterialPending ? temporaryMaterialIdentity(temporaryMaterial) : null
    return {
      ...defaultState,
      // 恢复对话 ID 和其他状态
      currentQuestionId: draft.lastQuestionId || (draft.historyId !== 'pending' ? draft.historyId || null : null),
      conversationId: draft.conversationId || draft.lastQuestionId || (draft.historyId !== 'pending' ? draft.historyId || null : null),
      mode: draft.mode || defaultState.mode, materialId: draft.materialId ?? null, chunkId: draft.chunkId ?? null,
      // 处理中断的流式消息
      messages: draft.messages.map((m) => {
        if (!m.thinking) return m
        // 有部分文本 → 附加"中断"提示
        if (m.text.trim()) return { ...m, thinking: false, text: m.text.trim() + '\n\n流被页面刷新中断，已恢复部分回答。' }
        // 没有文本 → 标记错误
        return { ...m, thinking: false, error: '流被页面刷新中断，请重新发送问题。', text: '' }
      }),
      conversationHistory: draft.conversationHistory || [],
      images: draft.images || [],
      temporaryMaterial,
      temporaryMaterialPending,
    }
  } catch {
    sessionStorage.removeItem(CHAT_DRAFT_KEY)
    localStorage.removeItem(CHAT_DRAFT_BACKUP_KEY)
    return defaultState
  }
}

/** 持久化当前状态到 sessionStorage + localStorage */
function persistSnapshot() {
  if (typeof window === 'undefined') return
  if (state.messages.length === 0) {
    sessionStorage.removeItem(CHAT_DRAFT_KEY)
    localStorage.removeItem(CHAT_DRAFT_BACKUP_KEY)
    return
  }
  const draft: PersistedChatDraft = {
    lastQuestionId: state.currentQuestionId, conversationId: state.conversationId,
    mode: state.mode, materialId: state.mode === 'MATERIAL' ? state.materialId : null,
    chunkId: state.mode === 'MATERIAL' ? state.chunkId : null,
    messages: compactChatMessages(state.messages), conversationHistory: state.conversationHistory, images: state.images,
    temporaryMaterial: compactTemporaryMaterial(state.temporaryMaterial),
    temporaryMaterialPending: state.temporaryMaterialPending,
  }
  sessionStorage.setItem(CHAT_DRAFT_KEY, JSON.stringify(draft))
  localStorage.setItem(CHAT_DRAFT_BACKUP_KEY, JSON.stringify(draft))
}

/** 记录 questionId → conversationId 的映射（用于从历史记录恢复对话） */
function rememberHistoryConversation(questionId: string, conversationId: string) {
  if (typeof window === 'undefined') return
  try {
    const raw = localStorage.getItem(CHAT_HISTORY_CONVERSATION_KEY)
    const map = raw ? JSON.parse(raw) as Record<string, string> : {}
    map[questionId] = conversationId
    map[conversationId] = conversationId
    localStorage.setItem(CHAT_HISTORY_CONVERSATION_KEY, JSON.stringify(map))
  } catch { localStorage.removeItem(CHAT_HISTORY_CONVERSATION_KEY) }
}

/** 从映射中读取 questionId 对应的 conversationId */
function readHistoryConversationId(questionId: string) {
  if (typeof window === 'undefined') return null
  try {
    const raw = localStorage.getItem(CHAT_HISTORY_CONVERSATION_KEY)
    const map = raw ? JSON.parse(raw) as Record<string, string> : {}
    return map[questionId] || null
  } catch { localStorage.removeItem(CHAT_HISTORY_CONVERSATION_KEY); return null }
}

/** 归档当前对话到 localStorage（按 conversationId 存储完整对话） */
function rememberConversationArchive() {
  if (typeof window === 'undefined' || !state.conversationId || state.messages.length === 0) return
  try {
    const archive = readConversationArchiveMap()
    archive[state.conversationId] = {
      conversationId: state.conversationId, currentQuestionId: state.currentQuestionId,
      mode: state.mode, materialId: state.materialId, chunkId: state.chunkId,
      messages: compactChatMessages(state.messages), conversationHistory: state.conversationHistory, images: state.images,
      temporaryMaterial: compactTemporaryMaterial(state.temporaryMaterial),
    }
    localStorage.setItem(CHAT_CONVERSATION_ARCHIVE_KEY, JSON.stringify(archive))
  } catch { localStorage.removeItem(CHAT_CONVERSATION_ARCHIVE_KEY) }
}

/** 从归档中读取指定对话 */
function readConversationArchive(conversationId: string): ConversationArchiveItem | null {
  return readConversationArchiveMap()[conversationId] || null
}

/** 读取所有归档对话 */
function readConversationArchiveMap(): Record<string, ConversationArchiveItem> {
  if (typeof window === 'undefined') return {}
  try { return JSON.parse(localStorage.getItem(CHAT_CONVERSATION_ARCHIVE_KEY) || '{}') }
  catch { localStorage.removeItem(CHAT_CONVERSATION_ARCHIVE_KEY); return {} }
}

/** 通知所有订阅者（触发 React 重渲染） */
function notify() { listeners.forEach((l) => l()) }

function compactChatMessages(messages: ChatMessage[]) {
  // 只压缩消息里携带的临时资料文本；图片 DataURL、消息文本和来源引用保持原样，
  // 这样恢复历史时仍能得到与发送时一致的视觉结果。
  return messages.map((message) => message.temporaryMaterial
    ? { ...message, temporaryMaterial: compactTemporaryMaterial(message.temporaryMaterial) }
    : message)
}

/** 生成临时资料的轻量标识，用于判断用户是否在同一会话里换了新资料。 */
function temporaryMaterialIdentity(material: TemporaryMaterial | null | undefined): string | null {
  if (!material) return null
  const partKeys = material.parts?.map(temporaryMaterialIdentity).filter(Boolean).join('|') || ''
  return [
    material.id || '',
    material.originalName || material.title || '',
    material.fileSize ?? '',
    material.text?.length ?? 0,
    partKeys,
  ].join(':')
}

/**
 * 判断本次问答是否需要把临时资料正文发给后端。
 *
 * 新会话首轮必须发送一次，让后端把资料内容保存到该 conversation；
 * 已有会话只有在用户重新上传/替换临时资料后才发送，避免每轮追问都重复携带大文本。
 */
function shouldSendTemporaryMaterialForRequest(
  mode: ChatMode,
  conversationId: string | null,
  temporaryKey: string | null,
) {
  if (mode !== 'GENERAL' || !temporaryKey) return false
  if (!conversationId) return true
  return pendingTemporaryMaterialKey === temporaryKey
}

/**
 * 决定本次请求是否保留 selectedText。
 *
 * 通用问答里 selectedText 只用于把临时资料正文塞给后端；当临时资料已在会话首轮保存后，
 * 后续追问必须只带 conversationId，避免每轮重复提交大段资料文本。
 * 资料问答/划词提问不受影响，仍然保留 selectedText。
 */
function shouldKeepSelectedTextForRequest(
  mode: ChatMode,
  temporaryMaterial: TemporaryMaterial | null | undefined,
  sendingTemporaryMaterial: boolean,
  selectedText: string | null | undefined,
) {
  if (!selectedText) return null
  if (mode === 'GENERAL' && temporaryMaterial && !sendingTemporaryMaterial) return null
  return selectedText
}

function compactTemporaryMaterial(material: TemporaryMaterial | null | undefined): TemporaryMaterial | null {
  if (!material) return null
  const compactParts = material.parts?.map((part) => compactTemporaryMaterial(part)).filter(Boolean) as TemporaryMaterial[] | undefined
  const text = material.text || ''
  if (text.length <= TEMPORARY_MATERIAL_TEXT_LIMIT) {
    return compactParts?.length ? { ...material, parts: compactParts } : material
  }
  // parts/files 等元数据保留完整，只有大段正文截断；预览弹窗仍能展示文件名、
  // 文件大小和“内容已截取”的提示。
  const compactText = text.slice(0, TEMPORARY_MATERIAL_TEXT_LIMIT)
  return {
    ...material,
    text: `${compactText}\n\n[内容过长，已截取前 ${TEMPORARY_MATERIAL_TEXT_LIMIT} 字]`,
    excerpt: material.excerpt || compactText.slice(0, 500),
    parts: compactParts,
  }
}

function temporaryMaterialForRequest(material: TemporaryMaterial | null | undefined): TemporaryMaterial | null {
  if (!material) return null
  if (hasTemporaryMaterialReference(material)) return temporaryMaterialReferenceForRequest(material)
  const text = material.text || ''
  if (text.length <= TEMPORARY_MATERIAL_REQUEST_TEXT_LIMIT) return material
  // 请求体保留开头和结尾，兼顾概览类问题和“最后/后半部分”问题；完整大文件应走资料问答后台解析。
  const headLength = Math.floor(TEMPORARY_MATERIAL_REQUEST_TEXT_LIMIT * 0.65)
  const tailLength = TEMPORARY_MATERIAL_REQUEST_TEXT_LIMIT - headLength
  const requestText = [
    text.slice(0, headLength),
    `\n\n[临时资料过长，已省略中间内容；单次智能问答最多携带 ${TEMPORARY_MATERIAL_REQUEST_TEXT_LIMIT} 字。大文件请上传到资料问答以获得完整索引。]\n\n`,
    text.slice(Math.max(0, text.length - tailLength)),
  ].join('')
  return {
    ...material,
    text: requestText,
    excerpt: material.excerpt || requestText.slice(0, 500),
  }
}

/** 后端已保存全文的临时资料，请求里只传引用和少量预览，避免大请求体和前端本地存储压力。 */
function temporaryMaterialReferenceForRequest(material: TemporaryMaterial): TemporaryMaterial {
  const parts = material.parts?.map((part) => temporaryMaterialReferenceForRequest(part))
  const preview = requestPreviewText(material)
  return {
    ...material,
    text: preview,
    excerpt: material.excerpt || preview.slice(0, 500),
    parts,
  }
}

/** 判断临时资料是否有后端全文引用。多文件资料只要子资料有 ID，就走引用恢复。 */
function hasTemporaryMaterialReference(material: TemporaryMaterial): boolean {
  if (material.contextStored && material.id) return true
  if (material.parts?.length) return material.parts.some(hasTemporaryMaterialReference)
  return false
}

/** 请求预览文本只用于历史回显和降级兜底，不再承担全文检索职责。 */
function requestPreviewText(material: TemporaryMaterial) {
  const text = material.text || material.excerpt || ''
  if (!text) return ''
  if (text.length <= TEMPORARY_MATERIAL_REQUEST_PREVIEW_LIMIT) return text
  return `${text.slice(0, TEMPORARY_MATERIAL_REQUEST_PREVIEW_LIMIT)}\n\n[仅发送预览，完整临时资料正文由后端按资料 ID 恢复。]`
}
