/**
 * constants/index.ts - 全局常量定义
 *
 * 功能说明：
 * - 存储项目中跨组件共享的常量值
 * - 包括：本地存储键名、文件上传限制、导航菜单配置、
 *   默认提示词、来源类型标签、解析状态标签和颜色映射
 */

// ==================== 本地存储相关 ====================

/** 浏览器 localStorage 中存储用户会话信息的键名 */
export const SESSION_KEY = 'learning-assistant.frontend.session'

// ==================== 文件上传限制 ====================

/** 持久资料上传大小上限：2GB（单位：字节） */
export const MATERIAL_UPLOAD_LIMIT_BYTES = 2 * 1024 * 1024 * 1024
/** 上传限制的用户友好显示文本 */
export const MATERIAL_UPLOAD_LIMIT_LABEL = '2GB'
/** 分片上传时每个分片的大小：1MB，降低代理单请求体限制导致的上传失败概率 */
export const MATERIAL_UPLOAD_CHUNK_BYTES = 1 * 1024 * 1024

// ==================== 导航菜单配置 ====================

/**
 * 工作区导航菜单项
 * - path: 路由路径
 * - label: 显示名称
 * - icon: 图标名称（对应 lucide-react 图标）
 * - kicker: 页面副标题/小标签
 */
export const WORKSPACE_SECTIONS = [
  { path: '/workspace/chat', label: '智能问答', icon: 'message-square', kicker: 'RAG' },
  { path: '/workspace/materials', label: '资料管理', icon: 'book-open', kicker: 'Library' },
  { path: '/workspace/reader', label: '边读边问', icon: 'file-text', kicker: 'Reader' },
  { path: '/workspace/history', label: '历史记录', icon: 'clock', kicker: 'Trace' },
  { path: '/workspace/favorites', label: '我的收藏', icon: 'star', kicker: 'Review' },
  { path: '/workspace/summary', label: '知识总结', icon: 'sparkles', kicker: 'Study' },
]

/**
 * 管理员后台导航菜单项
 * 结构同 WORKSPACE_SECTIONS
 */
export const ADMIN_SECTIONS = [
  { path: '/admin/dashboard', label: '管理员总览', icon: 'layout-dashboard', kicker: 'Overview' },
  { path: '/admin/users', label: '用户与角色', icon: 'users', kicker: 'Access' },
  { path: '/admin/materials', label: '资料队列', icon: 'folder', kicker: 'Queue' },
  { path: '/admin/evaluation', label: 'RAG 评估', icon: 'clipboard-check', kicker: '评估' },
  { path: '/admin/usage-records', label: '使用记录', icon: 'activity', kicker: 'Usage' },
  { path: '/admin/logs', label: '系统日志', icon: 'scroll-text', kicker: 'Audit' },
]

// ==================== 预设提示词 ====================

/** 通用模式下的推荐问题（非资料相关） */
export const GENERAL_PROMPTS = [
  '把这个概念用通俗语言解释给本科生听。',
  '帮我比较这个知识点和另一个常见概念的差异。',
  '给我一版适合课堂展示的简明回答。',
]

/** 资料模式下的推荐问题（结合具体资料内容） */
export const MATERIAL_PROMPTS = [
  '请结合当前资料回答，并标出可引用页码。',
  '请总结这一章的核心结论、证据和常见考点。',
]

// ==================== 资料类型标签 ====================

/** 资料来源类型映射：后端枚举值 -> 中文显示名 */
export const SOURCE_TYPE_LABELS: Record<string, string> = {
  PDF: 'PDF',
  DOCX: 'Word',
  WORD: 'Word',
  PPT: 'PPT',
  PPTX: 'PPT',
  XLSX: 'Excel',
  TXT: '文本',
  MD: 'Markdown',
  HTML: '网页',
  WEB: '网页',
}

// ==================== 解析状态相关 ====================

/** 解析状态映射：后端枚举值 -> 中文显示名 */
export const PARSE_STATUS_LABELS: Record<string, string> = {
  SUCCESS: '已解析',
  PARSED: '已解析',
  PROCESSING: '解析中',
  PARSING: '解析中',
  PENDING: '待解析',
  FAILED: '解析失败',
}

/** 解析状态映射：后端枚举值 -> Badge 组件 variant 名称 */
export const PARSE_STATUS_COLORS: Record<string, string> = {
  SUCCESS: 'success',       // 绿色
  PARSED: 'success',
  PROCESSING: 'warning',    // 黄色
  PARSING: 'warning',
  PENDING: 'secondary',     // 灰色
  FAILED: 'destructive',    // 红色
}
