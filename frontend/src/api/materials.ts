import api from '@/lib/axios'
import { useMutation, useQuery } from '@tanstack/react-query'
import { queryClient } from '@/lib/query-client'
import { hasStoredSession } from '@/lib/auth-gate'
import type { Material, MaterialChunk, MaterialPage, MaterialPageTextBlock, PageResult, TemporaryMaterial } from '@/types'

/**
 * 资料管理 API 模块 — 处理学习资料的上传、查询、分片上传、删除等操作。
 *
 * 核心功能：
 * 1. 普通上传（小文件）— 一次性 POST 文件
 * 2. 分片上传（大文件）— 1MB 一片，逐片上传，支持断点续传
 * 3. 上传后轮询状态 — 每 1.5 秒检查后端解析进度
 * 4. 资料 CRUD — 查询列表、详情、分块、更新标题、删除
 */

// ===== 常量 =====
export const LARGE_UPLOAD_CHUNK_SIZE = 1 * 1024 * 1024 // 每片 1MB，降低 nginx/网关单请求体限制导致的大文件分片失败概率
export const MAX_UPLOAD_BYTES = 2 * 1024 * 1024 * 1024 // 持久资料最大 2GB，适配大 PDF 后台解析
export const MAX_TEMPORARY_MATERIAL_BYTES = 100 * 1024 * 1024
const PROCESSING_POLL_INTERVAL_MS = 1500               // 轮询间隔 1.5 秒
const PROCESSING_TIMEOUT_MS = 60 * 60 * 1000           // 最长等待 60 分钟
const CHUNK_UPLOAD_RETRY_COUNT = 3                     // 每片最多重试 3 次
const CHUNK_UPLOAD_CONCURRENCY = 3                      // 同一文件最多并行上传 3 个分片，优先保证大文件在服务器端稳定落盘

/**
 * 上传会话 — 大文件分片上传的会话对象。
 * 后端在创建会话时返回，记录上传进度和解析状态。
 */
export interface UploadSession {
  sessionId: string           // 会话 ID
  clientUploadId: string      // 客户端生成的上传 ID（防重复提交）
  materialId: string | null   // 关联的资料 ID（解析完成后赋值）
  title: string               // 资料标题
  originalName: string        // 原始文件名
  sourceType: string          // 文件类型（PDF/DOCX/PPTX 等）
  sourceUrl: string | null    // 来源 URL（网页导入时）
  fileSize: number            // 文件总大小（字节）
  chunkSize: number           // 每片大小（当前默认 1MB）
  totalChunks: number         // 总片数
  uploadedChunks: number      // 已上传片数
  uploadedChunkIndexes?: number[] | null // 已上传分片索引，用于精确断点续传
  status: 'UPLOADING' | 'PROCESSING' | 'SUCCESS' | 'FAILED'
  errorMessage: string | null
  parseProgressPercent?: number | null  // 解析进度百分比
  parseStage?: string | null            // 当前解析阶段
  parseMessage?: string | null          // 解析阶段附加信息
  uploadStatus?: string | null
  textStatus?: string | null
  indexStatus?: string | null
  ocrStatus?: string | null
  processingProgressPercent?: number | null
  processingStage?: string | null
  processingMessage?: string | null
  indexedChunkCount?: number | null
  textPageCount?: number | null
  createdAt: string
  updatedAt: string
}

/** 上传进度回调数据 — 用于显示进度条 */
export interface UploadProgress {
  phase: 'uploading' | 'processing'  // 上传阶段 / 解析阶段
  percent: number                     // 百分比
  uploadedChunks: number
  totalChunks: number
  uploadedChunkIndexes?: number[] | null
  stage?: string | null               // 解析阶段描述
  message?: string | null             // 附加信息
}

export interface UploadProgressItem extends UploadProgress {
  id: string
  fileName: string
  fileSize: number
  status: 'pending' | 'uploading' | 'processing' | 'success' | 'error'
  error?: string | null
}

// ===== 纯 API 函数 =====

/** 获取当前用户的资料列表 */
export async function listMaterials(): Promise<Material[]> {
  const { data } = await api.get('/materials')
  return (data || []).map(normalizeMaterial)
}

/** 获取单个资料详情 */
export async function getMaterial(id: string): Promise<Material> {
  const { data } = await api.get(`/materials/${id}`)
  return normalizeMaterial(data)
}

/** 获取资料的所有文本分块（用于阅读器） */
export async function getMaterialChunks(id: string): Promise<MaterialChunk[]> {
  const { data } = await api.get(`/materials/${id}/chunks`)
  return (data || []).map(normalizeChunk)
}

/** 获取资料的页面列表（PDF/DOCX 预览用） */
export async function getMaterialPages(id: string): Promise<MaterialPage[]> {
  const { data } = await api.get(`/materials/${id}/pages`)
  return data
}

/** 按页获取原文透明文本层，用于阅读器直接在页面上选中划词。 */
export async function getMaterialPageTextLayer(id: string, pageNo: number): Promise<MaterialPageTextBlock[]> {
  const { data } = await api.get(`/materials/${id}/pages/${pageNo}/text-layer`)
  return (data || []).map(normalizePageTextBlock)
}

/** 获取资料原始文件（二进制 Blob，用于 PDF 预览等） */
export async function getMaterialFile(id: string) {
  const response = await api.get(`/materials/${id}/file`, { responseType: 'blob' })
  return {
    blob: response.data as Blob,
    contentType: (response.headers?.['content-type'] || '') as string,
    disposition: (response.headers?.['content-disposition'] || '') as string,
  }
}

/** 创建文件访问临时票据（用于安全打开原文件） */
export async function createMaterialFileTicket(id: string): Promise<{ ticket: string; url: string; expiresAt: number }> {
  const { data } = await api.post(`/materials/${id}/file-ticket`)
  return data
}

/** 普通上传（小文件）— 一次性 POST FormData */
export async function uploadMaterial(params: { file: File; title?: string; sourceType?: string; sourceUrl?: string }): Promise<Material> {
  const formData = new FormData()
  formData.append('file', params.file)
  if (params.title) formData.append('title', params.title)
  if (params.sourceType) formData.append('sourceType', params.sourceType)
  if (params.sourceUrl) formData.append('sourceUrl', params.sourceUrl)
  const { data } = await api.post('/materials', formData)
  return normalizeMaterial(data)
}

/** 临时上传资料：只解析文本供当前智能问答使用，不进入资料管理 */
export async function uploadTemporaryMaterial(
  params: { file: File; title?: string; sourceType?: string },
  onProgress?: (progress: { phase: 'uploading' | 'processing'; percent: number; message?: string }) => void,
): Promise<TemporaryMaterial> {
  if (params.file.size > MAX_TEMPORARY_MATERIAL_BYTES) {
    // 临时问答走同步解析，超大文件会卡住请求；引导用户使用后台解析流程。
    throw new Error('智能问答临时资料最大支持 100MB；大文件请切换到资料问答上传，系统会在后台解析并显示进度。')
  }
  const formData = new FormData()
  formData.append('file', params.file)
  if (params.title) formData.append('title', params.title)
  if (params.sourceType) formData.append('sourceType', params.sourceType)
  const { data } = await api.post('/materials/temporary', formData, {
    timeout: 180000,
    onUploadProgress: (event) => {
      if (!event.total) return
      // 上传阶段最多推进到 60%，剩余进度留给后端解析，避免进度条误报完成。
      const percent = Math.max(1, Math.min(60, Math.round((event.loaded / event.total) * 60)))
      onProgress?.({ phase: 'uploading', percent, message: `正在上传 ${percent}%` })
    },
  })
  onProgress?.({ phase: 'processing', percent: 100, message: '解析完成' })
  const parsed = { ...data, fileSize: params.file.size } as TemporaryMaterial
  // 临时资料必须能提取出可问答正文；否则用户会看到“上传成功”，但下一轮问答实际读不到内容。
  if (!hasTemporaryMaterialText(parsed)) {
    throw new Error('临时资料没有提取到可问答文本，请改用资料管理上传并查看解析状态。')
  }
  return parsed
}

/** 检查临时资料或多文件 parts 中是否存在有效正文。 */
function hasTemporaryMaterialText(material?: TemporaryMaterial | null): boolean {
  if (!material) return false
  const parts = material.parts?.length ? material.parts : [material]
  return parts.some((part) => (part.text || '').trim().length > 0)
}

/** 网页导入 — 输入 URL，后端抓取网页内容并创建资料 */
export async function importWebMaterial(params: { title?: string; sourceUrl: string }): Promise<Material> {
  const { data } = await api.post('/materials/web', params)
  return normalizeMaterial(data)
}

/** 创建分片上传会话 */
export async function createUploadSession(payload: { clientUploadId: string; title: string; originalName: string; fileSize: number; chunkSize: number; sourceType: string; sourceUrl?: string }) {
  const { data } = await api.post('/materials/upload-sessions', payload)
  return normalizeUploadSession(data)
}

/** 上传单个分片 */
export async function uploadChunk(sessionId: string, params: { chunk: Blob; chunkIndex: number; totalChunks: number; checksumSha256?: string }) {
  const formData = new FormData()
  formData.append('chunk', params.chunk)
  formData.append('chunkIndex', String(params.chunkIndex))
  formData.append('totalChunks', String(params.totalChunks))
  if (params.checksumSha256) formData.append('checksumSha256', params.checksumSha256)
  const { data } = await api.post(`/materials/upload-sessions/${sessionId}/chunks`, formData)
  return normalizeUploadSession(data)
}

/** 带重试的分片上传 — 每片最多重试 3 次，间隔递增（600ms×次数） */
async function uploadChunkWithRetry(sessionId: string, params: { chunk: Blob; chunkIndex: number; totalChunks: number; checksumSha256?: string }) {
  let lastError: unknown = null
  for (let attempt = 0; attempt < CHUNK_UPLOAD_RETRY_COUNT; attempt += 1) {
    try {
      return await uploadChunk(sessionId, params)
    } catch (error) {
      lastError = error
      if (attempt < CHUNK_UPLOAD_RETRY_COUNT - 1) {
        // 分片失败通常是瞬时网络抖动，递增等待能降低连续重试压力。
        await sleep(600 * (attempt + 1))  // 等待 600ms、1200ms
      }
    }
  }
  throw lastError instanceof Error ? lastError : new Error('分片上传失败，请检查网络后重试')
}

/** 查询上传会话状态（用于轮询解析进度） */
export async function getUploadSession(sessionId: string) {
  const { data } = await api.get(`/materials/upload-sessions/${sessionId}`)
  return normalizeUploadSession(data)
}

/**
 * 分片上传完整流程 — 这是大文件上传的主函数。
 *
 * 流程：
 * 1. 创建上传会话
 * 2. 将文件按 1MB 切片，并行 3 个分片上传（带重试）
 * 3. 所有片上传完成后，等待后端解析（轮询状态）
 * 4. 返回最终的 UploadSession
 *
 * @param params     文件和元数据
 * @param onProgress 进度回调（用于前端显示进度条）
 */
export async function uploadMaterialInChunks(
  params: { file: File; title?: string; sourceType: string; sourceUrl?: string },
  onProgress?: (progress: UploadProgress) => void,
): Promise<UploadSession> {
  if (params.file.size > MAX_UPLOAD_BYTES) {
    throw new Error('文件超过 2GB，请压缩或拆分后再上传')
  }
  const chunkSize = LARGE_UPLOAD_CHUNK_SIZE
  const totalChunks = Math.max(1, Math.ceil(params.file.size / chunkSize))
  // 创建会话
  const session = await createUploadSession({
    clientUploadId: buildClientUploadId(params.file, chunkSize, params.title),
    title: params.title?.trim() || params.file.name,
    originalName: params.file.name,
    sourceType: params.sourceType,
    sourceUrl: params.sourceUrl,
    fileSize: params.file.size,
    chunkSize,
  })
  const sessionId = session.sessionId
  const uploadedIndexes = uploadedChunkIndexSet(session, totalChunks)
  let completedChunks = session.status === 'SUCCESS' ? totalChunks : uploadedIndexes.size
  const pendingIndexes = Array.from({ length: totalChunks }, (_, index) => index)
    .filter((index) => session.status !== 'SUCCESS' && !uploadedIndexes.has(index))
  onProgress?.({
    phase: session.status === 'SUCCESS' ? 'processing' : 'uploading',
    percent: Math.round((completedChunks / totalChunks) * 100),
    uploadedChunks: completedChunks,
    totalChunks,
    uploadedChunkIndexes: Array.from(uploadedIndexes),
    stage: session.status === 'SUCCESS' ? session.processingStage || session.parseStage || '处理完成' : undefined,
    message: session.status === 'SUCCESS' ? session.processingMessage || session.parseMessage || '资料已经可以使用' : undefined,
  })
  await runWithConcurrency(pendingIndexes, CHUNK_UPLOAD_CONCURRENCY, async (index) => {
    const start = index * chunkSize
    const end = Math.min(params.file.size, start + chunkSize)
    // 只切当前片的 Blob，避免把大文件完整读入内存；并发数固定为 3，避免压垮 nginx 或后端线程。
    await uploadChunkWithRetry(sessionId, {
      chunk: params.file.slice(start, end), chunkIndex: index, totalChunks,
    })
    completedChunks += 1
    uploadedIndexes.add(index)
    onProgress?.({
      phase: 'uploading',
      percent: Math.round((completedChunks / totalChunks) * 100),
      uploadedChunks: completedChunks,
      totalChunks,
      uploadedChunkIndexes: Array.from(uploadedIndexes),
    })
  })
  const completedSession = await getUploadSession(sessionId)
  if (completedSession.status === 'FAILED') throw new Error(completedSession.errorMessage || '上传处理失败')
  // 等待后端解析完成
  return waitForUploadProcessing(sessionId, totalChunks, onProgress)
}

/**
 * 按固定并发执行异步任务。
 *
 * 这里不用 Promise.all 一次性丢出全部分片，避免 2GB 文件产生几千个并发请求。
 */
async function runWithConcurrency<T>(items: T[], concurrency: number, worker: (item: T) => Promise<void>) {
  if (items.length === 0) return
  let cursor = 0
  const workerCount = Math.max(1, Math.min(concurrency, items.length))
  const workers = Array.from({ length: workerCount }, async () => {
    while (cursor < items.length) {
      const item = items[cursor]
      cursor += 1
      await worker(item)
    }
  })
  await Promise.all(workers)
}

/** 轮询等待后端资料达到可用状态 — 每 1.5 秒检查一次，最长等 60 分钟。 */
async function waitForUploadProcessing(sessionId: string, totalChunks: number, onProgress?: (progress: UploadProgress) => void) {
  const startedAt = Date.now()
  while (Date.now() - startedAt < PROCESSING_TIMEOUT_MS) {
    const session = await getUploadSession(sessionId)
    if (session.status === 'FAILED') throw new Error(session.errorMessage || '资料解析失败')
    if (materialProcessingFailed(session)) {
      throw new Error(session.processingMessage || session.parseMessage || '资料解析失败，请查看任务面板或重新解析')
    }
    if (session.status === 'SUCCESS' && materialUsableForReader(session)) {
      onProgress?.({
        phase: 'processing',
        percent: 100,
        uploadedChunks: totalChunks,
        totalChunks,
        stage: session.processingStage || session.parseStage || '资料已可用',
        message: session.processingMessage || session.parseMessage || '资料已可用于阅读和问答，后台增强任务可能仍在继续',
      })
      return session
    }
    // 后端解析进度可能缺失，统一归一化后再通知 UI，避免进度条越界。
    onProgress?.({
      phase: 'processing',
      percent: normalizeProcessingPercent(session.processingProgressPercent ?? session.parseProgressPercent),
      uploadedChunks: totalChunks,
      totalChunks,
      stage: session.processingStage || session.parseStage || '后台处理中',
      message: session.processingMessage || session.parseMessage,
    })
    await sleep(PROCESSING_POLL_INTERVAL_MS)
  }
  throw new Error('资料仍在后台解析，请稍后刷新资料列表查看结果')
}

/** 后端上传会话 SUCCESS 只表示原文件已入库；真正可用要看资料流水线状态。 */
function materialUsableForReader(session: UploadSession) {
  const textStatus = normalizeStatus(session.textStatus)
  const indexStatus = normalizeStatus(session.indexStatus)
  if (!textStatus && !indexStatus) return session.status === 'SUCCESS'
  return ['READY', 'PARTIAL'].includes(textStatus) || ['READY', 'PARTIAL'].includes(indexStatus)
}

/** 文本或索引主链路失败时向用户明确报错；OCR 失败不阻塞页面预览和已有片段使用。 */
function materialProcessingFailed(session: UploadSession) {
  return normalizeStatus(session.textStatus) === 'FAILED' || normalizeStatus(session.indexStatus) === 'FAILED'
}

function normalizeStatus(value?: string | null) {
  return String(value || '').trim().toUpperCase()
}

/** 根据后端返回的真实分片索引恢复续传状态；旧后端未返回索引时退回连续数量。 */
function uploadedChunkIndexSet(session: UploadSession, totalChunks: number) {
  if (session.status === 'SUCCESS') {
    return new Set(Array.from({ length: totalChunks }, (_, index) => index))
  }
  if (Array.isArray(session.uploadedChunkIndexes) && session.uploadedChunkIndexes.length > 0) {
    return new Set(
      session.uploadedChunkIndexes
        .filter((index) => Number.isInteger(index) && index >= 0 && index < totalChunks),
    )
  }
  const uploadedCount = Math.max(0, Math.min(totalChunks, session.uploadedChunks || 0))
  return new Set(Array.from({ length: uploadedCount }, (_, index) => index))
}

/** 生成客户端上传 ID（固定短格式，避免长中文文件名超过后端数据库字段长度） */
function buildClientUploadId(file: File, chunkSize: number, title?: string): string {
  const identity = [file.name, file.size, file.lastModified, chunkSize, title?.trim() || file.name].join('\n')
  return ['web', file.size.toString(36), file.lastModified.toString(36), chunkSize.toString(36), hashUploadIdentity(identity)].join('-')
}

/** 对上传元数据做稳定哈希；同一文件重复上传仍能命中幂等会话，同时不会把长文件名直接写入 ID。 */
function hashUploadIdentity(value: string): string {
  let hash = 0x811c9dc5
  for (let index = 0; index < value.length; index += 1) {
    hash ^= value.charCodeAt(index)
    hash = Math.imul(hash, 0x01000193)
  }
  return (hash >>> 0).toString(16).padStart(8, '0')
}
function sleep(ms: number) { return new Promise((resolve) => window.setTimeout(resolve, ms)) }
// 缺少后端进度时给一个低起点；未成功前最高 99%，避免 UI 提前显示完成。
function normalizeProcessingPercent(percent?: number | null) { if (typeof percent !== 'number' || Number.isNaN(percent)) return 5; return Math.max(0, Math.min(99, Math.round(percent))) }
function normalizeMaterial(material: Material): Material { return { ...material, id: String(material.id), parseProgressPercent: typeof material.parseProgressPercent === 'number' ? material.parseProgressPercent : null } }
function normalizeChunk(chunk: MaterialChunk): MaterialChunk { return { ...chunk, id: String(chunk.id), materialId: String(chunk.materialId) } }
function normalizePageTextBlock(block: MaterialPageTextBlock): MaterialPageTextBlock { return { ...block, id: String(block.id), chunkId: block.chunkId == null ? null : String(block.chunkId) } }
function normalizeUploadSession(session: UploadSession): UploadSession {
  return {
    ...session,
    materialId: session.materialId == null ? null : String(session.materialId),
    uploadedChunkIndexes: Array.isArray(session.uploadedChunkIndexes)
      ? session.uploadedChunkIndexes.filter((index) => Number.isInteger(index))
      : [],
  }
}

/** 更新资料信息（标题、来源 URL） */
export async function updateMaterial(id: string, payload: { title?: string; sourceUrl?: string }): Promise<Material> {
  const { data } = await api.put(`/materials/${id}`, payload)
  return normalizeMaterial(data)
}

/** 重新解析资料 */
export async function reparseMaterial(id: string): Promise<Material> {
  const { data } = await api.post(`/materials/${id}/reparse`)
  return normalizeMaterial(data)
}

/** 删除资料（同时删除分块和文件） */
export async function deleteMaterial(id: string): Promise<void> {
  await api.delete(`/materials/${id}`)
}

// ===== React Hooks =====

/** 资料列表 query — 有资料在解析中时每 1.5 秒自动刷新（显示实时进度） */
export function useMaterials() {
  return useQuery({ queryKey: ['materials'], queryFn: listMaterials,
    enabled: hasStoredSession(),
    // 只有存在解析中资料时才轮询，解析完成后停止刷新，减少无意义请求。
    refetchInterval: (query) => { const data = query.state.data as Material[] | undefined; return data?.some(isMaterialParsing) ? 1500 : false } })
}
function isMaterialParsing(m: Material) {
  const parseStatus = String(m.parseStatus || '').toUpperCase()
  const textStatus = String(m.textStatus || '').toUpperCase()
  const indexStatus = String(m.indexStatus || '').toUpperCase()
  const ocrStatus = String(m.ocrStatus || '').toUpperCase()
  const processingPercent = m.processingProgressPercent ?? m.parseProgressPercent ?? 100
  const parseActive = ['PENDING', 'PARSING', 'PROCESSING'].includes(parseStatus) && processingPercent < 100
  const textActive = ['PENDING', 'RUNNING'].includes(textStatus)
    || (textStatus === 'PARTIAL' && processingPercent < 100)
  // 索引 PARTIAL 表示 BM25/部分向量已可用但后台仍可能继续补齐；进度未满时需要继续轮询，避免卡片长期停在 85%。
  const indexActive = ['PENDING', 'RUNNING'].includes(indexStatus)
    || (indexStatus === 'PARTIAL' && processingPercent < 100)
  const pageBackfillActive = m.textStatus === 'PARTIAL'
    && typeof m.pageCount === 'number'
    && typeof m.textPageCount === 'number'
    && m.textPageCount < m.pageCount
  // 图片型 PDF 会在文本/索引可用后继续跑 OCR；用户侧也必须轮询 OCR 状态，否则列表会停在旧片段数。
  const ocrActive = ['PENDING', 'RUNNING'].includes(ocrStatus)
    || (ocrStatus === 'PARTIAL' && (processingPercent < 100 || pageBackfillActive))
  const processingActive = processingPercent < 100
    && (
      !['FAILED', 'READY'].includes(textStatus)
      || !['FAILED', 'READY'].includes(indexStatus)
      || !['FAILED', 'READY', 'DISABLED'].includes(ocrStatus)
    )
  return parseActive || textActive || indexActive || ocrActive || pageBackfillActive || processingActive
}

export function useMaterial(id: string | null) { return useQuery({ queryKey: ['materials', id], queryFn: () => getMaterial(id!), enabled: !!id && hasStoredSession() }) }
export function useMaterialChunks(id: string | null) { return useQuery({ queryKey: ['materials', id, 'chunks'], queryFn: () => getMaterialChunks(id!), enabled: !!id && hasStoredSession() }) }
export function useMaterialPages(id: string | null) { return useQuery({ queryKey: ['materials', id, 'pages'], queryFn: () => getMaterialPages(id!), enabled: !!id && hasStoredSession() }) }
export function useMaterialPageTextLayer(id: string | null, pageNo: number | null) { return useQuery({ queryKey: ['materials', id, 'pages', pageNo, 'text-layer'], queryFn: () => getMaterialPageTextLayer(id!, pageNo!), enabled: !!id && !!pageNo && hasStoredSession() }) }
export function useUploadMaterial() { return useMutation({ mutationFn: uploadMaterial, onSuccess: () => queryClient.invalidateQueries({ queryKey: ['materials'] }) }) }
export function useImportWebMaterial() { return useMutation({ mutationFn: importWebMaterial, onSuccess: () => queryClient.invalidateQueries({ queryKey: ['materials'] }) }) }
export function useUpdateMaterial() { return useMutation({ mutationFn: ({ id, payload }: { id: string; payload: { title?: string; sourceUrl?: string } }) => updateMaterial(id, payload), onSuccess: () => queryClient.invalidateQueries({ queryKey: ['materials'] }) }) }
export function useReparseMaterial() { return useMutation({ mutationFn: reparseMaterial, onSuccess: (_m, id) => {
  // 重新解析会影响列表状态、详情、分块内容，以及依赖旧分块的历史检索展示。
  queryClient.invalidateQueries({ queryKey: ['materials'] }); queryClient.invalidateQueries({ queryKey: ['materials', id] }); queryClient.invalidateQueries({ queryKey: ['materials', id, 'chunks'] }); queryClient.invalidateQueries({ queryKey: ['history'] })
} }) }
export function useDeleteMaterial() { return useMutation({ mutationFn: deleteMaterial, onSuccess: () => queryClient.invalidateQueries({ queryKey: ['materials'] }) }) }
