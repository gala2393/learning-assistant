/**
 * 使用记录页面 - UsageRecordsPage
 *
 * 路由：由 AdminLayout 渲染，路径通常为 /admin/usage
 * 用途：记录用户的行为流水，包括问答（RAG_CHAT）、资料上传（UPLOAD_MATERIAL）、
 *       调用的模型名称、Token 消耗量等信息。
 *       注意：此页面展示的是用户行为记录，系统日志页面只保留管理员审计操作。
 *
 * 主要功能：
 * - 从后端 useAdminUsageRecords 获取使用记录，支持分页和关键词搜索（防抖 300ms）
 * - 展示统计指标卡片（当前页问答数/上传数/Token 总量/最近使用时间）
 * - 表格展示每条记录的详情：时间、用户、操作类型、模型、Token 消耗、对象/详情
 * - 支持手动刷新数据
 */

import { useMemo, useState } from 'react'
import { motion } from 'framer-motion'
import {
  useReactTable,
  getCoreRowModel,
  flexRender,
  createColumnHelper,
} from '@tanstack/react-table'
import {
  Activity,
  ChevronLeft,
  ChevronRight,
  Clock3,
  Database,
  FileUp,
  MessageSquareText,
  Search,
  UserRound,
} from 'lucide-react'
import { useAdminUsageRecords } from '@/api/admin'
import { useDebounce } from '@/hooks/useDebounce'
import { Input } from '@/components/ui/input'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { formatDate } from '@/lib/utils'
import type { AdminUsageRecord } from '@/types'

/** 每页显示条数 */
const PAGE_SIZE = 12
/** TanStack Table 列辅助工具 */
const columnHelper = createColumnHelper<AdminUsageRecord>()

/**
 * 操作类型中文映射
 * 将后端返回的英文 action 翻译为可读的中文标签
 * RAG_CHAT 和 RAG_CHAT_STREAM 都映射为"问答"
 */
const ACTION_LABELS: Record<string, string> = {
  RAG_CHAT: '问答',
  RAG_CHAT_STREAM: '问答',
  UPLOAD_MATERIAL: '上传资料',
  CREATE_UPLOAD_SESSION: '创建上传任务',
}

/**
 * UsageRecordsPage 组件
 * 无 Props，数据通过 useAdminUsageRecords hook 从后端获取
 */
export function UsageRecordsPage() {
  // 当前页码（从 0 开始）
  const [page, setPage] = useState(0)
  // 搜索关键词
  const [keyword, setKeyword] = useState('')
  // 防抖关键词，输入 300ms 后才触发请求
  const debouncedKeyword = useDebounce(keyword, 300)

  // 从后端获取使用记录，refetch 用于手动刷新
  const { data, isLoading, refetch, isFetching } = useAdminUsageRecords({
    page,
    size: PAGE_SIZE,
    // 使用 undefined 表达“不搜索”，保持 queryKey 与后端语义一致。
    keyword: debouncedKeyword || undefined,
  })

  const items = data?.items ?? []
  const total = data?.total ?? 0
  const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE))

  // 计算当前页的统计指标
  const stats = useMemo(() => {
    // 问答操作数
    const questionCount = items.filter(isQuestionAction).length
    // 上传操作数（非问答即上传）
    const uploadCount = items.filter((item) => !isQuestionAction(item)).length
    // Token 消耗总量
    // 后端可能返回字符串或 null，统一转 Number 后再累加。
    const tokenTotal = items.reduce((sum, item) => sum + Number(item.totalTokens || 0), 0)
    // 最近使用时间
    const latest = items[0]?.createdAt || ''
    return { questionCount, uploadCount, tokenTotal, latest }
  }, [items])

  // 表格列定义
  const columns = useMemo(
    () => [
      // 时间列
      columnHelper.accessor('createdAt', {
        header: '时间',
        cell: (info) => (
          <div className="min-w-[150px] text-sm tabular-nums text-slate-700 dark:text-slate-200">
            {formatDate(info.getValue()) || '-'}
          </div>
        ),
      }),
      // 用户列：显示用户名和用户 ID
      columnHelper.accessor('username', {
        header: '用户',
        cell: (info) => (
          <div className="min-w-[140px]">
            <div className="flex items-center gap-2 font-medium text-slate-900 dark:text-slate-100">
              <UserRound className="h-4 w-4 text-slate-400" />
              {info.getValue() || '未知用户'}
            </div>
            <div className="mt-1 text-xs text-muted-foreground">ID {info.row.original.userId ?? '-'}</div>
          </div>
        ),
      }),
      // 操作列：根据操作类型显示不同图标和 Badge
      columnHelper.accessor('action', {
        header: '操作',
        cell: (info) => {
          const row = info.row.original
          const question = isQuestionAction(row)
          // 问答用消息图标，上传用文件图标
          const Icon = question ? MessageSquareText : FileUp
          return (
            <Badge variant={question ? 'success' : 'warning'} className="gap-1.5 whitespace-nowrap">
              <Icon className="h-3.5 w-3.5" />
              {ACTION_LABELS[row.action] || row.action}
            </Badge>
          )
        },
      }),
      // 模型列：显示调用的模型名称
      columnHelper.accessor('modelName', {
        header: '模型',
        cell: (info) => (
          <div className="min-w-[150px]">
            {info.getValue() ? (
              <Badge variant="outline" className="max-w-[220px] truncate font-mono text-[11px]">
                {info.getValue()}
              </Badge>
            ) : (
              <span className="text-sm text-muted-foreground">-</span>
            )}
          </div>
        ),
      }),
      // Token 列：显示总量及输入/输出分项
      columnHelper.accessor('totalTokens', {
        header: 'Token',
        cell: (info) => {
          const row = info.row.original
          return (
            <div className="min-w-[150px]">
              <div className="text-sm font-semibold tabular-nums text-slate-900 dark:text-slate-100">
                {row.totalTokens ?? 0}
              </div>
              <div className="mt-1 text-[11px] text-muted-foreground">
                输入 {row.promptTokens ?? 0} / 输出 {row.completionTokens ?? 0}
              </div>
            </div>
          )
        },
      }),
      // 对象/详情列：显示目标类型和解析后的详情字段
      columnHelper.accessor('detail', {
        header: '对象 / 详情',
        cell: (info) => {
          const row = info.row.original
          const detail = parseDetail(info.getValue())
          return (
            <div className="min-w-[260px] max-w-[420px]">
              <div className="text-sm text-slate-800 dark:text-slate-200">
                {targetLabel(row.targetType)} #{row.targetId ?? '-'}
              </div>
              <div className="mt-1 truncate text-xs text-muted-foreground">
                {detail.title || detail.originalName || detail.mode || info.getValue() || '-'}
              </div>
            </div>
          )
        },
      }),
    ],
    [],
  )

  // 创建 TanStack Table 实例
  const table = useReactTable({
    data: items,
    columns,
    getCoreRowModel: getCoreRowModel(),
  })

  return (
    /* 页面容器，带入场动画 */
    <motion.div
      className="space-y-4 p-3 md:p-6"
      initial={{ opacity: 0, y: 12 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.25 }}
    >
      {/* ========== 页面顶部标题区域 ========== */}
      <section className="rounded-lg border bg-white p-4 dark:border-slate-800 dark:bg-slate-950/40">
        <div className="flex flex-col gap-3 lg:flex-row lg:items-center lg:justify-between">
          <div>
            <h2 className="flex items-center gap-2 text-lg font-semibold">
              <Activity className="h-5 w-5 text-cyan-600" />
              使用记录
            </h2>
            <p className="mt-1 text-sm text-muted-foreground">
              记录用户问答、资料上传、调用模型和 token 消耗。这里是用户行为流水，系统日志只保留管理员审计操作。
            </p>
          </div>
          <div className="flex items-center gap-2">
            <Badge variant="outline">共 {total} 条</Badge>
            {/* 手动刷新按钮 */}
            <Button variant="outline" size="sm" onClick={() => refetch()} disabled={isFetching}>
              {isFetching ? '刷新中...' : '刷新'}
            </Button>
          </div>
        </div>
      </section>

      {/* ========== 统计指标卡片 ========== */}
      <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
        <Metric icon={MessageSquareText} label="当前页问答" value={String(stats.questionCount)} />
        <Metric icon={FileUp} label="当前页上传" value={String(stats.uploadCount)} />
        <Metric icon={Database} label="当前页 Token" value={String(stats.tokenTotal)} />
        <Metric icon={Clock3} label="最近使用" value={stats.latest ? formatDate(stats.latest) : '-'} />
      </div>

      {/* ========== 使用明细表格 ========== */}
      <Card className="overflow-hidden rounded-lg">
        <CardHeader className="border-b bg-slate-50/70 p-4 dark:border-slate-800 dark:bg-slate-900/40">
          <div className="flex flex-col gap-3 lg:flex-row lg:items-center lg:justify-between">
            <CardTitle className="text-base">使用明细</CardTitle>
            {/* 搜索框 */}
            <div className="relative lg:w-96">
              <Search className="absolute left-2.5 top-2.5 h-4 w-4 text-muted-foreground" />
              <Input
                placeholder="搜索用户、模型、操作、资料或时间"
                className="h-9 pl-8"
                value={keyword}
                onChange={(event) => {
                  setKeyword(event.target.value)
                  // 新搜索从第一页开始，避免当前页超出新结果范围。
                  setPage(0) // 搜索时重置到第一页
                }}
              />
            </div>
          </div>
        </CardHeader>
        <CardContent className="p-0">
          <div className="overflow-x-auto">
            <Table>
              <TableHeader className="bg-white dark:bg-slate-950">
                {table.getHeaderGroups().map((headerGroup) => (
                  <TableRow key={headerGroup.id}>
                    {headerGroup.headers.map((header) => (
                      <TableHead key={header.id} className="whitespace-nowrap">
                        {flexRender(header.column.columnDef.header, header.getContext())}
                      </TableHead>
                    ))}
                  </TableRow>
                ))}
              </TableHeader>
              <TableBody>
                {isLoading ? (
                  /* 加载中状态 */
                  <TableRow>
                    <TableCell colSpan={columns.length} className="py-10 text-center text-muted-foreground">
                      正在加载使用记录...
                    </TableCell>
                  </TableRow>
                ) : items.length === 0 ? (
                  /* 无数据状态：展示友好提示 */
                  <TableRow>
                    <TableCell colSpan={columns.length} className="py-12 text-center">
                      <div className="mx-auto flex max-w-sm flex-col items-center gap-2 text-muted-foreground">
                        <Activity className="h-8 w-8 text-slate-300" />
                        <div className="font-medium text-slate-700 dark:text-slate-200">暂无使用记录</div>
                        <div className="text-sm">完成一次问答或上传资料后，这里会显示用户、模型、Token 和时间。</div>
                      </div>
                    </TableCell>
                  </TableRow>
                ) : (
                  /* 正常渲染行数据，hover 时显示青色背景 */
                  table.getRowModel().rows.map((row) => (
                    <TableRow key={row.id} className="hover:bg-cyan-50/40 dark:hover:bg-cyan-950/10">
                      {row.getVisibleCells().map((cell) => (
                        <TableCell key={cell.id}>{flexRender(cell.column.columnDef.cell, cell.getContext())}</TableCell>
                      ))}
                    </TableRow>
                  ))
                )}
              </TableBody>
            </Table>
          </div>
        </CardContent>
      </Card>

      {/* ========== 分页控制 ========== */}
      <div className="flex flex-col gap-2 text-sm text-muted-foreground sm:flex-row sm:items-center sm:justify-between">
        <span>第 {page + 1} / {totalPages} 页</span>
        <div className="flex items-center gap-2">
          <Button variant="outline" size="sm" disabled={page === 0} onClick={() => setPage((value) => Math.max(0, value - 1))}>
            <ChevronLeft className="h-4 w-4" />
          </Button>
          <Button variant="outline" size="sm" disabled={page >= totalPages - 1} onClick={() => setPage((value) => value + 1)}>
            <ChevronRight className="h-4 w-4" />
          </Button>
        </div>
      </div>
    </motion.div>
  )
}

/**
 * 统计指标卡片组件（简洁风格，青色主题）
 * @param icon - 图标组件
 * @param label - 指标名称
 * @param value - 指标值
 */
function Metric({ icon: Icon, label, value }: { icon: React.ElementType; label: string; value: string }) {
  return (
    <Card className="rounded-lg">
      <CardContent className="flex items-center justify-between p-4">
        <div className="min-w-0">
          <p className="text-xs text-muted-foreground">{label}</p>
          <p className="mt-1 truncate text-2xl font-semibold tabular-nums">{value}</p>
        </div>
        <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg bg-cyan-50 text-cyan-700 dark:bg-cyan-950/40 dark:text-cyan-300">
          <Icon className="h-4 w-4" />
        </span>
      </CardContent>
    </Card>
  )
}

/**
 * 判断记录是否为问答操作
 * RAG_CHAT 和 RAG_CHAT_STREAM 都算问答
 * @param item - 使用记录对象
 */
function isQuestionAction(item: AdminUsageRecord) {
  return item.action === 'RAG_CHAT' || item.action === 'RAG_CHAT_STREAM'
}

/**
 * 将目标类型英文转为中文
 * @param value - 后端返回的目标类型字符串
 */
function targetLabel(value: string) {
  if (value === 'RAG_QUESTION') return '问答'
  if (value === 'MATERIAL') return '资料'
  return value || '对象'
}

/**
 * 解析 detail 字符串为 key-value 对象
 * detail 格式为 "key1=value1,key2=value2"
 * @param value - detail 字符串
 */
function parseDetail(value: string) {
  const result: Record<string, string> = {}
  for (const part of String(value || '').split(',')) {
    const [key, ...rest] = part.split('=')
    if (!key || rest.length === 0) continue
    result[key.trim()] = rest.join('=').trim()
  }
  return result
}
