import api from '@/lib/axios'
import { useMutation, useQuery } from '@tanstack/react-query'
import { queryClient } from '@/lib/query-client'
import { hasStoredSession } from '@/lib/auth-gate'
import type { FavoriteItem } from '@/types'

/**
 * 收藏管理 API — 用户可以收藏有用的问答记录，方便回顾。
 *
 * 后端接口映射：
 * - GET    /api/favorites       → 获取收藏列表
 * - POST   /api/favorites       → 添加收藏（body: { questionId }）
 * - DELETE /api/favorites/{id}  → 取消收藏
 */

/** 获取当前用户的所有收藏 */
export async function listFavorites(): Promise<FavoriteItem[]> {
  const { data } = await api.get('/favorites')
  return data
}

/** 收藏一条问答记录 */
export async function addFavorite(questionId: string): Promise<FavoriteItem> {
  const { data } = await api.post('/favorites', { questionId })
  return data
}

/** 取消收藏 */
export async function deleteFavorite(id: string): Promise<void> {
  await api.delete(`/favorites/${id}`)
}

/** 收藏列表 query */
export function useFavorites() {
  return useQuery({ queryKey: ['favorites'], queryFn: listFavorites, enabled: hasStoredSession() })
}

/** 添加收藏 mutation — 同时刷新收藏列表和历史列表（历史中显示收藏标记） */
export function useAddFavorite() {
  return useMutation({ mutationFn: addFavorite, onSuccess: () => {
    queryClient.invalidateQueries({ queryKey: ['favorites'] })
    queryClient.invalidateQueries({ queryKey: ['history'] })
  } })
}

/** 取消收藏 mutation */
export function useDeleteFavorite() {
  return useMutation({ mutationFn: deleteFavorite, onSuccess: () => {
    queryClient.invalidateQueries({ queryKey: ['favorites'] })
    queryClient.invalidateQueries({ queryKey: ['history'] })
  } })
}
