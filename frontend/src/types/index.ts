/**
 * types/index.ts - 全局 TypeScript 类型定义
 *
 * 功能说明：
 * - 定义整个前端项目中共享的接口和类型
 * - 包括：API 响应、用户认证、资料管理、聊天问答、
 *   RAG 检索、历史记录、收藏、总结、管理员后台、LLM 配置等
 *
 * 类型组织顺序：
 * 1. 通用 API 响应
 * 2. 用户认证相关（Session、登录、注册、密码）
 * 3. 资料管理相关（Material、MaterialChunk、MaterialPage）
 * 4. 聊天与 RAG 相关（ChatPayload、RagSource、RagUsage）
 * 5. 历史记录与收藏（HistoryItem、FavoriteItem）
 * 6. RAG 评估（EvaluationCase/Suite）
 * 7. 总结（SummaryResult）
 * 8. 管理员后台（AdminStats、AdminUser、AdminLog、AdminUsageRecord）
 * 9. LLM 配置（LlmStatus、UserLlmConfig）
 */

// ==================== 通用 API 响应 ====================

/** 后端统一 API 响应格式 */
export interface ApiResponse<T> {
  code: number          // 业务状态码（0 表示成功）
  message: string       // 提示信息
  data: T | null        // 响应数据，失败时可能为 null
}

// ==================== 用户认证相关 ====================

/** 用户会话信息（登录成功后存储在前端） */
export interface Session {
  id: string | number | null   // 用户 ID
  username: string              // 用户名
  nickname: string              // 昵称
  avatar?: string               // 头像（Base64 或预设 ID）
  role: 'ADMIN' | 'USER'       // 角色
  token: string                 // JWT 认证令牌
}

/** 修改个人资料的请求体 */
export interface ProfilePayload {
  nickname: string              // 新昵称
  avatar: string                // 新头像值
}

/** 修改密码的请求体 */
export interface PasswordPayload {
  currentPassword: string       // 当前密码
  newPassword: string           // 新密码
  confirmPassword: string       // 确认新密码
}

/** 用户名密码登录的请求体 */
export interface LoginPayload {
  username: string
  password: string
  captchaChallengeId?: string
  captchaCode?: string
}

export interface LoginCaptchaResult {
  challengeId: string
  imageDataUrl: string
  expiresInSeconds: number
}

/** 邮箱验证码登录的请求体 */
export interface EmailLoginPayload {
  email: string                 // 邮箱地址
  code: string                  // 6 位验证码
}

/** 注册的请求体 */
export interface RegisterPayload {
  email: string
  username: string
  password: string
  confirmPassword: string
  code: string                  // 邮箱验证码
}

/** 重置密码的请求体 */
export interface ResetPasswordPayload {
  email: string
  code: string
  newPassword: string
  confirmPassword: string
}

/** 用户名可用性检查结果 */
export interface UsernameCheckResult {
  username: string              // 被检查的用户名
  available: boolean            // 是否可用
  message: string               // 提示信息
}

// ==================== 资料管理相关 ====================

/** 资料来源类型枚举 */
export type SourceType = 'PDF' | 'DOCX' | 'WORD' | 'PPT' | 'PPTX' | 'XLSX' | 'TXT' | 'MD' | 'HTML' | 'WEB'

/** 资料解析状态枚举 */
export type ParseStatus = 'SUCCESS' | 'PARSED' | 'PROCESSING' | 'PARSING' | 'PENDING' | 'FAILED'

/** 原文件上传状态 */
export type UploadStatus = 'UPLOADING' | 'UPLOADED' | 'FAILED'

/** 文本抽取状态 */
export type TextStatus = 'PENDING' | 'RUNNING' | 'PARTIAL' | 'READY' | 'FAILED'

/** 检索索引状态 */
export type IndexStatus = 'PENDING' | 'RUNNING' | 'PARTIAL' | 'READY' | 'FAILED'

/** OCR 处理状态 */
export type OcrStatus = 'DISABLED' | 'PENDING' | 'RUNNING' | 'PARTIAL' | 'READY' | 'FAILED'

/** 总结生成状态枚举 */
export type SummaryStatus = 'SUCCESS' | 'PENDING'

/** 页面预览状态枚举 */
export type PreviewStatus = 'NONE' | 'READY' | 'DEGRADED' | 'FAILED'

/** 学习资料完整信息 */
export interface Material {
  id: string                              // 资料 ID
  title: string                           // 资料标题
  sourceType: SourceType | string         // 来源类型（PDF/DOCX 等）
  originalName: string                    // 原始文件名
  sourceUrl: string                       // 来源 URL（网页资料使用）
  fileSize: number                        // 文件大小（字节）
  parseStatus: ParseStatus                // 解析状态
  parseProgressPercent?: number | null    // 解析进度百分比
  parseStage?: string | null              // 解析阶段名称
  parseMessage?: string | null            // 解析阶段说明消息
  uploadStatus?: UploadStatus | string | null   // 原文件上传状态
  textStatus?: TextStatus | string | null        // 文本抽取状态
  indexStatus?: IndexStatus | string | null      // 检索索引状态
  ocrStatus?: OcrStatus | string | null          // OCR 状态
  processingProgressPercent?: number | null      // 后台处理综合进度
  processingStage?: string | null                // 后台处理阶段
  processingMessage?: string | null              // 后台处理说明
  indexedChunkCount?: number | null              // 已进入索引的片段数
  textPageCount?: number | null                  // 已抽取文本的页数
  summaryStatus: SummaryStatus            // 总结生成状态
  previewStatus?: PreviewStatus           // 页面预览状态
  previewError?: string | null            // 预览失败的错误信息
  pageCount?: number | null               // 总页数
  chunkCount: number                      // 片段数量
  createdAt: string                       // 创建时间
  updatedAt?: string                      // 更新时间
}

/** 资料片段（解析后的文本块） */
export interface MaterialChunk {
  id: string                      // 片段 ID
  materialId: string              // 所属资料 ID
  chunkIndex: number              // 片段序号（从 0 开始）
  chunkText: string               // 片段文本内容
  pageNo: number | null           // 所在页码
  sectionTitle: string            // 章节标题
  hierarchyPath?: string | null   // 层级路径（如 "第一章 > 第二节"）
  summary?: string | null         // 片段摘要
  keywords?: string | null        // 关键词
  excerpt: string                 // 摘录（用于检索结果展示）
  createdAt?: string              // 创建时间
}

/** 资料页面信息（用于页面预览模式） */
export interface MaterialPage {
  pageNo: number                              // 页码
  width: number | null                        // 页面宽度（像素）
  height: number | null                       // 页面高度（像素）
  imageName: string                           // 预览图片文件名
  chunkIds: Array<string | number>            // 包含的片段 ID 列表
  renderStatus: 'READY' | 'PENDING' | 'FAILED' | string  // 渲染状态
}

// ==================== 聊天与 RAG 相关 ====================

/** 普通聊天请求体 */
/** 资料页面透明文本层块，用于在原文页面上直接选中划词。 */
export interface MaterialPageTextBlock {
  id: string
  pageNo: number
  blockIndex: number
  text: string
  blockType?: string | null
  source?: string | null
  chunkId?: string | number | null
  pageWidth?: number | null
  pageHeight?: number | null
  bboxX?: number | null
  bboxY?: number | null
  bboxWidth?: number | null
  bboxHeight?: number | null
  confidence?: number | null
}

export interface ChatPayload {
  question: string                            // 用户问题
  mode: 'GENERAL' | 'MATERIAL'               // 模式：通用/资料
  materialId?: string                         // 资料模式下的资料 ID
  answerStyle?: 'STUDY' | 'HOMEWORK'         // 回答风格：学习/作业
  conversationId?: string | number | null     // 会话 ID
  images?: ChatImagePayload[]                 // 附带的图片
  temporaryMaterial?: TemporaryMaterial | null // 附带的临时资料
}

/** 流式聊天请求体（SSE） */
export interface StreamChatPayload {
  question: string                            // 用户问题
  mode: 'GENERAL' | 'MATERIAL'               // 模式
  materialId?: string                         // 资料 ID
  chunkId?: string                            // 当前片段 ID（边读边问时使用）
  currentPageNo?: number                      // 当前页码
  currentPageChunkIds?: Array<string | number>  // 当前页的所有片段 ID
  selectedText?: string                       // 用户选中的文本
  answerStyle?: 'STUDY' | 'HOMEWORK'         // 回答风格
  history?: { role: string; content: string }[]  // 对话历史（最近几轮）
  conversationId?: string | number | null     // 会话 ID
  images?: ChatImagePayload[]                 // 附带的图片
  temporaryMaterial?: TemporaryMaterial | null // 附带的临时资料
}

/** 聊天附带的图片数据 */
export interface ChatImagePayload {
  dataUrl: string       // Base64 编码的图片数据
  mediaType: string     // MIME 类型（如 image/png）
}

/** 临时资料，只用于当前智能问答会话，不进入资料管理 */
export interface TemporaryMaterial {
  id: string
  title: string
  originalName: string
  sourceType: string
  text: string
  excerpt: string
  fileSize?: number
  contextStored?: boolean
  files?: Array<{ name: string; size?: number | null; type?: string | null }>
  parts?: TemporaryMaterial[]
}

/** RAG 检索来源信息 */
export interface RagSource {
  materialId: string      // 来源资料 ID
  chunkId: string         // 来源片段 ID
  materialTitle: string   // 资料标题
  pageNo: number          // 来源页码
  excerpt: string         // 摘录文本
  score: number           // 相关度分数（0~1）
}

/** RAG 问答使用额度 */
export interface RagUsage {
  dailyLimit: number          // 每日限额
  usedToday: number           // 今日已用
  remainingToday: number | null  // 今日剩余（null 表示无限）
  unlimited: boolean          // 是否不限额
}

// ==================== 历史记录与收藏 ====================

/** 历史问答记录 */
export interface HistoryItem {
  id: string                                // 记录 ID
  conversationId?: string | null            // 会话 ID
  title?: string | null                     // 自定义标题
  question: string                          // 问题
    answer: string                            // 回答
    continuable?: boolean                     // 回答是否可以继续生成
    continuationHint?: string | null          // 继续生成提示
  createdAt: string                         // 创建时间
  favoriteId: string | null                 // 收藏记录 ID（null 表示未收藏）
  favorite?: boolean                        // 是否已收藏
  pinned?: boolean                          // 是否置顶
  messages?: Array<{                        // 完整对话消息列表
    id: string
    role: 'user' | 'assistant'
    text: string
    images?: ChatImagePayload[]
      temporaryMaterial?: TemporaryMaterial | null
      continuable?: boolean
      continuationHint?: string | null
    }>
  sources?: RagSource[]                     // 检索来源列表
}

/** 收藏项 */
export interface FavoriteItem {
  id: string                                // 收藏记录 ID
  questionId: string                        // 对应的历史记录 ID
  conversationId?: string | null            // 会话 ID
  question: string                          // 问题
  answer: string                            // 回答
  createdAt: string                         // 收藏时间
  messages?: Array<{                        // 关联的对话消息
    id: string
    role: 'user' | 'assistant'
    text: string
    temporaryMaterial?: TemporaryMaterial | null
  }>
}

// ==================== RAG 评估相关 ====================

/** RAG 评估用例请求体 */
export interface RagEvaluationCasePayload {
  question: string                              // 测试问题
  materialId?: string | number | null           // 关联资料 ID
  expectedAnswerTerms?: string[]                // 期望回答中包含的关键词
  expectedSourceTerms?: string[]                // 期望来源中包含的关键词
}

/** RAG 评估套件请求体（批量用例） */
export interface RagEvaluationSuitePayload {
  cases: RagEvaluationCasePayload[]             // 用例列表
}

/** RAG 评估套件保存请求体 */
export interface RagEvaluationSuiteSavePayload {
  name: string                    // 套件名称
  description?: string            // 套件描述
  cases: RagEvaluationCasePayload[]  // 用例列表
}

/** 单个评估用例的运行结果 */
export interface RagEvaluationCaseResult {
  caseIndex: number               // 用例序号
  questionId: string | number | null  // 生成的问答 ID
  question: string                // 测试问题
  faithfulnessScore: number       // 忠实度得分
  contextRelevanceScore: number   // 上下文相关性得分
  overallScore: number            // 综合得分
  expectedAnswerCoverage: number  // 期望回答关键词覆盖率
  expectedSourceCoverage: number  // 期望来源关键词覆盖率
  verdict: string                 // 评估结论
  passed: boolean                 // 是否通过
  missingAnswerTerms: string[]    // 回答中缺失的关键词
  missingSourceTerms: string[]    // 来源中缺失的关键词
}

/** 评估套件的整体结果 */
export interface RagEvaluationSuiteResult {
  totalCases: number                        // 总用例数
  passedCases: number                       // 通过用例数
  passRate: number                          // 通过率
  averageFaithfulnessScore: number          // 平均忠实度
  averageContextRelevanceScore: number      // 平均上下文相关性
  averageOverallScore: number               // 平均综合分
  cases: RagEvaluationCaseResult[]          // 各用例结果
}

/** 评估套件摘要（列表页使用） */
export interface RagEvaluationSuiteSummary {
  id: string                              // 套件 ID
  name: string                            // 套件名称
  description?: string | null             // 描述
  caseCount: number                       // 用例数
  lastTotalCases?: number | null          // 最近运行的总用例数
  lastPassedCases?: number | null         // 最近运行的通过数
  lastPassRate?: number | null            // 最近运行的通过率
  lastAverageOverallScore?: number | null // 最近运行的综合分
  lastRunAt?: string | null               // 最近运行时间
  scheduled: boolean                      // 是否定时运行
  scheduleIntervalHours: number           // 定时间隔（小时）
  nextRunAt?: string | null               // 下次运行时间
  updatedAt?: string | null               // 更新时间
}

/** 评估套件运行记录 */
export interface RagEvaluationSuiteRun {
  id: string                              // 运行记录 ID
  suiteId: string                         // 所属套件 ID
  totalCases: number
  passedCases: number
  passRate: number
  averageFaithfulnessScore: number
  averageContextRelevanceScore: number
  averageOverallScore: number
  result?: RagEvaluationSuiteResult | null  // 完整结果
  createdAt: string                       // 运行时间
}

/** 评估套件详情（含用例配置和最新运行结果） */
export interface RagEvaluationSuiteDetail {
  id: string
  name: string
  description?: string | null
  cases: RagEvaluationCasePayload[]         // 配置的用例列表
  latestRun?: RagEvaluationSuiteRun | null  // 最新运行记录
  scheduled: boolean
  scheduleIntervalHours: number
  nextRunAt?: string | null
  createdAt?: string | null
  updatedAt?: string | null
}

// ==================== 知识总结 ====================

export type SummaryType = 'GENERAL' | 'BRIEF' | 'DETAILED' | 'OUTLINE' | 'REVIEW' | 'ACTION'

export interface SummarySource {
  materialId: string
  chunkId: string
  title: string
  pageNo: number | null
  chunkIndex: number | null
  excerpt: string
}

export interface SummarySection {
  title: string
  items: string[]
  sources?: SummarySource[]
}

/** AI 知识总结结果 */
export interface SummaryResult {
  summaryId: string           // 总结 ID
  materialId: string          // 资料 ID
  materialTitle: string       // 资料标题
  summary: string             // 总结内容
  summaryType?: SummaryType | string // 总结类型
  modelName: string           // 使用的模型名称
  sourceCount: number         // 引用来源数
  createdAt: string           // 生成时间
  sections?: SummarySection[] // 结构化摘要区块
  sources?: SummarySource[]   // 可跳转来源
  userNote?: string | null    // 用户整理版
}

// ==================== 管理员后台相关 ====================

/** 管理后台统计数据 */
export interface AdminStats {
  userCount: number           // 用户总数
  materialCount: number       // 资料总数
  questionCount: number       // 问答总数
  favoriteCount: number       // 收藏总数
  logCount: number            // 日志总数
}

/** 管理员视角的用户信息 */
export interface AdminUser {
  id: string
  username: string            // 用户名
  nickname: string            // 昵称
  role: 'ADMIN' | 'USER'     // 角色
  status: 'ACTIVE' | 'DISABLED'  // 状态（正常/禁用）
  createdAt: string           // 注册时间
  updatedAt: string           // 最后更新时间
}

/** 管理员视角的资料信息（继承 Material，增加所有者信息） */
export interface AdminMaterial extends Material {
  ownerId: string             // 所有者用户 ID
  ownerUsername: string       // 所有者用户名
}

/** 管理后台系统依赖自检结果 */
export interface SystemDependency {
  name: string                // 依赖名称，例如 pdfinfo、LibreOffice、Tesseract OCR
  enabled: boolean            // 配置层面是否启用
  healthy: boolean            // 当前运行环境是否可用
  message: string             // 检查结果说明
}

/** 管理员提交向量索引重建后的结果 */
export interface AdminVectorIndexRebuildResponse {
  submitted: number           // 已提交后台任务数量
  materialId?: string | number | null // 指定资料 ID；为空表示批量重建
  message: string             // 后端返回的处理说明
}

/** 管理员审计日志 */
export interface AdminLog {
  id: string
  actorUserId: string         // 操作者用户 ID
  actorUsername: string       // 操作者用户名
  action: string              // 操作类型
  targetType: string          // 操作对象类型
  targetId: string            // 操作对象 ID
  detail: string              // 操作详情
  createdAt: string           // 操作时间
}

/** 用户使用记录（含 Token 消耗统计） */
export interface AdminUsageRecord {
  id: string
  userId: string                // 用户 ID
  username: string              // 用户名
  action: string                // 操作类型（RAG_CHAT/UPLOAD_MATERIAL 等）
  targetType: string            // 对象类型
  targetId: string              // 对象 ID
  modelName?: string | null     // 使用的模型名
  promptTokens?: number | null  // 输入 Token 数
  completionTokens?: number | null  // 输出 Token 数
  totalTokens?: number | null   // 总 Token 数
  detail: string                // 详情（key=value 格式）
  createdAt: string             // 记录时间
}

/** 分页查询结果 */
export interface PageResult<T> {
  items: T[]                    // 当前页数据列表
  page: number                  // 当前页码（从 0 开始）
  size: number                  // 每页条数
  total: number                 // 总记录数
}

// ==================== LLM 配置相关 ====================

/** LLM 服务状态 */
export interface LlmStatus {
  enabled: boolean              // 是否启用
  configured: boolean           // 是否已配置
  message: string               // 状态描述信息
}

/** 用户自定义 LLM 配置 */
export interface UserLlmConfig {
  enabled: boolean                    // 是否启用自定义配置
  baseUrl: string                     // API 基础地址
  model: string                       // 模型名称
  hasApiKey: boolean                  // 是否设置了 API Key
  activeLabel: string                 // 当前活跃配置的显示名称
  activeConfigId: string | number | null  // 当前活跃配置 ID
  configs: UserLlmConfigItem[]        // 所有配置列表
}

/** 单条 LLM 配置项 */
export interface UserLlmConfigItem {
  id: string | number           // 配置 ID
  displayName: string           // 显示名称
  baseUrl: string               // API 基础地址
  model: string                 // 模型名称
  hasApiKey: boolean            // 是否设置了 API Key
  active: boolean               // 是否为当前活跃配置
}

/** 保存 LLM 配置的请求体 */
export interface UserLlmConfigPayload {
  id?: string | number | null   // 配置 ID（新增时不传）
  enabled: boolean              // 是否启用
  displayName?: string          // 显示名称
  baseUrl: string               // API 基础地址
  apiKey?: string               // API Key（不修改时不传）
  model: string                 // 模型名称
}

/** LLM 连接测试结果 */
export interface UserLlmTestResult {
  ok: boolean                   // 是否成功
  message: string               // 测试结果消息
  model: string                 // 实际连接的模型名
}
