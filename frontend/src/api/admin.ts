import api from '@/lib/axios'
import { useMutation, useQuery } from '@tanstack/react-query'
import { queryClient } from '@/lib/query-client'
import type {
  AdminLog,
  AdminMaterial,
  AdminStats,
  AdminUsageRecord,
  AdminUser,
  AdminVectorIndexRebuildResponse,
  PageResult,
  SystemDependency,
} from '@/types'

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

/** 获取运行环境依赖自检结果，用于提示 PDF/OCR/Office 转换能力是否完整。 */
export async function getAdminDependencies(): Promise<SystemDependency[]> {
  const { data } = await api.get('/admin/system/dependencies')
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

/** 提交 Qdrant 向量索引重建任务；不传 materialId 时重建所有已解析资料。 */
export async function rebuildAdminVectorIndex(materialId?: string): Promise<AdminVectorIndexRebuildResponse> {
  const { data } = await api.post('/admin/materials/vector-index/rebuild', null, {
    params: materialId ? { materialId } : undefined,
  })
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

/** 运行环境依赖自检 query */
export function useAdminDependencies() {
  return useQuery({
    queryKey: ['admin', 'system', 'dependencies'],
    queryFn: getAdminDependencies,
    refetchInterval: 30_000,
  })
}

/** 用户列表 query */
export function useAdminUsers(params: { page?: number; size?: number; keyword?: string } = {}) {
  // 分页和关键词放进 queryKey，确保每组筛选条件都有独立缓存。
  return useQuery({ queryKey: ['admin', 'users', params], queryFn: () => listAdminUsers(params) })
}

/** 修改用户角色 mutation */
export function useUpdateAdminUserRole() {
  return useMutation({
    mutationFn: ({ id, role }: { id: string; role: string }) => updateAdminUserRole(id, role),
    // 角色变更会影响列表中的权限标签，成功后让所有用户列表缓存重新拉取。
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['admin', 'users'] }),
  })
}

/** 修改用户状态 mutation */
export function useUpdateAdminUserStatus() {
  return useMutation({
    mutationFn: ({ id, status }: { id: string; status: string }) => updateAdminUserStatus(id, status),
    // 状态变更后不能只改当前行，分页/搜索结果中的同一用户也需要同步。
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['admin', 'users'] }),
  })
}

/** 资料列表 query（管理员视图） */
export function useAdminMaterials(params: { page?: number; size?: number; keyword?: string } = {}) {
  // 管理端资料列表同样按分页/搜索条件隔离缓存，避免切页时串数据。
  return useQuery({
    queryKey: ['admin', 'materials', params],
    queryFn: () => listAdminMaterials(params),
    // 管理员需要看到大 PDF 的 OCR/索引后台进度；只要当前页存在未完成流水线，就持续轻量轮询。
    refetchInterval: (query) => {
      const data = query.state.data as PageResult<AdminMaterial> | undefined
      return data?.items?.some(isAdminMaterialStillProcessing) ? 1500 : false
    },
    refetchIntervalInBackground: true,
  })
}

/** 判断管理员资料行是否仍有后台流水线未完成。 */
function isAdminMaterialStillProcessing(material: AdminMaterial) {
  const parseActive = ['PENDING', 'PARSING', 'PROCESSING'].includes(material.parseStatus)
  const textActive = ['PENDING', 'RUNNING', 'PARTIAL'].includes(String(material.textStatus || ''))
  const indexActive = ['PENDING', 'RUNNING', 'PARTIAL'].includes(String(material.indexStatus || ''))
  const ocrActive = ['PENDING', 'RUNNING', 'PARTIAL'].includes(String(material.ocrStatus || ''))
  const progressActive = typeof material.processingProgressPercent === 'number'
    && material.processingProgressPercent < 100
    && !['FAILED', 'READY'].includes(String(material.textStatus || ''))
  return parseActive || textActive || indexActive || ocrActive || progressActive
}

/** 修改资料状态 mutation */
export function useUpdateAdminMaterialStatus() {
  return useMutation({
    mutationFn: ({ id, payload }: { id: string; payload: { parseStatus?: string; summaryStatus?: string } }) => updateAdminMaterialStatus(id, payload),
    // 人工标记会改变状态筛选和统计口径，刷新资料列表缓存。
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['admin', 'materials'] }),
  })
}

/** 提交历史资料向量索引补建任务，主要用于首次启用 Qdrant 后回填已有资料。 */
export function useRebuildAdminVectorIndex() {
  return useMutation({
    mutationFn: (materialId?: string) => rebuildAdminVectorIndex(materialId),
    // 后台任务提交成功后刷新依赖和资料列表，让管理员看到 Qdrant 状态与资料处理状态的最新值。
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['admin', 'system', 'dependencies'] })
      queryClient.invalidateQueries({ queryKey: ['admin', 'materials'] })
    },
  })
}

/** 系统日志 query */
export function useAdminLogs(params: { page?: number; size?: number; keyword?: string } = {}) {
  // 日志查询参数进入 queryKey，保证搜索词变化时触发新的后端查询。
  return useQuery({ queryKey: ['admin', 'logs', params], queryFn: () => listAdminLogs(params) })
}

/** 使用记录 query — 每 5 秒自动刷新（实时监控用户操作） */
export function useAdminUsageRecords(params: { page?: number; size?: number; keyword?: string } = {}) {
  return useQuery({
    queryKey: ['admin', 'usage-records', params],
    queryFn: () => listAdminUsageRecords(params),
    // 使用记录是近实时流水，定时刷新比手动失效更适合管理端监控场景。
    refetchInterval: 5000,              // 每 5 秒刷新
    refetchIntervalInBackground: true,  // 后台标签页也继续刷新
  })
}
