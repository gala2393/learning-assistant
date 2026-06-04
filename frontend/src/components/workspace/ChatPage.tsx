import { useEffect, useState, useSyncExternalStore } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { motion } from 'framer-motion'
import { AlertCircle, BookOpen, CheckCircle2, Plus, Server, Sparkles, Trash2 } from 'lucide-react'
import { ChatThread } from './ChatThread'
import { ChatComposer } from './ChatComposer'
import { useDeleteHistory, useHistory, useRagUsage, useRenameHistory, useTogglePinHistory } from '@/api/rag'
import { useDeleteUserLlmConfig, useSaveUserLlmConfig, useTestUserLlmConfig, useUserLlmConfig } from '@/api/llm'
import { useAddFavorite, useDeleteFavorite, useFavorites } from '@/api/favorites'
import { useMaterials } from '@/api/materials'
import { GENERAL_PROMPTS, MATERIAL_PROMPTS } from '@/constants'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { cn } from '@/lib/utils'
import { queryClient } from '@/lib/query-client'
import { useToast } from '@/components/ui/toast'
import {
  getChatSessionSnapshot,
  resetChatSession,
  selectHistorySession,
  startChatSessionStream,
  subscribeChatSession,
  updateChatSession,
} from '@/lib/chat-session'
import type { HistoryItem, RagSource, UserLlmConfig } from '@/types'

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
    streaming,
  } = chat

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
    if (searchParams.get('new') === '1') {
      resetChatSession()
      setSearchParams(new URLSearchParams(), { replace: true })
      return
    }

    const historyId = searchParams.get('historyId')
    if (historyId) {
      const target = historyItems.find((item) => String(item.id) === historyId)
      if (target && selectedHistoryId !== historyId) {
        selectHistorySession(target)
      }
      return
    }

    const materialId = searchParams.get('materialId')
    const chunkId = searchParams.get('chunkId')
    if (materialId && (mode !== 'MATERIAL' || selectedMaterialId !== materialId || selectedChunkId !== chunkId)) {
      updateChatSession({
        mode: 'MATERIAL',
        materialId,
        chunkId,
      })
    }
  }, [historyItems, mode, searchParams, selectedChunkId, selectedHistoryId, selectedMaterialId, setSearchParams])

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

  const handleSubmit = () => {
    const question = input.trim()
    if (!question || streaming) return

    startChatSessionStream({
      question,
      mode,
      materialId: mode === 'MATERIAL' ? selectedMaterialId : null,
      chunkId: mode === 'MATERIAL' ? selectedChunkId : null,
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
    navigate(`/workspace/reader?materialId=${encodeURIComponent(source.materialId)}&chunkId=${encodeURIComponent(source.chunkId)}`)
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
      <div className="relative flex items-center justify-center gap-1 px-2 py-2 md:px-4">
        <Button
          variant={isGeneral ? 'secondary' : 'ghost'}
          size="sm"
          className={cn('h-8 rounded-full px-3 text-xs dark:text-slate-200 dark:hover:bg-white/[0.08] sm:px-4 sm:text-sm', isGeneral && 'bg-[#eef0f2] text-[#4b5563] hover:bg-[#e3e6e9] dark:bg-white/10 dark:text-slate-200 dark:hover:bg-white/15')}
          onClick={() => handleModeChange('GENERAL')}
        >
          <Sparkles className="mr-1.5 h-4 w-4" />
          智能问答
        </Button>
        <Button
          variant={!isGeneral ? 'secondary' : 'ghost'}
          size="sm"
          className={cn('h-8 rounded-full px-3 text-xs dark:text-slate-200 dark:hover:bg-white/[0.08] sm:px-4 sm:text-sm', !isGeneral && 'bg-[#eef0f2] text-[#4b5563] hover:bg-[#e3e6e9] dark:bg-white/10 dark:text-slate-200 dark:hover:bg-white/15')}
          onClick={() => handleModeChange('MATERIAL')}
        >
          <BookOpen className="mr-1.5 h-4 w-4" />
          资料问答
        </Button>
        <div className="absolute right-8 hidden md:block">
          <Badge variant={isGeneral ? 'secondary' : 'success'} className="text-xs">
            {isGeneral ? '通用知识模式' : '绑定资料模式'}
          </Badge>
        </div>
      </div>

      {!isEmptyChat && (
        <div className="flex items-center gap-2 border-b bg-background px-4 py-2 dark:border-slate-800 dark:bg-[#171a21]">
          {isGeneral ? (
            <p className="text-xs text-muted-foreground">基于通用知识回答，适合概念解释和开放式讨论</p>
          ) : (
            <>
              <p className="text-xs text-muted-foreground">
                {selectedMaterialId
                  ? `已绑定：${materials.find((m) => m.id === selectedMaterialId)?.title || '未知资料'}`
                  : '请先在左侧选择一份资料，再发起提问'}
              </p>
              {selectedMaterialId && (
                <Badge variant="outline" className="text-[10px]">
                  {materials.find((m) => m.id === selectedMaterialId)?.chunkCount || 0} 个切片
                </Badge>
              )}
            </>
          )}
          <div className="ml-auto" />
        </div>
      )}

      {isEmptyChat ? (
        <div className="flex flex-1 flex-col items-center justify-center px-6 pb-20">
          <div className="mb-6 text-center">
            <h2 className="text-4xl font-black tracking-[0.02em] text-black dark:text-white sm:text-5xl">智能问答</h2>
            <p className="mt-3 text-sm text-muted-foreground">描述问题、上传资料或选择资料后开始提问</p>
          </div>
          {!isGeneral && (
            <div className="order-3 mt-4 w-full max-w-[760px]">
              {parsedMaterials.length === 0 ? (
                <div className="flex items-center justify-between gap-3 rounded-2xl border border-dashed border-slate-300 bg-[#fafafa] px-4 py-3 text-sm text-muted-foreground dark:border-slate-700 dark:bg-slate-900 dark:text-slate-400">
                  <span>暂无已解析资料，请先导入并完成解析。</span>
                  <Button size="sm" variant="outline" onClick={() => navigate('/workspace/materials')}>
                    去导入资料
                  </Button>
                </div>
              ) : (
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
            disabled={usageExhausted || (!isGeneral && !selectedMaterialId)}
            disabledHint={usageExhausted ? '今日问答次数已用完' : '请先选择资料'}
            usageLabel={usageLabel}
            modelLabel={effectiveLlmConfig?.activeLabel || 'gpt5.5模型'}
            customModelEnabled={!!effectiveLlmConfig?.enabled}
            onOpenModelSettings={() => setModelDialogOpen(true)}
            images={images}
            onImagesChange={(nextImages) => updateChatSession({ images: nextImages })}
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
            disabled={usageExhausted || (!isGeneral && !selectedMaterialId)}
            disabledHint={usageExhausted ? '今日问答次数已用完' : '请先选择资料'}
            usageLabel={usageLabel}
            modelLabel={effectiveLlmConfig?.activeLabel || 'gpt5.5模型'}
            customModelEnabled={!!effectiveLlmConfig?.enabled}
            onOpenModelSettings={() => setModelDialogOpen(true)}
            images={images}
            onImagesChange={(nextImages) => updateChatSession({ images: nextImages })}
          />
        </>
      )}
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
