import { summarizeMaterial } from '@/api/rag'
import { queryClient } from '@/lib/query-client'
import type { SummaryResult, SummaryType } from '@/types'

interface SummaryTask {
  materialId: string
  summaryType: SummaryType
  loading: boolean
  error: string | null
  result: SummaryResult | null
}

export interface SummarySessionSnapshot {
  tasks: Record<string, SummaryTask>
}

let state: SummarySessionSnapshot = { tasks: {} }
const listeners = new Set<() => void>()

/**
 * 知识总结生成是普通 HTTP 请求，不是 SSE。
 * 但如果只使用组件内 mutation 状态，用户切换模块后组件会卸载，切回来就看不到“生成中”状态。
 * 这里用模块级 store 托管请求生命周期，让请求继续完成，并在完成后刷新总结历史缓存。
 */
export function getSummarySessionSnapshot() {
  return state
}

/** 供 useSyncExternalStore 订阅总结任务变化。 */
export function subscribeSummarySession(listener: () => void) {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

/** 读取某份资料当前的总结任务状态。 */
export function getSummaryTask(materialId: string | null | undefined) {
  if (!materialId) return null
  return state.tasks[materialId] || null
}

/**
 * 启动总结生成任务。
 * 同一资料正在生成时直接复用现有任务，避免用户来回切换页面造成重复请求。
 */
export async function startSummaryTask(payload: { materialId: string; summaryType: SummaryType }) {
  const existing = state.tasks[payload.materialId]
  if (existing?.loading) return

  setTask(payload.materialId, {
    materialId: payload.materialId,
    summaryType: payload.summaryType,
    loading: true,
    error: null,
    result: null,
  })

  try {
    const result = await summarizeMaterial(payload)
    setTask(payload.materialId, {
      materialId: payload.materialId,
      summaryType: payload.summaryType,
      loading: false,
      error: null,
      result,
    })
    queryClient.invalidateQueries({ queryKey: ['summaries', payload.materialId] })
    queryClient.invalidateQueries({ queryKey: ['summaries', payload.materialId, 'history'] })
  } catch (error) {
    setTask(payload.materialId, {
      materialId: payload.materialId,
      summaryType: payload.summaryType,
      loading: false,
      error: error instanceof Error ? error.message : '生成总结失败',
      result: null,
    })
  }
}

function setTask(materialId: string, task: SummaryTask) {
  state = {
    ...state,
    tasks: {
      ...state.tasks,
      [materialId]: task,
    },
  }
  listeners.forEach((listener) => listener())
}
