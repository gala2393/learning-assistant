import api from '@/lib/axios'
import { useMutation, useQuery } from '@tanstack/react-query'
import { queryClient } from '@/lib/query-client'
import type { AdminStats, AdminUser, AdminMaterial, AdminLog, AdminUsageRecord, PageResult } from '@/types'

/**
 * 管理后台 API — 仅管理员可用（后端 AuthInterceptor 检查 ADMIN 角色）。
 *
 * 功能：
 * - 仪表盘统计（用户数、资料数、问答数等）
 * - 用户管理（列表、搜索、修改角色、禁用/启用）
 * - 资料管理（列表、搜索、修改解析状态）
 * - 系统日志（操作记录查看）
 * - 使用记录（用户问答、上传等操作的 token 消耗统计）
 *
 * 后端接口映射：/api/admin/*
 */

// ===== 仪表盘 =====

/** 获取管理后台统计数据（用户数、资料数、问答数、收藏数、日志数） */
export async function getAdminStats(): Promise<AdminStats> {
  const { data } = await api.get('/admin/stats')
  return data
}

// ===== 用户管理 =====

/** 获取用户列表（支持分页和关键词搜索） */
export async function listAdminUsers(params: { page?: number; size?: number; keyword?: string } = {}): Promise<PageResult<AdminUser>> {
  const { data } = await api.get('/admin/users', { params })
  return data
}

/** 修改用户角色（USER ↔ ADMIN） */
export async function updateAdminUserRole(id: string, role: string): Promise<AdminUser> {
  const { data } = await api.patch(`/admin/users/${id}/role`, { role })
  return data
}

/** 修改用户状态（ACTIVE ↔ DISABLED，禁用后用户无法登录） */
export async function updateAdminUserStatus(id: string, status: string): Promise<AdminUser> {
  const { data } = await api.patch(`/admin/users/${id}/status`, { status })
  return data
}

// ===== 资料管理 =====

/** 获取所有用户的资料列表（管理员视图，包含上传者信息） */
export async function listAdminMaterials(params: { page?: number; size?: number; keyword?: string } = {}): Promise<PageResult<AdminMaterial>> {
  const { data } = await api.get('/admin/materials', { params })
  return data
}

/** 修改资料的解析状态或总结状态 */
export async function updateAdminMaterialStatus(id: string, payload: { parseStatus?: string; summaryStatus?: string }): Promise<AdminMaterial> {
  const { data } = await api.patch(`/admin/materials/${id}/status`, payload)
  return data
}

// ===== 系统日志 =====

/** 获取系统日志（管理员操作记录：角色变更、用户禁用等） */
export async function listAdminLogs(params: { page?: number; size?: number; keyword?: string } = {}): Promise<PageResult<AdminLog>> {
  const { data } = await api.get('/admin/logs', { params })
  return data
}

// ===== 使用记录 =====

/** 获取使用记录（用户问答、资料上传等操作的 token 消耗统计） */
export async function listAdminUsageRecords(params: { page?: number; size?: number; keyword?: string } = {}): Promise<PageResult<AdminUsageRecord>> {
  const { data } = await api.get('/admin/usage-records', { params })
  return data
}

// ===== React Hooks =====

/** 仪表盘统计 query */
export function useAdminStats() {
  return useQuery({ queryKey: ['admin', 'stats'], queryFn: getAdminStats })
}

/** 用户列表 query */
export function useAdminUsers(params: { page?: number; size?: number; keyword?: string } = {}) {
  return useQuery({ queryKey: ['admin', 'users', params], queryFn: () => listAdminUsers(params) })
}

/** 修改用户角色 mutation */
export function useUpdateAdminUserRole() {
  return useMutation({
    mutationFn: ({ id, role }: { id: string; role: string }) => updateAdminUserRole(id, role),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['admin', 'users'] }),
  })
}

/** 修改用户状态 mutation */
export function useUpdateAdminUserStatus() {
  return useMutation({
    mutationFn: ({ id, status }: { id: string; status: string }) => updateAdminUserStatus(id, status),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['admin', 'users'] }),
  })
}

/** 资料列表 query（管理员视图） */
export function useAdminMaterials(params: { page?: number; size?: number; keyword?: string } = {}) {
  return useQuery({ queryKey: ['admin', 'materials', params], queryFn: () => listAdminMaterials(params) })
}

/** 修改资料状态 mutation */
export function useUpdateAdminMaterialStatus() {
  return useMutation({
    mutationFn: ({ id, payload }: { id: string; payload: { parseStatus?: string; summaryStatus?: string } }) => updateAdminMaterialStatus(id, payload),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['admin', 'materials'] }),
  })
}

/** 系统日志 query */
export function useAdminLogs(params: { page?: number; size?: number; keyword?: string } = {}) {
  return useQuery({ queryKey: ['admin', 'logs', params], queryFn: () => listAdminLogs(params) })
}

/** 使用记录 query — 每 5 秒自动刷新（实时监控用户操作） */
export function useAdminUsageRecords(params: { page?: number; size?: number; keyword?: string } = {}) {
  return useQuery({
    queryKey: ['admin', 'usage-records', params],
    queryFn: () => listAdminUsageRecords(params),
    refetchInterval: 5000,              // 每 5 秒刷新
    refetchIntervalInBackground: true,  // 后台标签页也继续刷新
  })
}
