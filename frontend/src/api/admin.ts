import api from '@/lib/axios'
import { useMutation, useQuery } from '@tanstack/react-query'
import { queryClient } from '@/lib/query-client'
import type { AdminStats, AdminUser, AdminMaterial, AdminLog, AdminUsageRecord, PageResult } from '@/types'

export async function getAdminStats(): Promise<AdminStats> {
  const { data } = await api.get('/admin/stats')
  return data
}

export async function listAdminUsers(params: { page?: number; size?: number; keyword?: string } = {}): Promise<PageResult<AdminUser>> {
  const { data } = await api.get('/admin/users', { params })
  return data
}

export async function updateAdminUserRole(id: string, role: string): Promise<AdminUser> {
  const { data } = await api.patch(`/admin/users/${id}/role`, { role })
  return data
}

export async function updateAdminUserStatus(id: string, status: string): Promise<AdminUser> {
  const { data } = await api.patch(`/admin/users/${id}/status`, { status })
  return data
}

export async function listAdminMaterials(params: { page?: number; size?: number; keyword?: string } = {}): Promise<PageResult<AdminMaterial>> {
  const { data } = await api.get('/admin/materials', { params })
  return data
}

export async function updateAdminMaterialStatus(id: string, payload: { parseStatus?: string; summaryStatus?: string }): Promise<AdminMaterial> {
  const { data } = await api.patch(`/admin/materials/${id}/status`, payload)
  return data
}

export async function listAdminLogs(params: { page?: number; size?: number; keyword?: string } = {}): Promise<PageResult<AdminLog>> {
  const { data } = await api.get('/admin/logs', { params })
  return data
}

export async function listAdminUsageRecords(params: { page?: number; size?: number; keyword?: string } = {}): Promise<PageResult<AdminUsageRecord>> {
  const { data } = await api.get('/admin/usage-records', { params })
  return data
}

export function useAdminStats() {
  return useQuery({
    queryKey: ['admin', 'stats'],
    queryFn: getAdminStats,
  })
}

export function useAdminUsers(params: { page?: number; size?: number; keyword?: string } = {}) {
  return useQuery({
    queryKey: ['admin', 'users', params],
    queryFn: () => listAdminUsers(params),
  })
}

export function useUpdateAdminUserRole() {
  return useMutation({
    mutationFn: ({ id, role }: { id: string; role: string }) => updateAdminUserRole(id, role),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['admin', 'users'] }),
  })
}

export function useUpdateAdminUserStatus() {
  return useMutation({
    mutationFn: ({ id, status }: { id: string; status: string }) => updateAdminUserStatus(id, status),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['admin', 'users'] }),
  })
}

export function useAdminMaterials(params: { page?: number; size?: number; keyword?: string } = {}) {
  return useQuery({
    queryKey: ['admin', 'materials', params],
    queryFn: () => listAdminMaterials(params),
  })
}

export function useUpdateAdminMaterialStatus() {
  return useMutation({
    mutationFn: ({ id, payload }: { id: string; payload: { parseStatus?: string; summaryStatus?: string } }) => updateAdminMaterialStatus(id, payload),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['admin', 'materials'] }),
  })
}

export function useAdminLogs(params: { page?: number; size?: number; keyword?: string } = {}) {
  return useQuery({
    queryKey: ['admin', 'logs', params],
    queryFn: () => listAdminLogs(params),
  })
}

export function useAdminUsageRecords(params: { page?: number; size?: number; keyword?: string } = {}) {
  return useQuery({
    queryKey: ['admin', 'usage-records', params],
    queryFn: () => listAdminUsageRecords(params),
    refetchInterval: 5000,
    refetchIntervalInBackground: true,
  })
}
