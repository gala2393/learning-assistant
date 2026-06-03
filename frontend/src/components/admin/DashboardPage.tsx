import { motion } from 'framer-motion'
import { useAdminStats } from '@/api/admin'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import {
  Activity,
  BarChart3,
  BookOpen,
  LayoutDashboard,
  MessageSquare,
  ScrollText,
  ShieldCheck,
  Star,
  Users,
} from 'lucide-react'
import type { AdminStats } from '@/types'

const statCards = [
  { key: 'userCount', label: '用户', icon: Users, color: '#2563eb', bg: 'bg-blue-50 text-blue-700 dark:bg-blue-950/40 dark:text-blue-300' },
  { key: 'materialCount', label: '资料', icon: BookOpen, color: '#0f766e', bg: 'bg-teal-50 text-teal-700 dark:bg-teal-950/40 dark:text-teal-300' },
  { key: 'questionCount', label: '问答', icon: MessageSquare, color: '#7c3aed', bg: 'bg-violet-50 text-violet-700 dark:bg-violet-950/40 dark:text-violet-300' },
  { key: 'favoriteCount', label: '收藏', icon: Star, color: '#d97706', bg: 'bg-amber-50 text-amber-700 dark:bg-amber-950/40 dark:text-amber-300' },
  { key: 'logCount', label: '日志', icon: ScrollText, color: '#dc2626', bg: 'bg-red-50 text-red-700 dark:bg-red-950/40 dark:text-red-300' },
] as const

export function DashboardPage() {
  const { data: stats, isLoading } = useAdminStats()
  const maxValue = Math.max(...statCards.map((card) => getStat(stats, card.key)), 1)
  const totalActivity = getStat(stats, 'questionCount') + getStat(stats, 'favoriteCount') + getStat(stats, 'logCount')
  const materialCoverage = ratio(getStat(stats, 'materialCount'), getStat(stats, 'userCount'))
  const favoriteRate = ratio(getStat(stats, 'favoriteCount'), getStat(stats, 'questionCount'))

  return (
    <motion.div
      className="space-y-5 p-3 md:p-6"
      initial={{ opacity: 0, y: 12 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.3 }}
    >
      <section className="rounded-xl border border-slate-200 bg-gradient-to-r from-white to-slate-50 p-4 dark:border-slate-800 dark:from-[#171a21] dark:to-[#111318]">
        <div className="flex flex-col gap-3 md:flex-row md:items-center md:justify-between">
          <div>
            <h2 className="flex items-center gap-2 text-lg font-semibold">
              <LayoutDashboard className="h-5 w-5" /> 管理员总览
            </h2>
            <p className="mt-2 text-sm text-muted-foreground">
              以图表方式查看系统规模、资料沉淀、问答活跃度和后台审计量。
            </p>
          </div>
          <div className="flex items-center gap-2 rounded-lg border bg-white px-3 py-2 text-xs text-muted-foreground dark:border-slate-800 dark:bg-slate-900">
            <ShieldCheck className="h-4 w-4 text-emerald-600" />
            管理视图
          </div>
        </div>
      </section>

      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 xl:grid-cols-5">
        {statCards.map((card) => {
          const Icon = card.icon
          const value = getStat(stats, card.key)
          const percent = Math.max(8, Math.round((value / maxValue) * 100))

          return (
            <Card key={card.key} className="overflow-hidden">
              <CardContent className="p-4">
                <div className="flex items-start justify-between gap-3">
                  <div>
                    <p className="text-xs text-muted-foreground">{card.label}</p>
                    {isLoading ? <Skeleton className="mt-2 h-8 w-20" /> : <p className="mt-1 text-3xl font-semibold tabular-nums">{value}</p>}
                  </div>
                  <span className={`flex h-10 w-10 items-center justify-center rounded-lg ${card.bg}`}>
                    <Icon className="h-5 w-5" />
                  </span>
                </div>
                <div className="mt-4 h-2 overflow-hidden rounded-full bg-slate-100 dark:bg-slate-800">
                  <div
                    className="h-full rounded-full transition-all duration-700"
                    style={{ width: isLoading ? '30%' : `${percent}%`, backgroundColor: card.color }}
                  />
                </div>
              </CardContent>
            </Card>
          )
        })}
      </div>

      <div className="grid grid-cols-1 gap-4 xl:grid-cols-[1.35fr_0.9fr]">
        <Card>
          <CardHeader className="pb-3">
            <CardTitle className="flex items-center gap-2 text-base">
              <BarChart3 className="h-4 w-4" /> 系统指标对比
            </CardTitle>
          </CardHeader>
          <CardContent className="space-y-4">
            {statCards.map((card) => {
              const value = getStat(stats, card.key)
              const width = Math.max(4, Math.round((value / maxValue) * 100))
              return (
                <div key={card.key} className="grid grid-cols-[4rem_1fr_4rem] items-center gap-3">
                  <span className="text-sm text-muted-foreground">{card.label}</span>
                  <div className="h-8 overflow-hidden rounded-lg bg-slate-100 dark:bg-slate-800">
                    <div
                      className="flex h-full items-center justify-end rounded-lg pr-2 text-xs font-medium text-white transition-all duration-700"
                      style={{ width: isLoading ? '20%' : `${width}%`, backgroundColor: card.color }}
                    >
                      {!isLoading && width > 18 ? value : ''}
                    </div>
                  </div>
                  <span className="text-right text-sm font-medium tabular-nums">{isLoading ? '-' : value}</span>
                </div>
              )
            })}
          </CardContent>
        </Card>

        <Card>
          <CardHeader className="pb-3">
            <CardTitle className="flex items-center gap-2 text-base">
              <Activity className="h-4 w-4" /> 活跃度构成
            </CardTitle>
          </CardHeader>
          <CardContent>
            <div className="flex flex-col items-center gap-4 sm:flex-row xl:flex-col">
              <DonutChart
                stats={stats}
                loading={isLoading}
                keys={['questionCount', 'favoriteCount', 'logCount']}
              />
              <div className="w-full space-y-3">
                <Legend color="#7c3aed" label="问答" value={getStat(stats, 'questionCount')} />
                <Legend color="#d97706" label="收藏" value={getStat(stats, 'favoriteCount')} />
                <Legend color="#dc2626" label="日志" value={getStat(stats, 'logCount')} />
                <div className="rounded-lg bg-slate-50 p-3 text-xs text-muted-foreground dark:bg-slate-900">
                  活跃事件总量：<span className="font-medium text-foreground">{isLoading ? '-' : totalActivity}</span>
                </div>
              </div>
            </div>
          </CardContent>
        </Card>
      </div>

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
        <InsightCard title="资料覆盖" value={`${materialCoverage}%`} desc="资料数 / 用户数，用于判断用户资料沉淀是否充足。" />
        <InsightCard title="收藏转化" value={`${favoriteRate}%`} desc="收藏数 / 问答数，用于粗略判断回答内容是否被复用。" />
        <InsightCard title="审计压力" value={String(getStat(stats, 'logCount'))} desc="后台管理日志量，数值升高时应重点查看系统日志。" />
      </div>
    </motion.div>
  )
}

function getStat(stats: AdminStats | undefined, key: keyof AdminStats) {
  return stats?.[key] ?? 0
}

function ratio(numerator: number, denominator: number) {
  if (!denominator) return 0
  return Math.min(999, Math.round((numerator / denominator) * 100))
}

function Legend({ color, label, value }: { color: string; label: string; value: number }) {
  return (
    <div className="flex items-center justify-between gap-3 text-sm">
      <span className="flex items-center gap-2 text-muted-foreground">
        <span className="h-2.5 w-2.5 rounded-full" style={{ backgroundColor: color }} />
        {label}
      </span>
      <span className="font-medium tabular-nums">{value}</span>
    </div>
  )
}

function DonutChart({
  stats,
  keys,
  loading,
}: {
  stats: AdminStats | undefined
  keys: Array<keyof AdminStats>
  loading: boolean
}) {
  const colors = ['#7c3aed', '#d97706', '#dc2626']
  const values = keys.map((key) => getStat(stats, key))
  const total = values.reduce((sum, value) => sum + value, 0)
  let offset = 25

  return (
    <svg viewBox="0 0 120 120" className="h-44 w-44 shrink-0">
      <circle cx="60" cy="60" r="42" fill="none" stroke="currentColor" strokeWidth="16" className="text-slate-100 dark:text-slate-800" />
      {!loading && total > 0 && values.map((value, index) => {
        const dash = (value / total) * 263.89
        const segment = (
          <circle
            key={keys[index]}
            cx="60"
            cy="60"
            r="42"
            fill="none"
            stroke={colors[index]}
            strokeWidth="16"
            strokeLinecap="round"
            strokeDasharray={`${dash} 263.89`}
            strokeDashoffset={-offset}
            transform="rotate(-90 60 60)"
          />
        )
        offset += dash
        return segment
      })}
      <text x="60" y="57" textAnchor="middle" className="fill-foreground text-xl font-semibold">
        {loading ? '-' : total}
      </text>
      <text x="60" y="75" textAnchor="middle" className="fill-muted-foreground text-[10px]">
        活跃事件
      </text>
    </svg>
  )
}

function InsightCard({ title, value, desc }: { title: string; value: string; desc: string }) {
  return (
    <Card>
      <CardContent className="p-4">
        <p className="text-sm text-muted-foreground">{title}</p>
        <p className="mt-2 text-3xl font-semibold tabular-nums">{value}</p>
        <p className="mt-2 text-xs leading-5 text-muted-foreground">{desc}</p>
      </CardContent>
    </Card>
  )
}
