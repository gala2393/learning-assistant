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
  conversationHistory: ConversationMessage[]  // 多轮对话上下文（最近 10 条）
  streaming: boolean                 // 是否正在流式输出
  images: ChatImagePayload[]         // 附带的图片（图片问答功能）
  temporaryMaterial: TemporaryMaterial | null  // 智能问答临时资料上下文
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
  messages: [], conversationHistory: [], streaming: false, images: [], temporaryMaterial: null,
}

// ===== 全局状态 =====
let activeController: AbortController | null = null  // 当前活跃的 SSE 流（用于取消）
let activeRunId: string | null = null                 // 防止旧的回调污染新请求
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
  state = typeof updater === 'function' ? updater(state) : { ...state, ...updater }
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
  state = {
    ...defaultState,
    mode: options?.mode || defaultState.mode,
    materialId: options?.materialId ?? null,
    chunkId: options?.chunkId ?? null,
    images: options?.images ?? [],
    temporaryMaterial: options?.temporaryMaterial ?? null,
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
  }
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

  const requestImages = params.images || state.images || []
  const requestTemporaryMaterial = compactTemporaryMaterial(params.temporaryMaterial ?? state.temporaryMaterial)
  const userMsg: ChatMessage = {
    id: 'pending-user-' + Date.now(),
    role: 'user',
    text: params.question,
    images: requestImages,
    temporaryMaterial: requestTemporaryMaterial,
  }
  const assistantId = 'pending-assistant-' + Date.now()
  const thinkingMsg: ChatMessage = { id: assistantId, role: 'assistant', text: '', thinking: true }
  const historyBefore = state.conversationHistory  // 保存旧的对话历史
  const conversationIdBefore = state.conversationId
  const runId = `${Date.now()}-${Math.random().toString(36).slice(2)}`  // 唯一运行 ID
  let answer = ''           // 累积的 AI 回答
  let firstChunk = true     // 第一个 chunk 到达时关闭"思考中"动画
  let sources: RagSource[] = []

  activeRunId = runId
  // 立即更新 UI（显示用户消息 + 思考中占位）
  state = {
    ...state, selectedHistoryId: null, conversationId: conversationIdBefore,
    currentQuestionId: null, mode: params.mode, input: '',
    materialId: params.mode === 'MATERIAL' ? params.materialId : null,
    chunkId: params.mode === 'MATERIAL' ? params.chunkId : null,
    images: [], temporaryMaterial: null, messages: state.messages.concat(userMsg, thinkingMsg),
    conversationHistory: historyBefore, streaming: true,
  }
  persistSnapshot(); notify()

  // 发起 SSE 流式请求
  activeController = chatStream(
    {
      question: params.question, mode: params.mode,
      materialId: params.mode === 'MATERIAL' ? (params.materialId || undefined) : undefined,
      chunkId: params.mode === 'MATERIAL' ? (params.chunkId || undefined) : undefined,
      selectedText: params.selectedText || undefined,
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
        state = { ...state, messages: state.messages.map((m) =>
          m.id === assistantId ? { ...m, thinking: firstChunk ? false : m.thinking, text: cleanText } : m) }
        firstChunk = false
        persistSnapshot(); notify()
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
        const cleanAnswer = sanitizeAiText(result.answer)
        // 更新多轮对话上下文（保留最近 10 条）
        const nextConversationHistory = [
          ...historyBefore,
          { role: 'user', content: params.question },
          { role: 'assistant', content: cleanAnswer },
        ].slice(-10)

        activeRunId = null; activeController = null
        state = {
          ...state, conversationId, currentQuestionId: questionId,
          messages: state.messages.map((m) => m.id === assistantId
            ? { ...m, id: questionId, thinking: false, text: cleanAnswer, sources } : m),
          conversationHistory: nextConversationHistory, streaming: false,
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
        activeRunId = null; activeController = null
        state = {
          ...state, messages: state.messages.map((m) =>
            m.id === assistantId ? { ...m, thinking: false, error: message, text: '' } : m),
          streaming: false,
        }
        persistSnapshot(); notify()
        queryClient.invalidateQueries({ queryKey: ['history'] })
      },
    },
  )
}

/** 将后端返回的状态码转换为用户友好的中文提示 */
function streamStatusText(status: { stage?: string; message?: string }) {
  if (status.message?.trim()) return status.message.trim()
  if (status.stage === 'searching') return '正在检索相关资料...'
  return '正在准备回答...'
}

/** 取消当前活跃的 SSE 流 */
function abortActiveStream() {
  activeRunId = null
  activeController?.abort()
  activeController = null
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
      temporaryMaterial: draft.temporaryMaterial || null,
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

function compactTemporaryMaterial(material: TemporaryMaterial | null | undefined): TemporaryMaterial | null {
  if (!material) return null
  const text = material.text || ''
  if (text.length <= TEMPORARY_MATERIAL_TEXT_LIMIT) return material
  // parts/files 等元数据保留完整，只有大段正文截断；预览弹窗仍能展示文件名、
  // 文件大小和“内容已截取”的提示。
  const compactText = text.slice(0, TEMPORARY_MATERIAL_TEXT_LIMIT)
  return {
    ...material,
    text: `${compactText}\n\n[内容过长，已截取前 ${TEMPORARY_MATERIAL_TEXT_LIMIT} 字]`,
    excerpt: material.excerpt || compactText.slice(0, 500),
  }
}
