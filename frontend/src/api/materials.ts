import api from '@/lib/axios'
import { useMutation, useQuery } from '@tanstack/react-query'
import { queryClient } from '@/lib/query-client'
import type { Material, MaterialChunk, MaterialPage, PageResult } from '@/types'

const LARGE_UPLOAD_CHUNK_SIZE = 5 * 1024 * 1024
export const MAX_UPLOAD_BYTES = 500 * 1024 * 1024
const PROCESSING_POLL_INTERVAL_MS = 1500
const PROCESSING_TIMEOUT_MS = 15 * 60 * 1000
const CHUNK_UPLOAD_RETRY_COUNT = 3

export interface UploadSession {
  sessionId: string
  clientUploadId: string
  materialId: string | null
  title: string
  originalName: string
  sourceType: string
  sourceUrl: string | null
  fileSize: number
  chunkSize: number
  totalChunks: number
  uploadedChunks: number
  status: 'UPLOADING' | 'PROCESSING' | 'SUCCESS' | 'FAILED'
  errorMessage: string | null
  parseProgressPercent?: number | null
  parseStage?: string | null
  parseMessage?: string | null
  createdAt: string
  updatedAt: string
}

export interface UploadProgress {
  phase: 'uploading' | 'processing'
  percent: number
  uploadedChunks: number
  totalChunks: number
  stage?: string | null
  message?: string | null
}

export async function listMaterials(): Promise<Material[]> {
  const { data } = await api.get('/materials')
  return (data || []).map(normalizeMaterial)
}

export async function getMaterial(id: string): Promise<Material> {
  const { data } = await api.get(`/materials/${id}`)
  return normalizeMaterial(data)
}

export async function getMaterialChunks(id: string): Promise<MaterialChunk[]> {
  const { data } = await api.get(`/materials/${id}/chunks`)
  return (data || []).map(normalizeChunk)
}

export async function getMaterialPages(id: string): Promise<MaterialPage[]> {
  const { data } = await api.get(`/materials/${id}/pages`)
  return data
}

export async function getMaterialFile(id: string) {
  const response = await api.get(`/materials/${id}/file`, { responseType: 'blob' })
  return {
    blob: response.data as Blob,
    contentType: (response.headers?.['content-type'] || '') as string,
    disposition: (response.headers?.['content-disposition'] || '') as string,
  }
}

export async function createMaterialFileTicket(id: string): Promise<{ ticket: string; url: string; expiresAt: number }> {
  const { data } = await api.post(`/materials/${id}/file-ticket`)
  return data
}

export async function uploadMaterial(params: { file: File; title?: string; sourceType?: string; sourceUrl?: string }): Promise<Material> {
  const formData = new FormData()
  formData.append('file', params.file)
  if (params.title) formData.append('title', params.title)
  if (params.sourceType) formData.append('sourceType', params.sourceType)
  if (params.sourceUrl) formData.append('sourceUrl', params.sourceUrl)
  const { data } = await api.post('/materials', formData)
  return normalizeMaterial(data)
}

export async function importWebMaterial(params: { title?: string; sourceUrl: string }): Promise<Material> {
  const { data } = await api.post('/materials/web', params)
  return normalizeMaterial(data)
}

export async function createUploadSession(payload: {
  clientUploadId: string
  title: string
  originalName: string
  fileSize: number
  chunkSize: number
  sourceType: string
  sourceUrl?: string
}) {
  const { data } = await api.post('/materials/upload-sessions', payload)
  return normalizeUploadSession(data)
}

export async function uploadChunk(sessionId: string, params: { chunk: Blob; chunkIndex: number; totalChunks: number; checksumSha256?: string }) {
  const formData = new FormData()
  formData.append('chunk', params.chunk)
  formData.append('chunkIndex', String(params.chunkIndex))
  formData.append('totalChunks', String(params.totalChunks))
  if (params.checksumSha256) formData.append('checksumSha256', params.checksumSha256)
  const { data } = await api.post(`/materials/upload-sessions/${sessionId}/chunks`, formData)
  return normalizeUploadSession(data)
}

async function uploadChunkWithRetry(
  sessionId: string,
  params: { chunk: Blob; chunkIndex: number; totalChunks: number; checksumSha256?: string },
) {
  let lastError: unknown = null
  for (let attempt = 0; attempt < CHUNK_UPLOAD_RETRY_COUNT; attempt += 1) {
    try {
      return await uploadChunk(sessionId, params)
    } catch (error) {
      lastError = error
      if (attempt < CHUNK_UPLOAD_RETRY_COUNT - 1) {
        await sleep(600 * (attempt + 1))
      }
    }
  }
  throw lastError instanceof Error ? lastError : new Error('分片上传失败，请检查网络后重试')
}

export async function getUploadSession(sessionId: string) {
  const { data } = await api.get(`/materials/upload-sessions/${sessionId}`)
  return normalizeUploadSession(data)
}

export async function uploadMaterialInChunks(
  params: { file: File; title?: string; sourceType: string; sourceUrl?: string },
  onProgress?: (progress: UploadProgress) => void,
): Promise<UploadSession> {
  if (params.file.size > MAX_UPLOAD_BYTES) {
    throw new Error('文件超过 500MB，请压缩或拆分后再上传')
  }
  const chunkSize = LARGE_UPLOAD_CHUNK_SIZE
  const totalChunks = Math.max(1, Math.ceil(params.file.size / chunkSize))
  const session = await createUploadSession({
    clientUploadId: buildClientUploadId(params.file),
    title: params.title?.trim() || params.file.name,
    originalName: params.file.name,
    sourceType: params.sourceType,
    sourceUrl: params.sourceUrl,
    fileSize: params.file.size,
    chunkSize,
  })

  let latest = session as UploadSession
  for (let index = latest.uploadedChunks || 0; index < totalChunks; index++) {
    const start = index * chunkSize
    const end = Math.min(params.file.size, start + chunkSize)
    latest = await uploadChunkWithRetry(latest.sessionId, {
      chunk: params.file.slice(start, end),
      chunkIndex: index,
      totalChunks,
    }) as UploadSession
    onProgress?.({
      phase: 'uploading',
      percent: Math.round(((index + 1) / totalChunks) * 100),
      uploadedChunks: index + 1,
      totalChunks,
    })
  }

  if (latest.status === 'FAILED') {
    throw new Error(latest.errorMessage || '上传处理失败')
  }
  return waitForUploadProcessing(latest.sessionId, totalChunks, onProgress)
}

async function waitForUploadProcessing(
  sessionId: string,
  totalChunks: number,
  onProgress?: (progress: UploadProgress) => void,
): Promise<UploadSession> {
  const startedAt = Date.now()
  while (Date.now() - startedAt < PROCESSING_TIMEOUT_MS) {
    const session = await getUploadSession(sessionId) as UploadSession
    if (session.status === 'SUCCESS') {
      onProgress?.({
        phase: 'processing',
        percent: 100,
        uploadedChunks: totalChunks,
        totalChunks,
        stage: session.parseStage || '解析完成',
        message: session.parseMessage || '资料已经可以使用',
      })
      return session
    }
    if (session.status === 'FAILED') {
      throw new Error(session.errorMessage || '资料解析失败')
    }
    onProgress?.({
      phase: 'processing',
      percent: normalizeProcessingPercent(session.parseProgressPercent),
      uploadedChunks: totalChunks,
      totalChunks,
      stage: session.parseStage || '后台解析中',
      message: session.parseMessage,
    })
    await sleep(PROCESSING_POLL_INTERVAL_MS)
  }
  throw new Error('资料仍在后台解析，请稍后刷新资料列表查看结果')
}

function buildClientUploadId(file: File): string {
  return [
    'web',
    file.name,
    file.size,
    file.lastModified,
  ].join('-').replace(/[^A-Za-z0-9._-]/g, '_').slice(0, 180)
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => window.setTimeout(resolve, ms))
}

function normalizeProcessingPercent(percent?: number | null): number {
  if (typeof percent !== 'number' || Number.isNaN(percent)) return 5
  return Math.max(0, Math.min(99, Math.round(percent)))
}

function normalizeMaterial(material: Material): Material {
  return {
    ...material,
    id: String(material.id),
    parseProgressPercent: typeof material.parseProgressPercent === 'number' ? material.parseProgressPercent : null,
  }
}

function normalizeChunk(chunk: MaterialChunk): MaterialChunk {
  return {
    ...chunk,
    id: String(chunk.id),
    materialId: String(chunk.materialId),
  }
}

function normalizeUploadSession(session: UploadSession): UploadSession {
  return {
    ...session,
    materialId: session.materialId == null ? null : String(session.materialId),
  }
}

export async function updateMaterial(id: string, payload: { title?: string; sourceUrl?: string }): Promise<Material> {
  const { data } = await api.put(`/materials/${id}`, payload)
  return normalizeMaterial(data)
}

export async function reparseMaterial(id: string): Promise<Material> {
  const { data } = await api.post(`/materials/${id}/reparse`)
  return normalizeMaterial(data)
}

export async function deleteMaterial(id: string): Promise<void> {
  await api.delete(`/materials/${id}`)
}

export function useMaterials() {
  return useQuery({
    queryKey: ['materials'],
    queryFn: listMaterials,
    refetchInterval: (query) => {
      const data = query.state.data as Material[] | undefined
      return data?.some(isMaterialParsing) ? 1500 : false
    },
  })
}

function isMaterialParsing(material: Material): boolean {
  return ['PENDING', 'PARSING', 'PROCESSING'].includes(material.parseStatus)
    && (material.parseProgressPercent ?? 0) < 100
}

export function useMaterial(id: string | null) {
  return useQuery({
    queryKey: ['materials', id],
    queryFn: () => getMaterial(id!),
    enabled: !!id,
  })
}

export function useMaterialChunks(id: string | null) {
  return useQuery({
    queryKey: ['materials', id, 'chunks'],
    queryFn: () => getMaterialChunks(id!),
    enabled: !!id,
  })
}

export function useMaterialPages(id: string | null) {
  return useQuery({
    queryKey: ['materials', id, 'pages'],
    queryFn: () => getMaterialPages(id!),
    enabled: !!id,
  })
}

export function useUploadMaterial() {
  return useMutation({
    mutationFn: uploadMaterial,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['materials'] }),
  })
}

export function useImportWebMaterial() {
  return useMutation({
    mutationFn: importWebMaterial,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['materials'] }),
  })
}

export function useUpdateMaterial() {
  return useMutation({
    mutationFn: ({ id, payload }: { id: string; payload: { title?: string; sourceUrl?: string } }) => updateMaterial(id, payload),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['materials'] }),
  })
}

export function useReparseMaterial() {
  return useMutation({
    mutationFn: reparseMaterial,
    onSuccess: (_material, id) => {
      queryClient.invalidateQueries({ queryKey: ['materials'] })
      queryClient.invalidateQueries({ queryKey: ['materials', id] })
      queryClient.invalidateQueries({ queryKey: ['materials', id, 'chunks'] })
      queryClient.invalidateQueries({ queryKey: ['history'] })
    },
  })
}

export function useDeleteMaterial() {
  return useMutation({
    mutationFn: deleteMaterial,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['materials'] }),
  })
}
