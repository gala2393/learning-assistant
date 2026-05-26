import { useEffect, useRef, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { motion } from 'framer-motion'
import { BookOpen, Sparkles } from 'lucide-react'
import { ChatThread } from './ChatThread'
import { ChatComposer } from './ChatComposer'
import { chatStream, useDeleteHistory, useHistory, useRenameHistory, useTogglePinHistory } from '@/api/rag'
import { useAddFavorite, useDeleteFavorite, useFavorites } from '@/api/favorites'
import { useMaterials } from '@/api/materials'
import { GENERAL_PROMPTS, MATERIAL_PROMPTS } from '@/constants'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { cn, sanitizeAiText } from '@/lib/utils'
import { queryClient } from '@/lib/query-client'
import { useToast } from '@/components/ui/toast'
import type { HistoryItem, RagSource } from '@/types'
import type { ChatMessage } from './ChatThread'

const CHAT_DRAFT_KEY = 'learning-assistant.chat.current'

interface ChatDraft {
  lastQuestionId?: string | null
  historyId?: string | null
  mode: 'GENERAL' | 'MATERIAL'
  materialId: string | null
  chunkId: string | null
  messages: ChatMessage[]
  conversationHistory: { role: string; content: string }[]
}

export function ChatPage() {
  const navigate = useNavigate()
  const [searchParams, setSearchParams] = useSearchParams()
  const { data: historyItems = [] } = useHistory()
  const { data: favorites = [] } = useFavorites()
  const { data: materials = [] } = useMaterials()
  const deleteHistoryMutation = useDeleteHistory()
  const renameHistoryMutation = useRenameHistory()
  const togglePinHistoryMutation = useTogglePinHistory()
  const addFavoriteMutation = useAddFavorite()
  const deleteFavoriteMutation = useDeleteFavorite()
  const { showToast } = useToast()

  const [selectedHistoryId, setSelectedHistoryId] = useState<string | null>(null)
  const [mode, setMode] = useState<'GENERAL' | 'MATERIAL'>('GENERAL')
  const [input, setInput] = useState('')
  const [messages, setMessages] = useState<ChatMessage[]>([])
  const [selectedMaterialId, setSelectedMaterialId] = useState<string | null>(() => searchParams.get('materialId'))
  const [selectedChunkId, setSelectedChunkId] = useState<string | null>(() => searchParams.get('chunkId'))
  const [currentQuestionId, setCurrentQuestionId] = useState<string | null>(null)
  const [streaming, setStreaming] = useState(false)
  const abortRef = useRef<AbortController | null>(null)
  const answerRef = useRef('')
  const sourcesRef = useRef<RagSource[]>([])
  const conversationHistory = useRef<{ role: string; content: string }[]>([])

  const saveChatDraft = (draft: ChatDraft | null) => {
    if (typeof window === 'undefined') return
    if (!draft) {
      sessionStorage.removeItem(CHAT_DRAFT_KEY)
      return
    }
    sessionStorage.setItem(CHAT_DRAFT_KEY, JSON.stringify(draft))
  }

  const selectedHistory = historyItems.find((h) => String(h.id) === selectedHistoryId)

  const applyHistorySelection = (item: HistoryItem, updateUrl: boolean) => {
    const itemId = String(item.id)
    const source = item.sources?.[0]
    setSelectedHistoryId(itemId)
    setCurrentQuestionId(null)
    if (source) {
      setMode('MATERIAL')
      setSelectedMaterialId(source.materialId)
      setSelectedChunkId(source.chunkId)
    } else {
      setMode('GENERAL')
      setSelectedChunkId(null)
    }
    if (updateUrl) {
      const nextParams = new URLSearchParams()
      nextParams.set('historyId', itemId)
      if (source) {
        nextParams.set('materialId', source.materialId)
        nextParams.set('chunkId', source.chunkId)
      }
      setSearchParams(nextParams, { replace: true })
    }
  }

  const handleModeChange = (newMode: 'GENERAL' | 'MATERIAL') => {
    if (newMode === mode) return
    saveChatDraft(null)
    setMode(newMode)
    const nextParams = new URLSearchParams()
    if (newMode === 'MATERIAL' && selectedMaterialId) {
      nextParams.set('materialId', selectedMaterialId)
      if (selectedChunkId) nextParams.set('chunkId', selectedChunkId)
    } else {
      setSelectedChunkId(null)
    }
    setSearchParams(nextParams, { replace: true })
    setSelectedHistoryId(null)
    setCurrentQuestionId(null)
    setMessages([])
    setInput('')
    conversationHistory.current = []
    abortRef.current?.abort()
    setStreaming(false)
  }

  useEffect(() => {
    if (searchParams.get('new') === '1') {
      setSelectedHistoryId(null)
      setCurrentQuestionId(null)
      setMessages([])
      setInput('')
      conversationHistory.current = []
      abortRef.current?.abort()
      setStreaming(false)
      saveChatDraft(null)
      setSearchParams(new URLSearchParams(), { replace: true })
      return
    }

    const materialId = searchParams.get('materialId')
    const chunkId = searchParams.get('chunkId')
    const historyId = searchParams.get('historyId')
    if (!historyId && messages.length === 0) {
      try {
        const draft = JSON.parse(sessionStorage.getItem(CHAT_DRAFT_KEY) || 'null') as ChatDraft | null
        if (draft?.messages?.length) {
          const lastQuestionId = draft.lastQuestionId || (draft.historyId !== 'pending' ? draft.historyId : null) || null
          setSelectedHistoryId(null)
          setCurrentQuestionId(lastQuestionId)
          setMode(draft.mode)
          setSelectedMaterialId(draft.materialId)
          setSelectedChunkId(draft.chunkId)
          setMessages(draft.messages)
          conversationHistory.current = draft.conversationHistory
          const nextParams = new URLSearchParams()
          if (draft.mode === 'MATERIAL' && draft.materialId) {
            nextParams.set('materialId', draft.materialId)
            if (draft.chunkId) nextParams.set('chunkId', draft.chunkId)
          }
          setSearchParams(nextParams, { replace: true })
          return
        }
      } catch {
        saveChatDraft(null)
      }
    }
    if (materialId) {
      setMode('MATERIAL')
      setSelectedMaterialId(materialId)
      setSelectedChunkId(chunkId)
    }
    if (historyId) {
      const target = historyItems.find((item) => String(item.id) === historyId)
      if (target && selectedHistoryId !== historyId) {
        applyHistorySelection(target, false)
      }
    }
  }, [historyItems, messages.length, searchParams, selectedHistoryId])

  useEffect(() => {
    if (selectedHistory) {
      const source = selectedHistory.sources?.[0]
      const restoredMessages: ChatMessage[] = [
        { id: selectedHistory.id + '-user', role: 'user', text: selectedHistory.question },
        { id: selectedHistory.id + '-assistant', role: 'assistant', text: selectedHistory.answer, sources: selectedHistory.sources },
      ]
      const restoredHistory = [
        { role: 'user', content: selectedHistory.question },
        { role: 'assistant', content: selectedHistory.answer },
      ]
      setMessages(restoredMessages)
      setCurrentQuestionId(null)
      conversationHistory.current = restoredHistory
      saveChatDraft({
        lastQuestionId: String(selectedHistory.id),
        mode: source ? 'MATERIAL' : 'GENERAL',
        materialId: source?.materialId || null,
        chunkId: source?.chunkId || null,
        messages: restoredMessages,
        conversationHistory: restoredHistory,
      })
    }
  }, [selectedHistory])

  const handleNewChat = () => {
    setSelectedHistoryId(null)
    setCurrentQuestionId(null)
    setMessages([])
    setInput('')
    conversationHistory.current = []
    abortRef.current?.abort()
    setStreaming(false)
    saveChatDraft(null)
    const nextParams = new URLSearchParams()
    if (mode === 'MATERIAL' && selectedMaterialId) {
      nextParams.set('materialId', selectedMaterialId)
      if (selectedChunkId) nextParams.set('chunkId', selectedChunkId)
    }
    setSearchParams(nextParams, { replace: true })
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
    applyHistorySelection(item, true)
  }

  const handleMaterialSelect = (materialId: string) => {
    setSelectedMaterialId(materialId)
    setSelectedChunkId(null)
    const nextParams = new URLSearchParams()
    nextParams.set('materialId', materialId)
    setSearchParams(nextParams, { replace: true })
  }

  const handleSubmit = () => {
    const question = input.trim()
    if (!question || streaming) return

    const userMsg: ChatMessage = { id: 'pending-user-' + Date.now(), role: 'user', text: question }
    const thinkingMsg: ChatMessage = { id: 'pending-assistant-' + Date.now(), role: 'assistant', text: '', thinking: true }
    const pendingMessages = messages.concat(userMsg, thinkingMsg)

    setMessages(pendingMessages)
    setSelectedHistoryId(null)
    setCurrentQuestionId(null)
    setInput('')
    setStreaming(true)
    answerRef.current = ''
    sourcesRef.current = []
    saveChatDraft({
      lastQuestionId: currentQuestionId,
      mode,
      materialId: mode === 'MATERIAL' ? selectedMaterialId : null,
      chunkId: mode === 'MATERIAL' ? selectedChunkId : null,
      messages: pendingMessages,
      conversationHistory: conversationHistory.current,
    })
    const activeParams = new URLSearchParams()
    if (mode === 'MATERIAL' && selectedMaterialId) {
      activeParams.set('materialId', selectedMaterialId)
      if (selectedChunkId) activeParams.set('chunkId', selectedChunkId)
    }
    setSearchParams(activeParams, { replace: true })

    const assistantId = thinkingMsg.id
    let firstChunk = true

    abortRef.current = chatStream(
      {
        question,
        mode,
        materialId: mode === 'MATERIAL' ? (selectedMaterialId || undefined) : undefined,
        chunkId: mode === 'MATERIAL' ? (selectedChunkId || undefined) : undefined,
        history: conversationHistory.current,
      },
      {
        onChunk: (delta) => {
          answerRef.current += delta
          const cleanText = sanitizeAiText(answerRef.current)
          setMessages((prev) => {
            const nextMessages = prev.map((m) =>
              m.id === assistantId
                ? { ...m, thinking: firstChunk ? false : m.thinking, text: cleanText }
                : m,
            )
            saveChatDraft({
              lastQuestionId: currentQuestionId,
              mode,
              materialId: mode === 'MATERIAL' ? selectedMaterialId : null,
              chunkId: mode === 'MATERIAL' ? selectedChunkId : null,
              messages: nextMessages,
              conversationHistory: conversationHistory.current,
            })
            return nextMessages
          })
          firstChunk = false
        },
        onSources: (sources) => {
          sourcesRef.current = sources
          setMessages((prev) => {
            const nextMessages = prev.map((m) => (m.id === assistantId ? { ...m, sources } : m))
            saveChatDraft({
              lastQuestionId: currentQuestionId,
              mode,
              materialId: mode === 'MATERIAL' ? selectedMaterialId : null,
              chunkId: mode === 'MATERIAL' ? selectedChunkId : null,
              messages: nextMessages,
              conversationHistory: conversationHistory.current,
            })
            return nextMessages
          })
        },
        onDone: (result) => {
          const questionId = String(result.questionId)
          const cleanAnswer = sanitizeAiText(result.answer)
          const nextMessages = pendingMessages.map((m) =>
            m.id === assistantId ? { ...m, id: questionId, text: cleanAnswer, sources: sourcesRef.current } : m,
          )
          setMessages(nextMessages)
          setCurrentQuestionId(questionId)
          const nextConversationHistory = [
            ...conversationHistory.current,
            { role: 'user', content: question },
            { role: 'assistant', content: cleanAnswer },
          ].slice(-10)
          conversationHistory.current = nextConversationHistory
          const nextParams = new URLSearchParams()
          if (mode === 'MATERIAL' && selectedMaterialId) {
            nextParams.set('materialId', selectedMaterialId)
            if (selectedChunkId) nextParams.set('chunkId', selectedChunkId)
          }
          setSearchParams(nextParams, { replace: true })
          saveChatDraft({
            lastQuestionId: questionId,
            mode,
            materialId: mode === 'MATERIAL' ? selectedMaterialId : null,
            chunkId: mode === 'MATERIAL' ? selectedChunkId : null,
            messages: nextMessages,
            conversationHistory: nextConversationHistory,
          })
          setStreaming(false)
          queryClient.invalidateQueries({ queryKey: ['history'] })
        },
        onError: (message) => {
          setMessages((prev) => {
            const msgs = prev.filter((m) => m.id !== assistantId)
            return [...msgs, { id: 'error-' + Date.now(), role: 'assistant', text: '', error: message }]
          })
          setStreaming(false)
        },
      },
    )
  }

  const handleOpenSource = (source: RagSource) => {
    navigate(`/workspace/reader?materialId=${encodeURIComponent(source.materialId)}&chunkId=${encodeURIComponent(source.chunkId)}`)
  }

  const isGeneral = mode === 'GENERAL'
  const quickPrompts = isGeneral ? GENERAL_PROMPTS : MATERIAL_PROMPTS
  const parsedMaterials = materials.filter((m) => m.parseStatus === 'SUCCESS' || m.parseStatus === 'PARSED')
  const isEmptyChat = messages.length === 0

  return (
    <motion.div
      className="flex h-full min-h-0 flex-col overflow-hidden bg-white dark:bg-[#171a21]"
      initial={{ opacity: 0 }}
      animate={{ opacity: 1 }}
      transition={{ duration: 0.3 }}
    >
      <div className="relative flex items-center justify-center gap-1 px-4 py-2">
        <Button
          variant={isGeneral ? 'secondary' : 'ghost'}
          size="sm"
          className={cn('h-8 rounded-full px-4 text-sm dark:text-slate-200 dark:hover:bg-white/[0.08]', isGeneral && 'bg-[#eef5ff] text-[#2f80ff] hover:bg-[#e6f0ff] dark:bg-sky-400/15 dark:text-sky-300 dark:hover:bg-sky-400/20')}
          onClick={() => handleModeChange('GENERAL')}
        >
          <Sparkles className="mr-1.5 h-4 w-4" />
          智慧问答
        </Button>
        <Button
          variant={!isGeneral ? 'secondary' : 'ghost'}
          size="sm"
          className={cn('h-8 rounded-full px-4 text-sm dark:text-slate-200 dark:hover:bg-white/[0.08]', !isGeneral && 'bg-[#eef5ff] text-[#2f80ff] hover:bg-[#e6f0ff] dark:bg-sky-400/15 dark:text-sky-300 dark:hover:bg-sky-400/20')}
          onClick={() => handleModeChange('MATERIAL')}
        >
          <BookOpen className="mr-1.5 h-4 w-4" />
          资料问答
        </Button>
        <div className="absolute right-8">
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
          <div className="mb-7 text-center">
            <h2 className="text-5xl font-black tracking-[0.02em] text-black dark:text-white">智慧问答</h2>
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
            onChange={setInput}
            onSubmit={handleSubmit}
            loading={streaming}
            mode={mode}
            onModeChange={handleModeChange}
            quickPrompts={quickPrompts}
            disabled={!isGeneral && !selectedMaterialId}
            disabledHint="请先选择资料"
            centered
          />
        </div>
      ) : (
        <>
          <ChatThread messages={messages} onOpenSource={handleOpenSource} />
          <ChatComposer
            value={input}
            onChange={setInput}
            onSubmit={handleSubmit}
            loading={streaming}
            mode={mode}
            onModeChange={handleModeChange}
            quickPrompts={quickPrompts}
            disabled={!isGeneral && !selectedMaterialId}
            disabledHint="请先选择资料"
          />
        </>
      )}
    </motion.div>
  )
}
