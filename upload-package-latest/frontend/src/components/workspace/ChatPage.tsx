import { useEffect, useState, useSyncExternalStore } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { motion } from 'framer-motion'
import { AlertCircle, BookOpen, CheckCircle2, Plus, Search, Server, Sparkles, Trash2, Upload } from 'lucide-react'
import { ChatThread } from './ChatThread'
import { ChatComposer } from './ChatComposer'
import { MaterialUploadForm } from './MaterialUploadForm'
import { useDeleteHistory, useHistory, useRagUsage, useRenameHistory, useTogglePinHistory } from '@/api/rag'
import { useDeleteUserLlmConfig, useSaveUserLlmConfig, useTestUserLlmConfig, useUserLlmConfig } from '@/api/llm'
import { useAddFavorite, useDeleteFavorite, useFavorites } from '@/api/favorites'
import { MAX_TEMPORARY_MATERIAL_BYTES, uploadMaterialInChunks, uploadTemporaryMaterial, useMaterials } from '@/api/materials'
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
import {
  getChatSessionSnapshot,
  resetChatSession,
  selectHistorySession,
  startChatSessionStream,
  subscribeChatSession,
  updateChatSession,
} from '@/lib/chat-session'
import type { HistoryItem, RagSource, TemporaryMaterial, UserLlmConfig } from '@/types'
import type { UploadProgress, UploadProgressItem } from '@/api/materials'

/**
 * ChatPage — 聊天主页，项目最核心的页面组件。
 *
 * 路由：/workspace/chat
 *
 * 功能：
 * 1. 两种问答模式：通用模式（智能问答）和资料模式（资料问答）
 * 2. 空状态：居中 hero 布局，显示大标题 + 资料选择器 + 输入框
 * 3. 有消息时：上方消息列表 + 下方输入框
 * 4. 模型切换弹窗：支持系统默认模型和用户自定义 LLM 配置
 * 5. 通过 useSyncExternalStore 订阅 chat-session 的全局状态
 * 6. URL 参数同步：materialId、chunkId、historyId、new
 * 7. 快捷提示词芯片
 * 8. 使用量提示（今日剩余次数）
 *
 * 状态管理：使用 External Store 模式（chat-session.ts），
 * 而非 React useState，因为流式输出时需要高频更新（每秒 10+ 次）。
 */
export function ChatPage() {
  const navigate = useNavigate()
  const [searchParams, setSearchParams] = useSearchParams()
  const { data: historyItems = [] } = useHistory()
  const { data: ragUsage } = useRagUsage()
  const { data: llmConfig } = useUserLlmConfig()
  const { data: favorites = [] } = useFavorites()
  const { data: materials = [] } = useMaterials()
  const deleteHistoryMutation = useDeleteHistory()
  const renameHistoryMutation = useRenameHistory()
  const togglePinHistoryMutation = useTogglePinHistory()
  const addFavoriteMutation = useAddFavorite()
  const deleteFavoriteMutation = useDeleteFavorite()
  const { showToast } = useToast()
  const { open: searchOpen, setOpen: setSearchOpen, close: closeSearch } = useGlobalSearch()
  const [modelDialogOpen, setModelDialogOpen] = useState(false)
  const [modelMode, setModelMode] = useState<'SYSTEM' | 'CUSTOM'>('SYSTEM')
  const [selectedConfigId, setSelectedConfigId] = useState<string | null>(null)
  const [displayName, setDisplayName] = useState('')
  const [baseUrl, setBaseUrl] = useState('')
  const [apiKey, setApiKey] = useState('')
  const [modelName, setModelName] = useState('')
  const [actionNotice, setActionNotice] = useState<{ type: 'info' | 'success' | 'error'; message: string } | null>(null)
  const [deleteConfirmId, setDeleteConfirmId] = useState<string | null>(null)
  const [localLlmConfig, setLocalLlmConfig] = useState<UserLlmConfig | null>(null)
  const [uploadDialogOpen, setUploadDialogOpen] = useState(false)
  const [uploading, setUploading] = useState(false)
  const [uploadProgress, setUploadProgress] = useState<UploadProgress | null>(null)
  const [uploadProgressItems, setUploadProgressItems] = useState<UploadProgressItem[]>([])
  const [temporaryUploadFile, setTemporaryUploadFile] = useState<{ name: string; size: number; sourceType: string } | null>(null)
  const [temporaryUploadFiles, setTemporaryUploadFiles] = useState<{ name: string; size?: number | null; type?: string | null }[]>([])
  const [temporaryUploadProgress, setTemporaryUploadProgress] = useState<{ phase: 'uploading' | 'processing'; percent: number; message?: string } | null>(null)
  const [temporaryUploadError, setTemporaryUploadError] = useState<string | null>(null)
  const saveLlmConfigMutation = useSaveUserLlmConfig()
  const testLlmConfigMutation = useTestUserLlmConfig()
  const deleteLlmConfigMutation = useDeleteUserLlmConfig()
  const effectiveLlmConfig = localLlmConfig ?? llmConfig ?? null

  const chat = useSyncExternalStore(
    subscribeChatSession,
    getChatSessionSnapshot,
    getChatSessionSnapshot,
  )

  const {
    selectedHistoryId,
    currentQuestionId,
    mode,
    input,
    images,
    messages,
    materialId: selectedMaterialId,
    chunkId: selectedChunkId,
    temporaryMaterial,
    streaming,
  } = chat
  const newChatParam = searchParams.get('new')
  const historyParam = searchParams.get('historyId')
  const materialParam = searchParams.get('materialId')
  const chunkParam = searchParams.get('chunkId')

  useEffect(() => {
    if (!llmConfig) return
    setLocalLlmConfig((current) => mergeLlmConfig(current, llmConfig))
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

  useEffect(() => {
    if (newChatParam === '1') {
      resetChatSession()
      setSearchParams(new URLSearchParams(), { replace: true })
      return
    }

    if (historyParam) {
      const target = historyItems.find((item) => String(item.id) === historyParam)
      if (target && selectedHistoryId !== historyParam) {
        selectHistorySession(target)
      }
      return
    }

    if (materialParam && (mode !== 'MATERIAL' || selectedMaterialId !== materialParam || selectedChunkId !== chunkParam)) {
      updateChatSession({
        mode: 'MATERIAL',
        materialId: materialParam,
        chunkId: chunkParam,
      })
    }
  }, [chunkParam, historyItems, historyParam, materialParam, mode, newChatParam, selectedChunkId, selectedHistoryId, selectedMaterialId, setSearchParams])

  const updateLocationForContext = (newMode: 'GENERAL' | 'MATERIAL', materialId: string | null, chunkId: string | null) => {
    const nextParams = new URLSearchParams()
    if (newMode === 'MATERIAL' && materialId) {
      nextParams.set('materialId', materialId)
      if (chunkId) nextParams.set('chunkId', chunkId)
    }
    setSearchParams(nextParams, { replace: true })
  }

  const handleModeChange = (newMode: 'GENERAL' | 'MATERIAL') => {
    if (newMode === mode) return
    const nextMaterialId = newMode === 'MATERIAL' ? selectedMaterialId : null
    resetChatSession({
      mode: newMode,
      materialId: nextMaterialId,
      chunkId: newMode === 'MATERIAL' ? selectedChunkId : null,
    })
    updateLocationForContext(newMode, nextMaterialId, newMode === 'MATERIAL' ? selectedChunkId : null)
  }

  const handleNewChat = () => {
    resetChatSession({
      mode,
      materialId: mode === 'MATERIAL' ? selectedMaterialId : null,
      chunkId: mode === 'MATERIAL' ? selectedChunkId : null,
      images,
      temporaryMaterial: mode === 'GENERAL' ? temporaryMaterial : null,
    })
    updateLocationForContext(mode, selectedMaterialId, selectedChunkId)
  }

  const handleDeleteHistory = (id: string) => {
    deleteHistoryMutation.mutate(id, {
      onSuccess: () => {
        showToast('会话已删除')
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
      nextParams.set('materialId', source.materialId)
      nextParams.set('chunkId', source.chunkId)
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
    const files = data.files?.length ? data.files : data.file ? [data.file] : []
    await handleUploadMaterialFiles(files, data.title)
  }

  const handleUploadMaterialFiles = async (files: File[], title?: string) => {
    if (files.length === 0) return
    setUploading(true)
    setUploadProgress(null)
    setUploadProgressItems(createUploadProgressItems(files))
    setTemporaryUploadError(null)
    try {
      const results = await Promise.allSettled(files.map((file, index) => {
        const id = uploadProgressItemId(file, index)
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
            uploadedChunks: Math.max(1, Math.ceil(file.size / (5 * 1024 * 1024))),
            totalChunks: Math.max(1, Math.ceil(file.size / (5 * 1024 * 1024))),
            stage: '解析完成',
            message: '资料已上传完成',
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
        const message = `${failedCount}/${files.length} 份资料上传失败，请查看进度列表`
        setTemporaryUploadError(message)
        showToast(message)
        return
      }
      setUploadDialogOpen(false)
      const firstMaterialId = successfulMaterialIds[0]
      if (firstMaterialId) {
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
    await handleUploadTemporaryMaterials([file])
  }

  const handleUploadTemporaryMaterials = async (files: File[]) => {
    if (files.length === 0) return
    const tooLargeFile = files.find((file) => file.size > MAX_TEMPORARY_MATERIAL_BYTES)
    if (tooLargeFile) {
      const message = `${tooLargeFile.name} 超过智能问答临时资料上限 ${formatBytes(MAX_TEMPORARY_MATERIAL_BYTES)}；大文件请切换到资料问答上传，系统会在后台解析并显示进度。`
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
    setTemporaryUploadFiles([
      ...temporaryMaterialFileItems(temporaryMaterial),
      ...files.map((file) => ({ name: file.name, size: file.size, type: inferSourceType(file.name) })),
    ])
    setTemporaryUploadProgress({ phase: 'uploading', percent: 1, message: files.length > 1 ? `准备上传 ${files.length} 份资料` : '准备上传' })
    setTemporaryUploadError(null)
    try {
      const progressByIndex = new Map<number, { phase: 'uploading' | 'processing'; percent: number; message?: string }>()
      const updateAggregateProgress = () => {
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
                progressByIndex.set(index, { phase: 'processing', percent: 65, message: '正在解析资料，扫描版 PDF 会进行 OCR' })
                updateAggregateProgress()
              }
            }, 300)
          }
        })
      ))
      const uploaded = results
        .filter((result): result is PromiseFulfilledResult<TemporaryMaterial> => result.status === 'fulfilled')
        .map((result, index) => ({
          ...result.value,
          files: [{
            name: result.value.originalName || result.value.title || files[index]?.name || '临时资料',
            size: result.value.fileSize ?? files[index]?.size,
            type: result.value.sourceType || inferSourceType(files[index]?.name || ''),
          }],
        }))
      const failedCount = results.length - uploaded.length
      if (uploaded.length === 0) {
        throw new Error(failedCount > 0 ? `${failedCount}/${files.length} 份临时资料解析失败` : '临时资料解析失败，请重试')
      }
      const temporary = mergeTemporaryMaterials(temporaryMaterial ? [temporaryMaterial, ...uploaded] : uploaded)
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

  const handleSubmit = () => {
    const question = input.trim()
    if (!question || streaming) return
    if (mode === 'MATERIAL' && !selectedMaterialId) {
      showToast('请先上传或选择资料')
      return
    }

    startChatSessionStream({
      question,
      mode,
      materialId: mode === 'MATERIAL' ? selectedMaterialId : null,
      chunkId: mode === 'MATERIAL' ? selectedChunkId : null,
      selectedText: mode === 'GENERAL' && temporaryMaterial
        ? buildTemporaryMaterialContext(temporaryMaterial)
        : null,
      temporaryMaterial: mode === 'GENERAL' ? temporaryMaterial : null,
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
    navigate({ pathname: '/workspace/reader', search: params.toString() })
  }

  const handleRemoveTemporaryMaterialFile = (index: number) => {
    const next = removeTemporaryMaterialAtIndex(temporaryMaterial, index)
    updateChatSession({ temporaryMaterial: next })
  }

  const isGeneral = mode === 'GENERAL'
  const quickPrompts = isGeneral ? GENERAL_PROMPTS : MATERIAL_PROMPTS
  const parsedMaterials = materials.filter((m) => m.parseStatus === 'SUCCESS' || m.parseStatus === 'PARSED')
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
      className="flex h-full min-h-0 flex-col overflow-hidden bg-white dark:bg-[#171a21]"
      initial={{ opacity: 0 }}
      animate={{ opacity: 1 }}
      transition={{ duration: 0.3 }}
    >
      <div className="grid h-9 shrink-0 grid-cols-[1fr_auto_1fr] items-center gap-2 bg-white px-3 dark:bg-[#171a21] md:px-5">
        <div className="min-w-0">
          <p className="truncate text-xs text-muted-foreground">
            {isGeneral
              ? temporaryMaterial
                ? `已加载临时资料：${temporaryMaterial.title || temporaryMaterial.originalName || '未命名资料'}`
                : '基于通用知识回答'
              : selectedMaterialId
                ? `已绑定：${materials.find((m) => m.id === selectedMaterialId)?.title || '未知资料'}`
                : '请选择资料后提问'}
          </p>
        </div>
        <div className="flex rounded-full bg-[#f2f4f7] p-0.5 dark:bg-white/[0.08]">
          <Button
            variant="ghost"
            size="sm"
            className={cn(
              'h-7 rounded-full px-3 text-[13px] font-medium text-slate-500 hover:bg-white/70 dark:text-slate-300 dark:hover:bg-white/[0.08] sm:px-3.5',
              isGeneral && 'bg-white text-slate-900 shadow-sm hover:bg-white dark:bg-slate-900 dark:text-white',
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
              !isGeneral && 'bg-white text-slate-900 shadow-sm hover:bg-white dark:bg-slate-900 dark:text-white',
            )}
            onClick={() => handleModeChange('MATERIAL')}
          >
            <BookOpen className="mr-1.5 h-3.5 w-3.5" />
            资料
          </Button>
        </div>
        <div className="flex min-w-0 items-center justify-end gap-2">
          {!isGeneral && (
            <Button
              type="button"
              variant="ghost"
              size="sm"
              className="hidden h-7 shrink-0 rounded-full px-2.5 text-xs font-medium text-slate-500 hover:bg-slate-100 hover:text-slate-700 dark:text-slate-300 dark:hover:bg-slate-800 dark:hover:text-slate-100 sm:inline-flex"
              onClick={() => setUploadDialogOpen(true)}
            >
              <Upload className="mr-1 h-3.5 w-3.5" />
              上传资料
            </Button>
          )}
          {!isGeneral && selectedMaterialId ? (
            <Badge variant="outline" className="hidden shrink-0 text-[10px] font-medium sm:inline-flex">
              {materials.find((m) => m.id === selectedMaterialId)?.chunkCount || 0} 个切片
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
            className="h-7 w-7 shrink-0 rounded-full text-slate-500 hover:bg-slate-100 hover:text-slate-700 dark:text-slate-300 dark:hover:bg-slate-800 dark:hover:text-slate-100"
            onClick={() => setSearchOpen(true)}
          >
            <Search className="h-4 w-4" />
            <span className="sr-only">搜索</span>
          </Button>
        </div>
      </div>

      {isEmptyChat ? (
        <div className="flex flex-1 flex-col items-center justify-center px-6 pb-14">
          <div className="mb-6 text-center">
            <h2 className="text-4xl font-black tracking-[0.02em] text-black dark:text-white sm:text-5xl">智能问答</h2>
            <p className="mt-3 text-sm text-muted-foreground">描述问题、上传资料或选择资料后开始提问</p>
          </div>
          {!isGeneral && (
            <div className="order-3 mt-4 w-full max-w-[760px]">
              {parsedMaterials.length === 0 ? (
                <div className="flex items-center justify-between gap-3 rounded-2xl border border-dashed border-slate-300 bg-[#fafafa] px-4 py-3 text-sm text-muted-foreground dark:border-slate-700 dark:bg-slate-900 dark:text-slate-400">
                  <span>暂无已解析资料，请先导入并完成解析。</span>
                  <Button size="sm" variant="outline" onClick={() => setUploadDialogOpen(true)}>
                    <Upload className="mr-1.5 h-3.5 w-3.5" />
                    上传资料
                  </Button>
                </div>
              ) : (
                <div className="flex gap-2">
                  <Select value={selectedMaterialId || ''} onValueChange={handleMaterialSelect}>
                    <SelectTrigger className="h-11 rounded-2xl border-slate-200 bg-[#fafafa] px-4 text-sm shadow-none focus:ring-1 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-100">
                      <SelectValue placeholder="选择一份资料开始提问" />
                    </SelectTrigger>
                    <SelectContent className="max-h-72 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-100">
                      {parsedMaterials.map((material) => (
                        <SelectItem key={material.id} value={material.id} className="dark:focus:bg-slate-800">
                          <span className="mr-2 rounded bg-slate-100 px-1.5 py-0.5 text-[10px] font-semibold text-slate-500 dark:bg-slate-800">
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
                    onClick={() => setUploadDialogOpen(true)}
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
            loading={streaming}
            mode={mode}
            onModeChange={handleModeChange}
            quickPrompts={quickPrompts}
            disabled={usageExhausted}
            disabledHint="今日问答次数已用完"
            usageLabel={usageLabel}
            modelLabel={effectiveLlmConfig?.activeLabel || 'gpt5.5模型'}
            customModelEnabled={!!effectiveLlmConfig?.enabled}
            onOpenModelSettings={() => setModelDialogOpen(true)}
            images={images}
            onImagesChange={(nextImages) => updateChatSession({ images: nextImages })}
            onOpenUploadMaterial={!isGeneral ? () => setUploadDialogOpen(true) : undefined}
            onUploadMaterialFile={!isGeneral ? (file) => handleUploadMaterial({ file }) : undefined}
            onUploadMaterialFiles={!isGeneral ? handleUploadMaterialFiles : undefined}
            onUploadTemporaryMaterial={isGeneral ? handleUploadTemporaryMaterial : undefined}
            onUploadTemporaryMaterials={isGeneral ? handleUploadTemporaryMaterials : undefined}
            temporaryMaterialUploading={isGeneral && uploading}
            temporaryMaterial={isGeneral ? temporaryMaterial : null}
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
          <ChatThread messages={messages} onOpenSource={handleOpenSource} />
          <ChatComposer
            value={input}
            onChange={(value) => updateChatSession({ input: value })}
            onSubmit={handleSubmit}
            loading={streaming}
            mode={mode}
            onModeChange={handleModeChange}
            quickPrompts={quickPrompts}
            disabled={usageExhausted}
            disabledHint="今日问答次数已用完"
            usageLabel={usageLabel}
            modelLabel={effectiveLlmConfig?.activeLabel || 'gpt5.5模型'}
            customModelEnabled={!!effectiveLlmConfig?.enabled}
            onOpenModelSettings={() => setModelDialogOpen(true)}
            images={images}
            onImagesChange={(nextImages) => updateChatSession({ images: nextImages })}
            onOpenUploadMaterial={!isGeneral ? () => setUploadDialogOpen(true) : undefined}
            onUploadMaterialFile={!isGeneral ? (file) => handleUploadMaterial({ file }) : undefined}
            onUploadMaterialFiles={!isGeneral ? handleUploadMaterialFiles : undefined}
            onUploadTemporaryMaterial={isGeneral ? handleUploadTemporaryMaterial : undefined}
            onUploadTemporaryMaterials={isGeneral ? handleUploadTemporaryMaterials : undefined}
            temporaryMaterialUploading={isGeneral && uploading}
            temporaryMaterial={isGeneral ? temporaryMaterial : null}
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
            <div className="border-b bg-slate-50 px-6 py-5 dark:border-slate-800 dark:bg-slate-950/60">
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
            <div className="border-b bg-slate-50 px-6 py-5 dark:border-slate-800 dark:bg-slate-950/60">
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
            <aside className="border-r bg-slate-50 p-4 dark:border-slate-800 dark:bg-slate-950/40">
              <div className="space-y-2">
                <button
                  type="button"
                  className={cn(
                    'flex w-full items-center justify-between rounded-xl border px-3 py-2 text-left text-sm',
                    modelMode === 'SYSTEM' ? 'border-slate-300 bg-white shadow-sm dark:border-slate-700 dark:bg-slate-900' : 'border-transparent hover:bg-white dark:hover:bg-slate-900',
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
                        ? 'border-slate-300 bg-white shadow-sm dark:border-slate-700 dark:bg-slate-900'
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
                <div className="rounded-2xl border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900">
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
                <div className="flex items-start gap-2 rounded-xl border border-slate-200 bg-slate-50 px-3 py-2.5 text-xs leading-5 text-slate-600 dark:border-slate-800 dark:bg-slate-950/50 dark:text-slate-300">
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
                        : 'border-slate-200 bg-slate-50 text-slate-700 dark:border-slate-800 dark:bg-slate-900 dark:text-slate-300',
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
          <DialogFooter className="flex-col gap-3 border-t bg-slate-50 px-6 py-4 dark:border-slate-800 dark:bg-slate-950/60 sm:flex-row sm:items-center sm:justify-between">
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

function uploadProgressItemId(file: File, index: number) {
  return `${index}-${file.name}-${file.size}-${file.lastModified}`
}

function createUploadProgressItems(files: File[]): UploadProgressItem[] {
  return files.map((file, index) => ({
    id: uploadProgressItemId(file, index),
    fileName: file.name,
    fileSize: file.size,
    status: 'pending',
    phase: 'uploading',
    percent: 0,
    uploadedChunks: 0,
    totalChunks: Math.max(1, Math.ceil(file.size / (5 * 1024 * 1024))),
    message: '等待上传',
  }))
}

function updateUploadProgressItem(
  items: UploadProgressItem[],
  id: string,
  progress: UploadProgress | null,
  status?: UploadProgressItem['status'],
  error?: string,
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

function removeTemporaryMaterialAtIndex(material: TemporaryMaterial | null | undefined, index: number): TemporaryMaterial | null {
  if (!material || index < 0) return material || null
  const parts = material.parts?.length ? material.parts : [material]
  if (index >= parts.length) return material
  const nextParts = parts.filter((_, partIndex) => partIndex !== index)
  if (nextParts.length === 0) return null
  return mergeTemporaryMaterials(nextParts)
}

function buildTemporaryMaterialContext(material: TemporaryMaterial) {
  const title = material.title || material.originalName || '临时资料'
  const sourceType = material.sourceType || 'UNKNOWN'
  const rawText = material.text || ''
  const slicedText = rawText.length > 20000 ? rawText.slice(0, 20000) : rawText
  const text = cleanTemporaryMaterialText(slicedText)
  return [
    `[临时资料] ${title}`,
    `文件：${material.originalName || title}`,
    `类型：${sourceType}`,
    '',
    rawText.length > slicedText.length || text.length > 16000
      ? `${text.slice(0, 16000)}\n\n[内容过长，已截取前 16000 字]`
      : text,
  ].join('\n')
}
