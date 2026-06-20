import { useEffect, useState, type CSSProperties, type ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { motion, useReducedMotion } from 'framer-motion'
import { ArrowRight, BookOpen, BrainCircuit, Menu, Search, Sparkles, Upload, X } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { SiteBeianFooter } from '@/components/layout/SiteBeianFooter'
import { cn } from '@/lib/utils'

/**
 * 统一定义落地页各分幕的锚点，便于顶部导航、移动端菜单和页面内跳转复用。
 * 这样后续如果调整区块顺序，只需要改这里，不需要在多个组件里同步维护字符串。
 */
const landingSections = [
  { id: 'hero', label: '首页' },
  { id: 'problem', label: '问题' },
  { id: 'solution', label: '方案' },
  { id: 'features', label: '能力' },
  { id: 'workspace', label: '工作台' },
  { id: 'cta', label: '开始' },
] as const

/**
 * 落地页采用“分屏翻页”体验，这里把每一屏的 id 收敛为数组。
 * 后续悬浮向下按钮、右侧进度点、滚动监听都依赖同一份顺序，避免交互和页面结构不同步。
 */
/**
 * Hero 区标题按行拆分，方便实现逐行入场动画。
 * 其中“可追问”使用品牌深蓝色，作为全页唯一强锚点。
 */
const heroLines = [
  ['让每份资料'],
  ['都变成', '可追问'],
  ['的学习助手'],
]

/**
 * 工作台大图上的注解说明统一数据化，后续替换成真实截图时只需要调整定位。
 */
const workspaceCallouts = [
  { id: '①', title: '资料上下文保持', top: '22%', left: '12%' },
  { id: '②', title: '来源实时追溯', top: '46%', left: '68%' },
  { id: '③', title: '智能问答生成', top: '76%', left: '42%' },
] as const

/**
 * 本地衬线字体栈。这里避免依赖外部字体下载，确保落地页在当前工程中直接可运行。
 * 如果后续产品允许引入品牌字体，可在这一处替换为正式标题字体。
 */
const editorialSerifStyle: CSSProperties = {
  fontFamily: "'Iowan Old Style', 'Palatino Linotype', 'Book Antiqua', 'Cormorant Garamond', serif",
}

/**
 * 大多数版心宽度统一收敛到这里，便于所有分幕保持一致的留白节奏。
 */
const shellClassName = 'mx-auto w-full max-w-[1280px] px-5 sm:px-8 lg:px-10'

/**
 * 落地页中所有“图片感/截图感”的资料卡统一使用这套 hover 悬浮反馈。
 * 鼠标经过时轻微上浮、边框加深、阴影增强，保证首页多张卡片的交互手感一致。
 */
const floatingCardHoverClassName =
  'transition duration-300 ease-out hover:-translate-y-2 hover:border-[#b9c9dd] hover:shadow-[0_26px_78px_rgba(15,23,42,0.14)]'

/**
 * 大型产品预览“整张图”的悬浮反馈。
 * 和小卡片 hover 不同，它作用于整块截图/产品画面，让用户感知整张图都是可被激活的视觉对象。
 */
const floatingPreviewHoverClassName =
  'transition duration-500 ease-out hover:-translate-y-3 hover:scale-[1.012] hover:border-[#b9c9dd] hover:shadow-[0_36px_120px_rgba(15,23,42,0.16)]'

/**
 * 顶部导航中的按钮既可能跳到页面锚点，也可能直接跳到路由。
 * 抽成小组件后，视觉和交互可以保持一致。
 */
function LandingLink({
  href,
  children,
  className,
  onClick,
}: {
  href: string
  children: ReactNode
  className?: string
  onClick?: () => void
}) {
  const isRoute = href.startsWith('/')

  if (isRoute) {
    return (
      <Link className={className} to={href} onClick={onClick}>
        {children}
      </Link>
    )
  }

  return (
    <a className={className} href={href} onClick={onClick}>
      {children}
    </a>
  )
}

/**
 * 通用分幕标题区。大标题、编号、说明文案都统一这里输出，保证节奏一致。
 */
function SectionIntro({
  eyebrow,
  title,
  description,
  inverse = false,
}: {
  eyebrow: string
  title: string
  description: string
  inverse?: boolean
}) {
  return (
    <div className="max-w-3xl">
      <p className={cn('text-[11px] font-semibold uppercase tracking-[0.42em]', inverse ? 'text-white/55' : 'text-slate-400')}>
        {eyebrow}
      </p>
      <h2
        className={cn('mt-5 text-4xl leading-[1.02] sm:text-5xl lg:text-6xl', inverse ? 'text-white' : 'text-[#111111]')}
        style={editorialSerifStyle}
      >
        {title}
      </h2>
      <p className={cn('mt-5 max-w-2xl text-sm leading-7 sm:text-base', inverse ? 'text-white/66' : 'text-slate-500')}>
        {description}
      </p>
    </div>
  )
}

/**
 * 卡片组 stagger 动画。
 * 进入当前分屏后，子元素会按顺序轻微上浮，形成“内容被唤醒”的节奏。
 */
const staggerContainerMotion = {
  hidden: {
    transition: {
      staggerChildren: 0.055,
      staggerDirection: -1,
    },
  },
  show: {
    transition: {
      staggerChildren: 0.11,
      delayChildren: 0.18,
    },
  },
}

/**
 * 分屏切换的核心动画。
 * 这里不再依赖 whileInView，而是由 activeSectionId 显式驱动 show/hidden，
 * 这样点击悬浮按钮、滚轮下滑、右侧导航跳转时，旧页面会退场，新页面会进场。
 */
const sectionFrameMotion = {
  hidden: {
    opacity: 0,
    y: 72,
    scale: 1,
    filter: 'blur(14px)',
    transition: {
      duration: 0.58,
      ease: [0.4, 0, 0.2, 1] as const,
    },
  },
  show: {
    opacity: 1,
    y: 0,
    scale: 1,
    filter: 'blur(0px)',
    transition: {
      duration: 1.08,
      ease: [0.16, 1, 0.3, 1] as const,
      staggerChildren: 0.13,
      delayChildren: 0.1,
    },
  },
}

const floatItemMotion = {
  hidden: {
    opacity: 0,
    y: 58,
    scale: 0.985,
    filter: 'blur(12px)',
    transition: { duration: 0.5, ease: [0.4, 0, 0.2, 1] as const },
  },
  show: {
    opacity: 1,
    y: 0,
    scale: 1,
    filter: 'blur(0px)',
    transition: { duration: 1.02, ease: [0.16, 1, 0.3, 1] as const },
  },
}

function ScrollReveal({
  children,
  className,
  delay = 0,
  style,
}: {
  children: ReactNode
  className?: string
  delay?: number
  style?: CSSProperties
}) {
  const prefersReducedMotion = useReducedMotion()

  if (prefersReducedMotion) {
    return <div className={className} style={style}>{children}</div>
  }

  return (
    <motion.div
      className={className}
      style={style}
      initial={{ opacity: 0, y: 60 }}
      whileInView={{ opacity: 1, y: 0 }}
      viewport={{ once: true, amount: 0.1 }}
      transition={{ duration: 0.8, delay, ease: [0.16, 1, 0.3, 1] }}
    >
      {children}
    </motion.div>
  )
}

const heroLineMotion = {
  hidden: {
    opacity: 0,
    y: 42,
    scale: 0.985,
    filter: 'blur(18px)',
    transition: { duration: 0.48, ease: [0.4, 0, 0.2, 1] as const },
  },
  show: (index: number) => ({
    opacity: 1,
    y: 0,
    scale: 1,
    filter: 'blur(0px)',
    transition: {
      duration: 0.9,
      delay: index * 0.12,
      ease: [0.16, 1, 0.3, 1] as const,
    },
  }),
}

function AnimatedHeroText({ text, className, delay = 0 }: { text: string; className?: string; delay?: number }) {
  return (
    <span className={className}>
      {Array.from(text).map((char, index) => (
        <motion.span
          key={`${char}-${index}`}
          className="inline-block"
          initial={{ opacity: 0, y: 42, filter: 'blur(14px)' }}
          animate={{ opacity: 1, y: 0, filter: 'blur(0px)' }}
          transition={{
            duration: 0.72,
            delay: delay + index * 0.035,
            ease: [0.16, 1, 0.3, 1],
          }}
        >
          {char === ' ' ? '\u00a0' : char}
        </motion.span>
      ))}
    </span>
  )
}

function HeroMimoOrbit() {
  return (
    <motion.div
      className="pointer-events-none absolute right-[7%] top-[26%] hidden h-[360px] w-[360px] opacity-80 lg:block"
      initial={{ opacity: 0, x: 40, scale: 0.92, filter: 'blur(10px)' }}
      animate={{ opacity: 0.8, x: 0, scale: 1, filter: 'blur(0px)' }}
      transition={{ duration: 1.1, delay: 0.55, ease: [0.16, 1, 0.3, 1] }}
      aria-hidden="true"
    >
      <motion.div
        className="absolute inset-0 rounded-full border border-[#1a2a3a]/32"
        animate={{ rotate: 360 }}
        transition={{ duration: 24, repeat: Number.POSITIVE_INFINITY, ease: 'linear' }}
      />
      <motion.div
        className="absolute left-1/2 top-1/2 h-[88px] w-[420px] -translate-x-1/2 -translate-y-1/2 rounded-[50%] border border-[#1a2a3a]/20"
        animate={{ rotate: [0, 360] }}
        transition={{ duration: 18, repeat: Number.POSITIVE_INFINITY, ease: 'linear' }}
      />
      <motion.div
        className="absolute left-1/2 top-1/2 h-[360px] w-[108px] -translate-x-1/2 -translate-y-1/2 rounded-[50%] border border-[#1a2a3a]/12"
        animate={{ rotate: [0, -360] }}
        transition={{ duration: 28, repeat: Number.POSITIVE_INFINITY, ease: 'linear' }}
      />
      <svg className="absolute inset-0 h-full w-full" viewBox="0 0 360 360" fill="none">
        <motion.path
          d="M102 158L158 188L218 126L264 164"
          stroke="#1a2a3a"
          strokeWidth="1.2"
          strokeOpacity="0.42"
          pathLength={1}
          initial={{ pathLength: 0, opacity: 0 }}
          animate={{ pathLength: 1, opacity: 1 }}
          transition={{ duration: 1.6, delay: 0.85, ease: [0.16, 1, 0.3, 1] }}
        />
        {[
          [102, 158, '#7eb3d6'],
          [158, 188, '#75c98d'],
          [218, 126, '#d2a15d'],
          [264, 164, '#9aa5b1'],
        ].map(([cx, cy, color], index) => (
          <motion.circle
            key={`${cx}-${cy}`}
            cx={Number(cx)}
            cy={Number(cy)}
            r="5"
            fill={String(color)}
            stroke="white"
            strokeWidth="2"
            initial={{ scale: 0, opacity: 0 }}
            animate={{ scale: [1, 1.24, 1], opacity: 1 }}
            transition={{
              duration: 1.8,
              delay: 0.9 + index * 0.16,
              repeat: Number.POSITIVE_INFINITY,
              repeatDelay: 1.6,
              ease: 'easeInOut',
            }}
          />
        ))}
      </svg>
      <div className="absolute inset-[54px] rounded-full bg-[radial-gradient(circle,rgba(255,255,255,0.92),rgba(255,255,255,0)_62%)]" />
      <div className="absolute inset-[88px] rounded-full bg-[radial-gradient(circle,rgba(26,42,58,0.08),rgba(26,42,58,0)_64%)] blur-xl" />
      <div className="absolute left-16 top-24 grid grid-cols-12 gap-1 opacity-35">
        {Array.from({ length: 72 }).map((_, index) => (
          <span key={index} className="h-1 w-1 rounded-full bg-[#1a2a3a]" />
        ))}
      </div>
    </motion.div>
  )
}

function SectionAmbientMotion({
  align = 'right',
  tone = 'light',
}: {
  align?: 'left' | 'right' | 'center'
  tone?: 'light' | 'deep'
}) {
  const isDeep = tone === 'deep'
  const anchorClassName =
    align === 'left'
      ? 'left-[-110px] top-8'
      : align === 'center'
        ? 'left-1/2 top-8 -translate-x-1/2'
        : 'right-[-120px] top-10'
  const lineColor = isDeep ? 'border-white/18' : 'border-[#1a2a3a]/14'
  const dotColor = isDeep ? 'bg-white/55' : 'bg-[#1a2a3a]/42'

  return (
    <div className="pointer-events-none absolute inset-0 overflow-hidden" aria-hidden="true">
      <motion.div
        className={cn('absolute hidden h-[360px] w-[360px] rounded-full lg:block', anchorClassName)}
        initial={{ opacity: 0, y: 40, scale: 0.92, filter: 'blur(10px)' }}
        whileInView={{ opacity: isDeep ? 0.45 : 0.72, y: 0, scale: 1, filter: 'blur(0px)' }}
        viewport={{ once: false, amount: 0.12 }}
        transition={{ duration: 1.2, ease: [0.16, 1, 0.3, 1] }}
      >
        <motion.div
          className={cn('absolute inset-0 rounded-full border', lineColor)}
          animate={{ rotate: 360 }}
          transition={{ duration: 32, repeat: Number.POSITIVE_INFINITY, ease: 'linear' }}
        />
        <motion.div
          className={cn('absolute left-1/2 top-1/2 h-[86px] w-[430px] -translate-x-1/2 -translate-y-1/2 rounded-[50%] border', lineColor)}
          animate={{ rotate: -360 }}
          transition={{ duration: 24, repeat: Number.POSITIVE_INFINITY, ease: 'linear' }}
        />
        <motion.div
          className={cn('absolute left-1/2 top-1/2 h-[330px] w-[96px] -translate-x-1/2 -translate-y-1/2 rounded-[50%] border', lineColor)}
          animate={{ rotate: 360 }}
          transition={{ duration: 38, repeat: Number.POSITIVE_INFINITY, ease: 'linear' }}
        />
        <div className={cn('absolute left-[44%] top-[35%] h-2 w-2 rounded-full', dotColor)} />
        <motion.div
          className={cn('absolute left-[64%] top-[54%] h-3 w-3 rounded-full', dotColor)}
          animate={{ scale: [1, 1.65, 1], opacity: [0.35, 0.95, 0.35] }}
          transition={{ duration: 2.2, repeat: Number.POSITIVE_INFINITY, ease: 'easeInOut' }}
        />
      </motion.div>
      <motion.div
        className={cn(
          'absolute bottom-12 h-44 w-44 rounded-full blur-3xl',
          align === 'left' ? 'right-[8%]' : 'left-[8%]',
          isDeep ? 'bg-white/10' : 'bg-[#c9d8ea]/50',
        )}
        animate={{ y: [0, -18, 0], opacity: [0.35, 0.62, 0.35] }}
        transition={{ duration: 7, repeat: Number.POSITIVE_INFINITY, ease: 'easeInOut' }}
      />
    </div>
  )
}

function LandingIntroSequence({ onComplete }: { onComplete: () => void }) {
  const prefersReducedMotion = useReducedMotion()

  useEffect(() => {
    if (prefersReducedMotion) {
      onComplete()
      return undefined
    }

    const timer = window.setTimeout(onComplete, 2600)
    return () => window.clearTimeout(timer)
  }, [onComplete, prefersReducedMotion])

  if (prefersReducedMotion) {
    return null
  }

  const nodes = [
    { cx: 120, cy: 96, delay: 0.45 },
    { cx: 196, cy: 60, delay: 0.58 },
    { cx: 268, cy: 118, delay: 0.72 },
    { cx: 230, cy: 198, delay: 0.86 },
    { cx: 138, cy: 190, delay: 1 },
  ]

  return (
    <motion.div
      className="fixed inset-0 z-[100] flex items-center justify-center overflow-hidden bg-[#f7f8fb]"
      initial={{ opacity: 1 }}
      animate={{ opacity: [1, 1, 0] }}
      transition={{ duration: 2.6, times: [0, 0.82, 1], ease: [0.16, 1, 0.3, 1] }}
      onAnimationComplete={onComplete}
      onClick={onComplete}
      role="button"
      tabIndex={0}
      aria-label="跳过开场动画"
      onKeyDown={(event) => {
        if (event.key === 'Enter' || event.key === ' ') {
          onComplete()
        }
      }}
    >
      <div className="absolute inset-0 bg-[radial-gradient(circle_at_50%_42%,rgba(255,255,255,0.98),transparent_28%),radial-gradient(circle_at_50%_55%,rgba(197,212,231,0.72),transparent_34%),linear-gradient(180deg,#fcfcfd_0%,#f7f8fb_100%)]" />
      <div className="absolute inset-0 opacity-[0.18] [background-image:linear-gradient(rgba(100,116,139,0.08)_1px,transparent_1px),linear-gradient(90deg,rgba(100,116,139,0.08)_1px,transparent_1px)] [background-size:56px_56px]" />

      <div className="relative flex flex-col items-center">
        <motion.svg
          className="h-[280px] w-[360px] overflow-visible"
          viewBox="0 0 360 260"
          fill="none"
          initial={{ opacity: 0, scale: 0.94 }}
          animate={{ opacity: 1, scale: 1 }}
          transition={{ duration: 0.5, ease: [0.16, 1, 0.3, 1] }}
          aria-hidden="true"
        >
          <motion.circle
            cx="180"
            cy="130"
            r="8"
            fill="#1a2a3a"
            initial={{ scale: 0, opacity: 0 }}
            animate={{ scale: [0, 1.2, 1], opacity: 1 }}
            transition={{ duration: 0.58, ease: [0.16, 1, 0.3, 1] }}
          />
          <motion.circle
            cx="180"
            cy="130"
            r="42"
            stroke="#1a2a3a"
            strokeOpacity="0.12"
            initial={{ scale: 0.2, opacity: 0 }}
            animate={{ scale: [0.2, 1.45], opacity: [0, 0.42, 0] }}
            transition={{ duration: 1.35, delay: 0.18, ease: [0.16, 1, 0.3, 1] }}
          />
          <motion.path
            d="M180 130L120 96M180 130L196 60M180 130L268 118M180 130L230 198M180 130L138 190"
            stroke="#1a2a3a"
            strokeWidth="1.2"
            strokeOpacity="0.34"
            initial={{ pathLength: 0, opacity: 0 }}
            animate={{ pathLength: 1, opacity: 1 }}
            transition={{ duration: 0.82, delay: 0.42, ease: [0.16, 1, 0.3, 1] }}
          />
          {nodes.map((node) => (
            <motion.g key={`${node.cx}-${node.cy}`}>
              <motion.circle
                cx={node.cx}
                cy={node.cy}
                r="15"
                fill="#ffffff"
                stroke="#d6deea"
                initial={{ scale: 0, opacity: 0 }}
                animate={{ scale: 1, opacity: 1 }}
                transition={{ duration: 0.42, delay: node.delay, ease: [0.16, 1, 0.3, 1] }}
              />
              <motion.circle
                cx={node.cx}
                cy={node.cy}
                r="4"
                fill="#1a2a3a"
                initial={{ scale: 0, opacity: 0 }}
                animate={{ scale: [0, 1.35, 1], opacity: 1 }}
                transition={{ duration: 0.42, delay: node.delay + 0.08, ease: [0.16, 1, 0.3, 1] }}
              />
            </motion.g>
          ))}
        </motion.svg>

        <motion.p
          className="mt-[-28px] text-[11px] font-semibold uppercase tracking-[0.46em] text-slate-400"
          initial={{ opacity: 0, y: 28 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.72, delay: 1.12, ease: [0.16, 1, 0.3, 1] }}
        >
          Learning Assistant
        </motion.p>
        <motion.h2
          className="mt-5 text-center text-4xl leading-none text-[#111111] sm:text-5xl"
          style={editorialSerifStyle}
          initial={{ opacity: 0, y: 40 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.82, delay: 1.28, ease: [0.16, 1, 0.3, 1] }}
        >
          让资料开始回答问题
        </motion.h2>
        <motion.p
          className="mt-7 text-xs font-medium uppercase tracking-[0.28em] text-slate-400"
          initial={{ opacity: 0 }}
          animate={{ opacity: 0.72 }}
          transition={{ duration: 0.5, delay: 1.68 }}
        >
          点击跳过
        </motion.p>
      </div>
    </motion.div>
  )
}

function LandingIntroSequenceV2({ onComplete }: { onComplete: () => void }) {
  const prefersReducedMotion = useReducedMotion()

  useEffect(() => {
    if (prefersReducedMotion) {
      onComplete()
      return undefined
    }

    const timer = window.setTimeout(onComplete, 3200)
    return () => window.clearTimeout(timer)
  }, [onComplete, prefersReducedMotion])

  if (prefersReducedMotion) {
    return null
  }

  const fragments = [
    { label: '上传文档', className: '-translate-x-[280px] -translate-y-[118px]', exitX: 120, exitY: 62, delay: 0 },
    { label: 'RAG 知识索引', className: 'translate-x-[260px] -translate-y-[126px]', exitX: -120, exitY: 62, delay: 0.08 },
    { label: '来源引用', className: '-translate-x-[360px] translate-y-[92px]', exitX: 120, exitY: -62, delay: 0.16 },
    { label: '自动总结', className: 'translate-x-[330px] translate-y-[116px]', exitX: -120, exitY: -62, delay: 0.24 },
  ]

  const introPaths = [
    'M380 210C300 150 240 118 166 112',
    'M380 210C462 144 532 112 614 118',
    'M380 210C282 250 236 302 154 314',
    'M380 210C488 252 544 304 636 316',
    'M270 116H492',
    'M260 316H506',
  ]

  return (
    <motion.div
      className="fixed inset-0 z-[100] overflow-hidden bg-[#f7f8fb] px-5 text-[#111111] sm:px-8 lg:px-10"
      initial={{ opacity: 1 }}
      animate={{ opacity: [1, 1, 0] }}
      transition={{ duration: 3.2, times: [0, 0.84, 1], ease: [0.22, 1, 0.36, 1] }}
    >
      <div className="absolute inset-0 bg-[radial-gradient(circle_at_50%_36%,rgba(255,255,255,0.98),transparent_30%),radial-gradient(circle_at_50%_62%,rgba(197,212,231,0.72),transparent_34%),linear-gradient(180deg,#fcfcfd_0%,#f7f8fb_100%)]" />
      <div className="absolute inset-0 opacity-[0.18] [background-image:linear-gradient(rgba(100,116,139,0.08)_1px,transparent_1px),linear-gradient(90deg,rgba(100,116,139,0.08)_1px,transparent_1px)] [background-size:56px_56px]" />

      <button
        type="button"
        className="absolute right-5 top-5 z-20 rounded-full border border-white bg-white/82 px-4 py-2 text-xs font-semibold text-slate-600 shadow-[0_14px_34px_rgba(15,23,42,0.08)] backdrop-blur transition hover:-translate-y-0.5 hover:text-[#111111]"
        onClick={onComplete}
      >
        跳过
      </button>

      <section className="relative z-10 mx-auto flex h-screen w-full max-w-7xl flex-col items-center justify-center">
        <motion.div
          className="absolute top-9 inline-flex items-center gap-2 rounded-full border border-white bg-white/82 px-4 py-2 text-xs font-semibold text-slate-600 shadow-[0_14px_34px_rgba(15,23,42,0.08)] backdrop-blur"
          initial={{ opacity: 0, y: -18 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.42, ease: [0.22, 1, 0.36, 1] }}
        >
          <span className="h-2 w-2 rounded-full bg-[#1a2a3a]" />
          正在构建 RAG 知识索引
        </motion.div>

        <motion.svg
          className="absolute left-1/2 top-1/2 h-[420px] w-[760px] -translate-x-1/2 -translate-y-1/2 overflow-visible opacity-80"
          viewBox="0 0 760 420"
          fill="none"
          initial={{ opacity: 0, y: 36, scale: 0.96 }}
          animate={{ opacity: 0.8, y: 0, scale: 1 }}
          transition={{ duration: 0.58, delay: 0.28, ease: [0.22, 1, 0.36, 1] }}
          aria-hidden="true"
        >
          {introPaths.map((d, index) => (
            <motion.path
              key={d}
              d={d}
              stroke="#1a2a3a"
              strokeWidth="1.2"
              strokeOpacity="0.42"
              pathLength={1}
              initial={{ pathLength: 0, opacity: 0 }}
              animate={{ pathLength: 1, opacity: 0.55 }}
              transition={{ duration: 1.45, delay: 0.34 + index * 0.05, ease: [0.22, 1, 0.36, 1] }}
            />
          ))}
          <motion.circle
            cx="380"
            cy="210"
            r="44"
            fill="white"
            fillOpacity="0.82"
            stroke="#d6deea"
            initial={{ scale: 0.4, opacity: 0 }}
            animate={{ scale: 1, opacity: 1 }}
            transition={{ duration: 0.62, delay: 0.72, ease: [0.22, 1, 0.36, 1] }}
          />
          <motion.circle
            cx="380"
            cy="210"
            r="8"
            fill="#1a2a3a"
            initial={{ scale: 0, opacity: 0 }}
            animate={{ scale: [0, 1.35, 1], opacity: 1 }}
            transition={{ duration: 0.5, delay: 0.86, ease: [0.22, 1, 0.36, 1] }}
          />
        </motion.svg>

        {fragments.map((fragment) => (
          <motion.div
            key={fragment.label}
            className={cn(
              'absolute left-1/2 top-1/2 rounded-full border border-white bg-white/88 px-4 py-2 text-xs font-semibold text-slate-600 shadow-[0_18px_48px_rgba(15,23,42,0.1)] backdrop-blur',
              fragment.className,
            )}
            initial={{ opacity: 0, scale: 0.72 }}
            animate={{
              opacity: [0, 1, 1, 0],
              scale: [0.72, 1, 0.92, 0.78],
              x: [0, 0, 0, fragment.exitX],
              y: [0, 0, 0, fragment.exitY],
            }}
            transition={{
              duration: 2.15,
              delay: fragment.delay,
              times: [0, 0.28, 0.72, 1],
              ease: [0.22, 1, 0.36, 1],
            }}
          >
            {fragment.label}
          </motion.div>
        ))}

        <motion.div
          className="relative z-10 w-[min(980px,90vw)] text-center"
          initial={{ opacity: 0, y: 72, scale: 0.9 }}
          animate={{ opacity: 1, y: [72, 0, -112], scale: [0.9, 1, 0.72] }}
          transition={{ duration: 2.25, delay: 0.62, times: [0, 0.48, 1], ease: [0.2, 0.82, 0.18, 1] }}
        >
          <p className="mb-5 text-[11px] font-semibold uppercase tracking-[0.46em] text-slate-400">Learning Assistant</p>
          <h2 className="text-[2.6rem] font-semibold leading-[1.03] text-[#111111] sm:text-7xl lg:text-8xl" style={editorialSerifStyle}>
            让每份资料都变成
            <span className="block text-[#1a2a3a]">可追问的学习助手</span>
          </h2>
        </motion.div>

        <motion.p
          className="relative z-10 mt-8 max-w-3xl text-center text-base font-medium leading-8 text-slate-500 sm:text-xl sm:leading-9"
          initial={{ opacity: 0, y: 58, scale: 0.96 }}
          animate={{ opacity: [0, 1, 1, 0], y: [58, 0, -74, -112], scale: [0.96, 1, 0.96, 0.88] }}
          transition={{ duration: 2.1, delay: 0.88, times: [0, 0.38, 0.76, 1], ease: [0.2, 0.82, 0.18, 1] }}
        >
          上传文档后自动构建 RAG 知识索引，支持资料问答、来源引用、临时附件追问和自动总结。
        </motion.p>
      </section>
    </motion.div>
  )
}

export function ProductLandingPage() {
  const [mobileMenuOpen, setMobileMenuOpen] = useState(false)
  const [headerSolid, setHeaderSolid] = useState(false)
  const [introVisible, setIntroVisible] = useState(true)
  const prefersReducedMotion = useReducedMotion()
  const revealSectionMotion = prefersReducedMotion
    ? {}
    : {
        variants: sectionFrameMotion,
        initial: 'hidden',
        whileInView: 'show',
        viewport: { once: false, amount: 0.12, margin: '0px 0px -12% 0px' },
      }

  /**
   * 顶部导航和悬浮翻页控件共享同一个滚动监听。
   * 监听时会计算哪个分屏最靠近视口顶部，让右侧进度点和向下按钮保持当前页面状态。
   */
  useEffect(() => {
    const onScroll = () => {
      setHeaderSolid(window.scrollY > 20)
    }

    onScroll()
    window.addEventListener('scroll', onScroll, { passive: true })
    return () => window.removeEventListener('scroll', onScroll)
  }, [])

  /**
   * 真正产生“翻页感”的滚动容器是浏览器视口，对应 documentElement。
   * 这里在落地页挂载时临时开启 scroll snap，离开页面时恢复原值，避免影响工作台等普通滚动页面。
   */
  useEffect(() => {
    document.documentElement.style.scrollBehavior = prefersReducedMotion ? 'auto' : 'smooth'
  }, [prefersReducedMotion])

  /**
   * 鼠标滚轮和触控板下滑也走“PPT 切屏”逻辑。
   * 这里主动阻止原生滚动，统一交给 transitionToSection 控制，确保滚动和点击都有同样的淡入淡出质感。
   */
  useEffect(() => {
    return undefined
  }, [])

  useEffect(() => {
    return undefined
  }, [])

  const completeIntro = () => {
    setIntroVisible(false)
  }

  return (
    <div className="min-h-screen scroll-smooth bg-[#f7f8fb] text-[#111111]">
      {introVisible ? <LandingIntroSequenceV2 onComplete={completeIntro} /> : null}

      <header
        className={cn(
          'fixed inset-x-0 top-0 z-50 transition-all duration-300',
          headerSolid ? 'border-b border-black/8 bg-white/76 backdrop-blur-xl' : 'bg-transparent'
        )}
      >
        <div className={cn(shellClassName, 'flex h-16 items-center justify-between')}>
          <LandingLink href="#hero" className="flex items-center gap-3">
            <div className="flex h-9 w-9 items-center justify-center rounded-full border border-black/10 bg-white text-[#1a2a3a] shadow-[0_8px_24px_rgba(17,17,17,0.05)]">
              <BrainCircuit className="h-4 w-4" />
            </div>
            <div>
              <p className="text-[11px] font-semibold uppercase tracking-[0.24em] text-slate-400">Learning Assistant</p>
              <p className="text-sm font-semibold text-[#111111]">智学引擎</p>
            </div>
          </LandingLink>

          <nav className="hidden items-center gap-7 md:flex">
            {landingSections.slice(1).map((section) => (
              <LandingLink
                key={section.id}
                href={`#${section.id}`}
                className="text-sm text-slate-500 transition hover:text-[#111111]"
              >
                {section.label}
              </LandingLink>
            ))}
          </nav>

          <div className="hidden items-center gap-3 md:flex">
            <Button
              asChild
              variant="outline"
              className="h-11 rounded-full border-black/10 bg-white/76 px-5 text-[#111111] shadow-[0_10px_26px_rgba(15,23,42,0.04)] transition duration-300 hover:-translate-y-1 hover:border-[#111111] hover:bg-[#111111] hover:text-white hover:shadow-[0_18px_42px_rgba(15,23,42,0.14)]"
            >
              <Link to="/login">登录</Link>
            </Button>
            <Button
              asChild
              className="group h-11 rounded-full bg-[#111111] px-6 text-white shadow-[0_12px_30px_rgba(15,23,42,0.12)] transition duration-300 hover:-translate-y-1 hover:bg-white hover:text-[#111111] hover:shadow-[inset_0_0_0_1px_rgba(17,17,17,0.18),0_18px_42px_rgba(15,23,42,0.12)]"
            >
              <Link to="/workspace/chat?new=1" className="inline-flex items-center">
                开始使用
                <ArrowRight className="ml-2 h-4 w-4 transition duration-300 group-hover:translate-x-1" />
              </Link>
            </Button>
          </div>

          <button
            type="button"
            className="inline-flex h-10 w-10 items-center justify-center rounded-full border border-black/10 bg-white text-[#111111] md:hidden"
            aria-label={mobileMenuOpen ? '关闭导航菜单' : '打开导航菜单'}
            onClick={() => setMobileMenuOpen((value) => !value)}
          >
            {mobileMenuOpen ? <X className="h-4 w-4" /> : <Menu className="h-4 w-4" />}
          </button>
        </div>

        {mobileMenuOpen ? (
          <div className="border-t border-black/8 bg-white/92 px-5 py-5 backdrop-blur-xl md:hidden">
            <div className="mx-auto flex max-w-[1280px] flex-col gap-4">
              {landingSections.slice(1).map((section) => (
                <LandingLink
                  key={section.id}
                  href={`#${section.id}`}
                  className="text-base text-slate-600"
                  onClick={() => setMobileMenuOpen(false)}
                >
                  {section.label}
                </LandingLink>
              ))}
              <Button
                asChild
                variant="outline"
                className="mt-2 h-11 rounded-full border-black/10 bg-white text-[#111111] transition duration-300 hover:-translate-y-0.5 hover:bg-[#111111] hover:text-white"
              >
                <Link to="/login" onClick={() => setMobileMenuOpen(false)}>
                  登录
                </Link>
              </Button>
              <Button
                asChild
                className="h-11 rounded-full bg-[#111111] text-white shadow-[0_12px_30px_rgba(15,23,42,0.12)] transition duration-300 hover:-translate-y-0.5 hover:bg-white hover:text-[#111111] hover:shadow-[inset_0_0_0_1px_rgba(17,17,17,0.18)]"
              >
                <Link to="/workspace/chat?new=1" onClick={() => setMobileMenuOpen(false)}>
                  开始使用
                </Link>
              </Button>
            </div>
          </div>
        ) : null}
      </header>

      <main className="overflow-x-hidden">
        <section id="hero" className="relative min-h-screen overflow-hidden bg-[#f7f8fb] pt-28">
          <div className="absolute inset-0 bg-[radial-gradient(circle_at_top_left,rgba(255,255,255,0.96),transparent_30%),radial-gradient(circle_at_82%_18%,rgba(206,218,234,0.92),transparent_24%),radial-gradient(circle_at_22%_78%,rgba(226,233,243,0.84),transparent_28%),linear-gradient(180deg,#fcfcfd_0%,#f7f8fb_100%)]" />
          <div className="absolute inset-0 opacity-[0.22] [background-image:linear-gradient(rgba(100,116,139,0.08)_1px,transparent_1px),linear-gradient(90deg,rgba(100,116,139,0.08)_1px,transparent_1px)] [background-size:56px_56px]" />
          <div className="pointer-events-none absolute inset-x-0 bottom-[-1px] z-[1] h-40 bg-gradient-to-b from-transparent via-[#f7f8fb]/88 to-[#f7f8fb]" />
          <HeroMimoOrbit />
          <motion.div className={cn(shellClassName, 'relative flex min-h-[calc(100vh-7rem)] items-center py-14 lg:py-20')} {...revealSectionMotion}>
            <div className="flex max-w-[900px] -translate-y-12 flex-col items-center text-center lg:-translate-y-10 lg:items-start lg:text-left">
              <motion.p
                className="text-[11px] font-semibold uppercase tracking-[0.42em] text-slate-400"
                variants={floatItemMotion}
              >
                资料驱动的 AI 学习工作台
              </motion.p>

              <div className="mt-8 space-y-2">
                {heroLines.map((segments, index) => (
                  <motion.h1
                    key={segments.join('-')}
                    className="text-[3.1rem] leading-[0.95] text-[#111111] sm:text-[4.4rem] lg:text-[5.7rem]"
                    style={editorialSerifStyle}
                    custom={index}
                    variants={heroLineMotion}
                  >
                    {segments.map((segment, segmentIndex) => (
                      <AnimatedHeroText
                        key={`${segment}-${segmentIndex}`}
                        text={segment}
                        className={segment === '可追问' ? 'text-[#1a2a3a]' : undefined}
                        delay={0.18 + index * 0.18 + segmentIndex * 0.08}
                      />
                    ))}
                  </motion.h1>
                ))}
              </div>

              <motion.div
                className="mt-8 h-px w-full max-w-[520px] overflow-hidden bg-black/10"
                variants={floatItemMotion}
                style={{ transformOrigin: 'left center' }}
              >
                <div className="mx-auto h-full w-1/3 bg-[#1a2a3a] lg:mx-0" />
              </motion.div>

              <motion.p
                className="mt-8 max-w-[42rem] text-base leading-8 text-slate-500 sm:text-lg"
                variants={floatItemMotion}
              >
                上传 PDF、讲义、笔记与报告，立即获得可追问答案、来源页码与自动总结。
              </motion.p>

              <motion.div
                className="mt-10 flex flex-col justify-center gap-4 sm:flex-row lg:justify-start"
                variants={floatItemMotion}
              >
                <Button
                  asChild
                  className="group h-12 rounded-full bg-[#111111] px-7 text-white shadow-[0_14px_34px_rgba(15,23,42,0.12)] transition duration-300 hover:-translate-y-1 hover:bg-white hover:text-[#111111] hover:shadow-[inset_0_0_0_1px_rgba(17,17,17,0.18),0_22px_52px_rgba(15,23,42,0.14)]"
                >
                  <Link to="/workspace/chat?new=1" className="inline-flex items-center">
                    开始使用
                    <ArrowRight className="ml-2 h-4 w-4 transition duration-300 group-hover:translate-x-1" />
                  </Link>
                </Button>
                <Button
                  asChild
                  variant="outline"
                  className="h-12 rounded-full border-black/12 bg-white/80 px-7 text-[#111111] shadow-[0_12px_28px_rgba(15,23,42,0.05)] transition duration-300 hover:-translate-y-1 hover:border-[#111111] hover:bg-[#111111] hover:text-white hover:shadow-[0_22px_52px_rgba(15,23,42,0.12)]"
                >
                  <LandingLink href="#workspace">查看工作台</LandingLink>
                </Button>
              </motion.div>
            </div>
          </motion.div>
        </section>

        <section id="problem" className="relative -mt-1 overflow-hidden bg-[#f7f8fb] py-16 sm:py-20 lg:py-24">
          <div className="absolute inset-0 bg-[radial-gradient(circle_at_18%_10%,rgba(255,255,255,0.72),transparent_30%),radial-gradient(circle_at_78%_36%,rgba(194,209,228,0.7),transparent_26%),linear-gradient(180deg,#f7f8fb_0%,#f7f8fb_18%,rgba(247,248,251,0)_56%)]" />
          <div className="absolute inset-0 opacity-[0.32] [background-image:linear-gradient(rgba(100,116,139,0.08)_1px,transparent_1px),linear-gradient(90deg,rgba(100,116,139,0.08)_1px,transparent_1px)] [background-size:44px_44px] [mask-image:radial-gradient(circle_at_52%_46%,black,transparent_72%)]" />
          <SectionAmbientMotion align="right" />
          <div className="pointer-events-none absolute left-[8%] top-14 hidden text-[8rem] font-black uppercase leading-none tracking-[-0.08em] text-white/70 lg:block">
            UNREAD
          </div>
          <div className={cn(shellClassName, 'relative grid min-h-[560px] items-center gap-12 lg:grid-cols-[0.92fr_1.08fr]')}>
            <ScrollReveal>
              <SectionIntro
                eyebrow="Act I / Problem"
                title="资料可以存下来。"
                description="但很难真正被追问、被消化、被复习。信息在堆积，理解却没有同步增长。"
              />
              <div className="mt-9 grid max-w-xl gap-3 sm:grid-cols-3">
                {[
                  ['17', '课程讲义'],
                  ['42', '课堂笔记'],
                  ['0', '可追问索引'],
                ].map(([value, label], index) => (
                  <ScrollReveal key={label} delay={0.12 + index * 0.1} className={cn('rounded-[22px] border border-[#e6eaf0] bg-white/82 p-4 shadow-[0_14px_36px_rgba(15,23,42,0.05)] backdrop-blur', floatingCardHoverClassName)}>
                    <p className="text-3xl leading-none text-[#1a2a3a]" style={editorialSerifStyle}>{value}</p>
                    <p className="mt-2 text-xs font-semibold uppercase tracking-[0.22em] text-slate-400">{label}</p>
                  </ScrollReveal>
                ))}
              </div>
            </ScrollReveal>

            <ScrollReveal
              className={cn('relative mx-auto flex w-full max-w-[640px] justify-center lg:justify-end rounded-[38px]', floatingPreviewHoverClassName)}
              delay={0.12}
            >
              <div className="relative h-[460px] w-full max-w-[650px]">
                <div className="absolute left-8 right-10 top-[214px] h-px -rotate-[13deg] bg-gradient-to-r from-transparent via-[#9fb0c7] to-transparent" />
                <div className="absolute left-[48%] top-[38%] h-24 w-24 rounded-full border border-dashed border-[#b8c5d8] bg-white/52 shadow-[0_18px_60px_rgba(15,23,42,0.08)] backdrop-blur">
                  <div className="absolute left-1/2 top-1/2 h-2 w-2 -translate-x-1/2 -translate-y-1/2 rounded-full bg-[#1a2a3a]" />
                </div>

                {[
                  { name: '课程讲义', meta: '17 份', className: 'left-0 top-16 rotate-[-5deg]' },
                  { name: '课堂笔记', meta: '42 条', className: 'right-14 top-2 rotate-[6deg]' },
                  { name: '论文摘录', meta: '未关联', className: 'right-0 bottom-16 rotate-[-3deg]', warning: true },
                ].map((item) => (
                  <div
                    key={item.name}
                    className={cn(
                      'absolute w-[250px] rounded-[30px] border border-[#dbe3ee] bg-white/92 p-5 shadow-[0_28px_80px_rgba(15,23,42,0.1)] backdrop-blur',
                      floatingCardHoverClassName,
                      item.className,
                    )}
                  >
                    <div className="flex items-center justify-between gap-3">
                      <p className="text-[11px] font-semibold uppercase tracking-[0.26em] text-slate-400">Material</p>
                      <span className={cn('rounded-full px-3 py-1 text-xs font-semibold', item.warning ? 'bg-[#fff7e8] text-amber-600' : 'bg-[#f0f4f9] text-[#1a2a3a]')}>
                        {item.meta}
                      </span>
                    </div>
                    <p className="mt-7 text-2xl leading-none text-[#111111]" style={editorialSerifStyle}>{item.name}</p>
                    <div className="mt-6 space-y-2">
                      <div className="h-2 rounded-full bg-[#e6eaf0]" />
                      <div className="h-2 w-4/5 rounded-full bg-[#eef2f7]" />
                    </div>
                  </div>
                ))}

                <div className={cn('absolute left-20 bottom-5 w-[330px] rounded-[34px] border border-[#d6deea] bg-[#111318] p-6 text-white shadow-[0_30px_90px_rgba(15,23,42,0.18)]', floatingCardHoverClassName)}>
                  <p className="text-[11px] font-semibold uppercase tracking-[0.34em] text-white/45">Question Gap</p>
                  <p className="mt-5 text-2xl leading-[1.15]" style={editorialSerifStyle}>
                    没有索引，
                    <br />
                    资料就无法回答问题。
                  </p>
                  <div className="mt-6 flex items-center justify-between rounded-full bg-white/10 px-4 py-2 text-xs font-semibold uppercase tracking-[0.2em] text-white/72">
                    <span>Missing Context</span>
                    <span>0%</span>
                  </div>
                </div>
                <div className="absolute bottom-4 right-2 h-36 w-36 rounded-full bg-[#b9c9dd] blur-3xl" />
              </div>
            </ScrollReveal>
          </div>
        </section>

        <section id="solution" className="relative overflow-hidden bg-[#fcfcfd] py-16 sm:py-20 lg:py-24">
          <div className="absolute inset-x-0 top-0 h-28 bg-gradient-to-b from-[#f7f8fb] to-transparent" />
          <div className="absolute -left-24 top-24 h-72 w-72 rounded-full bg-[#dbe6f3] blur-3xl" />
          <SectionAmbientMotion align="left" />
          <div className={cn(shellClassName, 'relative grid min-h-[640px] items-center gap-14 lg:grid-cols-[0.86fr_1.14fr]')}>
            <ScrollReveal>
              <SectionIntro
                eyebrow="Act II / Solution"
                title="每一份资料，都自带一个可追问的智能体。"
                description="系统先读取原文片段，再组织回答；关键结论附带来源位置，追问时保留上下文。"
              />
            </ScrollReveal>

            <ScrollReveal
              className={cn('rounded-[32px] border border-[#e6eaf0] bg-white p-2.5 shadow-[0_24px_80px_rgba(15,23,42,0.08)] backdrop-blur', floatingPreviewHoverClassName)}
              delay={0.12}
            >
              <SolutionPreview />
            </ScrollReveal>
          </div>
        </section>

        <section id="features" className="relative overflow-hidden bg-[#f7f8fb] py-16 sm:py-20 lg:py-24">
          <div className="absolute inset-0 bg-[radial-gradient(circle_at_18%_0%,rgba(255,255,255,0.9),transparent_24%),radial-gradient(circle_at_82%_60%,rgba(207,219,234,0.64),transparent_28%)]" />
          <div className="absolute inset-0 opacity-[0.2] [background-image:linear-gradient(rgba(100,116,139,0.08)_1px,transparent_1px),linear-gradient(90deg,rgba(100,116,139,0.08)_1px,transparent_1px)] [background-size:48px_48px]" />
          <SectionAmbientMotion align="right" />
          <div className={cn(shellClassName, 'relative flex min-h-[640px] flex-col justify-center space-y-12')}>
            <ScrollReveal>
              <SectionIntro
                eyebrow="Act III / Montage"
                title="把资料变成可以连续操作的学习现场。"
                description="不是只给一个答案，而是把上传、检索、追问和复习串成一个连续工作流。"
              />
            </ScrollReveal>

            <div className={cn('grid gap-5 lg:grid-cols-[1.35fr_0.65fr]', floatingPreviewHoverClassName)}>
              <ScrollReveal delay={0.1} className="relative overflow-hidden rounded-[34px] border border-[#dce4ee] bg-white/84 p-6 shadow-[0_26px_80px_rgba(15,23,42,0.08)] backdrop-blur-xl">
                <div className="absolute inset-x-10 top-1/2 h-px bg-gradient-to-r from-transparent via-[#9fb0c7] to-transparent" />
                <div className="grid gap-4 lg:grid-cols-4">
                  {[
                    { title: '上传', text: 'PDF、讲义、笔记进入资料库。', icon: Upload },
                    { title: '索引', text: '自动切片、抽取、建立检索上下文。', icon: BookOpen },
                    { title: '追问', text: '围绕资料连续提问并保留来源。', icon: Search },
                    { title: '复习', text: '沉淀重点、易混概念和复习建议。', icon: Sparkles },
                  ].map((step, index) => {
                    const Icon = step.icon

                    return (
                      <ScrollReveal
                        key={step.title}
                        delay={0.18 + index * 0.1}
                        className={cn('group relative rounded-[28px] border border-[#e6eaf0] bg-[#f7f8fb]/92 p-5 shadow-[0_14px_34px_rgba(15,23,42,0.04)] backdrop-blur', floatingCardHoverClassName)}
                      >
                        <div className="flex items-center justify-between">
                          <span className="text-[11px] font-semibold uppercase tracking-[0.28em] text-slate-400">0{index + 1}</span>
                          <div className="flex h-11 w-11 items-center justify-center rounded-full border border-[#d6deea] bg-white text-[#1a2a3a] transition group-hover:bg-[#1a2a3a] group-hover:text-white">
                            <Icon className="h-5 w-5" />
                          </div>
                        </div>
                        <h3 className="mt-10 text-3xl leading-none text-[#111111]" style={editorialSerifStyle}>{step.title}</h3>
                        <p className="mt-4 text-sm leading-7 text-slate-600">{step.text}</p>
                      </ScrollReveal>
                    )
                  })}
                </div>
              </ScrollReveal>

              <ScrollReveal delay={0.24} className={cn('rounded-[34px] border border-[#dce4ee] bg-[#111318] p-6 text-white shadow-[0_28px_86px_rgba(15,23,42,0.18)]', floatingCardHoverClassName)}>
                <p className="text-[11px] font-semibold uppercase tracking-[0.34em] text-white/45">Workflow State</p>
                <h3 className="mt-6 text-4xl leading-[0.98]" style={editorialSerifStyle}>
                  一条链路，
                  <br />
                  串起所有学习动作。
                </h3>
                <div className="mt-8 space-y-3">
                  {['资料进入索引', '答案绑定来源', '总结回到复习'].map((item, index) => (
                    <div key={item} className="flex items-center justify-between rounded-2xl bg-white/10 px-4 py-3 text-sm text-white/72">
                      <span>{item}</span>
                      <span className="text-xs font-semibold uppercase tracking-[0.18em] text-white/42">0{index + 1}</span>
                    </div>
                  ))}
                </div>
              </ScrollReveal>
            </div>
          </div>
        </section>

        <section id="workspace" className="relative overflow-hidden bg-[#fcfcfd] py-16 sm:py-20 lg:py-24">
          <div className="absolute inset-x-0 top-0 h-28 bg-gradient-to-b from-[#f7f8fb] to-transparent" />
          <SectionAmbientMotion align="center" />
          <div className={cn(shellClassName, 'relative flex min-h-[640px] flex-col justify-center space-y-12')}>
            <ScrollReveal>
              <SectionIntro
                eyebrow="Act IV / Workspace"
                title="一个工作台，完成上传、追问与复习。"
                description="全景视图里同时保留资料入口、来源片段和对话现场，让整个学习过程在一个地方闭环。"
              />
            </ScrollReveal>

            <ScrollReveal
              className={cn('relative rounded-[36px] border border-[#e6eaf0] bg-[#f7f8fb] p-4 shadow-[0_30px_90px_rgba(15,23,42,0.08)] sm:p-6', floatingPreviewHoverClassName)}
              delay={0.12}
            >
              <div className="pointer-events-none absolute left-6 top-6 h-9 w-9 border-l border-t border-[#cbd5e1]" />
              <div className="pointer-events-none absolute right-6 top-6 h-9 w-9 border-r border-t border-[#cbd5e1]" />
              <div className="pointer-events-none absolute bottom-6 left-6 h-9 w-9 border-b border-l border-[#cbd5e1]" />
              <div className="pointer-events-none absolute bottom-6 right-6 h-9 w-9 border-b border-r border-[#cbd5e1]" />

              <div className="overflow-hidden rounded-[28px] border border-[#e6eaf0] bg-white">
                <WorkspacePreview />
              </div>

              {workspaceCallouts.map((callout) => (
                <motion.div
                  key={callout.id}
                  className="absolute hidden min-w-[150px] rounded-full border border-[#d6deea] bg-white/95 px-4 py-2 text-[11px] font-semibold uppercase tracking-[0.2em] text-[#1a2a3a] shadow-[0_12px_28px_rgba(15,23,42,0.08)] lg:block"
                  style={{ top: callout.top, left: callout.left }}
                  variants={floatItemMotion}
                >
                  {callout.id} {callout.title}
                </motion.div>
              ))}
            </ScrollReveal>
          </div>
        </section>

        <section id="cta" className="relative overflow-hidden bg-[#f7f8fb] py-18 sm:py-20 lg:py-24">
          <div className="absolute inset-0 bg-[radial-gradient(circle_at_50%_18%,rgba(255,255,255,0.95),transparent_30%),radial-gradient(circle_at_50%_70%,rgba(198,214,234,0.7),transparent_34%)]" />
          <SectionAmbientMotion align="center" />
          <div className={cn(shellClassName, 'relative flex min-h-[560px] items-center justify-center text-center')}>
            <ScrollReveal>
              <p className="text-[11px] font-semibold uppercase tracking-[0.42em] text-slate-400">Final Frame</p>
              <h2 className="mt-6 text-5xl leading-[0.98] text-[#111111] sm:text-6xl" style={editorialSerifStyle}>
                开始构建
                <br />
                你的知识索引
              </h2>
              <div className="mx-auto mt-10 flex max-w-md flex-col gap-4 sm:flex-row sm:justify-center">
                <Button asChild className="group h-12 rounded-full bg-[#111111] px-8 text-white hover:bg-white hover:text-[#111111] hover:shadow-[inset_0_0_0_1px_rgba(17,17,17,0.18)]">
                  <Link to="/workspace/chat?new=1">
                    开始使用
                    <ArrowRight className="ml-2 h-4 w-4 transition group-hover:translate-x-1" />
                  </Link>
                </Button>
                <Button asChild variant="outline" className="h-12 rounded-full border-black/12 bg-white px-8 text-[#111111] hover:border-[#111111]">
                  <Link to="/login">进入登录</Link>
                </Button>
              </div>
              <div className="mt-14 text-[11px] font-medium uppercase tracking-[0.3em] text-slate-400">
                智学引擎 · Learning Assistant
              </div>
              <p className="mt-3 text-sm text-slate-500">资料驱动的 AI 学习工作台</p>
            </ScrollReveal>
          </div>
        </section>
      </main>

      <SiteBeianFooter />
    </div>
  )
}

/**
 * 方案章节继续使用浅色工作页语言，避免突然切入深色造成品牌断层。
 * 内容重点强调 RAG 的检索、生成、来源回链三个步骤。
 */
function SolutionPreview() {
  return (
    <div className={cn('relative overflow-hidden rounded-[28px] border border-[#e3e6ea] bg-[#f7f8fb] p-3 shadow-[0_30px_90px_rgba(15,23,42,0.1)] sm:p-3.5', floatingPreviewHoverClassName)}>
      <div className="absolute inset-0 bg-[radial-gradient(circle_at_18%_18%,rgba(255,255,255,0.98),transparent_30%),radial-gradient(circle_at_82%_76%,rgba(17,17,17,0.055),transparent_34%),linear-gradient(135deg,#ffffff_0%,#f7f8fb_58%,#f0f2f5_100%)]" />
      <div className="absolute inset-x-8 top-0 h-px bg-gradient-to-r from-transparent via-black/14 to-transparent" />

      <div className="relative z-10 rounded-[24px] border border-[#e3e6ea] bg-white/78 p-4 shadow-[inset_0_1px_0_rgba(255,255,255,0.9)] backdrop-blur-xl sm:p-5">
        <ScrollReveal className="flex flex-wrap items-start justify-between gap-4 border-b border-[#e3e6ea] pb-4">
          <div>
            <p className="text-[11px] font-semibold uppercase tracking-[0.34em] text-slate-400">Focused Answer</p>
            <h3 className="mt-3 text-2xl leading-none text-[#101722] sm:text-[1.7rem]" style={editorialSerifStyle}>
              回答来自资料，而不是凭空生成
            </h3>
          </div>
          <span className="rounded-full border border-black/10 bg-white px-4 py-2 text-xs font-semibold text-[#111111] shadow-[0_8px_18px_rgba(15,23,42,0.04)]">
            RAG Flow
          </span>
        </ScrollReveal>

        <div className="mt-5 grid gap-4 lg:grid-cols-[1.05fr_0.95fr]">
          <ScrollReveal delay={0.1} className={cn('rounded-[24px] border border-[#e3e6ea] bg-white/88 p-4 shadow-[0_22px_58px_rgba(15,23,42,0.06)] sm:p-5', floatingCardHoverClassName)}>
            <div className="rounded-[22px] bg-gradient-to-br from-[#f3f4f6] via-white to-[#eceff3] p-4">
              <p className="text-sm leading-7 text-[#2f3742]">
                监督学习和无监督学习的关键区别在于：前者依赖带标签样本来学习输入到输出的映射，而后者更关注数据结构、聚类关系和潜在分布。
              </p>
            </div>

            <div className="mt-4 flex flex-wrap gap-2">
              {['来源第 12 页', '来源第 19 页', '课堂笔记节选'].map((source) => (
                <span key={source} className="rounded-full border border-black/10 bg-white px-3 py-1 text-xs font-semibold text-[#111111]">
                  {source}
                </span>
              ))}
            </div>

            <div className="mt-5 rounded-[22px] border border-[#e3e6ea] bg-gradient-to-br from-white to-[#f3f4f6] p-4">
              <p className="text-sm leading-7 text-slate-600">
                继续追问时，系统会保持当前资料上下文，并优先检索已命中的原文片段。
              </p>
            </div>
          </ScrollReveal>

          <ScrollReveal delay={0.2} className={cn('rounded-[24px] border border-[#e3e6ea] bg-white/84 p-4 shadow-[0_22px_58px_rgba(15,23,42,0.06)] sm:p-5', floatingCardHoverClassName)}>
            <p className="text-[11px] font-semibold uppercase tracking-[0.34em] text-slate-400">Indexed Fragments</p>
            <div className="mt-4 space-y-3">
              {[
                ['Fragment 01', '标签数据用于建立可泛化的预测函数。'],
                ['Fragment 02', '聚类方法用于发现样本之间的自然分组。'],
                ['Fragment 03', '模型评估需要结合验证集与误差分析。'],
              ].map(([title, text], index) => (
                <div
                  key={title}
                  className={cn(
                    'rounded-[20px] border border-[#e3e6ea] bg-gradient-to-br p-4 shadow-[0_12px_30px_rgba(15,23,42,0.045)]',
                    index === 0 ? 'from-[#f8f9fb] to-white' : index === 1 ? 'from-[#f4f5f7] to-white' : 'from-[#f1f3f5] to-white',
                    floatingCardHoverClassName,
                  )}
                >
                  <p className="text-[11px] font-semibold uppercase tracking-[0.28em] text-slate-400">{title}</p>
                  <p className="mt-3 text-sm leading-6 text-slate-600">{text}</p>
                </div>
              ))}
            </div>
          </ScrollReveal>
        </div>
      </div>
    </div>
  )
}

function WorkspacePreview() {
  return (
    <div className="grid min-h-[620px] gap-0 bg-[#fcfcfd] transition duration-500 hover:bg-white xl:grid-cols-[220px_1fr_280px]">
      <aside className="border-b border-[#e6eaf0] bg-[#f7f8fb] p-5 xl:border-b-0 xl:border-r">
        <div className="flex items-center gap-3">
          <div className="flex h-11 w-11 items-center justify-center rounded-2xl bg-[#111318] text-white">
            <BookOpen className="h-5 w-5" />
          </div>
          <div>
            <p className="text-sm font-semibold text-[#111111]">学习工作台</p>
            <p className="text-xs text-slate-500">Materials · Chat · Summary</p>
          </div>
        </div>

        <div className="mt-8 space-y-2">
          {[
            { label: '资料管理', active: false },
            { label: '智能问答', active: true },
            { label: '边读边问', active: false },
            { label: '知识总结', active: false },
          ].map((item) => (
            <div
              key={item.label}
              className={cn(
                'rounded-2xl px-4 py-3 text-sm',
                item.active ? 'bg-[#eceef2] font-medium text-[#202124]' : 'text-[#667085]'
              )}
            >
              {item.label}
            </div>
          ))}
        </div>

        <div className="mt-8 space-y-3">
          {[
            ['机器学习导论.pdf', '已解析'],
            ['课堂笔记.md', '可追问'],
            ['论文摘录.docx', '处理中'],
          ].map(([name, status]) => (
            <div key={name} className={cn('rounded-[22px] border border-[#e6eaf0] bg-white p-4 shadow-[0_8px_22px_rgba(15,23,42,0.04)]', floatingCardHoverClassName)}>
              <p className="truncate text-sm font-semibold text-[#111111]">{name}</p>
              <p className="mt-2 text-xs text-slate-400">{status}</p>
            </div>
          ))}
        </div>
      </aside>

      <section className="border-b border-[#e6eaf0] bg-white p-5 sm:p-6 xl:border-b-0 xl:border-r">
        <div className="flex flex-wrap items-start justify-between gap-4">
          <div>
            <p className="text-[11px] font-semibold uppercase tracking-[0.28em] text-slate-400">Knowledge Workspace</p>
            <h3 className="mt-3 text-3xl leading-none text-[#111111]" style={editorialSerifStyle}>
              问答与复习并行展开
            </h3>
          </div>
          <div className="rounded-full border border-[#d6deea] bg-[#f7f8fb] px-4 py-2 text-xs font-semibold text-[#667085]">
            当前模式 · 资料问答
          </div>
        </div>

        <div className="mt-8 grid gap-4 lg:grid-cols-[1fr_1fr]">
          <div className={cn('rounded-[24px] border border-[#e6eaf0] bg-[#f7f8fb] p-5', floatingCardHoverClassName)}>
            <div className="flex items-center justify-between">
              <p className="text-sm font-semibold text-[#111111]">提问记录</p>
              <span className="text-xs text-slate-400">Thread 01</span>
            </div>
            <div className="mt-5 space-y-3">
              <div className={cn('ml-auto max-w-[86%] rounded-[22px] border border-transparent bg-[#111318] px-4 py-3 text-sm leading-7 text-white', floatingCardHoverClassName)}>
                模型容量过高时为什么更容易过拟合？
              </div>
              <div className={cn('max-w-[90%] rounded-[22px] border border-[#e6eaf0] bg-white px-4 py-3 text-sm leading-7 text-slate-600', floatingCardHoverClassName)}>
                因为模型表达能力变强后，既能学习真实规律，也更容易记住训练集中的噪声与偶然模式。
              </div>
              <div className={cn('max-w-[84%] rounded-[22px] border border-[#e6eaf0] bg-white px-4 py-3 text-sm leading-7 text-slate-500', floatingCardHoverClassName)}>
                可继续追问正则化、验证集或偏差-方差权衡。
              </div>
            </div>
          </div>

          <div className={cn('rounded-[24px] border border-[#e6eaf0] bg-[#f7f8fb] p-5', floatingCardHoverClassName)}>
            <div className="flex items-center justify-between">
              <p className="text-sm font-semibold text-[#111111]">自动总结</p>
              <span className="text-xs text-slate-400">Summary</span>
            </div>
            <div className="mt-5 space-y-3">
              {[
                ['重点', '模型容量提升会增加表达能力，也提高记忆噪声的风险。'],
                ['易混概念', '过拟合不是“训练效果差”，而是“训练好但泛化差”。'],
                ['复习建议', '把正则化、验证集和偏差-方差一起复习。'],
              ].map(([title, text]) => (
                <div key={title} className={cn('rounded-[20px] border border-[#e6eaf0] bg-white p-4 shadow-[0_8px_20px_rgba(15,23,42,0.04)]', floatingCardHoverClassName)}>
                  <p className="text-xs font-semibold uppercase tracking-[0.22em] text-slate-400">{title}</p>
                  <p className="mt-3 text-sm leading-6 text-slate-600">{text}</p>
                </div>
              ))}
            </div>
          </div>
        </div>

        <div className={cn('mt-4 rounded-[24px] border border-[#d6deea] bg-[#fcfcfd] p-5', floatingCardHoverClassName)}>
          <div className="flex flex-wrap items-center justify-between gap-3">
            <div>
              <p className="text-[11px] font-semibold uppercase tracking-[0.28em] text-slate-400">Context Maintained</p>
              <p className="mt-3 text-sm leading-7 text-slate-500">同一份资料的提问、命中片段与总结结果，会在一个工作台内持续关联。</p>
            </div>
            <Button className="h-10 rounded-full bg-[#111318] px-5 text-white hover:bg-[#1f2937]">继续追问</Button>
          </div>
        </div>
      </section>

      <aside className="bg-[#f7f8fb] p-5">
        <div className={cn('rounded-[24px] border border-[#e6eaf0] bg-white p-4 shadow-[0_12px_30px_rgba(15,23,42,0.04)]', floatingCardHoverClassName)}>
          <div className="flex items-center justify-between">
            <p className="text-sm font-semibold text-[#111111]">来源片段</p>
            <span className="text-xs text-slate-400">Live</span>
          </div>
          <div className="mt-4 space-y-3">
            {[
              ['第 12 页', '标签数据用于建立可泛化的预测函数。'],
              ['第 19 页', '聚类方法常用于发现自然分组。'],
              ['课堂笔记', '验证集帮助判断模型是否过拟合。'],
            ].map(([page, text]) => (
              <div key={page} className={cn('rounded-[18px] border border-[#e6eaf0] bg-[#f7f8fb] p-4', floatingCardHoverClassName)}>
                <p className="text-xs font-semibold uppercase tracking-[0.2em] text-[#1a2a3a]">{page}</p>
                <p className="mt-3 text-sm leading-6 text-slate-600">{text}</p>
              </div>
            ))}
          </div>
        </div>

        <div className={cn('mt-4 rounded-[24px] border border-[#e6eaf0] bg-white p-4 shadow-[0_12px_30px_rgba(15,23,42,0.04)]', floatingCardHoverClassName)}>
          <p className="text-sm font-semibold text-[#111111]">资料状态</p>
          <div className="mt-4 space-y-4">
            {[
              ['文本抽取', '完成'],
              ['切片与索引', '完成'],
              ['来源回链', '可用'],
            ].map(([label, status]) => (
              <div key={label} className="flex items-center justify-between text-sm">
                <span className="text-slate-500">{label}</span>
                <span className="font-semibold text-[#111111]">{status}</span>
              </div>
            ))}
          </div>
        </div>
      </aside>
    </div>
  )
}
