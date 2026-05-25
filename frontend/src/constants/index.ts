export const SESSION_KEY = 'learning-assistant.frontend.session'

export const MATERIAL_UPLOAD_LIMIT_BYTES = 500 * 1024 * 1024
export const MATERIAL_UPLOAD_LIMIT_LABEL = '500MB'
export const MATERIAL_UPLOAD_CHUNK_BYTES = 5 * 1024 * 1024

export const WORKSPACE_SECTIONS = [
  { path: '/workspace/chat', label: '智能问答', icon: 'message-square', kicker: 'RAG' },
  { path: '/workspace/materials', label: '资料管理', icon: 'book-open', kicker: 'Library' },
  { path: '/workspace/reader', label: '边读边问', icon: 'file-text', kicker: 'Reader' },
  { path: '/workspace/history', label: '历史记录', icon: 'clock', kicker: 'Trace' },
  { path: '/workspace/favorites', label: '我的收藏', icon: 'star', kicker: 'Review' },
  { path: '/workspace/summary', label: '知识总结', icon: 'sparkles', kicker: 'Study' },
]

export const ADMIN_SECTIONS = [
  { path: '/admin/dashboard', label: '管理员总览', icon: 'layout-dashboard', kicker: 'Overview' },
  { path: '/admin/users', label: '用户与角色', icon: 'users', kicker: 'Access' },
  { path: '/admin/materials', label: '资料队列', icon: 'folder', kicker: 'Queue' },
  { path: '/admin/logs', label: '系统日志', icon: 'scroll-text', kicker: 'Audit' },
]

export const GENERAL_PROMPTS = [
  '把这个概念用通俗语言解释给本科生听。',
  '帮我比较这个知识点和另一个常见概念的差异。',
  '给我一版适合课堂展示的简明回答。',
  '把这个问题拆成 3 个可以追问的子问题。',
]

export const MATERIAL_PROMPTS = [
  '请结合当前资料回答，并标出可引用页码。',
  '请总结这一章的核心结论、证据和常见考点。',
  '把当前片段整理成答辩可直接使用的要点。',
  '请解释这段内容在课程中的实际应用。',
]

export const SOURCE_TYPE_LABELS: Record<string, string> = {
  PDF: 'PDF',
  DOCX: 'Word',
  WORD: 'Word',
  PPT: 'PPT',
  TXT: '文本',
  MD: 'Markdown',
  HTML: '网页',
  WEB: '网页',
}

export const PARSE_STATUS_LABELS: Record<string, string> = {
  SUCCESS: '已解析',
  PARSED: '已解析',
  PROCESSING: '解析中',
  PARSING: '解析中',
  PENDING: '待解析',
  FAILED: '解析失败',
}

export const PARSE_STATUS_COLORS: Record<string, string> = {
  SUCCESS: 'success',
  PARSED: 'success',
  PROCESSING: 'warning',
  PARSING: 'warning',
  PENDING: 'secondary',
  FAILED: 'destructive',
}
