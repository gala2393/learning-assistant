/**
 * 系统日志页面 - LogsPage
 *
 * 路由：由 AdminLayout 渲染，路径通常为 /admin/logs
 * 用途：管理员审计后台关键操作日志，包括用户角色变更、用户状态变更、
 *       资料解析状态变更等敏感操作。主要用于追责、排查误操作和确认管理动作是否符合预期。
 *       注意：此页面只展示管理员审计操作，不包含普通用户行为记录。
 *
 * 主要功能：
 * - 从后端 useAdminLogs 获取日志数据，支持分页和关键词搜索
 * - 前端过滤掉普通使用行为（RAG_CHAT 等），只展示管理员审计操作
 * - 支持按事件类型（角色变更/资料状态变更等）筛选
 * - 展示统计指标卡片（当前页事件/角色变更/资料状态变更/最近事件）
 * - 以表格形式展示审计明细，包含事件、操作者、对象、变更内容、时间
 */

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

/** TanStack Table 列辅助工具 */
const columnHelper = createColumnHelper<AdminLog>()
/** 每页显示条数 */
const PAGE_SIZE = 12

/**
 * 事件类型中文映射
 * 将后端返回的英文 action 翻译为可读的中文标签
 */
const ACTION_LABELS: Record<string, string> = {
  UPDATE_USER_ROLE: '调整用户角色',
  UPDATE_USER_STATUS: '调整用户状态',
  UPDATE_MATERIAL_STATUS: '修改资料状态',
}

/** 对象类型中文映射 */
const TARGET_LABELS: Record<string, string> = {
  USER: '用户',
  MATERIAL: '资料',
}

/**
 * 将英文 action 转为中文标签，如果无映射则将下划线替换为空格
 * @param action - 后端返回的事件类型字符串
 */
function actionLabel(action: string) {
  return ACTION_LABELS[action] || action.replace(/_/g, ' ')
}

/**
 * 根据事件类型返回对应的 Badge 颜色基调
 * - 含 USER 或 ROLE 的返回 "destructive"（红色警告）
 * - 含 MATERIAL 的返回 "warning"（橙色）
 * - 其他返回 "outline"
 */
function actionTone(action: string) {
  if (action.includes('USER') || action.includes('ROLE')) return 'destructive'
  if (action.includes('MATERIAL')) return 'warning'
  return 'outline'
}

/**
 * 为每条日志生成操作描述文字，帮助管理员理解操作含义
 * @param log - 日志对象
 */
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

/**
 * 判断是否为普通使用行为（问答/上传等）
 * 这些行为不展示在系统日志中，会在页面中被过滤掉
 * @param action - 事件类型
 */
function isUsageAction(action: string) {
  return action === 'RAG_CHAT'
    || action === 'RAG_CHAT_STREAM'
    || action === 'UPLOAD_MATERIAL'
    || action === 'CREATE_UPLOAD_SESSION'
}

/**
 * LogsPage 组件
 * 无 Props，数据通过 useAdminLogs hook 从后端获取
 */
export function LogsPage() {
  // 当前页码（从 0 开始）
  const [page, setPage] = useState(0)
  // 搜索关键词
  const [keyword, setKeyword] = useState('')
  // 事件类型筛选，默认 'ALL' 表示全部
  const [actionFilter, setActionFilter] = useState('ALL')
  // 防抖关键词，输入 300ms 后才触发请求，避免频繁调用接口
  const debouncedKeyword = useDebounce(keyword, 300)

  // 从后端获取日志数据
  const { data, isLoading } = useAdminLogs({
    page,
    size: PAGE_SIZE,
    // 防抖后的空值不传后端，表示查看全部日志。
    keyword: debouncedKeyword || undefined,
  })

  // 过滤掉普通使用行为（问答/上传），只保留管理员审计操作
  const allItems = (data?.items ?? []).filter((item) => !isUsageAction(item.action))
  // 根据事件类型筛选（前端筛选，仅作用于当前页数据）
  const items = useMemo(
    // 后端负责分页和关键词，事件类型在当前页做轻量筛选。
    () => actionFilter === 'ALL' ? allItems : allItems.filter((item) => item.action === actionFilter),
    [actionFilter, allItems],
  )
  const total = allItems.length
  const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE))

  // 计算统计指标：角色变更数、资料变更数、操作者数量、最近事件时间
  const stats = useMemo(() => {
    const roleChanges = allItems.filter((item) => item.action === 'UPDATE_USER_ROLE').length
    const materialChanges = allItems.filter((item) => item.action === 'UPDATE_MATERIAL_STATUS').length
    // 使用 Set 去重统计操作者数量
    const actors = new Set(allItems.map((item) => item.actorUsername || item.actorUserId).filter(Boolean)).size
    const latest = allItems[0]?.createdAt || '-'
    return { roleChanges, materialChanges, actors, latest }
  }, [allItems])

  // 提取当前页中出现的所有事件类型（去重），用于筛选下拉框
  const actionOptions = useMemo(
    () => Array.from(new Set(allItems.map((item) => item.action))).filter(Boolean),
    [allItems],
  )

  // 表格列定义
  const columns = useMemo(
    () => [
      // 事件列：显示事件类型 Badge 和操作描述
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
      // 操作者列：显示用户名和用户 ID
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
      // 对象列：显示对象类型（用户/资料）和对象 ID
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
      // 变更内容列：解析 detail 字段中的模型和 Token 信息，截断展示
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
      // 时间列
      columnHelper.accessor('createdAt', {
        header: '时间',
        cell: (info) => (
          <span className="min-w-[130px] text-xs text-muted-foreground">{formatDate(info.getValue())}</span>
        ),
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
      transition={{ duration: 0.3 }}
    >
      {/* ========== 页面顶部标题区域 ========== */}
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

      {/* ========== 统计指标卡片 ========== */}
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 xl:grid-cols-4">
        <MetricCard icon={Activity} label="当前页事件" value={String(allItems.length)} hint="按时间倒序" />
        <MetricCard icon={UserCog} label="角色变更" value={String(stats.roleChanges)} hint="高敏感操作" tone="danger" />
        <MetricCard icon={FileWarning} label="资料状态变更" value={String(stats.materialChanges)} hint="影响解析展示" tone="warning" />
        <MetricCard icon={Clock} label="最近事件" value={stats.latest === '-' ? '-' : formatDate(stats.latest)} hint={`${stats.actors} 个操作者`} />
      </div>

      {/* ========== 审计明细表格 ========== */}
      <Card>
        <CardHeader className="space-y-3 border-b p-4">
          <div className="flex flex-col gap-3 lg:flex-row lg:items-center lg:justify-between">
            <CardTitle className="flex items-center gap-2 text-base">
              <ShieldCheck className="h-4 w-4 text-emerald-600" />
              审计明细
            </CardTitle>
            {/* 搜索框 + 事件类型筛选下拉框 */}
            <div className="flex flex-col gap-2 sm:flex-row">
              <div className="relative sm:w-80">
                <Search className="absolute left-2.5 top-2.5 h-4 w-4 text-muted-foreground" />
                <Input
                  placeholder="搜索操作者、事件、对象 ID、详情"
                  className="h-9 pl-8"
                  value={keyword}
                  onChange={(event) => {
                    setKeyword(event.target.value)
                    // 搜索条件改变后重置页码，避免停留在旧查询的高页码。
                    setPage(0) // 搜索时重置到第一页
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
          {/* 安全提示 */}
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
                  /* 加载中状态 */
                  <TableRow>
                    <TableCell colSpan={columns.length} className="py-8 text-center text-muted-foreground">
                      加载中...
                    </TableCell>
                  </TableRow>
                ) : items.length === 0 ? (
                  /* 无数据状态 */
                  <TableRow>
                    <TableCell colSpan={columns.length} className="py-8 text-center text-muted-foreground">
                      暂无匹配日志
                    </TableCell>
                  </TableRow>
                ) : (
                  /* 正常渲染行数据 */
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

      {/* ========== 分页控制 ========== */}
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

/**
 * 解析 detail 字符串为 key-value 对象
 * detail 格式为 "key1=value1,key2=value2"
 * 提取其中的 model 和 totalTokens 字段
 * @param value - detail 字符串
 */
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

/**
 * 统计指标卡片组件
 * @param icon - 图标组件
 * @param label - 指标名称
 * @param value - 指标值
 * @param hint - 提示文字
 * @param tone - 颜色基调：neutral（默认灰）、warning（橙）、danger（红）
 */
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
  // 根据 tone 选择图标背景色类名
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
