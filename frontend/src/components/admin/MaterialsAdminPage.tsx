/**
 * 资料管理页面 - MaterialsAdminPage
 *
 * 路由：由 AdminLayout 渲染，路径通常为 /admin/materials
 * 用途：管理员查看全站资料导入后的解析质量，包括是否解析成功、
 *       生成了多少知识片段、是否仍在后台处理、哪些资料需要人工排查。
 *       注意：此页面的"人工标记"只修正状态标签，不会重新解析文件或重建向量索引。
 *
 * 主要功能：
 * - 从后端 useAdminMaterials 获取资料列表，支持分页和关键词搜索
 * - 前端支持按解析状态（全部/已解析/解析中/待解析/解析失败）筛选
 * - 展示统计指标卡片（当前页资料/解析成功/解析中/解析失败/知识片段数）
 * - 表格展示每份资料的详情，包括标题、类型、解析状态（含进度条）、知识片段数、文件大小
 * - 提供"处理建议"列，根据状态给出操作指引
 * - 提供"人工标记"列，可手动将资料标记为正常或异常
 */

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

/** TanStack Table 列辅助工具 */
const columnHelper = createColumnHelper<AdminMaterial>()
/** 每页显示条数 */
const PAGE_SIZE = 12

/**
 * 判断资料是否处于"解析中"状态
 * PARSING、PROCESSING、PENDING 都算正在处理
 * @param status - 解析状态字符串
 */
function isParsing(status: string) {
  return status === 'PARSING' || status === 'PROCESSING' || status === 'PENDING'
}

/**
 * 计算解析进度百分比
 * 优先使用后端返回的 parseProgressPercent，否则根据状态判断
 * @param material - 资料对象
 */
function parsePercent(material: AdminMaterial) {
  if (typeof material.parseProgressPercent === 'number') {
    return Math.max(0, Math.min(100, Math.round(material.parseProgressPercent)))
  }
  // 如果是解析中状态但无进度，返回 0；否则认为已完成返回 100
  return isParsing(material.parseStatus) ? 0 : 100
}

/**
 * 渲染解析状态 Badge
 * 根据状态从常量中获取颜色映射
 * @param status - 解析状态字符串
 */
function statusBadge(status: string) {
  const color = PARSE_STATUS_COLORS[status] || 'secondary'
  return <Badge variant={color as any}>{PARSE_STATUS_LABELS[status] || status}</Badge>
}

/**
 * MaterialsAdminPage 组件
 * 无 Props，数据通过 useAdminMaterials hook 从后端获取
 */
export function MaterialsAdminPage() {
  // 当前页码（从 0 开始）
  const [page, setPage] = useState(0)
  // 搜索关键词
  const [keyword, setKeyword] = useState('')
  // 解析状态筛选，默认 'ALL' 表示全部
  const [statusFilter, setStatusFilter] = useState('ALL')

  // 从后端获取资料数据
  const { data, isLoading } = useAdminMaterials({
    page,
    size: PAGE_SIZE,
    // trim 后为空则不传 keyword，减少后端无效过滤分支。
    keyword: keyword.trim() || undefined,
  })
  // 修改资料状态的 mutation hook
  const updateStatusMutation = useUpdateAdminMaterialStatus()

  const allItems = data?.items ?? []
  // 前端按解析状态筛选（仅作用于当前页数据）
  const items = useMemo(
    // 状态筛选只作用于当前页，跨页搜索仍由后端分页接口负责。
    () => statusFilter === 'ALL' ? allItems : allItems.filter((item) => item.parseStatus === statusFilter),
    [allItems, statusFilter],
  )
  const total = data?.total ?? 0
  const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE))

  // 计算统计指标
  const stats = useMemo(() => {
    // 解析成功（兼容 SUCCESS 和 PARSED 两种状态值）
    const success = allItems.filter((item) => item.parseStatus === 'SUCCESS' || item.parseStatus === 'PARSED').length
    const failed = allItems.filter((item) => item.parseStatus === 'FAILED').length
    const parsing = allItems.filter((item) => isParsing(item.parseStatus)).length
    // 知识片段总数
    const chunks = allItems.reduce((sum, item) => sum + (item.chunkCount || 0), 0)
    // 文件总大小
    const storage = allItems.reduce((sum, item) => sum + (item.fileSize || 0), 0)
    return { success, failed, parsing, chunks, storage }
  }, [allItems])

  /**
   * 人工标记资料状态（仅修正状态标签，不触发重新解析）
   * @param id - 资料 ID
   * @param status - 目标状态（'SUCCESS' 或 'FAILED'）
   */
  const handleSetStatus = (id: string, status: string) => {
    // 人工标记只修正 parseStatus，summaryStatus 等其他状态保持不变。
    updateStatusMutation.mutate({ id, payload: { parseStatus: status } })
  }

  // 表格列定义
  const columns = useMemo(
    () => [
      // 资料列：标题/原始文件名、上传者、上传时间
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
      // 类型列：资料来源类型（上传/链接等）
      columnHelper.accessor('sourceType', {
        header: '类型',
        cell: (info) => (
          <Badge variant="outline">{SOURCE_TYPE_LABELS[info.getValue()] || info.getValue()}</Badge>
        ),
      }),
      // 解析状态列：状态 Badge + 进度条 + 阶段描述
      columnHelper.accessor('parseStatus', {
        header: '解析状态',
        cell: (info) => {
          const material = info.row.original
          const percent = parsePercent(material)
          return (
            <div className="min-w-[170px] space-y-2">
              <div className="flex items-center justify-between gap-2">
                {statusBadge(info.getValue())}
                {/* 解析中状态显示百分比 */}
                {isParsing(info.getValue()) && <span className="text-xs tabular-nums text-muted-foreground">{percent}%</span>}
              </div>
              {/* 解析中状态显示渐变进度条 */}
              {isParsing(info.getValue()) && (
                <div className="h-1.5 overflow-hidden rounded-full bg-slate-200 dark:bg-slate-800">
                  <div
                    className="h-full rounded-full bg-gradient-to-r from-[#2563eb] via-[#0f766e] to-[#65a30d]"
                    style={{ width: `${percent}%` }}
                  />
                </div>
              )}
              {/* 显示解析阶段或提示信息 */}
              <p className="line-clamp-1 text-xs text-muted-foreground">
                {material.parseStage || material.parseMessage || '等待系统更新解析状态'}
              </p>
            </div>
          )
        },
      }),
      // 知识片段数列
      columnHelper.accessor('chunkCount', {
        header: '知识片段',
        cell: (info) => <span className="tabular-nums">{info.getValue() || 0}</span>,
      }),
      // 文件大小列
      columnHelper.accessor('fileSize', {
        header: '文件大小',
        cell: (info) => info.getValue() > 0 ? formatBytes(info.getValue()) : '-',
      }),
      // 处理建议列（非数据列，仅展示操作指引）
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
      // 人工标记列（非数据列，提供手动修正状态的按钮）
      columnHelper.display({
        id: 'actions',
        header: '人工标记',
        cell: (info) => {
          const material = info.row.original
          return (
            <div className="flex min-w-[150px] items-center gap-1">
              {/* 标记为正常按钮 */}
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
              {/* 标记为异常按钮 */}
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
    [updateStatusMutation.isPending], // mutation 状态变化时重新计算列（更新按钮 disabled 状态）
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
              <FolderSearch className="h-5 w-5" /> 资料分析
            </h2>
            <p className="mt-2 max-w-3xl text-sm leading-6 text-muted-foreground">
              用于管理员查看全站资料导入后的解析质量：是否解析成功、生成了多少知识片段、是否仍在后台处理、哪些资料需要人工排查。这里的"人工标记"只修正状态，不会重新解析文件或重建向量索引。
            </p>
          </div>
          <Badge variant="outline" className="w-fit">
            共 {total} 份资料
          </Badge>
        </div>
      </section>

      {/* ========== 统计指标卡片 ========== */}
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 xl:grid-cols-5">
        <MetricCard icon={Database} label="当前页资料" value={String(allItems.length)} hint="按上传时间倒序" />
        <MetricCard icon={CheckCircle} label="解析成功" value={String(stats.success)} hint="可用于问答" tone="success" />
        <MetricCard icon={BarChart3} label="解析中" value={String(stats.parsing)} hint="自动刷新后查看进度" tone="warning" />
        <MetricCard icon={FileWarning} label="解析失败" value={String(stats.failed)} hint="需排查格式或文件" tone="danger" />
        <MetricCard icon={Database} label="知识片段" value={String(stats.chunks)} hint={`文件 ${formatBytes(stats.storage)}`} />
      </div>

      {/* ========== 资料明细表格 ========== */}
      <Card>
        <CardHeader className="space-y-3 border-b p-4">
          <div className="flex flex-col gap-3 lg:flex-row lg:items-center lg:justify-between">
            <CardTitle className="flex items-center gap-2 text-base">
              <AlertTriangle className="h-4 w-4 text-amber-600" />
              解析明细与人工处理
            </CardTitle>
            {/* 搜索框 + 状态筛选下拉框 */}
            <div className="flex flex-col gap-2 sm:flex-row">
              <div className="relative sm:w-72">
                <Search className="absolute left-2.5 top-2.5 h-4 w-4 text-muted-foreground" />
                <Input
                  className="h-9 pl-8"
                  placeholder="搜索标题、上传者、原文件名"
                  value={keyword}
                  onChange={(event) => {
                    setKeyword(event.target.value)
                    setPage(0) // 搜索时重置到第一页
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
          {/* 操作提示 */}
          <p className="text-xs text-muted-foreground">
            建议优先处理"解析失败"和"已解析但 0 片段"的资料；解析中资料不建议手动改状态，除非确认后台任务已经异常停止。
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
                      暂无匹配资料
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

/**
 * 统计指标卡片组件
 * @param icon - 图标组件
 * @param label - 指标名称
 * @param value - 指标值
 * @param hint - 提示文字
 * @param tone - 颜色基调：neutral（灰）、success（绿）、warning（橙）、danger（红）
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
  tone?: 'neutral' | 'success' | 'warning' | 'danger'
}) {
  // 根据 tone 选择图标背景色类名
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
