import { Link } from 'react-router-dom'

/**
 * 首页底部信息栏。
 * ICP 备案号和公安备案号都支持环境变量覆盖，默认展示当前线上备案信息。
 */
const ICP_BEIAN_TEXT = import.meta.env.VITE_ICP_BEIAN_TEXT?.trim()
const ICP_BEIAN_URL = import.meta.env.VITE_ICP_BEIAN_URL?.trim() || 'https://beian.miit.gov.cn/'
const PUBLIC_SECURITY_BEIAN_TEXT = import.meta.env.VITE_PUBLIC_SECURITY_BEIAN_TEXT?.trim() || '闽公网安备35052402061802号'
const PUBLIC_SECURITY_BEIAN_URL = import.meta.env.VITE_PUBLIC_SECURITY_BEIAN_URL?.trim() || 'https://beian.mps.gov.cn/#/query/webSearch?code=35052402061802'
const PUBLIC_SECURITY_BEIAN_ICON = import.meta.env.VITE_PUBLIC_SECURITY_BEIAN_ICON?.trim() || '/beian-icon.png'

export function SiteBeianFooter() {
  return (
    <footer className="relative z-10 border-t border-white/80 bg-[#f7f9fb] px-5 py-6 text-center text-xs text-slate-500 sm:px-8 lg:px-10" aria-label="网站底部信息">
      <div className="flex flex-wrap items-center justify-center gap-x-4 gap-y-2">
        <Link className="transition hover:text-slate-800 hover:underline" to="/terms">
          使用协议
        </Link>
        <Link className="transition hover:text-slate-800 hover:underline" to="/privacy">
          隐私政策
        </Link>
        <Link className="transition hover:text-slate-800 hover:underline" to="/about">
          关于我们
        </Link>
        {ICP_BEIAN_TEXT ? (
          <a className="transition hover:text-slate-800 hover:underline" href={ICP_BEIAN_URL} target="_blank" rel="noreferrer">
            {ICP_BEIAN_TEXT}
          </a>
        ) : null}
        {PUBLIC_SECURITY_BEIAN_TEXT && PUBLIC_SECURITY_BEIAN_URL ? (
          <a className="inline-flex items-center gap-1 transition hover:text-slate-800 hover:underline" href={PUBLIC_SECURITY_BEIAN_URL} target="_blank" rel="noreferrer">
            <img className="h-4 w-4 shrink-0" src={PUBLIC_SECURITY_BEIAN_ICON} alt="" aria-hidden="true" />
            <span>{PUBLIC_SECURITY_BEIAN_TEXT}</span>
          </a>
        ) : null}
      </div>
    </footer>
  )
}
