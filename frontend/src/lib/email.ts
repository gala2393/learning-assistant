export type EmailDomain = 'qq.com' | '163.com'
export type EmailProvider = 'qq' | 'netease'

/** 域名到后端 provider 标识的映射 */
const DOMAIN_PROVIDER: Record<EmailDomain, EmailProvider> = {
  'qq.com': 'qq',
  '163.com': 'netease',
}

/**
 * 标准化邮箱地址 — 用户可能只输入用户名部分（如 "test"），
 * 这个函数会自动补全域名（如 "test@qq.com"）。
 *
 * @param input         用户输入（可能是 "test" 或 "test@qq.com"）
 * @param selectedDomain 当前选择的邮箱域名
 * @returns 完整的邮箱地址
 */
export function normalizeEmail(input: string, selectedDomain: EmailDomain) {
  const trimmed = input.trim()
  if (!trimmed) return ''
  return trimmed.includes('@') ? trimmed : `${trimmed}@${selectedDomain}`
}

/**
 * 根据邮箱地址识别后端 provider（用于选择对应的 SMTP 通道发送验证码）。
 * 后端支持 QQ 邮箱和 163 邮箱两个通道。
 *
 * @param input         用户输入
 * @param selectedDomain 当前选择的域名
 * @returns 'qq' 或 'netease'
 */
export function providerForEmail(input: string, selectedDomain: EmailDomain): EmailProvider {
  const email = normalizeEmail(input, selectedDomain).toLowerCase()
  if (email.endsWith('@163.com')) return 'netease'  // 163 邮箱走网易通道
  return DOMAIN_PROVIDER[selectedDomain]            // 其他走用户选择的通道
}
