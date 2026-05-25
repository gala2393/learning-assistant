import { useState, useMemo } from 'react'
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
import {
  Table, TableHeader, TableBody, TableRow, TableHead, TableCell,
} from '@/components/ui/table'
import { Card, CardContent } from '@/components/ui/card'
import { formatDate, formatBytes } from '@/lib/utils'
import { SOURCE_TYPE_LABELS, PARSE_STATUS_LABELS, PARSE_STATUS_COLORS } from '@/constants'
import { Folder, ChevronLeft, ChevronRight, CheckCircle, XCircle } from 'lucide-react'
import type { AdminMaterial } from '@/types'

const columnHelper = createColumnHelper<AdminMaterial>()

export function MaterialsAdminPage() {
  const [page, setPage] = useState(0)

  const { data, isLoading } = useAdminMaterials({ page, size: 10 })
  const updateStatusMutation = useUpdateAdminMaterialStatus()

  const items = data?.items ?? []
  const total = data?.total ?? 0
  const totalPages = Math.ceil(total / 10)

  const handleSetStatus = (id: string, status: string) => {
    updateStatusMutation.mutate({ id, payload: { parseStatus: status } })
  }

  const columns = useMemo(
    () => [
      columnHelper.accessor('title', {
        header: '标题',
        cell: (info) => <span className="font-medium max-w-[200px] truncate block">{info.getValue() || info.row.original.originalName}</span>,
      }),
      columnHelper.accessor('ownerUsername', {
        header: '上传者',
        cell: (info) => info.getValue() || '-',
      }),
      columnHelper.accessor('sourceType', {
        header: '类型',
        cell: (info) => (
          <Badge variant="outline">{SOURCE_TYPE_LABELS[info.getValue()] || info.getValue()}</Badge>
        ),
      }),
      columnHelper.accessor('parseStatus', {
        header: '状态',
        cell: (info) => {
          const status = info.getValue()
          const color = PARSE_STATUS_COLORS[status] || 'secondary'
          return <Badge variant={color as any}>{PARSE_STATUS_LABELS[status] || status}</Badge>
        },
      }),
      columnHelper.accessor('chunkCount', {
        header: '片段',
        cell: (info) => info.getValue(),
      }),
      columnHelper.accessor('fileSize', {
        header: '大小',
        cell: (info) => info.getValue() > 0 ? formatBytes(info.getValue()) : '-',
      }),
      columnHelper.display({
        id: 'actions',
        header: '操作',
        cell: (info) => {
          const m = info.row.original
          return (
            <div className="flex items-center gap-1">
              <Button
                variant="ghost" size="sm" className="h-7 text-xs"
                onClick={() => handleSetStatus(m.id, 'SUCCESS')}
                disabled={updateStatusMutation.isPending || m.parseStatus === 'SUCCESS'}
              >
                <CheckCircle className="h-3.5 w-3.5 mr-1 text-emerald-500" /> 通过
              </Button>
              <Button
                variant="ghost" size="sm" className="h-7 text-xs"
                onClick={() => handleSetStatus(m.id, 'FAILED')}
                disabled={updateStatusMutation.isPending || m.parseStatus === 'FAILED'}
              >
                <XCircle className="h-3.5 w-3.5 mr-1 text-destructive" /> 失败
              </Button>
            </div>
          )
        },
      }),
    ],
    [updateStatusMutation.isPending]
  )

  const table = useReactTable({
    data: items,
    columns,
    getCoreRowModel: getCoreRowModel(),
  })

  return (
    <motion.div
      className="p-6 space-y-4"
      initial={{ opacity: 0, y: 12 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.3 }}
    >
      <div className="flex items-center justify-between">
        <h2 className="text-lg font-semibold flex items-center gap-2">
          <Folder className="h-5 w-5" /> 资料队列
        </h2>
        <span className="text-sm text-muted-foreground">共 {total} 份资料</span>
      </div>

      <Card>
        <CardContent className="p-0">
          <Table>
            <TableHeader>
              {table.getHeaderGroups().map((hg) => (
                <TableRow key={hg.id}>
                  {hg.headers.map((header) => (
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
                  <TableCell colSpan={columns.length} className="text-center py-8 text-muted-foreground">
                    加载中...
                  </TableCell>
                </TableRow>
              ) : items.length === 0 ? (
                <TableRow>
                  <TableCell colSpan={columns.length} className="text-center py-8 text-muted-foreground">
                    暂无数据
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
        </CardContent>
      </Card>

      {/* Pagination */}
      {totalPages > 1 && (
        <div className="flex items-center justify-center gap-2">
          <Button
            variant="outline" size="sm"
            disabled={page === 0}
            onClick={() => setPage((p) => p - 1)}
          >
            <ChevronLeft className="h-4 w-4" />
          </Button>
          <span className="text-sm text-muted-foreground">
            第 {page + 1} / {totalPages} 页
          </span>
          <Button
            variant="outline" size="sm"
            disabled={page >= totalPages - 1}
            onClick={() => setPage((p) => p + 1)}
          >
            <ChevronRight className="h-4 w-4" />
          </Button>
        </div>
      )}
    </motion.div>
  )
}
