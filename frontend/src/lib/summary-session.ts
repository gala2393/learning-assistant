import { summarizeMaterial } from '@/api/rag'
import { queryClient } from '@/lib/query-client'
import type { SummaryResult, SummaryType } from '@/types'

interface SummaryTask {
  materialId: string
  summaryType: SummaryType
  loading: boolean
  error: string | null
  notice: string | null
  result: SummaryResult | null
}

export interface SummarySessionSnapshot {
  tasks: Record<string, SummaryTask>
}

let state: SummarySessionSnapshot = { tasks: {} }
const activeControllers = new Map<string, AbortController>()
const listeners = new Set<() => void>()

/**
 * 知识总结生成是普通 HTTP 请求，不是 SSE。
 * 但如果只使用组件内 mutation 状态，用户切换模块后组件会卸载，切回来就看不到“生成中”状态。
 * 这里用模块级 store 托管请求生命周期；用户点击“暂停生成”时通过 AbortController 中止当前请求。
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
    notice: null,
    result: null,
  })

  const controller = new AbortController()
  activeControllers.set(payload.materialId, controller)

  try {
    const result = await summarizeMaterial(payload, { signal: controller.signal })
    setTask(payload.materialId, {
      materialId: payload.materialId,
      summaryType: payload.summaryType,
      loading: false,
      error: null,
      notice: null,
      result,
    })
    queryClient.invalidateQueries({ queryKey: ['summaries', payload.materialId] })
    queryClient.invalidateQueries({ queryKey: ['summaries', payload.materialId, 'history'] })
  } catch (error) {
    if (isCanceledRequest(error) || controller.signal.aborted) {
      setTask(payload.materialId, {
        materialId: payload.materialId,
        summaryType: payload.summaryType,
        loading: false,
        error: null,
        notice: '已暂停生成。',
        result: null,
      })
      return
    }
    setTask(payload.materialId, {
      materialId: payload.materialId,
      summaryType: payload.summaryType,
      loading: false,
      error: error instanceof Error ? error.message : '生成总结失败',
      notice: null,
      result: null,
    })
  } finally {
    if (activeControllers.get(payload.materialId) === controller) {
      activeControllers.delete(payload.materialId)
    }
  }
}

/** 暂停某份资料正在进行的总结生成；已生成完成的任务不会被改动。 */
export function pauseSummaryTask(materialId: string | null | undefined) {
  if (!materialId) return
  const controller = activeControllers.get(materialId)
  const existing = state.tasks[materialId]
  if (!controller || !existing?.loading) return
  controller.abort()
  activeControllers.delete(materialId)
  setTask(materialId, {
    ...existing,
    loading: false,
    error: null,
    notice: '已暂停生成。',
  })
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

/** Axios 取消请求时会抛出 CanceledError，这里统一识别，避免把用户主动暂停显示成错误。 */
function isCanceledRequest(error: unknown) {
  const maybeCanceled = error as { code?: string; name?: string; message?: string } | null
  return maybeCanceled?.code === 'ERR_CANCELED'
    || maybeCanceled?.name === 'CanceledError'
    || maybeCanceled?.message === 'canceled'
}
