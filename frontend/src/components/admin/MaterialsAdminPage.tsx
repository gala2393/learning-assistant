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
 * - 提供 Qdrant 向量索引重建入口，用于首次启用 Qdrant 后回填历史资料
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
  useAdminDependencies,
  useAdminMaterials,
  useRebuildAdminVectorIndex,
  useUpdateAdminMaterialStatus,
} from '@/api/admin'
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
  RefreshCw,
  Search,
  XCircle,
} from 'lucide-react'
import type { AdminMaterial, SystemDependency } from '@/types'

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
 * 判断资料是否仍有后台流水线在运行。
 * 大 PDF 会先生成可阅读/可检索的占位片段，parseStatus 可能已经是 SUCCESS，
 * 但 OCR、文本补齐或索引补齐仍在后台继续处理，管理员页必须把这种状态显示出来。
 * @param material - 资料对象
 */
function isMaterialStillProcessing(material: AdminMaterial) {
  const parseActive = isParsing(material.parseStatus)
  const textActive = ['PENDING', 'RUNNING', 'PARTIAL'].includes(String(material.textStatus || ''))
  const indexActive = ['PENDING', 'RUNNING', 'PARTIAL'].includes(String(material.indexStatus || ''))
  const ocrActive = ['PENDING', 'RUNNING', 'PARTIAL'].includes(String(material.ocrStatus || ''))
  const progressActive = typeof material.processingProgressPercent === 'number'
    && material.processingProgressPercent < 100
    && !['FAILED', 'READY'].includes(String(material.textStatus || ''))
  return parseActive || textActive || indexActive || ocrActive || progressActive
}

/**
 * 计算管理员页展示用的综合进度。
 * 优先展示新流水线的 processingProgressPercent，缺失时再回退旧解析进度。
 * @param material - 资料对象
 */
function processingPercent(material: AdminMaterial) {
  const rawPercent = typeof material.processingProgressPercent === 'number'
    ? material.processingProgressPercent
    : material.parseProgressPercent
  if (typeof rawPercent === 'number') {
    return Math.max(0, Math.min(100, Math.round(rawPercent)))
  }
  return isMaterialStillProcessing(material) ? 0 : 100
}

/**
 * 获取管理员页展示的处理阶段文案。
 * 新流水线文案比旧 parseStage 更准确，例如 OCR 后台识别、向量索引补齐等。
 * @param material - 资料对象
 */
function processingDescription(material: AdminMaterial) {
  return material.processingStage
    || material.parseStage
    || material.processingMessage
    || material.parseMessage
    || '等待系统更新处理状态'
}

/**
 * 当前页状态筛选规则。
 * 选择“解析中”时同时包含 OCR/索引补齐中的资料，避免 SUCCESS + RUNNING 被误归为已完成。
 * @param material - 资料对象
 * @param filter - 筛选值
 */
function matchesStatusFilter(material: AdminMaterial, filter: string) {
  if (filter === 'ALL') return true
  if (filter === 'PARSING') return isMaterialStillProcessing(material)
  if (filter === 'SUCCESS') {
    return (material.parseStatus === 'SUCCESS' || material.parseStatus === 'PARSED')
      && !isMaterialStillProcessing(material)
  }
  return material.parseStatus === filter
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
  // 管理员提交向量索引重建后的提示文案
  const [vectorRebuildMessage, setVectorRebuildMessage] = useState('')

  // 从后端获取资料数据
  const { data, isLoading } = useAdminMaterials({
    page,
    size: PAGE_SIZE,
    // trim 后为空则不传 keyword，减少后端无效过滤分支。
    keyword: keyword.trim() || undefined,
  })
  // 运行环境依赖会影响 PDF 页数识别、Word/PPT 预览和 OCR，管理员需要在资料队列页直接看到缺失项。
  const { data: dependencies = [], isLoading: dependenciesLoading, refetch: refetchDependencies } = useAdminDependencies()
  // 修改资料状态的 mutation hook
  const updateStatusMutation = useUpdateAdminMaterialStatus()
  // Qdrant 向量索引重建 mutation hook
  const rebuildVectorIndexMutation = useRebuildAdminVectorIndex()

  const allItems = data?.items ?? []
  // 前端按解析状态筛选（仅作用于当前页数据）
  const items = useMemo(
    // 状态筛选只作用于当前页，跨页搜索仍由后端分页接口负责。
    () => allItems.filter((item) => matchesStatusFilter(item, statusFilter)),
    [allItems, statusFilter],
  )
  const total = data?.total ?? 0
  const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE))
  const qdrantDependency = dependencies.find((dependency) => dependency.name.toLowerCase() === 'qdrant')
  const qdrantReady = Boolean(qdrantDependency?.enabled && qdrantDependency.healthy)

  // 计算统计指标
  const stats = useMemo(() => {
    // 处理完成：兼容 SUCCESS/PARSED，且 OCR、文本、索引等后台流水线也已经结束。
    const success = allItems.filter((item) => (
      item.parseStatus === 'SUCCESS' || item.parseStatus === 'PARSED'
    ) && !isMaterialStillProcessing(item)).length
    const failed = allItems.filter((item) => item.parseStatus === 'FAILED').length
    const parsing = allItems.filter((item) => isMaterialStillProcessing(item)).length
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

  /** 提交全量向量索引重建任务，后台会复用已有 chunk 分批写入 Qdrant。 */
  const handleRebuildVectorIndex = () => {
    setVectorRebuildMessage('')
    rebuildVectorIndexMutation.mutate(undefined, {
      onSuccess: (result) => setVectorRebuildMessage(result.message),
      onError: (error) => setVectorRebuildMessage(error instanceof Error ? error.message : '向量索引重建任务提交失败'),
    })
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
          const isProcessing = isMaterialStillProcessing(material)
          const percent = processingPercent(material)
          return (
            <div className="min-w-[170px] space-y-2">
              <div className="flex items-center justify-between gap-2">
                <div className="flex flex-wrap items-center gap-1.5">
                  {statusBadge(info.getValue())}
                  {/* parseStatus 已成功但 OCR/索引仍在补齐时，额外标出真实后台状态。 */}
                  {isProcessing && !isParsing(info.getValue()) && <Badge variant="outline">后台处理中</Badge>}
                </div>
                {/* 处理中状态显示综合百分比 */}
                {isProcessing && <span className="text-xs tabular-nums text-muted-foreground">{percent}%</span>}
              </div>
              {/* 处理中状态显示渐变进度条 */}
              {isProcessing && (
                <div className="h-1.5 overflow-hidden rounded-full bg-slate-200 dark:bg-slate-800">
                  <div
                    className="h-full rounded-full bg-gradient-to-r from-[#2563eb] via-[#0f766e] to-[#65a30d]"
                    style={{ width: `${percent}%` }}
                  />
                </div>
              )}
              {/* 显示后台流水线阶段或旧解析阶段 */}
              <p className="line-clamp-1 text-xs text-muted-foreground">
                {processingDescription(material)}
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
          if (isMaterialStillProcessing(material)) {
            return <span className="text-xs text-amber-600">后台仍在补齐 OCR/索引，通常无需人工干预。</span>
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
              用于管理员查看全站资料导入后的解析质量：是否解析成功、生成了多少知识片段、是否仍在后台处理、哪些资料需要人工排查。这里的"人工标记"只修正状态，不会重新解析文件。
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
        <MetricCard icon={CheckCircle} label="处理完成" value={String(stats.success)} hint="已完成 OCR/索引" tone="success" />
        <MetricCard icon={BarChart3} label="后台处理中" value={String(stats.parsing)} hint="自动刷新后查看进度" tone="warning" />
        <MetricCard icon={FileWarning} label="解析失败" value={String(stats.failed)} hint="需排查格式或文件" tone="danger" />
        <MetricCard icon={Database} label="知识片段" value={String(stats.chunks)} hint={`文件 ${formatBytes(stats.storage)}`} />
      </div>

      <SystemDependencyPanel
        dependencies={dependencies}
        loading={dependenciesLoading}
        onRefresh={() => refetchDependencies()}
      />

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
              <Button
                type="button"
                variant="outline"
                size="sm"
                className="h-9 whitespace-nowrap"
                onClick={handleRebuildVectorIndex}
                disabled={!qdrantReady || rebuildVectorIndexMutation.isPending}
                title={qdrantReady ? '复用已有资料片段，后台分批写入 Qdrant' : '请先启用并重启 Qdrant'}
              >
                <RefreshCw className={`mr-1.5 h-3.5 w-3.5 ${rebuildVectorIndexMutation.isPending ? 'animate-spin' : ''}`} />
                重建向量索引
              </Button>
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
                  <SelectItem value="SUCCESS">处理完成</SelectItem>
                  <SelectItem value="PARSING">后台处理中</SelectItem>
                  <SelectItem value="PENDING">待解析</SelectItem>
                  <SelectItem value="FAILED">解析失败</SelectItem>
                </SelectContent>
              </Select>
            </div>
          </div>
          {/* 操作提示 */}
          <p className="text-xs text-muted-foreground">
            建议优先处理"解析失败"和"已解析但 0 片段"的资料；后台处理中资料不建议手动改状态，除非确认后台任务已经异常停止。
          </p>
          {vectorRebuildMessage && (
            <p className={`text-xs ${rebuildVectorIndexMutation.isError ? 'text-destructive' : 'text-emerald-600'}`}>
              {vectorRebuildMessage}
            </p>
          )}
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
 * 系统依赖自检面板。
 * 资料解析依赖外部命令，缺少 Poppler/LibreOffice/OCR 时不应只在后端日志里报错。
 */
function SystemDependencyPanel({
  dependencies,
  loading,
  onRefresh,
}: {
  dependencies: SystemDependency[]
  loading: boolean
  onRefresh: () => void
}) {
  const unhealthy = dependencies.filter((dependency) => dependency.enabled && !dependency.healthy)
  const disabled = dependencies.filter((dependency) => !dependency.enabled)
  const panelTone = unhealthy.length > 0 ? 'border-amber-200 bg-amber-50/60 dark:border-amber-900/50 dark:bg-amber-950/20' : 'border-emerald-200 bg-emerald-50/60 dark:border-emerald-900/50 dark:bg-emerald-950/20'

  return (
    <Card className={panelTone}>
      <CardContent className="p-4">
        <div className="flex flex-col gap-3 lg:flex-row lg:items-start lg:justify-between">
          <div className="min-w-0">
            <div className="flex flex-wrap items-center gap-2">
              {unhealthy.length > 0 ? (
                <AlertTriangle className="h-4 w-4 text-amber-600" />
              ) : (
                <CheckCircle className="h-4 w-4 text-emerald-600" />
              )}
              <h3 className="text-sm font-semibold">运行环境依赖</h3>
              {loading && <Badge variant="outline">检查中</Badge>}
              {!loading && unhealthy.length > 0 && <Badge variant="outline">{unhealthy.length} 项不可用</Badge>}
              {!loading && disabled.length > 0 && <Badge variant="secondary">{disabled.length} 项未启用</Badge>}
            </div>
            <p className="mt-1 text-xs leading-5 text-muted-foreground">
              Poppler 影响 PDF 页数、文本抽取和页面预览，LibreOffice 影响 Word/PPT 预览，OCR 影响扫描版 PDF 文字识别。
            </p>
          </div>
          <Button type="button" variant="outline" size="sm" className="h-8 w-fit" onClick={onRefresh} disabled={loading}>
            <RefreshCw className={`mr-1.5 h-3.5 w-3.5 ${loading ? 'animate-spin' : ''}`} />
            重新检查
          </Button>
        </div>
        <div className="mt-3 grid gap-2 md:grid-cols-2 xl:grid-cols-6">
          {dependencies.map((dependency) => (
            <div
              key={dependency.name}
              className="rounded-lg border border-white/70 bg-white/75 p-3 text-xs shadow-sm dark:border-slate-800 dark:bg-slate-950/40"
            >
              <div className="flex items-center justify-between gap-2">
                <span className="truncate font-medium" title={dependency.name}>{dependency.name}</span>
                <DependencyBadge dependency={dependency} />
              </div>
              <p className="mt-2 line-clamp-2 leading-5 text-muted-foreground" title={dependency.message}>
                {dependency.message}
              </p>
            </div>
          ))}
          {!loading && dependencies.length === 0 && (
            <div className="rounded-lg border border-dashed bg-white/70 p-3 text-xs text-muted-foreground dark:border-slate-800 dark:bg-slate-950/40">
              暂无依赖检查结果
            </div>
          )}
        </div>
      </CardContent>
    </Card>
  )
}

/** 按依赖状态渲染短标签，方便管理员快速扫出不可用项。 */
function DependencyBadge({ dependency }: { dependency: SystemDependency }) {
  if (!dependency.enabled) return <Badge variant="secondary">未启用</Badge>
  if (dependency.healthy) return <Badge variant="default">可用</Badge>
  return <Badge variant="destructive">不可用</Badge>
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
