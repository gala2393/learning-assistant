import { useEffect, useMemo, useRef, useState, type CSSProperties } from 'react'
import { Link } from 'react-router-dom'
import {
  ArrowLeft,
  ArrowRight,
  BookOpen,
  BrainCircuit,
  CheckCircle2,
  ChevronRight,
  Clock,
  FileText,
  Layers3,
  MessageSquare,
  Search,
  Sparkles,
  Star,
  Upload,
} from 'lucide-react'

type PreviewSlide = 'chat' | 'materials' | 'summary'

const previewSlides: Array<{ id: PreviewSlide; title: string; desc: string }> = [
  { id: 'chat', title: '智能问答工作台', desc: '围绕资料提问，答案带来源引用' },
  { id: 'materials', title: '资料解析中心', desc: '上传、解析、切片和检索状态一屏掌握' },
  { id: 'summary', title: '知识总结看板', desc: '自动生成重点、易混概念和复习路径' },
]

const features = [
  {
    icon: Upload,
    title: '资料管理',
    text: '上传课程文档、笔记和报告，集中查看解析状态、来源片段和会话上下文。',
  },
  {
    icon: Search,
    title: 'RAG 问答',
    text: '先检索资料片段，再生成答案，保留可追溯来源，减少凭空回答。',
  },
  {
    icon: Sparkles,
    title: '阅读总结',
    text: '把长资料整理成重点、结论、易混概念和复习建议，适合课后快速回顾。',
  },
]

const steps = ['上传资料', 'AI 解析', '提问检索', '生成答案/总结']

const navItems = [
  { icon: MessageSquare, label: '智能问答', active: true },
  { icon: BookOpen, label: '资料管理' },
  { icon: FileText, label: '边读边问' },
  { icon: Clock, label: '历史记录' },
  { icon: Star, label: '我的收藏' },
  { icon: Sparkles, label: '知识总结' },
]

const TRACK_TRANSITION_MS = 1450
const CAROUSEL_INTERVAL_MS = 3500

const landingAnimationCss = `
@keyframes landingFloat {
  0%, 100% { transform: translate3d(0, 0, 0); }
  50% { transform: translate3d(0, -10px, 0); }
}

@keyframes landingDrift {
  0%, 100% { transform: translate3d(-2px, -1px, 0); opacity: .3; }
  50% { transform: translate3d(3px, 2px, 0); opacity: .46; }
}

@keyframes landingSweep {
  0% { transform: translateX(-130%); }
  100% { transform: translateX(130%); }
}

@keyframes landingEdgeFlow {
  0% { transform: translateX(-120%); opacity: 0; }
  18% { opacity: .7; }
  82% { opacity: .7; }
  100% { transform: translateX(120%); opacity: 0; }
}

@keyframes landingBorderFlow {
  0% { background-position: 0% 50%; opacity: .58; }
  50% { background-position: 100% 50%; opacity: .9; }
  100% { background-position: 0% 50%; opacity: .58; }
}

@keyframes landingCornerBreath {
  0%, 100% { opacity: .42; transform: scale(.98); }
  50% { opacity: .86; transform: scale(1.03); }
}

@keyframes landingPulse {
  0%, 100% { opacity: .44; transform: scale(1); }
  50% { opacity: .92; transform: scale(1.06); }
}

@keyframes landingRise {
  from { opacity: 0; transform: translateY(16px); }
  to { opacity: 1; transform: translateY(0); }
}

@keyframes landingMeter {
  0% { transform: translateX(-45%); }
  100% { transform: translateX(45%); }
}

@keyframes landingFrameBreath {
  0%, 100% { box-shadow: 0 34px 90px rgba(34,40,51,.16); transform: translateY(0); }
  50% { box-shadow: 0 42px 110px rgba(34,40,51,.2); transform: translateY(-4px); }
}

@keyframes landingStatusMove {
  0%, 100% { transform: translate3d(0, 0, 0); opacity: .72; }
  50% { transform: translate3d(10px, 0, 0); opacity: 1; }
}

@keyframes landingCardBreathe {
  0%, 100% { transform: translateY(0); }
  50% { transform: translateY(-5px); }
}

@keyframes heroRuleGlow {
  0%, 100% { opacity: .44; transform: translate(-50%, -50%) scaleX(.88); }
  50% { opacity: .82; transform: translate(-50%, -50%) scaleX(1); }
}

@keyframes landingSelectedBreath {
  0%, 100% { box-shadow: 0 0 0 1px rgba(34,40,51,.08), 0 12px 32px rgba(34,40,51,.04); }
  50% { box-shadow: 0 0 0 1px rgba(34,40,51,.22), 0 18px 44px rgba(34,40,51,.1); }
}

@keyframes landingStripeFlow {
  0% { background-position: 0 0; }
  100% { background-position: 42px 0; }
}

@keyframes retrievalNode {
  0%, 100% { background: rgba(255,255,255,.16); transform: scale(.92); }
  35% { background: rgba(255,255,255,.86); transform: scale(1.08); }
}

@keyframes featureGlow {
  0%, 100% { box-shadow: 0 24px 70px rgba(34,40,51,.06); }
  50% { box-shadow: 0 30px 86px rgba(34,40,51,.1); }
}

@keyframes flowTopLine {
  0% { transform: translateX(-120%); opacity: 0; }
  20% { opacity: .72; }
  100% { transform: translateX(120%); opacity: 0; }
}

.landing-page {
  --pointer-x: 50%;
  --pointer-y: 28%;
  --carousel-overlap: 180px;
}

.landing-bg::before {
  content: '';
  position: absolute;
  inset: 0;
  background:
    radial-gradient(circle at var(--pointer-x) var(--pointer-y), rgba(255,255,255,.92), rgba(255,255,255,0) 23rem),
    radial-gradient(circle at 18% 12%, rgba(255,255,255,.98), rgba(255,255,255,0) 28rem),
    radial-gradient(circle at 83% 38%, rgba(100,116,139,.12), rgba(100,116,139,0) 24rem);
}

.landing-bg::after {
  content: '';
  position: absolute;
  inset: 0;
  background-image:
    linear-gradient(rgba(34,40,51,.045) 1px, transparent 1px),
    linear-gradient(90deg, rgba(34,40,51,.045) 1px, transparent 1px);
  background-size: 72px 72px;
  mask-image: radial-gradient(circle at 50% 16%, black, transparent 72%);
  opacity: .32;
}

.landing-motion-canvas {
  mix-blend-mode: multiply;
}

.bg-flow-line {
  position: absolute;
  left: 8%;
  right: 8%;
  height: 1px;
  overflow: hidden;
  opacity: .58;
}

.bg-flow-line::before {
  content: '';
  position: absolute;
  inset: 0;
  background: linear-gradient(90deg, transparent, rgba(255,255,255,.92), rgba(34,40,51,.14), transparent);
  animation: landingEdgeFlow 7s ease-in-out infinite;
}

.bg-flow-line:nth-of-type(2)::before {
  animation-delay: -3.2s;
}

.bg-node {
  animation: landingDrift 12s ease-in-out infinite;
}

.soft-orbit {
  animation: landingDrift 13s ease-in-out infinite;
}

.soft-float {
  animation: landingFloat 5.2s ease-in-out infinite;
}

.rise-in {
  animation: landingRise .72s cubic-bezier(.22, 1, .36, 1) both;
}

.preview-shell {
  position: relative;
  isolation: isolate;
  animation: landingFrameBreath 5.6s ease-in-out infinite;
}

.preview-shell::before {
  content: '';
  position: absolute;
  inset: -1px;
  border-radius: inherit;
  background: linear-gradient(115deg, rgba(255,255,255,.32), rgba(34,40,51,.1), rgba(103,232,249,.14), rgba(255,255,255,.5));
  background-size: 220% 220%;
  animation: landingBorderFlow 5.8s ease-in-out infinite;
  opacity: .7;
  z-index: -1;
}

.preview-shell:hover {
  animation-play-state: paused;
}

.preview-shell::after {
  content: '';
  position: absolute;
  left: 12%;
  right: 12%;
  bottom: -18px;
  height: 1px;
  background: linear-gradient(90deg, transparent, rgba(34,40,51,.32), transparent);
}

.preview-sheen {
  position: relative;
  overflow: hidden;
}

.preview-sheen::after {
  content: '';
  position: absolute;
  left: 8%;
  right: 8%;
  top: 0;
  height: 1px;
  width: auto;
  background: linear-gradient(90deg, transparent, rgba(34,40,51,.22), transparent);
  transform: translateX(-130%);
  animation: landingSweep 4.8s ease-in-out infinite;
  pointer-events: none;
}

.corner-glow {
  position: absolute;
  right: 13px;
  top: 13px;
  z-index: 2;
  height: 42px;
  width: 42px;
  border-right: 2px solid rgba(103,232,249,.38);
  border-top: 2px solid rgba(103,232,249,.38);
  border-radius: 0 12px 0 0;
  animation: landingCornerBreath 2.6s ease-in-out infinite;
  pointer-events: none;
}

.hero-rule {
  position: relative;
  height: 12px;
  width: min(640px, 84vw);
}

.hero-rule::before,
.hero-rule::after {
  content: '';
  position: absolute;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  transform-origin: center center;
  pointer-events: none;
}

.hero-rule::before {
  width: 100%;
  height: 1px;
  background: linear-gradient(90deg, transparent 0%, rgba(34,40,51,.05) 22%, rgba(34,40,51,.18) 50%, rgba(34,40,51,.05) 78%, transparent 100%);
  border-radius: 999px;
  box-shadow: none;
}

.hero-rule::after {
  width: 30%;
  height: 3px;
  border-radius: 999px;
  background:
    radial-gradient(ellipse at center, rgba(34,40,51,.2) 0%, rgba(34,40,51,.1) 34%, rgba(34,40,51,.03) 68%, transparent 82%),
    linear-gradient(90deg, transparent, rgba(255,255,255,.94), rgba(34,40,51,.13), rgba(255,255,255,.94), transparent);
  box-shadow: 0 5px 12px rgba(34,40,51,.035);
  animation: heroRuleGlow 3.6s ease-in-out infinite;
}

.carousel-stage {
  overflow: visible;
}

.carousel-track {
  display: flex;
  height: 100%;
  width: 100%;
  transition: transform 1.45s cubic-bezier(.2, .78, .18, 1);
  will-change: transform;
}

.carousel-track.is-instant {
  transition: none;
}

.carousel-panel {
  position: relative;
  flex: 0 0 100%;
  margin-inline: calc(var(--carousel-overlap) / -2);
  min-width: 0;
  pointer-events: none;
  opacity: .7;
  filter: blur(3.2px);
  transform: scale(.982);
  z-index: 1;
  transition:
    filter 1.12s cubic-bezier(.22, 1, .36, 1),
    opacity 1.12s cubic-bezier(.22, 1, .36, 1),
    transform 1.12s cubic-bezier(.22, 1, .36, 1);
  will-change: filter, opacity, transform;
}

.carousel-panel::after {
  content: '';
  position: absolute;
  inset: 3px;
  border-radius: 16px;
  background: rgba(255,255,255,.2);
  pointer-events: none;
  transition: opacity 1.12s cubic-bezier(.22, 1, .36, 1);
}

.carousel-panel.is-sharp {
  pointer-events: auto;
  opacity: 1;
  filter: blur(0);
  transform: scale(1);
  z-index: 3;
}

.carousel-panel.is-sharp::after {
  opacity: 0;
}

.carousel-panel.is-soft .preview-sheen::after {
  opacity: .36;
}

.feature-card {
  position: relative;
  overflow: hidden;
  animation: featureGlow 5s ease-in-out infinite;
  transition: transform .28s ease, box-shadow .28s ease, border-color .28s ease;
}

.feature-card:hover {
  transform: translateY(-6px);
  border-color: rgba(34,40,51,.2);
  box-shadow: 0 30px 90px rgba(34,40,51,.12);
}

.meter-bar::after {
  content: '';
  position: absolute;
  inset: 0;
  width: 44%;
  border-radius: inherit;
  background:
    linear-gradient(90deg, transparent, rgba(255,255,255,.68), transparent),
    repeating-linear-gradient(115deg, rgba(255,255,255,.22) 0 7px, transparent 7px 14px);
  background-size: 100% 100%, 42px 42px;
  animation: landingMeter 2.2s ease-in-out infinite alternate, landingStripeFlow 1.2s linear infinite;
}

.pulse-node {
  animation: landingPulse 1.7s ease-in-out infinite;
}

.status-line {
  animation: landingStatusMove 2.4s ease-in-out infinite;
}

.live-card {
  animation: landingCardBreathe 3.2s ease-in-out infinite;
}

.selected-breathe {
  animation: landingSelectedBreath 2.5s ease-in-out infinite;
}

.qa-sweep {
  position: relative;
  overflow: hidden;
}

.qa-sweep::after {
  content: '';
  position: absolute;
  inset: 0;
  width: 38%;
  background: linear-gradient(105deg, transparent, rgba(255,255,255,.78), transparent);
  transform: translateX(-130%);
  animation: landingSweep 5.2s ease-in-out infinite;
  pointer-events: none;
}

.retrieval-node {
  animation: retrievalNode 1.6s ease-in-out infinite;
}

.flow-card {
  position: relative;
  overflow: hidden;
}

.flow-card::before {
  content: '';
  position: absolute;
  left: 0;
  top: 0;
  height: 1px;
  width: 100%;
  background: linear-gradient(90deg, transparent, rgba(34,40,51,.34), transparent);
  transform: translateX(-120%);
  animation: flowTopLine 3s ease-in-out infinite;
}

@media (prefers-reduced-motion: reduce) {
  .soft-orbit,
  .soft-float,
  .bg-node,
  .bg-flow-line::before,
  .rise-in,
  .preview-shell::before,
  .preview-sheen::after,
  .corner-glow,
  .meter-bar::after,
  .pulse-node,
  .preview-shell,
  .status-line,
  .live-card,
  .selected-breathe,
  .qa-sweep::after,
  .retrieval-node,
  .feature-card,
  .hero-rule::after,
  .carousel-track,
  .carousel-panel,
  .flow-card::before {
    animation: none !important;
  }

  .carousel-track,
  .carousel-panel {
    transition: none !important;
  }
}
`

export function ProductLandingPage() {
  const [activeSlide, setActiveSlide] = useState(0)
  const [trackIndex, setTrackIndex] = useState(2)
  const [trackTransitionEnabled, setTrackTransitionEnabled] = useState(true)
  const [isTrackAnimating, setIsTrackAnimating] = useState(false)
  const [carouselOverlap, setCarouselOverlap] = useState(180)
  const [pointer, setPointer] = useState({ x: 50, y: 28 })
  const activeSlideRef = useRef(0)
  const trackIndexRef = useRef(2)
  const isTrackAnimatingRef = useRef(false)
  const resetTimerRef = useRef<number | null>(null)

  const carouselItems = useMemo(() => {
    const lastIndex = previewSlides.length - 1
    const beforeLastIndex = previewSlides.length - 2

    return [
      { key: 'clone-before-last', slide: previewSlides[beforeLastIndex], realIndex: beforeLastIndex },
      { key: 'clone-last', slide: previewSlides[lastIndex], realIndex: lastIndex },
      ...previewSlides.map((slide, index) => ({ key: slide.id, slide, realIndex: index })),
      { key: 'clone-first', slide: previewSlides[0], realIndex: 0 },
      { key: 'clone-second', slide: previewSlides[1], realIndex: 1 },
    ]
  }, [])

  const clearCarouselTimers = () => {
    if (resetTimerRef.current) window.clearTimeout(resetTimerRef.current)
  }

  const pageStyle = useMemo(
    () => ({
      '--pointer-x': `${pointer.x}%`,
      '--pointer-y': `${pointer.y}%`,
      '--carousel-overlap': `${carouselOverlap}px`,
    }) as CSSProperties,
    [carouselOverlap, pointer],
  )

  const startSlideChange = (nextSlide: number, nextTrackIndex: number) => {
    if (isTrackAnimatingRef.current) return

    clearCarouselTimers()
    activeSlideRef.current = nextSlide
    trackIndexRef.current = nextTrackIndex
    isTrackAnimatingRef.current = true
    setTrackTransitionEnabled(true)
    setIsTrackAnimating(true)
    setActiveSlide(nextSlide)
    setTrackIndex(nextTrackIndex)

    resetTimerRef.current = window.setTimeout(() => {
      if (nextTrackIndex <= 1 || nextTrackIndex >= previewSlides.length + 2) {
        const realTrackIndex = nextSlide + 2
        setTrackTransitionEnabled(false)
        trackIndexRef.current = realTrackIndex
        setTrackIndex(realTrackIndex)
        window.requestAnimationFrame(() => {
          window.requestAnimationFrame(() => setTrackTransitionEnabled(true))
        })
      }
      isTrackAnimatingRef.current = false
      setIsTrackAnimating(false)
    }, TRACK_TRANSITION_MS)
  }

  const moveSlide = (direction: 1 | -1) => {
    const currentSlide = activeSlideRef.current
    const currentTrackIndex = trackIndexRef.current
    const nextSlide = (currentSlide + direction + previewSlides.length) % previewSlides.length
    startSlideChange(nextSlide, currentTrackIndex + direction)
  }

  const selectSlide = (index: number) => {
    if (index === activeSlide) return

    const lastIndex = previewSlides.length - 1
    let nextTrackIndex = index + 2
    if (activeSlide === 0 && index === lastIndex) nextTrackIndex = 1
    if (activeSlide === lastIndex && index === 0) nextTrackIndex = previewSlides.length + 2

    startSlideChange(index, nextTrackIndex)
  }

  useEffect(() => {
    const timer = window.setInterval(() => {
      moveSlide(1)
    }, CAROUSEL_INTERVAL_MS)

    return () => window.clearInterval(timer)
  }, [])

  useEffect(() => {
    const updateOverlap = () => {
      setCarouselOverlap(Math.round(Math.min(220, Math.max(72, window.innerWidth * 0.16))))
    }

    updateOverlap()
    window.addEventListener('resize', updateOverlap)

    return () => window.removeEventListener('resize', updateOverlap)
  }, [])

  useEffect(() => {
    return () => clearCarouselTimers()
  }, [])

  return (
    <main
      className="landing-page min-h-screen overflow-hidden bg-[#eef3f7] text-[#222833]"
      style={pageStyle}
      onPointerMove={(event) => {
        const nextX = (event.clientX / window.innerWidth) * 100
        const nextY = (event.clientY / window.innerHeight) * 100
        setPointer({ x: nextX, y: nextY })
      }}
    >
      <style>{landingAnimationCss}</style>

      <div className="landing-bg pointer-events-none fixed inset-0">
        <LandingMotionCanvas />
        <div className="bg-flow-line top-[57%]" />
        <div className="bg-flow-line top-[76%]" />
        <div className="bg-node absolute left-[9%] top-[61%] h-16 w-16 rounded-full border border-white/55 bg-white/10 shadow-[inset_0_1px_0_rgba(255,255,255,0.55)]" />
        <div className="bg-node absolute left-[18%] top-[16%] h-1.5 w-1.5 rounded-full bg-slate-400/25 [animation-delay:-2s]" />
        <div className="bg-node absolute right-[20%] top-[68%] h-2 w-2 rounded-full bg-slate-500/20 [animation-delay:-7s]" />
        <div className="absolute inset-x-0 top-0 h-44 bg-gradient-to-b from-white/75 to-transparent" />
      </div>

      <div className="relative z-10 mx-auto flex min-h-screen w-full max-w-7xl flex-col px-5 py-6 sm:px-8 lg:px-10">
        <header className="rise-in flex items-center justify-between gap-4">
          <Link to="/" className="flex min-w-0 items-center gap-3">
            <div className="grid h-10 w-10 shrink-0 place-items-center rounded-2xl border border-white bg-white shadow-[0_18px_45px_rgba(34,40,51,0.10)]">
              <BrainCircuit className="h-5 w-5 text-[#222833]" />
            </div>
            <div className="min-w-0">
              <div className="truncate text-base font-black tracking-normal">智学引擎</div>
              <div className="hidden text-xs text-slate-500 sm:block">Learning Assistant</div>
            </div>
          </Link>

          <nav className="hidden rounded-full border border-white bg-white/82 p-1 text-sm font-bold text-slate-500 shadow-[0_18px_60px_rgba(34,40,51,0.08)] backdrop-blur md:flex">
            <a href="#preview" className="rounded-full bg-[#222833] px-5 py-2 text-white">首页</a>
            <a href="#features" className="rounded-full px-5 py-2 transition hover:text-[#222833]">功能</a>
            <a href="#flow" className="rounded-full px-5 py-2 transition hover:text-[#222833]">流程</a>
          </nav>

          <div className="flex shrink-0 items-center gap-2">
            <Link
              to="/login"
              className="inline-flex h-10 items-center justify-center rounded-full border border-white bg-white/82 px-5 text-sm font-bold text-[#222833] shadow-sm transition hover:bg-white"
            >
              登录
            </Link>
            <Link
              to="/workspace/chat?new=1"
              className="inline-flex h-10 items-center justify-center rounded-full bg-[#222833] px-5 text-sm font-bold text-white shadow-[0_16px_38px_rgba(34,40,51,0.18)] transition hover:bg-[#111827]"
            >
              开始使用
            </Link>
          </div>
        </header>

        <section className="flex flex-1 flex-col items-center pb-10 pt-9 text-center sm:pt-12">
          <div className="rise-in inline-flex items-center gap-2 rounded-full border border-white bg-white/82 px-4 py-2 text-xs font-black text-slate-600 shadow-[0_18px_50px_rgba(34,40,51,0.08)] [animation-delay:.06s]">
            <span className="relative flex h-2 w-2">
              <span className="pulse-node absolute inline-flex h-full w-full rounded-full bg-slate-500 opacity-40" />
              <span className="relative inline-flex h-2 w-2 rounded-full bg-slate-700" />
            </span>
            资料驱动的 AI 学习工作台
          </div>

          <h1 className="rise-in mt-6 max-w-5xl text-4xl font-black leading-[1.08] tracking-normal text-[#1f2933] sm:text-6xl lg:text-7xl [animation-delay:.12s]">
            <span className="hidden sm:inline">让每份资料都变成</span>
            <span className="block sm:hidden">让资料变成</span>
            <span className="block text-slate-500">可追问的学习助手</span>
          </h1>

          <div className="hero-rule rise-in mt-5 [animation-delay:.15s]" />

          <p className="rise-in mt-4 max-w-2xl text-base leading-8 text-slate-500 sm:text-lg [animation-delay:.18s]">
            上传文档、切换资料问答、查看来源引用和自动总结。首页预览直接模拟项目工作台，展示用户真正会用到的学习流程。
          </p>

          <div className="rise-in mt-6 flex w-full flex-col items-center justify-center gap-3 sm:flex-row [animation-delay:.24s]">
            <Link
              to="/workspace/chat?new=1"
              className="inline-flex h-12 w-full max-w-[220px] items-center justify-center gap-2 rounded-full bg-[#222833] px-7 text-sm font-black text-white shadow-[0_20px_50px_rgba(34,40,51,0.22)] transition hover:-translate-y-0.5 hover:bg-[#111827]"
            >
              开始使用
              <ArrowRight className="h-4 w-4" />
            </Link>
            <Link
              to="/login"
              className="inline-flex h-12 w-full max-w-[220px] items-center justify-center rounded-full border border-white bg-white/82 px-7 text-sm font-black text-[#222833] shadow-sm transition hover:-translate-y-0.5 hover:bg-white"
            >
              进入登录
            </Link>
          </div>

          <section id="preview" className="rise-in mt-7 w-full max-w-6xl text-left sm:mt-8 [animation-delay:.3s]">
            <div className="preview-shell rounded-[20px] bg-white p-3 shadow-[0_34px_90px_rgba(34,40,51,0.16)]">
              <span className="corner-glow" aria-hidden="true" />
              <div className="relative overflow-visible">
                <div className="carousel-stage relative min-h-[540px] rounded-[16px] bg-[#f8fafc] sm:min-h-[580px] lg:min-h-[590px]">
                  <div
                    className={`carousel-track ${trackTransitionEnabled ? '' : 'is-instant'}`}
                    style={{
                      transform: `translate3d(calc(${carouselOverlap / 2}px - ${trackIndex * 100}% + ${trackIndex * carouselOverlap}px), 0, 0)`,
                    }}
                  >
                    {carouselItems.map(({ key, slide, realIndex }, itemIndex) => {
                      const panelState = trackIndex === itemIndex ? 'is-sharp' : 'is-soft'

                      return (
                        <div
                          key={key}
                          className={`carousel-panel ${panelState}`}
                          aria-hidden={realIndex !== activeSlide}
                        >
                          <PreviewFrame title={slide.title} desc={slide.desc}>
                            {slide.id === 'chat' && <ChatPreview />}
                            {slide.id === 'materials' && <MaterialsPreview />}
                            {slide.id === 'summary' && <SummaryPreview />}
                          </PreviewFrame>
                        </div>
                      )
                    })}
                  </div>
                </div>

                <button
                  type="button"
                  className="absolute left-3 top-1/2 z-20 grid h-10 w-10 -translate-y-1/2 place-items-center rounded-full border border-white/80 bg-white/65 text-[#222833] shadow-[0_18px_45px_rgba(34,40,51,0.13)] backdrop-blur transition hover:bg-white sm:left-5"
                  aria-label="上一张"
                  onClick={() => moveSlide(-1)}
                >
                  <ArrowLeft className="h-4 w-4" />
                </button>
                <button
                  type="button"
                  className="absolute right-3 top-1/2 z-20 grid h-10 w-10 -translate-y-1/2 place-items-center rounded-full border border-white/80 bg-white/65 text-[#222833] shadow-[0_18px_45px_rgba(34,40,51,0.13)] backdrop-blur transition hover:bg-white sm:right-5"
                  aria-label="下一张"
                  onClick={() => moveSlide(1)}
                >
                  <ArrowRight className="h-4 w-4" />
                </button>
              </div>

              <div className="mt-4 flex items-center justify-center gap-2">
                {previewSlides.map((slide, index) => (
                  <button
                    key={slide.id}
                    type="button"
                    className={`h-1.5 rounded-full transition-all ${index === activeSlide ? 'w-8 bg-[#222833]' : 'w-3 bg-slate-300 hover:bg-slate-400'}`}
                    aria-label={`切换到${slide.title}`}
                    onClick={() => selectSlide(index)}
                  />
                ))}
              </div>
            </div>
          </section>
        </section>
      </div>

      <section id="features" className="relative z-10 border-y border-white/80 bg-white px-5 py-14 sm:px-8 lg:px-10">
        <div className="mx-auto grid max-w-7xl gap-4 md:grid-cols-3">
          {features.map((feature, index) => {
            const Icon = feature.icon
            return (
              <article
                key={feature.title}
                className="feature-card rounded-lg border border-slate-200 bg-white p-6 shadow-[0_24px_70px_rgba(34,40,51,0.08)]"
                style={{ animationDelay: `${index * 80}ms` }}
              >
                <div className="grid h-11 w-11 place-items-center rounded-2xl bg-[#222833] text-white shadow-[0_16px_34px_rgba(34,40,51,0.16)]">
                  <Icon className="h-5 w-5" />
                </div>
                <h2 className="mt-5 text-xl font-black text-[#222833]">{feature.title}</h2>
                <p className="mt-3 text-sm leading-7 text-slate-500">{feature.text}</p>
                <div className="mt-6 grid grid-cols-5 gap-1">
                  {Array.from({ length: 10 }).map((_, cellIndex) => (
                    <span
                      key={cellIndex}
                      className={`h-1.5 rounded-full ${cellIndex <= 5 + index ? 'bg-slate-300' : 'bg-slate-100'}`}
                    />
                  ))}
                </div>
              </article>
            )
          })}
        </div>
      </section>

      <section id="flow" className="relative z-10 bg-[#f7f9fb] px-5 py-14 sm:px-8 lg:px-10">
        <div className="mx-auto max-w-7xl">
          <div className="mb-7 flex flex-col justify-between gap-3 sm:flex-row sm:items-end">
            <div>
              <p className="text-sm font-black text-slate-600">学习流程</p>
              <h2 className="mt-2 text-3xl font-black tracking-normal text-[#222833]">从资料到答案，路径清晰</h2>
            </div>
            <Link to="/workspace/chat?new=1" className="inline-flex w-fit items-center gap-2 rounded-full bg-[#222833] px-5 py-3 text-sm font-black text-white">
              立即体验 <ChevronRight className="h-4 w-4" />
            </Link>
          </div>
          <div className="grid gap-3 md:grid-cols-4">
            {steps.map((item, index) => (
              <div key={item} className="feature-card flow-card rounded-lg border border-slate-200 bg-white p-5 shadow-sm">
                <div className="mb-4 grid h-9 w-9 place-items-center rounded-full bg-slate-100 text-sm font-black text-[#222833]">
                  {index + 1}
                </div>
                <h3 className="text-lg font-black">{item}</h3>
                <p className="mt-3 text-sm leading-6 text-slate-500">
                  <CheckCircle2 className="mr-2 inline h-4 w-4 text-slate-500" />
                  保留上下文、来源和可复习结构。
                </p>
              </div>
            ))}
          </div>
        </div>
      </section>
    </main>
  )
}

function LandingMotionCanvas() {
  const canvasRef = useRef<HTMLCanvasElement | null>(null)

  useEffect(() => {
    const canvas = canvasRef.current
    if (!canvas) return

    const ctx = canvas.getContext('2d')
    if (!ctx) return

    type Particle = {
      x: number
      y: number
      vx: number
      vy: number
      r: number
      alpha: number
    }

    const particles: Particle[] = []
    const pointer = { x: window.innerWidth * 0.5, y: window.innerHeight * 0.28 }
    let frame = 0
    let tick = 0

    const resize = () => {
      const ratio = window.devicePixelRatio || 1
      const width = window.innerWidth
      const height = window.innerHeight
      canvas.width = Math.floor(width * ratio)
      canvas.height = Math.floor(height * ratio)
      canvas.style.width = `${width}px`
      canvas.style.height = `${height}px`
      ctx.setTransform(ratio, 0, 0, ratio, 0, 0)

      particles.length = 0
      const amount = Math.min(42, Math.floor((width * height) / 36000))
      for (let i = 0; i < amount; i += 1) {
        particles.push({
          x: Math.random() * width,
          y: Math.random() * height,
          vx: (Math.random() - 0.5) * 0.18,
          vy: (Math.random() - 0.5) * 0.18,
          r: 0.7 + Math.random() * 1.2,
          alpha: 0.08 + Math.random() * 0.12,
        })
      }
    }

    const handlePointerMove = (event: PointerEvent) => {
      pointer.x = event.clientX
      pointer.y = event.clientY
    }

    const drawFlowLines = (width: number, height: number) => {
      for (let line = 0; line < 4; line += 1) {
        ctx.beginPath()
        const baseY = height * (0.2 + line * 0.18)
        for (let x = -90; x <= width + 90; x += 26) {
          const y =
            baseY +
            Math.sin((x + tick * (0.45 + line * 0.08)) / 165 + line) * 12 +
            Math.cos((x - tick * 0.22) / 300) * 8

          if (x === -90) ctx.moveTo(x, y)
          else ctx.lineTo(x, y)
        }
        ctx.strokeStyle = `rgba(34,40,51,${0.035 + line * 0.008})`
        ctx.lineWidth = 1
        ctx.stroke()
      }
    }

    const draw = () => {
      const width = window.innerWidth
      const height = window.innerHeight
      tick += 1
      ctx.clearRect(0, 0, width, height)

      const glow = ctx.createRadialGradient(pointer.x, pointer.y, 0, pointer.x, pointer.y, 330)
      glow.addColorStop(0, 'rgba(255,255,255,0.7)')
      glow.addColorStop(0.48, 'rgba(34,40,51,0.055)')
      glow.addColorStop(1, 'rgba(255,255,255,0)')
      ctx.fillStyle = glow
      ctx.fillRect(0, 0, width, height)

      for (const particle of particles) {
        const dx = pointer.x - particle.x
        const dy = pointer.y - particle.y
        const distance = Math.sqrt(dx * dx + dy * dy)
        if (distance < 150) {
          const pull = (150 - distance) / 150
          particle.vx += (dx / Math.max(distance, 1)) * pull * 0.004
          particle.vy += (dy / Math.max(distance, 1)) * pull * 0.004
        }

        particle.x += particle.vx
        particle.y += particle.vy
        particle.vx *= 0.996
        particle.vy *= 0.996

        if (particle.x < -12) particle.x = width + 12
        if (particle.x > width + 12) particle.x = -12
        if (particle.y < -12) particle.y = height + 12
        if (particle.y > height + 12) particle.y = -12

        ctx.beginPath()
        ctx.arc(particle.x, particle.y, particle.r, 0, Math.PI * 2)
        ctx.fillStyle = `rgba(34,40,51,${particle.alpha})`
        ctx.fill()
      }

      frame = requestAnimationFrame(draw)
    }

    resize()
    draw()
    window.addEventListener('resize', resize)
    window.addEventListener('pointermove', handlePointerMove)

    return () => {
      cancelAnimationFrame(frame)
      window.removeEventListener('resize', resize)
      window.removeEventListener('pointermove', handlePointerMove)
    }
  }, [])

  return <canvas ref={canvasRef} className="landing-motion-canvas absolute inset-0 h-full w-full opacity-55" aria-hidden="true" />
}

function PreviewFrame({ title, desc, children }: { title: string; desc: string; children: React.ReactNode }) {
  return (
    <div className="h-full p-3 sm:p-4">
      <div className="preview-sheen flex h-full flex-col overflow-hidden rounded-[14px] border border-slate-200 bg-white shadow-[inset_0_1px_0_rgba(255,255,255,.8)]">
        <div className="flex items-center justify-between border-b border-slate-200 px-4 py-3 sm:px-5">
          <div className="flex items-center gap-2">
            <span className="h-3 w-3 rounded-full bg-slate-300" />
            <span className="h-3 w-3 rounded-full bg-slate-400" />
            <span className="h-3 w-3 rounded-full bg-slate-500" />
          </div>
          <div className="text-right">
            <div className="text-sm font-black text-[#222833]">{title}</div>
            <div className="hidden text-xs font-bold text-slate-500 sm:block">{desc}</div>
          </div>
        </div>
        {children}
      </div>
    </div>
  )
}

function ChatPreview() {
  return (
    <div className="grid min-h-0 flex-1 gap-0 lg:grid-cols-[240px_1fr_270px]">
      <aside className="hidden border-r border-slate-200 bg-[#222833] p-5 text-white lg:block">
        <div className="mb-5 flex items-center gap-3">
          <div className="grid h-10 w-10 place-items-center rounded-2xl bg-white/12">
            <BrainCircuit className="h-5 w-5" />
          </div>
          <div>
            <p className="text-sm font-black">智学引擎</p>
            <p className="text-xs text-slate-300">资料问答空间</p>
          </div>
        </div>
        <div className="space-y-2">
          {navItems.map((item) => {
            const Icon = item.icon
            return (
              <div key={item.label} className={`flex items-center gap-3 rounded-xl px-3 py-2.5 text-sm font-bold ${item.active ? 'selected-breathe bg-white text-[#222833]' : 'text-slate-300'}`}>
                <Icon className="h-4 w-4" />
                <span>{item.label}</span>
              </div>
            )
          })}
        </div>
      </aside>

      <section className="flex min-w-0 flex-col bg-[#f8fafc] p-4 sm:p-5">
        <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
          <div>
            <h2 className="text-xl font-black text-[#222833] sm:text-2xl">智能问答</h2>
            <p className="text-sm font-bold text-slate-500">当前资料：机器学习导论.pdf</p>
          </div>
          <div className="flex flex-wrap items-center justify-end gap-2">
            <div className="rounded-full bg-white p-1 text-xs font-black text-slate-500 shadow-sm">
              <span className="px-3 py-1">智能</span>
              <span className="selected-breathe inline-flex rounded-full bg-[#222833] px-3 py-1 text-white">资料</span>
            </div>
            <span className="status-line rounded-full bg-white px-4 py-2 text-xs font-black text-slate-600 shadow-sm">3 个来源已命中</span>
          </div>
        </div>

        <div className="space-y-3">
          <div className="live-card ml-auto max-w-[78%] rounded-2xl bg-[#222833] px-4 py-3 text-sm font-bold leading-6 text-white">
            监督学习和无监督学习的核心区别是什么？
          </div>
          <div className="qa-sweep max-w-[88%] rounded-2xl border border-slate-200 bg-white px-4 py-3 text-sm leading-6 text-slate-600 shadow-sm">
            监督学习依赖带标签样本，目标是学习输入到输出的映射；无监督学习没有明确标签，更关注数据结构、聚类关系和潜在分布。
            <div className="mt-3 flex flex-wrap gap-2">
              {['第 12 页', '第 19 页', '课堂笔记'].map((source) => (
                <span key={source} className="rounded-full bg-slate-100 px-3 py-1 text-xs font-black text-slate-600">
                  来源 {source}
                </span>
              ))}
            </div>
          </div>
          <div className="max-w-[84%] rounded-2xl border border-slate-200 bg-white px-4 py-3 text-sm leading-6 text-slate-600 shadow-sm">
            可以继续追问模型评估、过拟合或正则化之间的关系。
          </div>
        </div>

        <div className="mt-auto pt-4">
          <div className="rounded-2xl border border-slate-200 bg-white p-3 shadow-sm">
            <div className="flex items-center gap-3">
              <div className="h-9 flex-1 rounded-xl bg-slate-100 px-4 py-2 text-sm font-bold text-slate-400">
                继续针对这份资料提问...
              </div>
              <div className="grid h-9 w-9 place-items-center rounded-xl bg-[#222833] text-white">
                <ArrowRight className="h-4 w-4" />
              </div>
            </div>
          </div>
        </div>
      </section>

      <aside className="hidden border-l border-slate-200 bg-white p-5 lg:block">
        <h3 className="text-sm font-black text-[#222833]">来源片段</h3>
        <div className="mt-4 space-y-3">
          {[
            ['机器学习导论.pdf', '标签数据用于建立可泛化的预测函数。', '92%'],
            ['课堂笔记.md', '聚类方法常用于发现样本之间的自然分组。', '86%'],
            ['复习提纲.docx', '模型效果需要结合验证集和误差分析判断。', '79%'],
          ].map(([name, text, score], index) => (
            <div key={name} className="live-card rounded-xl border border-slate-200 bg-[#f8fafc] p-3" style={{ animationDelay: `${index * 140}ms` }}>
              <div className="flex items-center justify-between gap-2">
                <p className="truncate text-xs font-black text-[#222833]">{name}</p>
                <span className="text-xs font-black text-slate-500">{score}</span>
              </div>
              <p className="mt-2 text-xs leading-5 text-slate-500">{text}</p>
            </div>
          ))}
        </div>
      </aside>
    </div>
  )
}

function MaterialsPreview() {
  const rows = [
    ['深度学习笔记.pdf', '已解析', 94],
    ['数据结构复习.docx', '切片完成', 82],
    ['论文阅读记录.md', '索引中', 68],
  ]

  return (
    <div className="grid min-h-0 flex-1 gap-4 bg-[#f8fafc] p-4 sm:p-5 lg:grid-cols-[.96fr_1.04fr]">
      <section className="rounded-xl border border-slate-200 bg-white p-5 shadow-sm">
        <div className="flex items-center justify-between gap-3">
          <div>
            <h2 className="text-xl font-black text-[#222833] sm:text-2xl">资料解析中心</h2>
            <p className="mt-1 text-sm font-bold text-slate-500">从上传到检索索引，状态透明可见</p>
          </div>
          <div className="grid h-11 w-11 place-items-center rounded-2xl bg-[#222833] text-white">
            <Layers3 className="h-5 w-5" />
          </div>
        </div>

        <div className="live-card mt-5 rounded-2xl border border-dashed border-slate-300 bg-slate-50 p-5">
          <div className="flex items-center gap-4">
            <div className="grid h-12 w-12 place-items-center rounded-2xl bg-white text-[#222833] shadow-sm">
              <Upload className="h-5 w-5" />
            </div>
            <div>
              <p className="text-sm font-black text-[#222833]">拖入资料或点击上传</p>
              <p className="mt-1 text-xs font-bold text-slate-500">支持 PDF、Word、Markdown、课堂笔记</p>
            </div>
          </div>
        </div>

        <div className="mt-5 space-y-3">
          {rows.map(([name, status, percent], index) => (
            <div key={name} className={`live-card rounded-xl border border-slate-200 bg-white p-4 shadow-sm ${index === 0 ? 'selected-breathe' : ''}`} style={{ animationDelay: `${index * 120}ms` }}>
              <div className="flex items-center justify-between gap-3">
                <div className="min-w-0">
                  <p className="truncate text-sm font-black text-[#222833]">{name}</p>
                  <p className="mt-1 text-xs font-bold text-slate-500">{status}</p>
                </div>
                <span className="text-xs font-black text-slate-500">{percent}%</span>
              </div>
              <div className="relative mt-3 h-2 overflow-hidden rounded-full bg-slate-100">
                <div className="meter-bar relative h-full overflow-hidden rounded-full bg-slate-700" style={{ width: `${percent}%` }} />
              </div>
            </div>
          ))}
        </div>
      </section>

      <section className="rounded-xl border border-slate-200 bg-white p-5 shadow-sm">
        <div className="mb-5 flex items-center justify-between">
          <div>
            <h3 className="text-lg font-black text-[#222833]">解析管线</h3>
            <p className="mt-1 text-sm font-bold text-slate-500">切片、向量化、索引和问答上下文</p>
          </div>
          <span className="rounded-full bg-slate-100 px-3 py-1 text-xs font-black text-slate-600">实时</span>
        </div>
        <div className="grid grid-cols-2 gap-3">
          {[
            ['文本提取', '32 页内容已读取'],
            ['语义切片', '156 个片段'],
            ['向量索引', '检索可用'],
            ['来源回链', '答案可追溯'],
          ].map(([title, text], index) => (
            <div key={title} className="live-card rounded-xl bg-[#f8fafc] p-4" style={{ animationDelay: `${index * 110}ms` }}>
              <div className="grid h-8 w-8 place-items-center rounded-full bg-white text-xs font-black text-[#222833] shadow-sm">
                {index + 1}
              </div>
              <h4 className="mt-4 text-sm font-black text-[#222833]">{title}</h4>
              <p className="mt-2 text-xs leading-5 text-slate-500">{text}</p>
            </div>
          ))}
        </div>
        <div className="mt-4 rounded-xl bg-[#222833] p-4 text-white">
          <div className="mb-3 flex items-center gap-2">
            {[0, 1, 2, 3].map((item) => (
              <span
                key={item}
                className="retrieval-node h-2 w-2 rounded-full bg-white/20"
                style={{ animationDelay: `${item * 180}ms` }}
              />
            ))}
          </div>
          <p className="text-sm font-black">下一步建议</p>
          <p className="mt-2 text-sm leading-6 text-slate-300">围绕“泛化能力”和“模型评估”发起追问，系统会优先检索刚解析完成的资料。</p>
        </div>
      </section>
    </div>
  )
}

function SummaryPreview() {
  return (
    <div className="grid min-h-0 flex-1 gap-4 bg-[#f8fafc] p-4 sm:p-5 lg:grid-cols-[0.92fr_1.08fr]">
      <aside className="rounded-xl bg-[#222833] p-5 text-white">
        <p className="text-sm font-black text-slate-300">知识总结看板</p>
        <h2 className="mt-4 text-2xl font-black leading-tight">从长资料里生成可复习的结构</h2>
        {['重点结论', '易混概念', '复习建议'].map((item, index) => (
          <div key={item} className="live-card mt-4 rounded-xl bg-white/10 p-4" style={{ animationDelay: `${index * 130}ms` }}>
            <div className="flex items-center justify-between text-sm font-black">
              <span>{item}</span>
              <span className="text-slate-300">0{index + 1}</span>
            </div>
            <div className="mt-3 h-2 overflow-hidden rounded-full bg-white/15">
              <div className="h-full rounded-full bg-white/70" style={{ width: `${82 - index * 12}%` }} />
            </div>
          </div>
        ))}
      </aside>

      <section className="rounded-xl border border-slate-200 bg-white p-5">
        <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
          <div>
            <h2 className="text-xl font-black text-[#222833] sm:text-2xl">复习路径</h2>
            <p className="text-sm font-bold text-slate-500">按理解、对比、巩固三步生成</p>
          </div>
          <span className="rounded-full bg-slate-100 px-4 py-2 text-sm font-black text-slate-600">自动更新</span>
        </div>
        <div className="grid gap-3 sm:grid-cols-3">
          {['理解', '对比', '巩固'].map((item, index) => (
            <div key={item} className="live-card rounded-xl border border-slate-200 bg-[#f8fafc] p-5 shadow-sm" style={{ animationDelay: `${index * 120}ms` }}>
              <div className="grid h-10 w-10 place-items-center rounded-full bg-white text-sm font-black shadow-sm">{index + 1}</div>
              <h3 className="mt-5 text-lg font-black text-[#222833]">{item}</h3>
              <p className="mt-2 text-sm leading-6 text-slate-500">
                {index === 0 ? '先读定义和结论' : index === 1 ? '区分相近概念边界' : '用追问检查薄弱点'}
              </p>
            </div>
          ))}
        </div>
        <div className="mt-4 rounded-xl bg-[#f8fafc] p-4 shadow-sm">
          <h3 className="text-lg font-black text-[#222833]">自动总结摘录</h3>
          <div className="mt-3 space-y-3 text-sm font-bold text-slate-600">
            <p className="rounded-lg bg-white p-3">模型容量过高时更容易过拟合，需要正则化或更多数据。</p>
            <p className="rounded-lg bg-white p-3">监督学习关注输入输出映射，无监督学习关注数据结构。</p>
          </div>
        </div>
      </section>
    </div>
  )
}
