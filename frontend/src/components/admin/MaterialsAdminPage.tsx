import { useMemo, useState } from 'react'
import { motion } from 'framer-motion'
import {
  useReactTable,
  getCoreRowModel,
  flexRender,
  createColumnHelper,
} from '@tanstack/react-table'
import { useAdminMaterials, useUpdateAdminMaterialStatus } from '@/api/admin'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { Input } from '@/components/ui/input'
import {
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
} from '@/components/ui/select'
import {
  Table, TableHeader, TableBody, TableRow, TableHead, TableCell,
} from '@/components/ui/table'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { formatDate, formatBytes } from '@/lib/utils'
import { SOURCE_TYPE_LABELS, PARSE_STATUS_LABELS, PARSE_STATUS_COLORS } from '@/constants'
import {
  AlertTriangle,
  BarChart3,
  CheckCircle,
  ChevronLeft,
  ChevronRight,
  Database,
  FileWarning,
  FolderSearch,
  Search,
  XCircle,
} from 'lucide-react'
import type { AdminMaterial } from '@/types'

const columnHelper = createColumnHelper<AdminMaterial>()
const PAGE_SIZE = 12

function isParsing(status: string) {
  return status === 'PARSING' || status === 'PROCESSING' || status === 'PENDING'
}

function parsePercent(material: AdminMaterial) {
  if (typeof material.parseProgressPercent === 'number') {
    return Math.max(0, Math.min(100, Math.round(material.parseProgressPercent)))
  }
  return isParsing(material.parseStatus) ? 0 : 100
}

function statusBadge(status: string) {
  const color = PARSE_STATUS_COLORS[status] || 'secondary'
  return <Badge variant={color as any}>{PARSE_STATUS_LABELS[status] || status}</Badge>
}

export function MaterialsAdminPage() {
  const [page, setPage] = useState(0)
  const [keyword, setKeyword] = useState('')
  const [statusFilter, setStatusFilter] = useState('ALL')

  const { data, isLoading } = useAdminMaterials({ page, size: PAGE_SIZE, keyword: keyword.trim() || undefined })
  const updateStatusMutation = useUpdateAdminMaterialStatus()

  const allItems = data?.items ?? []
  const items = useMemo(
    () => statusFilter === 'ALL' ? allItems : allItems.filter((item) => item.parseStatus === statusFilter),
    [allItems, statusFilter],
  )
  const total = data?.total ?? 0
  const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE))

  const stats = useMemo(() => {
    const success = allItems.filter((item) => item.parseStatus === 'SUCCESS' || item.parseStatus === 'PARSED').length
    const failed = allItems.filter((item) => item.parseStatus === 'FAILED').length
    const parsing = allItems.filter((item) => isParsing(item.parseStatus)).length
    const chunks = allItems.reduce((sum, item) => sum + (item.chunkCount || 0), 0)
    const storage = allItems.reduce((sum, item) => sum + (item.fileSize || 0), 0)
    return { success, failed, parsing, chunks, storage }
  }, [allItems])

  const handleSetStatus = (id: string, status: string) => {
    updateStatusMutation.mutate({ id, payload: { parseStatus: status } })
  }

  const columns = useMemo(
    () => [
      columnHelper.accessor('title', {
        header: '资料',
        cell: (info) => {
          const material = info.row.original
          return (
            <div className="min-w-[220px]">
              <div className="truncate font-medium">{info.getValue() || material.originalName}</div>
              <div className="mt-1 flex items-center gap-2 text-xs text-muted-foreground">
                <span>{material.ownerUsername || '-'}</span>
                <span>{formatDate(material.createdAt)}</span>
              </div>
            </div>
          )
        },
      }),
      columnHelper.accessor('sourceType', {
        header: '类型',
        cell: (info) => (
          <Badge variant="outline">{SOURCE_TYPE_LABELS[info.getValue()] || info.getValue()}</Badge>
        ),
      }),
      columnHelper.accessor('parseStatus', {
        header: '解析状态',
        cell: (info) => {
          const material = info.row.original
          const percent = parsePercent(material)
          return (
            <div className="min-w-[170px] space-y-2">
              <div className="flex items-center justify-between gap-2">
                {statusBadge(info.getValue())}
                {isParsing(info.getValue()) && <span className="text-xs tabular-nums text-muted-foreground">{percent}%</span>}
              </div>
              {isParsing(info.getValue()) && (
                <div className="h-1.5 overflow-hidden rounded-full bg-slate-200 dark:bg-slate-800">
                  <div
                    className="h-full rounded-full bg-gradient-to-r from-[#2563eb] via-[#0f766e] to-[#65a30d]"
                    style={{ width: `${percent}%` }}
                  />
                </div>
              )}
              <p className="line-clamp-1 text-xs text-muted-foreground">
                {material.parseStage || material.parseMessage || '等待系统更新解析状态'}
              </p>
            </div>
          )
        },
      }),
      columnHelper.accessor('chunkCount', {
        header: '知识片段',
        cell: (info) => <span className="tabular-nums">{info.getValue() || 0}</span>,
      }),
      columnHelper.accessor('fileSize', {
        header: '文件大小',
        cell: (info) => info.getValue() > 0 ? formatBytes(info.getValue()) : '-',
      }),
      columnHelper.display({
        id: 'advice',
        header: '处理建议',
        cell: (info) => {
          const material = info.row.original
          if (material.parseStatus === 'FAILED') {
            return <span className="text-xs text-destructive">通知上传者重新上传，或检查文件格式/权限。</span>
          }
          if (isParsing(material.parseStatus)) {
            return <span className="text-xs text-amber-600">后台处理中，通常无需人工干预。</span>
          }
          if ((material.chunkCount || 0) === 0) {
            return <span className="text-xs text-amber-600">已解析但无片段，建议复查资料内容。</span>
          }
          return <span className="text-xs text-muted-foreground">可用于 RAG 检索和资料问答。</span>
        },
      }),
      columnHelper.display({
        id: 'actions',
        header: '人工标记',
        cell: (info) => {
          const material = info.row.original
          return (
            <div className="flex min-w-[150px] items-center gap-1">
              <Button
                variant="ghost"
                size="sm"
                className="h-7 text-xs"
                onClick={() => handleSetStatus(material.id, 'SUCCESS')}
                disabled={updateStatusMutation.isPending || material.parseStatus === 'SUCCESS'}
                title="仅修正状态，不会重新解析或重建向量"
              >
                <CheckCircle className="mr-1 h-3.5 w-3.5 text-emerald-500" /> 正常
              </Button>
              <Button
                variant="ghost"
                size="sm"
                className="h-7 text-xs"
                onClick={() => handleSetStatus(material.id, 'FAILED')}
                disabled={updateStatusMutation.isPending || material.parseStatus === 'FAILED'}
                title="仅修正状态，不会删除文件"
              >
                <XCircle className="mr-1 h-3.5 w-3.5 text-destructive" /> 异常
              </Button>
            </div>
          )
        },
      }),
    ],
    [updateStatusMutation.isPending],
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
              <FolderSearch className="h-5 w-5" /> 资料分析
            </h2>
            <p className="mt-2 max-w-3xl text-sm leading-6 text-muted-foreground">
              用于管理员查看全站资料导入后的解析质量：是否解析成功、生成了多少知识片段、是否仍在后台处理、哪些资料需要人工排查。这里的“人工标记”只修正状态，不会重新解析文件或重建向量索引。
            </p>
          </div>
          <Badge variant="outline" className="w-fit">
            共 {total} 份资料
          </Badge>
        </div>
      </section>

      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 xl:grid-cols-5">
        <MetricCard icon={Database} label="当前页资料" value={String(allItems.length)} hint="按上传时间倒序" />
        <MetricCard icon={CheckCircle} label="解析成功" value={String(stats.success)} hint="可用于问答" tone="success" />
        <MetricCard icon={BarChart3} label="解析中" value={String(stats.parsing)} hint="自动刷新后查看进度" tone="warning" />
        <MetricCard icon={FileWarning} label="解析失败" value={String(stats.failed)} hint="需排查格式或文件" tone="danger" />
        <MetricCard icon={Database} label="知识片段" value={String(stats.chunks)} hint={`文件 ${formatBytes(stats.storage)}`} />
      </div>

      <Card>
        <CardHeader className="space-y-3 border-b p-4">
          <div className="flex flex-col gap-3 lg:flex-row lg:items-center lg:justify-between">
            <CardTitle className="flex items-center gap-2 text-base">
              <AlertTriangle className="h-4 w-4 text-amber-600" />
              解析明细与人工处理
            </CardTitle>
            <div className="flex flex-col gap-2 sm:flex-row">
              <div className="relative sm:w-72">
                <Search className="absolute left-2.5 top-2.5 h-4 w-4 text-muted-foreground" />
                <Input
                  className="h-9 pl-8"
                  placeholder="搜索标题、上传者、原文件名"
                  value={keyword}
                  onChange={(event) => {
                    setKeyword(event.target.value)
                    setPage(0)
                  }}
                />
              </div>
              <Select value={statusFilter} onValueChange={setStatusFilter}>
                <SelectTrigger className="h-9 sm:w-36">
                  <SelectValue placeholder="状态" />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="ALL">全部状态</SelectItem>
                  <SelectItem value="SUCCESS">已解析</SelectItem>
                  <SelectItem value="PARSING">解析中</SelectItem>
                  <SelectItem value="PENDING">待解析</SelectItem>
                  <SelectItem value="FAILED">解析失败</SelectItem>
                </SelectContent>
              </Select>
            </div>
          </div>
          <p className="text-xs text-muted-foreground">
            建议优先处理“解析失败”和“已解析但 0 片段”的资料；解析中资料不建议手动改状态，除非确认后台任务已经异常停止。
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
                      暂无匹配资料
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
        <span>第 {page + 1} / {totalPages} 页，状态筛选仅作用于当前页数据。</span>
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
  tone?: 'neutral' | 'success' | 'warning' | 'danger'
}) {
  const toneClass = {
    neutral: 'text-slate-600 bg-slate-100 dark:bg-slate-800 dark:text-slate-300',
    success: 'text-emerald-700 bg-emerald-50 dark:bg-emerald-950/40 dark:text-emerald-300',
    warning: 'text-amber-700 bg-amber-50 dark:bg-amber-950/40 dark:text-amber-300',
    danger: 'text-red-700 bg-red-50 dark:bg-red-950/40 dark:text-red-300',
  }[tone]

  return (
    <Card>
      <CardContent className="p-4">
        <div className="flex items-start justify-between gap-3">
          <div>
            <p className="text-xs text-muted-foreground">{label}</p>
            <p className="mt-1 text-2xl font-semibold tabular-nums">{value}</p>
            <p className="mt-1 text-xs text-muted-foreground">{hint}</p>
          </div>
          <span className={`flex h-9 w-9 items-center justify-center rounded-lg ${toneClass}`}>
            <Icon className="h-4 w-4" />
          </span>
        </div>
      </CardContent>
    </Card>
  )
}
