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

export interface EmailLoginPayload {
  email: string
  code: string
}

export interface RegisterPayload {
  email: string
  username: string
  password: string
  confirmPassword: string
  code: string
}

export interface ResetPasswordPayload {
  email: string
  code: string
  newPassword: string
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
  parseProgressPercent?: number | null
  parseStage?: string | null
  parseMessage?: string | null
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
  hierarchyPath?: string | null
  summary?: string | null
  keywords?: string | null
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
  conversationId?: string | number | null
  images?: ChatImagePayload[]
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
  conversationId?: string | number | null
  images?: ChatImagePayload[]
}

export interface ChatImagePayload {
  dataUrl: string
  mediaType: string
}

export interface RagSource {
  materialId: string
  chunkId: string
  materialTitle: string
  pageNo: number
  excerpt: string
  score: number
}

export interface RagUsage {
  dailyLimit: number
  usedToday: number
  remainingToday: number | null
  unlimited: boolean
}

export interface HistoryItem {
  id: string
  conversationId?: string | null
  title?: string | null
  question: string
  answer: string
  createdAt: string
  favoriteId: string | null
  favorite?: boolean
  pinned?: boolean
  messages?: Array<{
    id: string
    role: 'user' | 'assistant'
    text: string
  }>
  sources?: RagSource[]
}

export interface RagEvaluationCasePayload {
  question: string
  materialId?: string | number | null
  expectedAnswerTerms?: string[]
  expectedSourceTerms?: string[]
}

export interface RagEvaluationSuitePayload {
  cases: RagEvaluationCasePayload[]
}

export interface RagEvaluationSuiteSavePayload {
  name: string
  description?: string
  cases: RagEvaluationCasePayload[]
}

export interface RagEvaluationCaseResult {
  caseIndex: number
  questionId: string | number | null
  question: string
  faithfulnessScore: number
  contextRelevanceScore: number
  overallScore: number
  expectedAnswerCoverage: number
  expectedSourceCoverage: number
  verdict: string
  passed: boolean
  missingAnswerTerms: string[]
  missingSourceTerms: string[]
}

export interface RagEvaluationSuiteResult {
  totalCases: number
  passedCases: number
  passRate: number
  averageFaithfulnessScore: number
  averageContextRelevanceScore: number
  averageOverallScore: number
  cases: RagEvaluationCaseResult[]
}

export interface RagEvaluationSuiteSummary {
  id: string
  name: string
  description?: string | null
  caseCount: number
  lastTotalCases?: number | null
  lastPassedCases?: number | null
  lastPassRate?: number | null
  lastAverageOverallScore?: number | null
  lastRunAt?: string | null
  scheduled: boolean
  scheduleIntervalHours: number
  nextRunAt?: string | null
  updatedAt?: string | null
}

export interface RagEvaluationSuiteRun {
  id: string
  suiteId: string
  totalCases: number
  passedCases: number
  passRate: number
  averageFaithfulnessScore: number
  averageContextRelevanceScore: number
  averageOverallScore: number
  result?: RagEvaluationSuiteResult | null
  createdAt: string
}

export interface RagEvaluationSuiteDetail {
  id: string
  name: string
  description?: string | null
  cases: RagEvaluationCasePayload[]
  latestRun?: RagEvaluationSuiteRun | null
  scheduled: boolean
  scheduleIntervalHours: number
  nextRunAt?: string | null
  createdAt?: string | null
  updatedAt?: string | null
}

export interface FavoriteItem {
  id: string
  questionId: string
  conversationId?: string | null
  question: string
  answer: string
  createdAt: string
  messages?: Array<{
    id: string
    role: 'user' | 'assistant'
    text: string
  }>
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

export interface AdminUsageRecord {
  id: string
  userId: string
  username: string
  action: string
  targetType: string
  targetId: string
  modelName?: string | null
  promptTokens?: number | null
  completionTokens?: number | null
  totalTokens?: number | null
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

export interface UserLlmConfig {
  enabled: boolean
  baseUrl: string
  model: string
  hasApiKey: boolean
  activeLabel: string
  activeConfigId: string | number | null
  configs: UserLlmConfigItem[]
}

export interface UserLlmConfigItem {
  id: string | number
  displayName: string
  baseUrl: string
  model: string
  hasApiKey: boolean
  active: boolean
}

export interface UserLlmConfigPayload {
  id?: string | number | null
  enabled: boolean
  displayName?: string
  baseUrl: string
  apiKey?: string
  model: string
}

export interface UserLlmTestResult {
  ok: boolean
  message: string
  model: string
}
