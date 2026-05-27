export type EmailDomain = 'qq.com' | '163.com'
export type EmailProvider = 'qq' | 'netease'

const DOMAIN_PROVIDER: Record<EmailDomain, EmailProvider> = {
  'qq.com': 'qq',
  '163.com': 'netease',
}

export function normalizeEmail(input: string, selectedDomain: EmailDomain) {
  const trimmed = input.trim()
  if (!trimmed) {
    return ''
  }

  return trimmed.includes('@') ? trimmed : `${trimmed}@${selectedDomain}`
}

export function providerForEmail(input: string, selectedDomain: EmailDomain): EmailProvider {
  const email = normalizeEmail(input, selectedDomain).toLowerCase()
  if (email.endsWith('@163.com')) {
    return 'netease'
  }
  return DOMAIN_PROVIDER[selectedDomain]
}
