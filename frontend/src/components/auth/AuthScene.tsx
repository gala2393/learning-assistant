import { Link } from 'react-router-dom'
import { motion } from 'framer-motion'
import { ArrowUpRight } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import type { CSSProperties, ReactNode } from 'react'

/**
 * 认证页统一使用的衬线标题字体栈。
 * 这里沿用首页的“编辑感”方向，但仍然只依赖系统可用字体，避免额外引入网络字体。
 */
export const authEditorialSerifStyle: CSSProperties = {
  fontFamily: "'Iowan Old Style', 'Palatino Linotype', 'Book Antiqua', 'Cormorant Garamond', serif",
}

/**
 * 认证表单统一输入框样式。
 * 使用下划线式输入，而不是传统卡片输入框，使视觉更接近首页的克制排版。
 */
export const authInputClassName =
  'h-12 rounded-2xl border border-[#e1e7f0] bg-white/72 px-4 text-[#111111] shadow-[inset_0_1px_0_rgba(255,255,255,0.82)] placeholder:text-slate-400 focus-visible:border-[#1a2a3a] focus-visible:ring-2 focus-visible:ring-[#dbe6f3] focus-visible:ring-offset-0'

/**
 * 认证页统一下拉框样式。
 */
export const authSelectClassName =
  'h-12 rounded-2xl border border-[#e1e7f0] bg-white/72 px-3 text-sm font-medium text-slate-600 outline-none transition hover:border-[#cbd8e8] focus:border-[#1a2a3a] focus:ring-2 focus:ring-[#dbe6f3]'

/**
 * 认证页主按钮样式。
 */
export const authPrimaryButtonClassName =
  'h-12 rounded-full bg-[#111318] px-6 text-sm font-semibold text-white shadow-[0_18px_44px_rgba(15,23,42,0.16)] transition hover:-translate-y-0.5 hover:bg-white hover:text-[#111318] hover:shadow-[0_20px_54px_rgba(15,23,42,0.14),inset_0_0_0_1px_rgba(15,23,42,0.14)] disabled:bg-[#d9dee7] disabled:text-white disabled:shadow-none disabled:hover:translate-y-0'

/**
 * 认证页副按钮样式。
 */
export const authSecondaryButtonClassName =
  'h-11 rounded-full border border-[#d6deea] bg-white/76 px-6 text-sm font-semibold text-[#111111] shadow-[0_12px_32px_rgba(15,23,42,0.06)] backdrop-blur-xl hover:border-[#1a2a3a] hover:bg-white'

/**
 * 认证场景壳层。
 * 左侧承载品牌叙事和轻量产品预览，右侧承载具体表单。
 */
export function AuthScene({
  eyebrow,
  title,
  description,
  sideTitle,
  sideDescription,
  sideNotes,
  sideActionLabel,
  sideActionTo,
  children,
}: {
  eyebrow: string
  title: string
  description: string
  sideTitle: string
  sideDescription: string
  sideNotes: string[]
  sideActionLabel: string
  sideActionTo: string
  children: ReactNode
}) {
  return (
    <motion.section
      className="mx-auto grid min-h-[720px] w-full max-w-[1200px] overflow-hidden rounded-[38px] border border-[#dce4ee] bg-white/70 shadow-[0_34px_110px_rgba(15,23,42,0.12),inset_0_1px_0_rgba(255,255,255,0.9)] backdrop-blur-2xl lg:grid-cols-[0.92fr_1.08fr]"
      initial={{ opacity: 0, y: 34, scale: 0.985, filter: 'blur(14px)' }}
      animate={{ opacity: 1, y: 0, scale: 1, filter: 'blur(0px)' }}
      transition={{ duration: 0.9, ease: [0.16, 1, 0.3, 1] }}
    >
      <aside className="relative overflow-hidden border-b border-[#dce4ee] bg-[#f7f8fb]/86 px-6 py-10 sm:px-8 lg:border-b-0 lg:border-r lg:px-10 lg:py-12">
        <div className="absolute left-[-12%] top-[-8%] h-64 w-64 rounded-full bg-[#c7d7ea]/70 blur-3xl" />
        <div className="absolute bottom-[-10%] right-[-8%] h-72 w-72 rounded-full bg-white/80 blur-3xl" />
        <div className="absolute inset-0 opacity-[0.22] [background-image:linear-gradient(rgba(100,116,139,0.09)_1px,transparent_1px),linear-gradient(90deg,rgba(100,116,139,0.09)_1px,transparent_1px)] [background-size:42px_42px]" />
        <div className="pointer-events-none absolute -right-16 top-28 h-40 w-40 rounded-full border border-[#d6deea]" />
        <div className="relative z-10 flex h-full flex-col">
          <div className="flex items-center gap-3">
            <div className="flex h-11 w-11 items-center justify-center rounded-full border border-black/10 bg-white text-[#1a2a3a] shadow-[0_12px_30px_rgba(17,17,17,0.06)]">
              智
            </div>
            <div>
              <p className="text-[11px] font-semibold uppercase tracking-[0.28em] text-slate-400">Learning Assistant</p>
              <p className="text-sm font-semibold text-[#111111]">智学引擎</p>
            </div>
          </div>

          <div className="mt-12">
            <p className="text-[11px] font-semibold uppercase tracking-[0.42em] text-slate-400">{eyebrow}</p>
            <h1 className="mt-6 text-5xl leading-[0.98] text-[#111111] sm:text-6xl" style={authEditorialSerifStyle}>
              {sideTitle}
            </h1>
            <p className="mt-6 max-w-[28rem] text-sm leading-7 text-slate-500 sm:text-base">{sideDescription}</p>
          </div>

          <div className="mt-10 grid gap-3">
            {sideNotes.map((note, index) => (
              <motion.div
                key={note}
                className="group rounded-[24px] border border-[#dce4ee] bg-white/74 p-4 shadow-[0_14px_36px_rgba(15,23,42,0.05)] backdrop-blur transition duration-300 hover:-translate-y-2 hover:border-[#b9c9dd] hover:bg-white/92 hover:shadow-[0_24px_70px_rgba(15,23,42,0.12)]"
                initial={{ opacity: 0, y: 18, filter: 'blur(8px)' }}
                animate={{ opacity: 1, y: 0, filter: 'blur(0px)' }}
                transition={{ duration: 0.55, delay: 0.18 + index * 0.1, ease: [0.16, 1, 0.3, 1] }}
                whileHover={{ scale: 1.018 }}
              >
                <div className="flex items-center justify-between">
                  <div className="text-[11px] font-semibold uppercase tracking-[0.28em] text-slate-400 transition group-hover:text-[#1a2a3a]">0{index + 1}</div>
                  <div className="h-2 w-10 rounded-full bg-[#dbe6f3] transition group-hover:w-16 group-hover:bg-[#1a2a3a]" />
                </div>
                <p className="mt-3 text-sm leading-6 text-slate-600 transition group-hover:text-[#34465c]">{note}</p>
              </motion.div>
            ))}
          </div>

          <div className="mt-auto pt-10">
            <Button asChild className={cn(authSecondaryButtonClassName, 'group')}>
              <Link to={sideActionTo}>
                {sideActionLabel}
                <ArrowUpRight className="ml-2 h-4 w-4 transition group-hover:translate-x-0.5 group-hover:-translate-y-0.5" />
              </Link>
            </Button>
          </div>
        </div>
      </aside>

      <div className="relative flex items-center justify-center bg-white/62 px-6 py-10 sm:px-8 lg:px-12 lg:py-12">
        <div className="absolute right-8 top-8 hidden rounded-full border border-[#d6deea] bg-[#f7f8fb] px-4 py-2 text-[10px] font-semibold uppercase tracking-[0.24em] text-slate-400 lg:block">
          Secure Access
        </div>
        <motion.div
          className="w-full max-w-[430px] rounded-[32px] border border-[#e1e7f0] bg-white/72 p-6 shadow-[0_24px_80px_rgba(15,23,42,0.08),inset_0_1px_0_rgba(255,255,255,0.9)] backdrop-blur-xl sm:p-8"
          initial={{ opacity: 0, y: 24, filter: 'blur(10px)' }}
          animate={{ opacity: 1, y: 0, filter: 'blur(0px)' }}
          transition={{ duration: 0.72, delay: 0.12, ease: [0.16, 1, 0.3, 1] }}
        >
          <p className="text-[11px] font-semibold uppercase tracking-[0.38em] text-slate-400">{eyebrow}</p>
          <h2 className="mt-5 text-4xl leading-none text-[#111111] sm:text-5xl" style={authEditorialSerifStyle}>
            {title}
          </h2>
          <p className="mt-5 text-sm leading-7 text-slate-500">{description}</p>
          <div className="mt-8">{children}</div>
        </motion.div>
      </div>
    </motion.section>
  )
}
