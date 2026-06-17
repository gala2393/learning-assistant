import { Link } from 'react-router-dom'

/**
 * 首页底部信息栏。
 * ICP 备案号通过环境变量配置；法律页面入口始终展示，方便本地和线上访问。
 */
const ICP_BEIAN_TEXT = import.meta.env.VITE_ICP_BEIAN_TEXT?.trim()
const ICP_BEIAN_URL = import.meta.env.VITE_ICP_BEIAN_URL?.trim() || 'https://beian.miit.gov.cn/'

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
      </div>
    </footer>
  )
}
