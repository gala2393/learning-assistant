import { useEffect, useRef, useState, useSyncExternalStore } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { motion } from 'framer-motion'
import { AlertCircle, BookOpen, CheckCircle2, Plus, Search, Server, Sparkles, Trash2, Upload } from 'lucide-react'
import { ChatThread } from './ChatThread'
import { ChatComposer } from './ChatComposer'
import { MaterialUploadForm } from './MaterialUploadForm'
import { getHistory, useDeleteHistory, useHistory, useRagUsage, useRenameHistory, useTogglePinHistory } from '@/api/rag'
import { useDeleteUserLlmConfig, useSaveUserLlmConfig, useTestUserLlmConfig, useUserLlmConfig } from '@/api/llm'
import { useAddFavorite, useDeleteFavorite, useFavorites } from '@/api/favorites'
import { LARGE_UPLOAD_CHUNK_SIZE, MAX_TEMPORARY_MATERIAL_BYTES, uploadMaterialInChunks, uploadTemporaryMaterial, useMaterials } from '@/api/materials'
import { GENERAL_PROMPTS, MATERIAL_PROMPTS } from '@/constants'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { cleanTemporaryMaterialText, cn, formatBytes, inferSourceType } from '@/lib/utils'
import { queryClient } from '@/lib/query-client'
import { useToast } from '@/components/ui/toast'
import { useGlobalSearch } from '@/hooks/useGlobalSearch'
import { GlobalSearch } from '@/components/layout/GlobalSearch'
import { LOGIN_REQUIRED_MESSAGE, redirectToLogin } from '@/lib/auth-gate'
import { useAuth } from '@/context/AuthContext'
import {
  getChatSessionSnapshot,
  pauseActiveChatStream,
  resetChatSession,
  selectHistorySession,
  startChatSessionStream,
  subscribeChatSession,
  updateChatSession,
} from '@/lib/chat-session'
import type { HistoryItem, RagSource, TemporaryMaterial, UserLlmConfig } from '@/types'
import type { UploadProgress, UploadProgressItem } from '@/api/materials'

/**
 * ChatPage -- 聊天主页（项目最核心的页面组件）
 *
 * 【路由】/workspace/chat
 *
 * 【页面结构】
 *
 * 顶部导航栏（h-9）：
 *   左侧：当前模式状态文字（"基于通用知识回答" / "已绑定：xxx"）
 *   中间：模式切换标签（智能 / 资料）
 *   右侧：上传资料按钮（资料模式）+ 搜索按钮
 *
 * 空状态布局（isEmptyChat=true）：
 *   居中 hero 布局，显示大标题"智能问答" + 资料选择器（资料模式）+ 居中输入框
 *
 * 有消息布局（isEmptyChat=false）：
 *   上方：ChatThread 消息列表（支持富文本渲染、来源引用）
 *   下方：ChatComposer 输入框（底部固定）
 *
 * 弹窗：
 *   - 资料上传弹窗（MaterialUploadForm）
 *   - 模型切换弹窗（系统默认 / 自定义 LLM 配置，支持保存多个、测试连接）
 *
 * 【核心功能详解】
 *
 * 1. 两种问答模式：
 *    - GENERAL（智能问答）：基于通用知识回答，支持上传临时资料增强上下文
 *    - MATERIAL（资料问答）：绑定一份资料，基于 RAG 检索回答
 *
 * 2. SSE 流式回答（核心交互）：
 *    通过 chat-session.ts 的 External Store 模式管理状态。
 *    用户提交问题后，startChatSessionStream() 发起 SSE 连接，
 *    流式接收 AI 回答的文本片段（delta），每秒 10+ 次高频更新。
 *    使用 useSyncExternalStore 订阅状态变化，而非 React useState，
 *    这样可以在流式输出时避免不必要的组件重渲染。
 *
 * 3. URL 参数同步：
 *    - materialId：当前绑定的资料 ID（资料模式）
 *    - chunkId：当前选中的片段 ID
 *    - historyId：当前加载的历史会话 ID
 *    - new=1：新建会话标记
 *
 * 4. 模型切换弹窗：
 *    - 左侧边栏：系统默认模型 + 用户保存的自定义模型列表
 *    - 右侧表单：显示名称、URL 地址、API 密钥、模型名称
 *    - 支持测试连接、保存、删除自定义模型
 *    - 启用自定义模型后，今日问答次数变为不限
 *
 * 5. 临时资料上传（通用模式）：
 *    用户可以上传文件作为临时资料，AI 在回答时会参考这些资料。
 *    临时资料不会保存到资料库，仅在当前会话有效。
 *    支持多文件上传，每个文件独立追踪上传和解析进度。
 *
 * 【状态管理架构】
 * 使用 External Store 模式（chat-session.ts）管理核心聊天状态：
 * - messages: 消息列表
 * - input: 输入框文本
 * - images: 待发送图片
 * - streaming: 是否正在流式输出
 * - mode: 问答模式
 * - materialId/chunkId: 当前绑定的资料
 * - temporaryMaterial: 临时资料
 * - selectedHistoryId: 当前历史会话 ID
 */
export function ChatPage() {
  const navigate = useNavigate()
  const [searchParams, setSearchParams] = useSearchParams()
  const consumedNewChatRef = useRef(false)
  const { isAuthenticated } = useAuth()

  // === 数据获取（React Query hooks） ===
  /** 历史会话列表 */
  const { data: historyItems = [] } = useHistory()
  /** 今日使用额度信息 */
  const { data: ragUsage } = useRagUsage()
  /** 用户 LLM 配置（模型设置） */
  const { data: llmConfig } = useUserLlmConfig()
  /** 收藏列表 */
  const { data: favorites = [] } = useFavorites()
  /** 资料列表 */
  const { data: materials = [] } = useMaterials()

  // === Mutations ===
  const deleteHistoryMutation = useDeleteHistory()
  const renameHistoryMutation = useRenameHistory()
  const togglePinHistoryMutation = useTogglePinHistory()
  const addFavoriteMutation = useAddFavorite()
  const deleteFavoriteMutation = useDeleteFavorite()
  const saveLlmConfigMutation = useSaveUserLlmConfig()
  const testLlmConfigMutation = useTestUserLlmConfig()
  const deleteLlmConfigMutation = useDeleteUserLlmConfig()

  const { showToast } = useToast()
  const { open: searchOpen, setOpen: setSearchOpen, close: closeSearch } = useGlobalSearch()

  const requireLogin = () => {
    if (isAuthenticated) return true
    showToast(LOGIN_REQUIRED_MESSAGE, 2000)
    redirectToLogin()
    return false
  }

  const openUploadDialog = () => {
    if (!requireLogin()) return
    setUploadDialogOpen(true)
  }

  const openModelDialog = () => {
    if (!requireLogin()) return
    setModelDialogOpen(true)
  }

  // === 模型切换弹窗状态 ===
  /** 模型切换弹窗是否打开 */
  const [modelDialogOpen, setModelDialogOpen] = useState(false)
  /** 模型模式：'SYSTEM' 系统默认 | 'CUSTOM' 自定义 */
  const [modelMode, setModelMode] = useState<'SYSTEM' | 'CUSTOM'>('SYSTEM')
  /** 当前选中的自定义模型配置 ID */
  const [selectedConfigId, setSelectedConfigId] = useState<string | null>(null)
  /** 模型表单字段 */
  const [displayName, setDisplayName] = useState('')  // 显示名称
  const [baseUrl, setBaseUrl] = useState('')          // API URL 地址
  const [apiKey, setApiKey] = useState('')            // API 密钥
  const [modelName, setModelName] = useState('')      // 模型名称
  /** 模型操作的通知消息（测试/保存/删除结果） */
  const [actionNotice, setActionNotice] = useState<{ type: 'info' | 'success' | 'error'; message: string } | null>(null)
  /** 删除确认状态（第一次点击显示确认，第二次执行删除） */
  const [deleteConfirmId, setDeleteConfirmId] = useState<string | null>(null)
  /** 本地 LLM 配置（乐观更新，避免等待服务器响应） */
  const [localLlmConfig, setLocalLlmConfig] = useState<UserLlmConfig | null>(null)
  /** 正在通过 URL historyId 兜底加载的历史 ID，避免列表刷新期间重复请求同一条详情。 */
  const [fallbackHistoryLoadingId, setFallbackHistoryLoadingId] = useState<string | null>(null)

  // === 资料上传弹窗状态 ===
  /** 上传弹窗是否打开 */
  const [uploadDialogOpen, setUploadDialogOpen] = useState(false)
  /** 是否正在上传 */
  const [uploading, setUploading] = useState(false)
  /** 单文件上传进度 */
  const [uploadProgress, setUploadProgress] = useState<UploadProgress | null>(null)
  /** 多文件上传进度列表 */
  const [uploadProgressItems, setUploadProgressItems] = useState<UploadProgressItem[]>([])

  // === 临时资料上传状态 ===
  /** 正在上传/解析的临时资料文件信息 */
  const [temporaryUploadFile, setTemporaryUploadFile] = useState<{ name: string; size: number; sourceType: string } | null>(null)
  /** 临时上传的文件列表（用于显示附件卡片） */
  const [temporaryUploadFiles, setTemporaryUploadFiles] = useState<{ name: string; size?: number | null; type?: string | null }[]>([])
  /** 临时资料上传/解析进度 */
  const [temporaryUploadProgress, setTemporaryUploadProgress] = useState<{ phase: 'uploading' | 'processing'; percent: number; message?: string } | null>(null)
  /** 临时资料上传/解析错误 */
  const [temporaryUploadError, setTemporaryUploadError] = useState<string | null>(null)

  /** 有效的 LLM 配置（优先本地乐观更新 > 服务器数据） */
  const effectiveLlmConfig = localLlmConfig ?? llmConfig ?? null

  /**
   * 订阅 chat-session 外部状态存储
   * 使用 useSyncExternalStore 而非 useState 的原因：
   * SSE 流式输出时，文本片段每秒更新 10+ 次，
   * 如果用 useState 会导致每次更新都触发完整的 React 渲染周期。
   * External Store 模式可以在 store 内部批量更新，
   * React 只在需要时读取快照，避免不必要的重渲染。
   */
  const chat = useSyncExternalStore(
    subscribeChatSession,
    getChatSessionSnapshot,
    getChatSessionSnapshot,
  )

  // === 从 External Store 解构聊天状态 ===
  const {
    selectedHistoryId,   // 当前加载的历史会话 ID
    conversationId,      // 当前会话 ID，继续生成时用于承接上一段回答
    currentQuestionId,   // 当前问题 ID
    mode,                // 问答模式：'GENERAL' | 'MATERIAL'
    input,               // 输入框文本
    images,              // 待发送的图片列表
    messages,            // 消息列表（ChatMessage[]）
    materialId: selectedMaterialId,   // 当前绑定的资料 ID
    chunkId: selectedChunkId,         // 当前选中的片段 ID
    temporaryMaterial,   // 临时资料对象
    temporaryMaterialPending, // 临时资料是否还在等待随下一条消息提交
    streaming,           // 是否正在流式输出
  } = chat

  // === URL 参数 ===
  const newChatParam = searchParams.get('new')       // 新建会话标记（'1' 时重置会话）
  const historyParam = searchParams.get('historyId')  // 历史会话 ID
  const materialParam = searchParams.get('materialId')  // 资料 ID
  const chunkParam = searchParams.get('chunkId')        // 片段 ID

  /**
   * LLM 配置同步效果
   * 当服务器返回的 LLM 配置变化时，同步到本地状态
   * 初始化模型弹窗的表单字段
   */
  useEffect(() => {
    if (!llmConfig) return
    setModelMode(llmConfig.enabled ? 'CUSTOM' : 'SYSTEM')
    setSelectedConfigId(llmConfig.activeConfigId == null ? null : String(llmConfig.activeConfigId))
    const active = llmConfig.configs?.find((item) => String(item.id) === String(llmConfig.activeConfigId))
    setDisplayName(active?.displayName || llmConfig.model || '')
    setBaseUrl(llmConfig.baseUrl || '')
    setModelName(llmConfig.model || '')
    setApiKey('')
    setActionNotice(null)
    setDeleteConfirmId(null)
  }, [llmConfig])

  /**
   * URL 参数同步效果
   * 监听 URL 参数变化，同步到聊天状态：
   * - new=1：重置会话（新建聊天）
   * - historyId：加载指定的历史会话
   * - materialId + chunkId：切换到指定的资料和片段
   */
  useEffect(() => {
    if (newChatParam === '1') {
      if (consumedNewChatRef.current) return
      consumedNewChatRef.current = true
      setSearchParams(new URLSearchParams(), { replace: true })
      resetChatSession()
      return
    }
    consumedNewChatRef.current = false

    if (historyParam) {
      const target = historyItems.find((item) => String(item.id) === historyParam)
      if (target && selectedHistoryId !== historyParam) {
        // 历史会话以服务端快照为准，避免把当前未完成的聊天状态混入历史详情。
        selectHistorySession(target)
      } else if (!target && selectedHistoryId !== historyParam && fallbackHistoryLoadingId !== historyParam) {
        // 历史列表会按 conversationId 合并，只展示每个会话最新一条。
        // 收藏页可能跳到同一会话中的较早 questionId，因此列表找不到时直接请求详情兜底恢复。
        setFallbackHistoryLoadingId(historyParam)
        getHistory(historyParam)
          .then((detail) => selectHistorySession(detail))
          .catch(() => showToast('历史记录不存在或已被删除', 2000))
          .finally(() => setFallbackHistoryLoadingId((current) => current === historyParam ? null : current))
      }
      return
    }

    if (materialParam && (mode !== 'MATERIAL' || selectedMaterialId !== materialParam || selectedChunkId !== chunkParam)) {
      // 资料阅读器跳转过来时，仅同步资料上下文，不主动创建新消息。
      updateChatSession({
        mode: 'MATERIAL',
        materialId: materialParam,
        chunkId: chunkParam,
      })
    }
  }, [chunkParam, fallbackHistoryLoadingId, historyItems, historyParam, materialParam, mode, newChatParam, selectedChunkId, selectedHistoryId, selectedMaterialId, setSearchParams, showToast])

  /**
   * 更新 URL 参数以反映当前聊天上下文
   * 资料模式时保留 materialId 和 chunkId，通用模式时清空
   */
  const updateLocationForContext = (newMode: 'GENERAL' | 'MATERIAL', materialId: string | null, chunkId: string | null) => {
    const nextParams = new URLSearchParams()
    if (newMode === 'MATERIAL' && materialId) {
      nextParams.set('materialId', materialId)
      if (chunkId) nextParams.set('chunkId', chunkId)
    }
    setSearchParams(nextParams, { replace: true })
  }

  /**
   * 切换问答模式（智能 <-> 资料）
   * 切换时重置聊天会话，保留目标模式的资料绑定
   */
  const handleModeChange = (newMode: 'GENERAL' | 'MATERIAL') => {
    if (newMode === mode) return
    // 切到通用模式时清空资料绑定，切回资料模式时保留最近一次选中的资料。
    const nextMaterialId = newMode === 'MATERIAL' ? selectedMaterialId : null
    resetChatSession({
      mode: newMode,
      materialId: nextMaterialId,
      chunkId: newMode === 'MATERIAL' ? selectedChunkId : null,
    })
    updateLocationForContext(newMode, nextMaterialId, newMode === 'MATERIAL' ? selectedChunkId : null)
  }

  /** 新建会话（保留当前模式和资料绑定，清除消息和临时资料） */
  const handleNewChat = () => {
    resetChatSession({
      mode,
      materialId: mode === 'MATERIAL' ? selectedMaterialId : null,
      chunkId: mode === 'MATERIAL' ? selectedChunkId : null,
      images,
      temporaryMaterial: null,
    })
    updateLocationForContext(mode, selectedMaterialId, selectedChunkId)
  }

  const handleDeleteHistory = (id: string) => {
    deleteHistoryMutation.mutate(id, {
      onSuccess: () => {
        showToast('会话已删除')
        // 删除当前正在查看的会话时，回到同模式的新会话，避免界面继续指向失效 historyId。
        if (selectedHistoryId === id || currentQuestionId === id) handleNewChat()
      },
      onError: (error) => showToast(error instanceof Error ? error.message : '删除失败'),
    })
  }

  const handleRenameHistory = (id: string, title: string) => {
    renameHistoryMutation.mutate({ id, title }, {
      onSuccess: () => {
        showToast('会话已重命名')
        queryClient.invalidateQueries({ queryKey: ['history'] })
      },
      onError: (error) => showToast(error instanceof Error ? error.message : '重命名失败'),
    })
  }

  const handleTogglePinHistory = (id: string) => {
    togglePinHistoryMutation.mutate(id, {
      onSuccess: () => {
        showToast('会话已更新')
        queryClient.invalidateQueries({ queryKey: ['history'] })
      },
      onError: (error) => showToast(error instanceof Error ? error.message : '操作失败'),
    })
  }

  const getHistoryFavoriteId = (item: HistoryItem) =>
    favorites.find((favorite) => String(favorite.questionId) === String(item.id))?.id || item.favoriteId || null

  const handleToggleFavorite = (item: HistoryItem) => {
    const favoriteId = getHistoryFavoriteId(item)
    if (favoriteId) {
      // favoriteId 可能来自收藏列表或历史项冗余字段，优先用真实收藏记录删除。
      deleteFavoriteMutation.mutate(favoriteId, {
        onSuccess: () => showToast('已取消收藏'),
        onError: (error) => showToast(error instanceof Error ? error.message : '取消收藏失败'),
      })
    } else {
      addFavoriteMutation.mutate(String(item.id), {
        onSuccess: () => showToast('已加入收藏'),
        onError: (error) => showToast(error instanceof Error ? error.message : '收藏失败'),
      })
    }
  }

  const handleSelectHistory = (item: HistoryItem) => {
    selectHistorySession(item)
    const nextParams = new URLSearchParams()
    nextParams.set('historyId', String(item.id))
    const source = item.sources?.[0]
    if (source) {
      // 带上首个来源，聊天页恢复历史时也能同步左侧资料/片段上下文。
      nextParams.set('materialId', source.materialId)
      nextParams.set('chunkId', source.chunkId)
      if (source.pageNo && source.pageNo > 0) nextParams.set('pageNo', String(source.pageNo))
      nextParams.set('view', 'smart')
    }
    setSearchParams(nextParams, { replace: true })
  }

  const handleMaterialSelect = (materialId: string) => {
    updateChatSession({
      mode: 'MATERIAL',
      materialId,
      chunkId: null,
    })
    updateLocationForContext('MATERIAL', materialId, null)
  }

  const handleUploadMaterial = async (data: { title?: string; file?: File; files?: File[] }) => {
    if (!requireLogin()) return
    const files = data.files?.length ? data.files : data.file ? [data.file] : []
    await handleUploadMaterialFiles(files, data.title)
  }

  /**
   * handleUploadMaterialFiles -- 持久资料上传（资料模式核心功能）
   *
   * 将文件上传为持久资料（保存到资料库），使用分片上传策略。
   * 上传完成后自动选中第一份资料并切换到资料问答模式。
   *
   * 流程：
   * 1. 并行上传所有文件（uploadMaterialInChunks，分片大小 1MB）
   * 2. 每个文件独立追踪分片上传进度
   * 3. 全部完成后：
   *    - 刷新资料列表缓存
   *    - 自动选中第一份资料
   *    - 切换到 MATERIAL 模式
   *    - 有失败时显示错误信息
   */
  const handleUploadMaterialFiles = async (files: File[], title?: string) => {
    if (!requireLogin()) return
    if (files.length === 0) return
    // 持久资料上传与临时资料共用顶部 uploading 状态，进入前先清空两类进度/错误残留。
    setUploading(true)
    setUploadProgress(null)
    setUploadProgressItems(createUploadProgressItems(files))
    setTemporaryUploadError(null)
    try {
      const results = await Promise.allSettled(files.map((file, index) => {
        const id = uploadProgressItemId(file, index)
        // 每个文件独立分片上传，失败不会中断其他文件的上传结果收集。
        return uploadMaterialInChunks({
          file,
          title: files.length === 1 ? title : undefined,
          sourceType: inferSourceType(file.name),
        }, (progress) => {
          setUploadProgressItems((current) => updateUploadProgressItem(current, id, progress))
        }).then((session) => {
          setUploadProgressItems((current) => updateUploadProgressItem(current, id, {
            phase: 'processing',
            percent: 100,
            uploadedChunks: Math.max(1, Math.ceil(file.size / LARGE_UPLOAD_CHUNK_SIZE)),
            totalChunks: Math.max(1, Math.ceil(file.size / LARGE_UPLOAD_CHUNK_SIZE)),
            stage: session.processingStage || session.parseStage || '资料已可用',
            message: session.processingMessage || session.parseMessage || '资料已可用于阅读和问答，后台增强任务可能仍在继续',
          }, 'success'))
          return session
        }).catch((error) => {
          setUploadProgressItems((current) => updateUploadProgressItem(current, id, null, 'error', error instanceof Error ? error.message : '上传失败'))
          throw error
        })
      }))
      const successfulMaterialIds = results
        .filter((result): result is PromiseFulfilledResult<Awaited<ReturnType<typeof uploadMaterialInChunks>>> => result.status === 'fulfilled')
        .map((result) => result.value.materialId)
        .filter((materialId): materialId is string => !!materialId)
      const failedCount = results.length - successfulMaterialIds.length
      await queryClient.invalidateQueries({ queryKey: ['materials'] })
      if (failedCount > 0) {
        // 保留进度列表让用户看到具体失败项，不自动关闭上传弹窗。
        const message = `${failedCount}/${files.length} 份资料上传失败，请查看进度列表`
        setTemporaryUploadError(message)
        showToast(message)
        return
      }
      setUploadDialogOpen(false)
      const firstMaterialId = successfulMaterialIds[0]
      if (firstMaterialId) {
        // 多文件上传完成后选中第一份资料，给后续资料问答一个确定的默认上下文。
        updateChatSession({
          mode: 'MATERIAL',
          materialId: firstMaterialId,
          chunkId: null,
        })
        updateLocationForContext('MATERIAL', firstMaterialId, null)
        showToast(files.length > 1 ? `已上传 ${files.length} 份资料，并选中第一份` : '资料已上传并选中，可以直接提问')
      } else {
        showToast(files.length > 1 ? `已上传 ${files.length} 份资料，请在列表中选择后提问` : '资料已上传，请在列表中选择后提问')
      }
    } catch (error) {
      const message = error instanceof Error ? error.message : '资料上传失败，请重试'
      setTemporaryUploadError(message)
      showToast(message)
    } finally {
      setUploading(false)
      setUploadProgress(null)
    }
  }

  const handleUploadTemporaryMaterial = async (file: File) => {
    if (!requireLogin()) return
    await handleUploadTemporaryMaterials([file])
  }

  /**
   * handleUploadTemporaryMaterials -- 临时资料上传（通用模式核心功能）
   *
   * 允许用户在智能问答模式下上传文件作为临时资料。
   * AI 在回答时会参考这些临时资料的内容。
   *
   * 流程：
   * 1. 检查文件大小限制（MAX_TEMPORARY_MATERIAL_BYTES）
   * 2. 初始化上传进度状态
   * 3. 对每个文件并行调用 uploadTemporaryMaterial()
   *    - 上传过程追踪：聚合多文件进度为单一进度条
   *    - 如果进度超过 60% 仍在上传状态，300ms 后自动切换为"解析中"状态
   * 4. 上传完成后：
   *    - 如果已存在临时资料，合并新旧资料
   *    - 调用 updateChatSession 更新聊天状态
   *    - 显示成功/失败提示
   * 5. 最终状态清理（600ms 后隐藏进度条）
   */
  const handleUploadTemporaryMaterials = async (files: File[]) => {
    if (!requireLogin()) return
    if (files.length === 0) return
    const tooLargeFile = files.find((file) => file.size > MAX_TEMPORARY_MATERIAL_BYTES)
    if (tooLargeFile) {
      // 临时资料直接进入对话上下文，超大文件改走持久资料流程以获得后台解析和进度跟踪。
      const message = `${tooLargeFile.name} 超过智能问答临时资料上限 ${formatBytes(MAX_TEMPORARY_MATERIAL_BYTES)}；大文件请切换到资料问答上传，系统会在后台解析并显示进度。`
      setTemporaryUploadError(message)
      setTemporaryUploadProgress(null)
      setTemporaryUploadFile(null)
      setTemporaryUploadFiles([])
      showToast(message)
      return
    }
    const totalTemporaryBytes = files.reduce((sum, file) => sum + file.size, 0)
    if (totalTemporaryBytes > MAX_TEMPORARY_MATERIAL_BYTES) {
      // 多文件临时资料会并发同步解析，总量也限制在同一阈值内，避免多个大文件同时占用上传、内存和解析资源。
      const message = `本次临时资料总大小超过 ${formatBytes(MAX_TEMPORARY_MATERIAL_BYTES)}；大文件请切换到资料问答上传，系统会在后台解析并显示进度。`
      setTemporaryUploadError(message)
      setTemporaryUploadProgress(null)
      setTemporaryUploadFile(null)
      setTemporaryUploadFiles([])
      showToast(message)
      return
    }
    setUploading(true)
    setTemporaryUploadFile({
      name: files.length > 1 ? `${files.length} 份临时资料` : files[0].name,
      size: files.reduce((sum, file) => sum + file.size, 0),
      sourceType: files.length > 1 ? 'MULTI' : inferSourceType(files[0].name),
    })
    // 输入框上方只展示“本轮仍待发送”的临时资料；已经随上一轮发送过的资料只保留在会话上下文里，
    // 不能在用户上传新文件时再次混进附件栏，否则会像旧文件又被重新上传了一样。
    const pendingTemporaryMaterial = removeAlreadySentTemporaryMaterials(
      temporaryMaterialPending ? temporaryMaterial : null,
      messages,
    )
    setTemporaryUploadFiles([
      ...temporaryMaterialFileItems(pendingTemporaryMaterial),
      ...files.map((file) => ({ name: file.name, size: file.size, type: inferSourceType(file.name) })),
    ])
    setTemporaryUploadProgress({ phase: 'uploading', percent: 1, message: files.length > 1 ? `准备上传 ${files.length} 份资料` : '准备上传' })
    setTemporaryUploadError(null)
    try {
      const progressByIndex = new Map<number, { phase: 'uploading' | 'processing'; percent: number; message?: string }>()
      const updateAggregateProgress = () => {
        // 多个临时文件共用一个提示条，这里把各文件进度折算为平均百分比。
        const values = files.map((_, index) => progressByIndex.get(index) || { phase: 'uploading' as const, percent: 1 })
        const completed = values.filter((progress) => progress.percent >= 100).length
        const percent = Math.max(1, Math.min(99, Math.round(values.reduce((sum, progress) => sum + progress.percent, 0) / values.length)))
        const phase = values.some((progress) => progress.phase === 'uploading') ? 'uploading' : 'processing'
        setTemporaryUploadProgress({
          phase,
          percent,
          message: files.length > 1 ? `已完成 ${completed}/${files.length} 份` : values[0]?.message || '正在处理资料',
        })
      }
      const results = await Promise.allSettled(files.map((file, index) =>
        uploadTemporaryMaterial({
          file,
          title: file.name,
          sourceType: inferSourceType(file.name),
        }, (progress) => {
          progressByIndex.set(index, progress)
          updateAggregateProgress()
          if (progress.phase === 'uploading' && progress.percent >= 60) {
            window.setTimeout(() => {
              const current = progressByIndex.get(index)
              if (current?.phase === 'uploading') {
                // 后端可能在上传后进入 OCR/解析但尚未回调，先切到解析态避免进度条卡在上传中。
                progressByIndex.set(index, { phase: 'processing', percent: 65, message: '正在解析资料，扫描版 PDF 会进行 OCR' })
                updateAggregateProgress()
              }
            }, 300)
          }
        })
      ))
      const uploaded = results.flatMap((result, index) => {
        // 后端正常返回但正文为空时，不能把它当作可问答资料写入会话，否则下一轮会出现“上传成功但读取不到内容”。
        if (result.status !== 'fulfilled' || !isUsableTemporaryMaterial(result.value)) return []
        return [{
          ...result.value,
          files: [{
            name: result.value.originalName || result.value.title || files[index]?.name || '临时资料',
            size: result.value.fileSize ?? files[index]?.size,
            type: result.value.sourceType || inferSourceType(files[index]?.name || ''),
          }],
        }]
      })
      const failedCount = results.length - uploaded.length
      if (uploaded.length === 0) {
        // 全部失败时抛错进入统一错误分支；部分失败则保留成功解析的资料。
        throw new Error(failedCount > 0 ? `${failedCount}/${files.length} 份临时资料解析失败` : '临时资料解析失败，请重试')
      }
      // 新上传资料只和“尚未发送”的临时资料合并；已发送过的临时资料由后端 conversationId 继续作为上下文恢复。
      const temporary = mergeTemporaryMaterials(pendingTemporaryMaterial ? [pendingTemporaryMaterial, ...uploaded] : uploaded)
      setTemporaryUploadProgress({ phase: 'processing', percent: 100, message: '解析完成' })
      updateChatSession({
        mode: 'GENERAL',
        temporaryMaterial: temporary,
      })
      if (failedCount > 0) {
        const message = `已解析 ${uploaded.length}/${files.length} 份临时资料，${failedCount} 份失败`
        setTemporaryUploadError(message)
        showToast(message)
      } else {
        showToast(files.length > 1 ? `已解析 ${files.length} 份临时资料，可以在智能问答中提问` : '临时资料已解析，可以在智能问答中提问')
      }
    } catch (error) {
      const message = error instanceof Error ? error.message : '临时资料解析失败，请重试'
      setTemporaryUploadError(message)
      showToast(message)
    } finally {
      setUploading(false)
      setTemporaryUploadFile(null)
      setTemporaryUploadFiles([])
      window.setTimeout(() => setTemporaryUploadProgress(null), 600)
    }
  }

  /**
   * handleSubmit -- 提交问题（核心交互入口）
   *
   * 流程：
   * 1. 校验：有输入文本、不在流式中、资料模式下已选择资料
   * 2. 判断临时资料是否仍处于待发送状态；只有首轮或替换资料后的下一轮会提交资料正文
   * 3. 调用 startChatSessionStream() 发起 SSE 流式请求
   *    （该函数在 chat-session.ts 中管理完整的流式接收过程）
   * 4. 更新 URL 参数
   */
  const handleSubmit = () => {
    if (!requireLogin()) return
    const question = input.trim()
    if (!question || streaming) return
    if (mode === 'MATERIAL' && !selectedMaterialId) {
      // 资料问答必须有 materialId，否则后端无法限定 RAG 检索范围。
      showToast('请先上传或选择资料')
      return
    }

    startChatSessionStream({
      question,
      mode,
      materialId: mode === 'MATERIAL' ? selectedMaterialId : null,
      chunkId: mode === 'MATERIAL' ? selectedChunkId : null,
      // 临时资料只通过 temporaryMaterial 字段提交一次；后续追问由后端按 conversationId 恢复上下文，避免每轮重复携带大段文本。
      selectedText: null,
      temporaryMaterial: mode === 'GENERAL' && temporaryMaterialPending ? temporaryMaterial : null,
    })
    updateLocationForContext(mode, selectedMaterialId, selectedChunkId)
  }

  /** 点击 AI 回答下方的“继续生成”，直接续接当前会话，不要求用户再手动输入“继续”。 */
  const handleContinueGeneration = () => {
    if (!requireLogin()) return
    if (streaming) return
    if (!conversationId) {
      showToast('当前会话还没有可继续的上下文')
      return
    }
    startChatSessionStream({
      question: '继续',
      mode,
      materialId: mode === 'MATERIAL' ? selectedMaterialId : null,
      chunkId: mode === 'MATERIAL' ? selectedChunkId : null,
      selectedText: null,
      temporaryMaterial: null,
    })
    updateLocationForContext(mode, selectedMaterialId, selectedChunkId)
  }

  const currentLlmPayload = () => ({
    id: selectedConfigId,
    enabled: modelMode === 'CUSTOM',
    displayName: displayName.trim(),
    baseUrl: baseUrl.trim(),
    apiKey,
    model: modelName.trim(),
  })

  const handleTestModel = () => {
    if (!requireLogin()) return
    setDeleteConfirmId(null)
    setActionNotice({ type: 'info', message: '正在测试连接...' })
    testLlmConfigMutation.mutate(currentLlmPayload(), {
      onSuccess: (result) => {
        setActionNotice({ type: result.ok ? 'success' : 'error', message: result.message })
      },
      onError: (error) => {
        const message = error instanceof Error ? error.message : '连通性测试失败'
        setActionNotice({ type: 'error', message })
      },
    })
  }

  const ensureSavedModelVisible = (config: UserLlmConfig): UserLlmConfig => {
    if (modelMode !== 'CUSTOM') return config
    const activeId = config.activeConfigId ?? selectedConfigId ?? `local-${Date.now()}`
    const exists = (config.configs || []).some((item) => String(item.id) === String(activeId))
    if (exists) return config
    // 保存接口返回延迟或缺少新配置时，先在本地补一条，保证弹窗列表立即可见。
    return {
      ...config,
      enabled: true,
      activeConfigId: activeId,
      activeLabel: displayName || modelName || '自定义模型',
      configs: [
        {
          id: activeId,
          displayName: displayName || modelName || '自定义模型',
          baseUrl,
          model: modelName,
          hasApiKey: Boolean(apiKey),
          active: true,
        },
        ...(config.configs || []),
      ],
    }
  }

  const handleSaveModel = () => {
    if (!requireLogin()) return
    setDeleteConfirmId(null)
    setActionNotice({ type: 'info', message: modelMode === 'CUSTOM' ? '正在应用模型配置...' : '正在应用系统模型...' })
    saveLlmConfigMutation.mutate(currentLlmPayload(), {
      onSuccess: (nextConfig) => {
        const mergedConfig = ensureSavedModelVisible(mergeLlmConfig(effectiveLlmConfig, nextConfig))
        setLocalLlmConfig(mergedConfig)
        queryClient.setQueryData(['llm', 'user-config'], mergedConfig)
        setActionNotice({ type: 'success', message: modelMode === 'CUSTOM' ? '已应用自定义模型' : '已应用系统模型' })
        if (modelMode === 'CUSTOM') {
          const activeId = mergedConfig.activeConfigId == null ? null : String(mergedConfig.activeConfigId)
          const activeConfig = mergedConfig.configs?.find((item) => String(item.id) === activeId)
          setSelectedConfigId(activeId)
          setDisplayName(activeConfig?.displayName || displayName)
          setBaseUrl(activeConfig?.baseUrl || baseUrl)
          setModelName(activeConfig?.model || modelName)
          setApiKey('')
        }
      },
      onError: (error) => setActionNotice({ type: 'error', message: error instanceof Error ? error.message : '应用失败' }),
    })
  }

  const applySelectedModel = (config: NonNullable<UserLlmConfig['configs']>[number]) => {
    setDeleteConfirmId(null)
    setActionNotice({ type: 'info', message: `正在切换到「${config.displayName || config.model || '自定义模型'}」...` })
    saveLlmConfigMutation.mutate({
      id: config.id,
      enabled: true,
      displayName: config.displayName || config.model || '自定义模型',
      baseUrl: config.baseUrl || '',
      apiKey: '',
      model: config.model || '',
    }, {
      onSuccess: (nextConfig) => {
        const mergedConfig = mergeLlmConfig(effectiveLlmConfig, nextConfig)
        const activeId = mergedConfig.activeConfigId == null ? null : String(mergedConfig.activeConfigId)
        const activeConfig = mergedConfig.configs?.find((item) => String(item.id) === activeId)
        setLocalLlmConfig(mergedConfig)
        queryClient.setQueryData(['llm', 'user-config'], mergedConfig)
        setModelMode(activeId ? 'CUSTOM' : 'SYSTEM')
        setSelectedConfigId(activeId)
        setDisplayName(activeConfig?.displayName || config.displayName || config.model || '')
        setBaseUrl(activeConfig?.baseUrl || config.baseUrl || '')
        setModelName(activeConfig?.model || config.model || '')
        setApiKey('')
        setActionNotice({ type: 'success', message: `已切换到「${activeConfig?.displayName || activeConfig?.model || config.displayName || config.model || '自定义模型'}」` })
        queryClient.invalidateQueries({ queryKey: ['rag-usage'] })
      },
      onError: (error) => setActionNotice({ type: 'error', message: error instanceof Error ? error.message : '切换失败' }),
    })
  }

  const handleSelectSystemModel = () => {
    setModelMode('SYSTEM')
    setSelectedConfigId(null)
    setDisplayName('')
    setBaseUrl('')
    setModelName('')
    setApiKey('')
    setDeleteConfirmId(null)
    setActionNotice({ type: 'info', message: '正在切换到系统模型...' })
    saveLlmConfigMutation.mutate({
      id: null,
      enabled: false,
      displayName: '',
      baseUrl: '',
      apiKey: '',
      model: '',
    }, {
      onSuccess: (nextConfig) => {
        setLocalLlmConfig(nextConfig)
        queryClient.setQueryData(['llm', 'user-config'], nextConfig)
        setActionNotice({ type: 'success', message: '已切换到 gpt5.5模型' })
        queryClient.invalidateQueries({ queryKey: ['rag-usage'] })
      },
      onError: (error) => setActionNotice({ type: 'error', message: error instanceof Error ? error.message : '切换失败' }),
    })
  }

  const handleSelectSavedModel = (id: string) => {
    const config = effectiveLlmConfig?.configs?.find((item) => String(item.id) === id)
    if (!config) return
    setModelMode('CUSTOM')
    setSelectedConfigId(String(config.id))
    setDisplayName(config.displayName || config.model)
    setBaseUrl(config.baseUrl || '')
    setModelName(config.model || '')
    setApiKey('')
    setDeleteConfirmId(null)
    applySelectedModel(config)
  }

  const handleNewModel = () => {
    setModelMode('CUSTOM')
    setSelectedConfigId(null)
    setDisplayName('')
    setBaseUrl('')
    setModelName('')
    setApiKey('')
    setActionNotice(null)
    setDeleteConfirmId(null)
  }

  const handleDeleteModel = () => {
    if (!selectedConfigId) return
    const target = effectiveLlmConfig?.configs?.find((item) => String(item.id) === selectedConfigId)
    if (deleteConfirmId !== selectedConfigId) {
      setDeleteConfirmId(selectedConfigId)
      setActionNotice({ type: 'error', message: `确认删除模型「${target?.displayName || target?.model || '自定义模型'}」？` })
      return
    }
    deleteLlmConfigMutation.mutate(selectedConfigId, {
      onSuccess: (nextConfig) => {
        setLocalLlmConfig(nextConfig)
        queryClient.setQueryData(['llm', 'user-config'], nextConfig)
        setActionNotice({ type: 'success', message: '模型已删除' })
        setModelMode('SYSTEM')
        setSelectedConfigId(null)
        setDisplayName('')
        setBaseUrl('')
        setModelName('')
        setApiKey('')
        setDeleteConfirmId(null)
      },
      onError: (error) => setActionNotice({ type: 'error', message: error instanceof Error ? error.message : '删除失败' }),
    })
  }

  const handleOpenSource = (source: RagSource) => {
    const params = new URLSearchParams()
    params.set('materialId', String(source.materialId))
    params.set('chunkId', String(source.chunkId))
    if (source.pageNo && source.pageNo > 0) params.set('pageNo', String(source.pageNo))
    params.set('view', 'smart')
    navigate({ pathname: '/workspace/reader', search: params.toString() })
  }

  const handleRemoveTemporaryMaterialFile = (index: number) => {
    const next = removeTemporaryMaterialAtIndex(temporaryMaterial, index)
    updateChatSession({ temporaryMaterial: next })
  }

  const isGeneral = mode === 'GENERAL'
  // 输入框上方只展示“待发送”的临时资料；已发送的资料继续作为会话上下文保留，不再伪装成待上传附件。
  const composerTemporaryMaterial = isGeneral && temporaryMaterialPending
    ? removeAlreadySentTemporaryMaterials(temporaryMaterial, messages)
    : null
  const temporaryMaterialLabel = temporaryMaterial?.title || temporaryMaterial?.originalName || '未命名资料'
  const quickPrompts = isGeneral ? GENERAL_PROMPTS : MATERIAL_PROMPTS
  const parsedMaterials = materials.filter((m) => m.parseStatus === 'SUCCESS' || m.parseStatus === 'PARSED')
  const selectedMaterial = selectedMaterialId
    ? materials.find((m) => m.id === selectedMaterialId) || null
    : null
  const selectedMaterialLabel = selectedMaterial?.title || selectedMaterial?.originalName || ''
  const isEmptyChat = messages.length === 0
  const usageLabel = ragUsage
    ? ragUsage.unlimited
      ? '今日问答：不限'
      : `今日剩余：${ragUsage.remainingToday ?? 0}/${ragUsage.dailyLimit}`
    : ''
  const usageExhausted = !!ragUsage && !ragUsage.unlimited && (ragUsage.remainingToday ?? 0) <= 0
  const modelFormValid = modelMode === 'SYSTEM'
    || Boolean(baseUrl.trim() && modelName.trim() && (selectedConfigId || apiKey.trim()))

  return (
    <motion.div
      className="flex h-full min-h-0 flex-col overflow-hidden bg-[#fcfcfd] dark:bg-[#171a21]"
      initial={{ opacity: 0 }}
      animate={{ opacity: 1 }}
      transition={{ duration: 0.3 }}
    >
      <div className="grid min-h-11 shrink-0 grid-cols-[auto_1fr_auto] items-center gap-2 border-b border-[#edf1f5] bg-[#fcfcfd] px-2 py-1.5 dark:border-slate-800 dark:bg-[#171a21] md:h-9 md:grid-cols-[1fr_auto_1fr] md:border-b-0 md:px-5 md:py-0">
        <div className="hidden min-w-0 md:block">
          <p className="truncate text-xs text-muted-foreground">
            {isGeneral
              ? temporaryMaterial && temporaryMaterialPending
                ? `待发送临时资料：${temporaryMaterialLabel}`
                : '基于通用知识和当前对话回答'
              : selectedMaterialId
                ? '已绑定资料，可基于资料提问'
                : '请选择资料后提问'}
          </p>
        </div>
        <div className="flex justify-self-start rounded-full bg-[#f1f3f7] p-0.5 dark:bg-white/[0.08] md:justify-self-center">
          <Button
            variant="ghost"
            size="sm"
            className={cn(
              'h-7 rounded-full px-3 text-[13px] font-medium text-slate-500 hover:bg-white/70 dark:text-slate-300 dark:hover:bg-white/[0.08] sm:px-3.5',
              isGeneral && 'bg-white text-slate-900 shadow-[0_3px_12px_rgba(15,23,42,0.05)] hover:bg-white dark:bg-slate-900 dark:text-white',
            )}
            onClick={() => handleModeChange('GENERAL')}
          >
            <Sparkles className="mr-1.5 h-3.5 w-3.5" />
            智能
          </Button>
          <Button
            variant="ghost"
            size="sm"
            className={cn(
              'h-7 rounded-full px-3 text-[13px] font-medium text-slate-500 hover:bg-white/70 dark:text-slate-300 dark:hover:bg-white/[0.08] sm:px-3.5',
              !isGeneral && 'bg-white text-slate-900 shadow-[0_3px_12px_rgba(15,23,42,0.05)] hover:bg-white dark:bg-slate-900 dark:text-white',
            )}
            onClick={() => handleModeChange('MATERIAL')}
          >
            <BookOpen className="mr-1.5 h-3.5 w-3.5" />
            资料
          </Button>
        </div>
        <div className="flex min-w-0 items-center justify-end gap-1.5 md:gap-2">
          {!isGeneral && (
            <Button
              type="button"
              variant="ghost"
              size="sm"
              className="hidden h-7 shrink-0 rounded-full px-2.5 text-xs font-medium text-slate-500 hover:bg-[#f2f4f7] hover:text-slate-700 dark:text-slate-300 dark:hover:bg-slate-800 dark:hover:text-slate-100 sm:inline-flex"
              onClick={openUploadDialog}
            >
              <Upload className="mr-1 h-3.5 w-3.5" />
              上传资料
            </Button>
          )}
          {!isGeneral && selectedMaterialId ? (
            <Badge variant="outline" className="hidden shrink-0 text-[10px] font-medium sm:inline-flex">
              {selectedMaterial?.chunkCount || 0} 个切片
            </Badge>
          ) : (
            <Badge variant="secondary" className="hidden shrink-0 text-[10px] font-medium sm:inline-flex">
              {isGeneral ? '通用模式' : '资料模式'}
            </Badge>
          )}
          <Button
            type="button"
            variant="ghost"
            size="icon"
            title="搜索"
            className="h-7 w-7 shrink-0 rounded-full text-slate-500 hover:bg-[#f2f4f7] hover:text-slate-700 dark:text-slate-300 dark:hover:bg-slate-800 dark:hover:text-slate-100"
            onClick={() => setSearchOpen(true)}
          >
            <Search className="h-4 w-4" />
            <span className="sr-only">搜索</span>
          </Button>
        </div>
      </div>

      {isEmptyChat ? (
        <div className="flex flex-1 flex-col items-center justify-center px-3 pb-4 md:px-6 md:pb-14">
          <div className="mb-4 text-center md:mb-6">
            <h2 className="text-3xl font-black tracking-[0.02em] text-black dark:text-white sm:text-5xl">
              {isGeneral ? '智能问答' : '资料问答'}
            </h2>
            <p className="mt-3 text-sm text-muted-foreground">描述问题、上传资料或选择资料后开始提问</p>
          </div>
          {!isGeneral && (
            <div className="order-3 mt-4 w-full max-w-[760px]">
              {parsedMaterials.length === 0 ? (
                <div className="flex items-center justify-between gap-3 rounded-[24px] border border-dashed border-[#dfe5ec] bg-[#fafbfd] px-4 py-3 text-sm text-muted-foreground dark:border-slate-700 dark:bg-slate-900 dark:text-slate-400">
                  <span>暂无已解析资料，请先导入并完成解析。</span>
                  <Button size="sm" variant="outline" onClick={openUploadDialog}>
                    <Upload className="mr-1.5 h-3.5 w-3.5" />
                    上传资料
                  </Button>
                </div>
              ) : (
                <div className="flex gap-2">
                  <Select value={selectedMaterialId || ''} onValueChange={handleMaterialSelect}>
                    <SelectTrigger className="h-11 rounded-[24px] border-[#e2e7ee] bg-[#fafbfd] px-4 text-sm shadow-none focus:ring-1 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-100">
                      <SelectValue placeholder="选择一份资料开始提问" />
                    </SelectTrigger>
                    <SelectContent className="max-h-72 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-100">
                      {parsedMaterials.map((material) => (
                        <SelectItem key={material.id} value={material.id} className="dark:focus:bg-slate-800">
                          <span className="mr-2 rounded bg-[#eef1f5] px-1.5 py-0.5 text-[10px] font-semibold text-slate-500 dark:bg-slate-800">
                            {material.sourceType}
                          </span>
                          {material.title || material.originalName}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                  <Button
                    type="button"
                    variant="outline"
                    className="h-11 shrink-0 rounded-2xl px-3"
                    onClick={openUploadDialog}
                  >
                    <Upload className="h-4 w-4 sm:mr-1.5" />
                    <span className="hidden sm:inline">上传</span>
                  </Button>
                </div>
              )}
            </div>
          )}
            <ChatComposer
              value={input}
              onChange={(value) => updateChatSession({ input: value })}
              onSubmit={handleSubmit}
              onPauseOutput={pauseActiveChatStream}
              loading={streaming}
            mode={mode}
            onModeChange={handleModeChange}
            quickPrompts={quickPrompts}
            disabled={usageExhausted}
            disabledHint="今日问答次数已用完"
            usageLabel={usageLabel}
            modelLabel={effectiveLlmConfig?.activeLabel || 'gpt5.5模型'}
            boundMaterialLabel={!isGeneral ? selectedMaterialLabel : undefined}
            customModelEnabled={!!effectiveLlmConfig?.enabled}
            onOpenModelSettings={openModelDialog}
            images={images}
            onImagesChange={(nextImages) => updateChatSession({ images: nextImages })}
            onOpenUploadMaterial={!isGeneral ? openUploadDialog : undefined}
            onUploadMaterialFile={!isGeneral ? (file) => handleUploadMaterial({ file }) : undefined}
            onUploadMaterialFiles={!isGeneral ? handleUploadMaterialFiles : undefined}
            onUploadTemporaryMaterial={isGeneral ? handleUploadTemporaryMaterial : undefined}
            onUploadTemporaryMaterials={isGeneral ? handleUploadTemporaryMaterials : undefined}
            temporaryMaterialUploading={isGeneral && uploading}
            temporaryMaterial={composerTemporaryMaterial}
            temporaryUploadFile={isGeneral ? temporaryUploadFile : null}
            temporaryUploadFiles={isGeneral ? temporaryUploadFiles : []}
            temporaryUploadProgress={isGeneral ? temporaryUploadProgress : null}
            temporaryUploadError={isGeneral ? temporaryUploadError : null}
            onClearTemporaryMaterial={() => updateChatSession({ temporaryMaterial: null })}
            onRemoveTemporaryMaterialFile={handleRemoveTemporaryMaterialFile}
            centered
          />
        </div>
      ) : (
        <>
          <div className="min-h-0 flex-1">
            <ChatThread messages={messages} onOpenSource={handleOpenSource} onContinueGeneration={handleContinueGeneration} />
          </div>
            <ChatComposer
              value={input}
              onChange={(value) => updateChatSession({ input: value })}
              onSubmit={handleSubmit}
              onPauseOutput={pauseActiveChatStream}
              loading={streaming}
            mode={mode}
            onModeChange={handleModeChange}
            quickPrompts={quickPrompts}
            disabled={usageExhausted}
            disabledHint="今日问答次数已用完"
            usageLabel={usageLabel}
            modelLabel={effectiveLlmConfig?.activeLabel || 'gpt5.5模型'}
            boundMaterialLabel={!isGeneral ? selectedMaterialLabel : undefined}
            customModelEnabled={!!effectiveLlmConfig?.enabled}
            onOpenModelSettings={openModelDialog}
            images={images}
            onImagesChange={(nextImages) => updateChatSession({ images: nextImages })}
            onOpenUploadMaterial={!isGeneral ? openUploadDialog : undefined}
            onUploadMaterialFile={!isGeneral ? (file) => handleUploadMaterial({ file }) : undefined}
            onUploadMaterialFiles={!isGeneral ? handleUploadMaterialFiles : undefined}
            onUploadTemporaryMaterial={isGeneral ? handleUploadTemporaryMaterial : undefined}
            onUploadTemporaryMaterials={isGeneral ? handleUploadTemporaryMaterials : undefined}
            temporaryMaterialUploading={isGeneral && uploading}
            temporaryMaterial={composerTemporaryMaterial}
            temporaryUploadFile={isGeneral ? temporaryUploadFile : null}
            temporaryUploadFiles={isGeneral ? temporaryUploadFiles : []}
            temporaryUploadProgress={isGeneral ? temporaryUploadProgress : null}
            temporaryUploadError={isGeneral ? temporaryUploadError : null}
            onClearTemporaryMaterial={() => updateChatSession({ temporaryMaterial: null })}
            onRemoveTemporaryMaterialFile={handleRemoveTemporaryMaterialFile}
          />
        </>
      )}
      <Dialog
        open={uploadDialogOpen}
        onOpenChange={(open) => {
          if (uploading && !open) return
          setUploadDialogOpen(open)
        }}
      >
        <DialogContent className="max-w-xl overflow-hidden p-0">
          <DialogHeader>
            <div className="border-b border-[#edf1f5] bg-[#f8f9fb] px-6 py-5 dark:border-slate-800 dark:bg-slate-950/60">
              <DialogTitle className="flex items-center gap-2">
                <Upload className="h-5 w-5 text-slate-700 dark:text-slate-200" />
                上传资料
              </DialogTitle>
              <DialogDescription className="mt-2">
                支持 PDF、Word、PPT、Markdown、TXT 和网页文件，解析完成后会自动绑定到资料问答。
              </DialogDescription>
            </div>
          </DialogHeader>
          <div className="max-h-[72dvh] overflow-auto p-5">
            <MaterialUploadForm
              onSubmit={handleUploadMaterial}
              loading={uploading}
              progress={uploadProgress}
              progressItems={uploadProgressItems}
            />
            {temporaryUploadError && (
              <div className="mt-3 rounded-xl border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700 dark:border-red-900 dark:bg-red-950/30 dark:text-red-300">
                {temporaryUploadError}
              </div>
            )}
          </div>
        </DialogContent>
      </Dialog>
      <Dialog open={modelDialogOpen} onOpenChange={setModelDialogOpen}>
        <DialogContent className="max-w-3xl overflow-hidden p-0">
          <DialogHeader>
            <div className="border-b border-[#edf1f5] bg-[#f8f9fb] px-6 py-5 dark:border-slate-800 dark:bg-slate-950/60">
              <DialogTitle className="flex items-center gap-2">
                <Server className="h-5 w-5 text-slate-700 dark:text-slate-200" />
                切换大模型
              </DialogTitle>
              <DialogDescription className="mt-2">
                保存多个模型后可以直接切换；启用自定义模型后，今日问答次数显示为不限。
              </DialogDescription>
            </div>
          </DialogHeader>
          <div className="grid gap-0 md:grid-cols-[240px_1fr]">
            <aside className="border-r border-[#edf1f5] bg-[#f8f9fb] p-4 dark:border-slate-800 dark:bg-slate-950/40">
              <div className="space-y-2">
                <button
                  type="button"
                  className={cn(
                    'flex w-full items-center justify-between rounded-xl border px-3 py-2 text-left text-sm',
                    modelMode === 'SYSTEM' ? 'border-[#dde3eb] bg-white shadow-[0_4px_16px_rgba(15,23,42,0.04)] dark:border-slate-700 dark:bg-slate-900' : 'border-transparent hover:bg-white dark:hover:bg-slate-900',
                  )}
                  onClick={handleSelectSystemModel}
                >
                  <span className="font-medium">gpt5.5模型</span>
                  {modelMode === 'SYSTEM' && <CheckCircle2 className="h-4 w-4 text-emerald-600" />}
                </button>
                {(effectiveLlmConfig?.configs || []).map((config) => (
                  <button
                    key={config.id}
                    type="button"
                    className={cn(
                      'flex w-full items-center justify-between rounded-xl border px-3 py-2.5 text-left text-sm',
                      selectedConfigId === String(config.id) && modelMode === 'CUSTOM'
                        ? 'border-[#dde3eb] bg-white shadow-[0_4px_16px_rgba(15,23,42,0.04)] dark:border-slate-700 dark:bg-slate-900'
                        : 'border-transparent hover:bg-white dark:hover:bg-slate-900',
                    )}
                    onClick={() => handleSelectSavedModel(String(config.id))}
                  >
                    <span className="truncate font-medium">{config.displayName || config.model || '自定义模型'}</span>
                    {selectedConfigId === String(config.id) && modelMode === 'CUSTOM' && <CheckCircle2 className="ml-2 h-4 w-4 shrink-0 text-emerald-600" />}
                  </button>
                ))}
                <Button variant="outline" className="mt-2 h-9 w-full justify-start rounded-xl text-xs" onClick={handleNewModel}>
                  <Plus className="mr-1.5 h-4 w-4" />
                  新建模型
                </Button>
              </div>
            </aside>
            <section className="space-y-4 p-5">
              {modelMode === 'SYSTEM' ? (
                <div className="rounded-[24px] border border-[#e5e9ef] bg-white p-4 dark:border-slate-800 dark:bg-slate-900">
                  <div className="text-sm font-medium">当前使用 gpt5.5模型</div>
                  <p className="mt-2 text-sm text-muted-foreground">切回系统模型后，普通用户继续按每日次数限制使用。</p>
                </div>
              ) : (
                <div className="space-y-3">
                  <div className="space-y-2">
                    <Label>显示名称</Label>
                    <Input
                      value={displayName}
                      onChange={(event) => setDisplayName(event.target.value)}
                      placeholder="例如：我的 GPT-5.5"
                    />
                  </div>
                <div className="space-y-2">
                  <Label>URL 地址</Label>
                  <Input
                    value={baseUrl}
                    onChange={(event) => setBaseUrl(event.target.value)}
                    placeholder="https://api.openai.com"
                  />
                </div>
                <div className="space-y-2">
                  <Label>密钥</Label>
                  <Input
                    value={apiKey}
                    onChange={(event) => setApiKey(event.target.value)}
                    placeholder={selectedConfigId ? '已保存，留空则继续使用原密钥' : '请输入 API Key'}
                    type="password"
                  />
                </div>
                <div className="space-y-2">
                  <Label>模型名称</Label>
                  <Input
                    value={modelName}
                    onChange={(event) => setModelName(event.target.value)}
                    placeholder="gpt-5.4"
                  />
                </div>
                <div className="flex items-start gap-2 rounded-xl border border-[#e5e9ef] bg-[#f8f9fb] px-3 py-2.5 text-xs leading-5 text-slate-600 dark:border-slate-800 dark:bg-slate-950/50 dark:text-slate-300">
                  <AlertCircle className="mt-0.5 h-4 w-4 shrink-0 text-slate-400" />
                  <span>提醒：API Key 会保存到系统中，仅用于测试连接和调用你选择的大模型；请勿填写与本系统无关的敏感密钥。</span>
                </div>
              </div>
              )}
              {actionNotice && (
                <div
                  className={cn(
                    'flex items-start gap-2 rounded-xl border px-3 py-2.5 text-sm',
                    actionNotice.type === 'success'
                      ? 'border-emerald-200 bg-emerald-50 text-emerald-700 dark:border-emerald-900 dark:bg-emerald-950/30 dark:text-emerald-300'
                      : actionNotice.type === 'error'
                        ? 'border-red-200 bg-red-50 text-red-700 dark:border-red-900 dark:bg-red-950/30 dark:text-red-300'
                        : 'border-[#e5e9ef] bg-[#f8f9fb] text-slate-700 dark:border-slate-800 dark:bg-slate-900 dark:text-slate-300',
                  )}
                >
                  {actionNotice.type === 'success'
                    ? <CheckCircle2 className="mt-0.5 h-4 w-4 shrink-0" />
                    : <AlertCircle className="mt-0.5 h-4 w-4 shrink-0" />}
                  <span className="min-w-0">{actionNotice.message}</span>
                </div>
              )}
            </section>
          </div>
          <DialogFooter className="flex-col gap-3 border-t border-[#edf1f5] bg-[#f8f9fb] px-6 py-4 dark:border-slate-800 dark:bg-slate-950/60 sm:flex-row sm:items-center sm:justify-between">
            <div className="flex min-h-9 flex-wrap items-center gap-2">
              {modelMode === 'CUSTOM' && selectedConfigId && (
                <>
                  <Button
                    type="button"
                    variant="outline"
                    onClick={handleDeleteModel}
                    disabled={deleteLlmConfigMutation.isPending}
                    className={cn(
                      'border-red-200 text-red-600 hover:bg-red-50 hover:text-red-700 dark:border-red-900/60 dark:hover:bg-red-950/30',
                      deleteConfirmId === selectedConfigId && 'bg-red-600 text-white hover:bg-red-700 hover:text-white',
                    )}
                  >
                    <Trash2 className="mr-1.5 h-4 w-4" />
                    {deleteLlmConfigMutation.isPending
                      ? '删除中...'
                      : deleteConfirmId === selectedConfigId
                        ? '确认删除'
                        : '删除'}
                  </Button>
                  {deleteConfirmId === selectedConfigId && (
                    <Button
                      type="button"
                      variant="ghost"
                      onClick={() => {
                        setDeleteConfirmId(null)
                        setActionNotice(null)
                      }}
                    >
                      取消
                    </Button>
                  )}
                </>
              )}
            </div>
            <div className="flex flex-wrap items-center justify-end gap-2">
              {modelMode === 'CUSTOM' && (
                <Button
                  type="button"
                  variant="outline"
                  onClick={handleTestModel}
                  disabled={testLlmConfigMutation.isPending || !modelFormValid}
                  className="min-w-[104px]"
                >
                  {testLlmConfigMutation.isPending ? '测试中...' : '测试连接'}
                </Button>
              )}
              <Button
                type="button"
                onClick={handleSaveModel}
                disabled={saveLlmConfigMutation.isPending || !modelFormValid}
                className="min-w-[92px] bg-slate-800 text-white hover:bg-slate-700 dark:bg-slate-200 dark:text-slate-950 dark:hover:bg-white"
              >
                {saveLlmConfigMutation.isPending ? '应用中...' : '应用'}
              </Button>
            </div>
          </DialogFooter>
        </DialogContent>
      </Dialog>
      <GlobalSearch open={searchOpen} onClose={closeSearch} />
    </motion.div>
  )
}

// ========== 辅助函数 ==========

/**
 * mergeLlmConfig -- 合并 LLM 配置
 * 将当前本地配置和服务器返回的新配置合并，
 * 新配置优先，但保留当前配置中已有的自定义模型列表。
 * 活跃配置排在列表最前面。
 */
function mergeLlmConfig(current: UserLlmConfig | null, next: UserLlmConfig): UserLlmConfig {
  if (!current) return next
  const configsById = new Map<string, UserLlmConfig['configs'][number]>()
  for (const config of current.configs || []) {
    configsById.set(String(config.id), config)
  }
  for (const config of next.configs || []) {
    configsById.set(String(config.id), config)
  }
  return {
    ...next,
    configs: Array.from(configsById.values()).sort((a, b) => {
      if (String(a.id) === String(next.activeConfigId)) return -1
      if (String(b.id) === String(next.activeConfigId)) return 1
      return 0
    }),
  }
}

/** 生成文件的唯一进度条 ID */
function uploadProgressItemId(file: File, index: number) {
  return `${index}-${file.name}-${file.size}-${file.lastModified}`
}

/** 为多个文件创建初始进度条状态 */
function createUploadProgressItems(files: File[]): UploadProgressItem[] {
  return files.map((file, index) => ({
    id: uploadProgressItemId(file, index),
    fileName: file.name,
    fileSize: file.size,
    status: 'pending',
    phase: 'uploading',
    percent: 0,
    uploadedChunks: 0,
    totalChunks: Math.max(1, Math.ceil(file.size / LARGE_UPLOAD_CHUNK_SIZE)),
    message: '等待上传',
  }))
}

/** 更新指定文件的进度条状态（不可变更新） */
function updateUploadProgressItem(
  items: UploadProgressItem[],
  id: string,
  progress: UploadProgress | null,
  status?: UploadProgressItem['status'],
  error?: string | null,
): UploadProgressItem[] {
  return items.map((item) => {
    if (item.id !== id) return item
    if (!progress) {
      return {
        ...item,
        status: status || item.status,
        error: error || item.error,
      }
    }
    return {
      ...item,
      ...progress,
      status: status || (progress.phase === 'processing' ? 'processing' : 'uploading'),
      error: null,
    }
  })
}

/**
 * 判断临时资料是否真的提取出了可问答文本。
 * 多文件资料会被合并到 parts 中，只要其中一份有有效正文，就允许进入会话上下文。
 */
function isUsableTemporaryMaterial(material?: TemporaryMaterial | null): boolean {
  if (!material) return false
  const parts = material.parts?.length ? material.parts : [material]
  return parts.some((part) => cleanTemporaryMaterialText(part.text || '').trim().length > 0)
}

/**
 * 将 TemporaryMaterial 转换为文件列表项
 * 处理多文件临时资料和单文件临时资料两种情况
 */
function temporaryMaterialFileItems(material?: TemporaryMaterial | null): { name: string; size?: number | null; type?: string | null }[] {
  if (!material) return []
  if (material.files?.length) return material.files
  const sourceType = (material.sourceType || '').toUpperCase()
  const fallbackName = material.originalName || material.title || '临时资料'
  if (sourceType !== 'MULTI') {
    return [{ name: fallbackName, size: material.fileSize, type: sourceType || 'FILE' }]
  }
  return fallbackName
    .split(/[、,]/)
    .map((name) => name.trim())
    .filter(Boolean)
    .map((name) => ({ name, type: 'FILE' }))
}

/**
 * mergeTemporaryMaterials -- 合并多个临时资料为一个
 * 将多个独立上传的临时资料合并为一个含 parts 数组的临时资料对象。
 * 合并后的资料保留所有原始信息，文本按 "临时资料 1/2/3" 分段拼接。
 */
function mergeTemporaryMaterials(materials: TemporaryMaterial[]): TemporaryMaterial {
  const parts = materials.flatMap((material) => material.parts?.length ? material.parts : [material])
  if (parts.length === 1) return parts[0]
  const files = parts.flatMap((material) => material.files?.length
    ? material.files
    : temporaryMaterialFileItems(material))
  const names = parts.map((material) => material.originalName || material.title || '未命名资料')
  const text = parts
    .map((material, index) => [
      `[临时资料 ${index + 1}] ${material.title || material.originalName || '未命名资料'}`,
      `文件：${material.originalName || material.title || '未命名资料'}`,
      `类型：${material.sourceType || 'UNKNOWN'}`,
      '',
      material.text || '',
    ].join('\n'))
    .join('\n\n---\n\n')
  return {
    id: `temporary-batch-${Date.now()}`,
    title: `${files.length || materials.length} 份临时资料`,
    originalName: names.join('、'),
    sourceType: 'MULTI',
    text,
    excerpt: parts.map((material) => material.excerpt || '').filter(Boolean).join('\n\n').slice(0, 500),
    fileSize: parts.reduce((sum, material) => sum + (material.fileSize || 0), 0),
    files,
    parts,
  }
}

/**
 * 从待发送临时资料中移除已经随历史用户消息发送过的文件。
 *
 * 旧版本会把“已发送过的资料”和“新上传资料”再次合并到输入框附件栏；
 * 这里按文件元数据过滤，保证已成为会话上下文的资料不再伪装成待发送附件。
 */
function removeAlreadySentTemporaryMaterials(
  material: TemporaryMaterial | null | undefined,
  messages: Array<{ temporaryMaterial?: TemporaryMaterial | null }>,
): TemporaryMaterial | null {
  if (!material) return null
  const sentKeys = new Set<string>()
  messages.forEach((message) => {
    collectTemporaryMaterialKeys(message.temporaryMaterial, sentKeys)
  })
  if (sentKeys.size === 0) return material
  const parts = material.parts?.length ? material.parts : [material]
  const pendingParts = parts.filter((part) => !sentKeys.has(temporaryMaterialPartKey(part)))
  if (pendingParts.length === parts.length) return material
  if (pendingParts.length === 0) return null
  return mergeTemporaryMaterials(pendingParts)
}

/** 递归收集临时资料每个子文件的轻量标识。 */
function collectTemporaryMaterialKeys(material: TemporaryMaterial | null | undefined, keys: Set<string>) {
  if (!material) return
  const parts = material.parts?.length ? material.parts : [material]
  parts.forEach((part) => keys.add(temporaryMaterialPartKey(part)))
}

/** 用稳定文件元数据判断临时资料是否已经发送过，避免依赖每次上传都会变化的展示状态。 */
function temporaryMaterialPartKey(material: TemporaryMaterial) {
  return [
    material.id || '',
    material.originalName || material.title || '',
    material.fileSize ?? '',
    material.sourceType || '',
  ].join('|')
}

/**
 * removeTemporaryMaterialAtIndex -- 移除临时资料中指定索引的文件
 * 如果移除后没有剩余文件，返回 null（清除临时资料）
 * 否则重新合并剩余的临时资料
 */
function removeTemporaryMaterialAtIndex(material: TemporaryMaterial | null | undefined, index: number): TemporaryMaterial | null {
  if (!material || index < 0) return material || null
  const parts = material.parts?.length ? material.parts : [material]
  if (index >= parts.length) return material
  const nextParts = parts.filter((_, partIndex) => partIndex !== index)
  if (nextParts.length === 0) return null
  return mergeTemporaryMaterials(nextParts)
}

