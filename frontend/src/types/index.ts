export interface ApiResponse<T> {
  code: number
  message: string
  data: T | null
}

export interface Session {
  id: string | number | null
  username: string
  nickname: string
  avatar?: string
  role: 'ADMIN' | 'USER'
  token: string
}

export interface ProfilePayload {
  nickname: string
  avatar: string
}

export interface PasswordPayload {
  currentPassword: string
  newPassword: string
  confirmPassword: string
}

export interface LoginPayload {
  username: string
  password: string
}

export interface RegisterPayload {
  username: string
  nickname: string
  password: string
  confirmPassword: string
}

export interface UsernameCheckResult {
  username: string
  available: boolean
  message: string
}

export type SourceType = 'PDF' | 'DOCX' | 'WORD' | 'PPT' | 'TXT' | 'MD' | 'HTML' | 'WEB'
export type ParseStatus = 'SUCCESS' | 'PARSED' | 'PROCESSING' | 'PARSING' | 'PENDING' | 'FAILED'
export type SummaryStatus = 'SUCCESS' | 'PENDING'
export type PreviewStatus = 'NONE' | 'READY' | 'DEGRADED' | 'FAILED'

export interface Material {
  id: string
  title: string
  sourceType: SourceType | string
  originalName: string
  sourceUrl: string
  fileSize: number
  parseStatus: ParseStatus
  summaryStatus: SummaryStatus
  previewStatus?: PreviewStatus
  previewError?: string | null
  pageCount?: number | null
  chunkCount: number
  createdAt: string
  updatedAt?: string
}

export interface MaterialChunk {
  id: string
  materialId: string
  chunkIndex: number
  chunkText: string
  pageNo: number | null
  sectionTitle: string
  excerpt: string
  createdAt?: string
}

export interface MaterialPage {
  pageNo: number
  width: number | null
  height: number | null
  imageName: string
  chunkIds: Array<string | number>
  renderStatus: 'READY' | 'PENDING' | 'FAILED' | string
}

export interface ChatPayload {
  question: string
  mode: 'GENERAL' | 'MATERIAL'
  materialId?: string
  answerStyle?: 'STUDY' | 'HOMEWORK'
}

export interface StreamChatPayload {
  question: string
  mode: 'GENERAL' | 'MATERIAL'
  materialId?: string
  chunkId?: string
  currentPageNo?: number
  currentPageChunkIds?: Array<string | number>
  selectedText?: string
  answerStyle?: 'STUDY' | 'HOMEWORK'
  history?: { role: string; content: string }[]
}

export interface RagSource {
  materialId: string
  chunkId: string
  materialTitle: string
  pageNo: number
  excerpt: string
  score: number
}

export interface HistoryItem {
  id: string
  title?: string | null
  question: string
  answer: string
  createdAt: string
  favoriteId: string | null
  favorite?: boolean
  pinned?: boolean
  sources?: RagSource[]
}

export interface FavoriteItem {
  id: string
  questionId: string
  question: string
  answer: string
  createdAt: string
}

export interface SummaryResult {
  summaryId: string
  materialId: string
  materialTitle: string
  summary: string
  modelName: string
  sourceCount: number
  createdAt: string
}

export interface AdminStats {
  userCount: number
  materialCount: number
  questionCount: number
  favoriteCount: number
  logCount: number
}

export interface AdminUser {
  id: string
  username: string
  nickname: string
  role: 'ADMIN' | 'USER'
  status: 'ACTIVE' | 'DISABLED'
  createdAt: string
  updatedAt: string
}

export interface AdminMaterial extends Material {
  ownerId: string
  ownerUsername: string
}

export interface AdminLog {
  id: string
  actorUserId: string
  actorUsername: string
  action: string
  targetType: string
  targetId: string
  detail: string
  createdAt: string
}

export interface PageResult<T> {
  items: T[]
  page: number
  size: number
  total: number
}

export interface LlmStatus {
  enabled: boolean
  configured: boolean
  message: string
}
