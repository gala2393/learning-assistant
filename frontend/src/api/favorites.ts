import api from '@/lib/axios'
import { useMutation, useQuery } from '@tanstack/react-query'
import { queryClient } from '@/lib/query-client'
import type { FavoriteItem } from '@/types'

export async function listFavorites(): Promise<FavoriteItem[]> {
  const { data } = await api.get('/favorites')
  return data
}

export async function addFavorite(questionId: string): Promise<FavoriteItem> {
  const { data } = await api.post('/favorites', { questionId })
  return data
}

export async function deleteFavorite(id: string): Promise<void> {
  await api.delete(`/favorites/${id}`)
}

export function useFavorites() {
  return useQuery({
    queryKey: ['favorites'],
    queryFn: listFavorites,
  })
}

export function useAddFavorite() {
  return useMutation({
    mutationFn: addFavorite,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['favorites'] })
      queryClient.invalidateQueries({ queryKey: ['history'] })
    },
  })
}

export function useDeleteFavorite() {
  return useMutation({
    mutationFn: deleteFavorite,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['favorites'] })
      queryClient.invalidateQueries({ queryKey: ['history'] })
    },
  })
}
