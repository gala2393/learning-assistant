import { useMemo, useState } from 'react'
import { motion } from 'framer-motion'
import {
  useReactTable,
  getCoreRowModel,
  flexRender,
  createColumnHelper,
} from '@tanstack/react-table'
import { useAdminLogs } from '@/api/admin'
import { useDebounce } from '@/hooks/useDebounce'
import { Input } from '@/components/ui/input'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import {
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
} from '@/components/ui/select'
import {
  Table, TableHeader, TableBody, TableRow, TableHead, TableCell,
} from '@/components/ui/table'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { formatDate, truncate } from '@/lib/utils'
import {
  Activity,
  AlertTriangle,
  ChevronLeft,
  ChevronRight,
  Clock,
  FileWarning,
  KeyRound,
  ScrollText,
  Search,
  ShieldCheck,
  UserCog,
} from 'lucide-react'
import type { AdminLog } from '@/types'

const columnHelper = createColumnHelper<AdminLog>()
const PAGE_SIZE = 12

const ACTION_LABELS: Record<string, string> = {
  UPDATE_USER_ROLE: '调整用户角色',
  UPDATE_USER_STATUS: '调整用户状态',
  UPDATE_MATERIAL_STATUS: '修改资料状态',
}

const TARGET_LABELS: Record<string, string> = {
  USER: '用户',
  MATERIAL: '资料',
}

function actionLabel(action: string) {
  return ACTION_LABELS[action] || action.replace(/_/g, ' ')
}

function actionTone(action: string) {
  if (action.includes('USER') || action.includes('ROLE')) return 'destructive'
  if (action.includes('MATERIAL')) return 'warning'
  return 'outline'
}

function actionDescription(log: AdminLog) {
  if (log.action === 'UPDATE_USER_ROLE') {
    return '管理员修改了用户权限，建议确认是否符合授权范围。'
  }
  if (log.action === 'UPDATE_USER_STATUS') {
    return '管理员修改了用户登录状态。'
  }
  if (log.action === 'UPDATE_MATERIAL_STATUS') {
    return '管理员人工修正了资料解析状态，不代表系统重新解析了文件。'
  }
  return '系统记录了一次后台管理操作。'
}

function isUsageAction(action: string) {
  return action === 'RAG_CHAT'
    || action === 'RAG_CHAT_STREAM'
    || action === 'UPLOAD_MATERIAL'
    || action === 'CREATE_UPLOAD_SESSION'
}

export function LogsPage() {
  const [page, setPage] = useState(0)
  const [keyword, setKeyword] = useState('')
  const [actionFilter, setActionFilter] = useState('ALL')
  const debouncedKeyword = useDebounce(keyword, 300)

  const { data, isLoading } = useAdminLogs({
    page,
    size: PAGE_SIZE,
    keyword: debouncedKeyword || undefined,
  })

  const allItems = (data?.items ?? []).filter((item) => !isUsageAction(item.action))
  const items = useMemo(
    () => actionFilter === 'ALL' ? allItems : allItems.filter((item) => item.action === actionFilter),
    [actionFilter, allItems],
  )
  const total = allItems.length
  const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE))

  const stats = useMemo(() => {
    const roleChanges = allItems.filter((item) => item.action === 'UPDATE_USER_ROLE').length
    const materialChanges = allItems.filter((item) => item.action === 'UPDATE_MATERIAL_STATUS').length
    const actors = new Set(allItems.map((item) => item.actorUsername || item.actorUserId).filter(Boolean)).size
    const latest = allItems[0]?.createdAt || '-'
    return { roleChanges, materialChanges, actors, latest }
  }, [allItems])

  const actionOptions = useMemo(
    () => Array.from(new Set(allItems.map((item) => item.action))).filter(Boolean),
    [allItems],
  )

  const columns = useMemo(
    () => [
      columnHelper.accessor('action', {
        header: '事件',
        cell: (info) => {
          const log = info.row.original
          return (
            <div className="min-w-[210px]">
              <Badge variant={actionTone(log.action) as any} className="text-xs">
                {actionLabel(log.action)}
              </Badge>
              <p className="mt-1 line-clamp-2 text-xs text-muted-foreground">{actionDescription(log)}</p>
            </div>
          )
        },
      }),
      columnHelper.accessor('actorUsername', {
        header: '操作者',
        cell: (info) => {
          const log = info.row.original
          return (
            <div className="min-w-[120px]">
              <div className="font-medium">{info.getValue() || '-'}</div>
              <div className="text-xs text-muted-foreground">ID {log.actorUserId ?? '-'}</div>
            </div>
          )
        },
      }),
      columnHelper.accessor('targetType', {
        header: '对象',
        cell: (info) => {
          const log = info.row.original
          return (
            <div className="min-w-[110px] text-sm">
              <span>{TARGET_LABELS[info.getValue()] || info.getValue() || '-'}</span>
              <div className="mt-1 font-mono text-xs text-muted-foreground">#{log.targetId ?? '-'}</div>
            </div>
          )
        },
      }),
      columnHelper.accessor('detail', {
        header: '变更内容',
        cell: (info) => {
          const value = info.getValue()
          const detail = parseDetail(value)
          return (
            <div className="min-w-[240px] max-w-[360px]">
              {detail.model && (
                <div className="mb-1 flex flex-wrap gap-1.5">
                  <Badge variant="outline" className="text-[10px]">模型：{detail.model}</Badge>
                  <Badge variant="secondary" className="text-[10px]">Token：{detail.totalTokens || 0}</Badge>
                </div>
              )}
              <code className="rounded bg-slate-100 px-2 py-1 text-xs text-slate-700 dark:bg-slate-900 dark:text-slate-300">
                {truncate(value, 80) || '无详情'}
              </code>
            </div>
          )
        },
      }),
      columnHelper.accessor('createdAt', {
        header: '时间',
        cell: (info) => (
          <span className="min-w-[130px] text-xs text-muted-foreground">{formatDate(info.getValue())}</span>
        ),
      }),
    ],
    [],
  )

  const table = useReactTable({
    data: items,
    columns,
    getCoreRowModel: getCoreRowModel(),
  })

  return (
    <motion.div
      className="space-y-4 p-3 md:p-6"
      initial={{ opacity: 0, y: 12 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.3 }}
    >
      <section className="rounded-xl border border-slate-200 bg-gradient-to-r from-white to-slate-50 p-4 dark:border-slate-800 dark:from-[#171a21] dark:to-[#111318]">
        <div className="flex flex-col gap-3 md:flex-row md:items-start md:justify-between">
          <div>
            <h2 className="flex items-center gap-2 text-lg font-semibold">
              <ScrollText className="h-5 w-5" /> 系统日志
            </h2>
            <p className="mt-2 max-w-3xl text-sm leading-6 text-muted-foreground">
              用于管理员审计后台关键操作：谁在什么时候修改了用户权限、资料解析状态等敏感配置。它主要用于追责、排查误操作和确认管理动作是否符合预期，不是普通运行日志。
            </p>
          </div>
          <Badge variant="outline" className="w-fit">
            共 {total} 条日志
          </Badge>
        </div>
      </section>

      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 xl:grid-cols-4">
        <MetricCard icon={Activity} label="当前页事件" value={String(allItems.length)} hint="按时间倒序" />
        <MetricCard icon={UserCog} label="角色变更" value={String(stats.roleChanges)} hint="高敏感操作" tone="danger" />
        <MetricCard icon={FileWarning} label="资料状态变更" value={String(stats.materialChanges)} hint="影响解析展示" tone="warning" />
        <MetricCard icon={Clock} label="最近事件" value={stats.latest === '-' ? '-' : formatDate(stats.latest)} hint={`${stats.actors} 个操作者`} />
      </div>

      <Card>
        <CardHeader className="space-y-3 border-b p-4">
          <div className="flex flex-col gap-3 lg:flex-row lg:items-center lg:justify-between">
            <CardTitle className="flex items-center gap-2 text-base">
              <ShieldCheck className="h-4 w-4 text-emerald-600" />
              审计明细
            </CardTitle>
            <div className="flex flex-col gap-2 sm:flex-row">
              <div className="relative sm:w-80">
                <Search className="absolute left-2.5 top-2.5 h-4 w-4 text-muted-foreground" />
                <Input
                  placeholder="搜索操作者、事件、对象 ID、详情"
                  className="h-9 pl-8"
                  value={keyword}
                  onChange={(event) => {
                    setKeyword(event.target.value)
                    setPage(0)
                  }}
                />
              </div>
              <Select value={actionFilter} onValueChange={setActionFilter}>
                <SelectTrigger className="h-9 sm:w-44">
                  <SelectValue placeholder="事件类型" />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="ALL">全部事件</SelectItem>
                  {actionOptions.map((action) => (
                    <SelectItem key={action} value={action}>{actionLabel(action)}</SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
          </div>
          <p className="flex items-start gap-2 text-xs text-muted-foreground">
            <AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0 text-amber-600" />
            发现非预期的角色变更或资料状态变更时，应先核对操作者、对象 ID 和变更内容，再决定是否回滚业务数据。
          </p>
        </CardHeader>
        <CardContent className="p-0">
          <div className="overflow-x-auto">
            <Table>
              <TableHeader>
                {table.getHeaderGroups().map((headerGroup) => (
                  <TableRow key={headerGroup.id}>
                    {headerGroup.headers.map((header) => (
                      <TableHead key={header.id}>
                        {flexRender(header.column.columnDef.header, header.getContext())}
                      </TableHead>
                    ))}
                  </TableRow>
                ))}
              </TableHeader>
              <TableBody>
                {isLoading ? (
                  <TableRow>
                    <TableCell colSpan={columns.length} className="py-8 text-center text-muted-foreground">
                      加载中...
                    </TableCell>
                  </TableRow>
                ) : items.length === 0 ? (
                  <TableRow>
                    <TableCell colSpan={columns.length} className="py-8 text-center text-muted-foreground">
                      暂无匹配日志
                    </TableCell>
                  </TableRow>
                ) : (
                  table.getRowModel().rows.map((row) => (
                    <TableRow key={row.id}>
                      {row.getVisibleCells().map((cell) => (
                        <TableCell key={cell.id}>
                          {flexRender(cell.column.columnDef.cell, cell.getContext())}
                        </TableCell>
                      ))}
                    </TableRow>
                  ))
                )}
              </TableBody>
            </Table>
          </div>
        </CardContent>
      </Card>

      <div className="flex flex-col gap-2 text-xs text-muted-foreground sm:flex-row sm:items-center sm:justify-between">
        <span>第 {page + 1} / {totalPages} 页，事件类型筛选仅作用于当前页数据。</span>
        <div className="flex items-center justify-center gap-2">
          <Button
            variant="outline"
            size="sm"
            disabled={page === 0}
            onClick={() => setPage((value) => Math.max(0, value - 1))}
          >
            <ChevronLeft className="h-4 w-4" />
          </Button>
          <Button
            variant="outline"
            size="sm"
            disabled={page >= totalPages - 1}
            onClick={() => setPage((value) => value + 1)}
          >
            <ChevronRight className="h-4 w-4" />
          </Button>
        </div>
      </div>
    </motion.div>
  )
}

function parseDetail(value: string) {
  const result: Record<string, string> = {}
  for (const part of String(value || '').split(',')) {
    const [key, ...rest] = part.split('=')
    if (!key || rest.length === 0) continue
    result[key.trim()] = rest.join('=').trim()
  }
  return {
    model: result.model,
    totalTokens: result.totalTokens,
  }
}

function MetricCard({
  icon: Icon,
  label,
  value,
  hint,
  tone = 'neutral',
}: {
  icon: React.ElementType
  label: string
  value: string
  hint: string
  tone?: 'neutral' | 'warning' | 'danger'
}) {
  const toneClass = {
    neutral: 'text-slate-600 bg-slate-100 dark:bg-slate-800 dark:text-slate-300',
    warning: 'text-amber-700 bg-amber-50 dark:bg-amber-950/40 dark:text-amber-300',
    danger: 'text-red-700 bg-red-50 dark:bg-red-950/40 dark:text-red-300',
  }[tone]

  return (
    <Card>
      <CardContent className="p-4">
        <div className="flex items-start justify-between gap-3">
          <div className="min-w-0">
            <p className="text-xs text-muted-foreground">{label}</p>
            <p className="mt-1 truncate text-2xl font-semibold tabular-nums">{value}</p>
            <p className="mt-1 text-xs text-muted-foreground">{hint}</p>
          </div>
          <span className={`flex h-9 w-9 shrink-0 items-center justify-center rounded-lg ${toneClass}`}>
            <Icon className="h-4 w-4" />
          </span>
        </div>
      </CardContent>
    </Card>
  )
}
