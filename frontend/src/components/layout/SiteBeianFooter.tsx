/**
 * SiteBeianFooter 组件 —— 全站备案号展示栏。
 *
 * 备案号必须使用真实已通过的 ICP 备案号，通过 Vite 环境变量配置。
 * 公安备案号和 ICP 备案号不是同一个编号，公安备案未通过前不要展示公网安备号。
 */
const ICP_BEIAN_TEXT = import.meta.env.VITE_ICP_BEIAN_TEXT?.trim()
const ICP_BEIAN_URL = import.meta.env.VITE_ICP_BEIAN_URL?.trim() || 'https://beian.miit.gov.cn/'

export function SiteBeianFooter() {
  if (!ICP_BEIAN_TEXT) {
    return null
  }

  return (
    <footer className="relative z-10 border-t border-white/80 bg-[#f7f9fb] px-5 py-6 text-center text-xs text-slate-500 sm:px-8 lg:px-10" aria-label="网站备案信息">
      <a className="transition hover:text-slate-800 hover:underline" href={ICP_BEIAN_URL} target="_blank" rel="noreferrer">
        {ICP_BEIAN_TEXT}
      </a>
    </footer>
  )
}
