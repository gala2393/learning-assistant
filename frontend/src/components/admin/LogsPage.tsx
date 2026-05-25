import { useState, useMemo } from 'react'
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
  Table, TableHeader, TableBody, TableRow, TableHead, TableCell,
} from '@/components/ui/table'
import { Card, CardContent } from '@/components/ui/card'
import { formatDate, truncate } from '@/lib/utils'
import { Search, ScrollText, ChevronLeft, ChevronRight } from 'lucide-react'
import type { AdminLog } from '@/types'

const columnHelper = createColumnHelper<AdminLog>()

export function LogsPage() {
  const [page, setPage] = useState(0)
  const [keyword, setKeyword] = useState('')
  const debouncedKeyword = useDebounce(keyword, 300)

  const { data, isLoading } = useAdminLogs({
    page,
    size: 10,
    keyword: debouncedKeyword || undefined,
  })

  const items = data?.items ?? []
  const total = data?.total ?? 0
  const totalPages = Math.ceil(total / 10)

  const columns = useMemo(
    () => [
      columnHelper.accessor('action', {
        header: '操作',
        cell: (info) => (
          <Badge variant="outline" className="text-xs">{info.getValue()}</Badge>
        ),
      }),
      columnHelper.accessor('actorUsername', {
        header: '操作者',
        cell: (info) => info.getValue() || '-',
      }),
      columnHelper.accessor('targetType', {
        header: '目标类型',
        cell: (info) => info.getValue() || '-',
      }),
      columnHelper.accessor('targetId', {
        header: '目标 ID',
        cell: (info) => (
          <span className="text-xs font-mono text-muted-foreground">{truncate(info.getValue(), 12)}</span>
        ),
      }),
      columnHelper.accessor('detail', {
        header: '详情',
        cell: (info) => (
          <span className="text-xs text-muted-foreground">{truncate(info.getValue(), 40) || '-'}</span>
        ),
      }),
      columnHelper.accessor('createdAt', {
        header: '时间',
        cell: (info) => <span className="text-xs text-muted-foreground">{formatDate(info.getValue())}</span>,
      }),
    ],
    []
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
          <ScrollText className="h-5 w-5" /> 系统日志
        </h2>
        <span className="text-sm text-muted-foreground">共 {total} 条日志</span>
      </div>

      <div className="relative max-w-sm">
        <Search className="absolute left-2.5 top-2.5 h-4 w-4 text-muted-foreground" />
        <Input
          placeholder="搜索操作或操作者..."
          className="pl-8 h-9"
          value={keyword}
          onChange={(e) => { setKeyword(e.target.value); setPage(0) }}
        />
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
