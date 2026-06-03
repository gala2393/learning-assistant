import { useState, useMemo } from 'react'
import { motion } from 'framer-motion'
import {
  useReactTable,
  getCoreRowModel,
  flexRender,
  createColumnHelper,
} from '@tanstack/react-table'
import { useAdminUsers, useUpdateAdminUserRole, useUpdateAdminUserStatus } from '@/api/admin'
import { useDebounce } from '@/hooks/useDebounce'
import { Input } from '@/components/ui/input'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import {
  Table, TableHeader, TableBody, TableRow, TableHead, TableCell,
} from '@/components/ui/table'
import { Card, CardContent } from '@/components/ui/card'
import { formatDate } from '@/lib/utils'
import { Search, Users, ChevronLeft, ChevronRight, Shield, User, Ban, RotateCcw } from 'lucide-react'
import type { AdminUser } from '@/types'

const columnHelper = createColumnHelper<AdminUser>()
const PAGE_SIZE = 10

export function UsersPage() {
  const [page, setPage] = useState(0)
  const [keyword, setKeyword] = useState('')
  const debouncedKeyword = useDebounce(keyword, 300)

  const { data, isLoading } = useAdminUsers({
    page,
    size: PAGE_SIZE,
    keyword: debouncedKeyword || undefined,
  })

  const updateRoleMutation = useUpdateAdminUserRole()
  const updateStatusMutation = useUpdateAdminUserStatus()

  const items = data?.items ?? []
  const total = data?.total ?? 0
  const totalPages = Math.ceil(total / PAGE_SIZE)
  const actionPending = updateRoleMutation.isPending || updateStatusMutation.isPending

  const handleToggleRole = (user: AdminUser) => {
    const newRole = user.role === 'ADMIN' ? 'USER' : 'ADMIN'
    updateRoleMutation.mutate({ id: user.id, role: newRole })
  }

  const handleToggleStatus = (user: AdminUser) => {
    const newStatus = user.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE'
    updateStatusMutation.mutate({ id: user.id, status: newStatus })
  }

  const columns = useMemo(
    () => [
      columnHelper.accessor('username', {
        header: '用户名',
        cell: (info) => <span className="font-medium">{info.getValue()}</span>,
      }),
      columnHelper.accessor('nickname', {
        header: '昵称',
        cell: (info) => info.getValue() || '-',
      }),
      columnHelper.accessor('role', {
        header: '角色',
        cell: (info) => {
          const role = info.getValue()
          return (
            <Badge variant={role === 'ADMIN' ? 'default' : 'secondary'}>
              {role === 'ADMIN' ? '管理员' : '普通用户'}
            </Badge>
          )
        },
      }),
      columnHelper.accessor('status', {
        header: '状态',
        cell: (info) => (
          <Badge variant={info.getValue() === 'ACTIVE' ? 'success' : 'destructive'}>
            {info.getValue() === 'ACTIVE' ? '正常' : '禁用'}
          </Badge>
        ),
      }),
      columnHelper.accessor('createdAt', {
        header: '注册时间',
        cell: (info) => <span className="text-xs text-muted-foreground">{formatDate(info.getValue())}</span>,
      }),
      columnHelper.display({
        id: 'actions',
        header: '操作',
        cell: (info) => {
          const user = info.row.original
          const disabled = user.status === 'DISABLED'
          return (
            <div className="flex flex-wrap gap-2">
              <Button
                variant="outline"
                size="sm"
                className="h-7 text-xs"
                onClick={() => handleToggleRole(user)}
                disabled={actionPending}
              >
                {user.role === 'ADMIN' ? (
                  <><User className="mr-1 h-3.5 w-3.5" /> 降为用户</>
                ) : (
                  <><Shield className="mr-1 h-3.5 w-3.5" /> 升为管理员</>
                )}
              </Button>
              <Button
                variant={disabled ? 'outline' : 'destructive'}
                size="sm"
                className="h-7 text-xs"
                onClick={() => handleToggleStatus(user)}
                disabled={actionPending}
              >
                {disabled ? (
                  <><RotateCcw className="mr-1 h-3.5 w-3.5" /> 解除禁用</>
                ) : (
                  <><Ban className="mr-1 h-3.5 w-3.5" /> 禁止登录</>
                )}
              </Button>
            </div>
          )
        },
      }),
    ],
    [actionPending],
  )

  const table = useReactTable({
    data: items,
    columns,
    getCoreRowModel: getCoreRowModel(),
  })

  return (
    <motion.div
      className="space-y-4 p-6"
      initial={{ opacity: 0, y: 12 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.3 }}
    >
      <div className="flex items-center justify-between">
        <h2 className="flex items-center gap-2 text-lg font-semibold">
          <Users className="h-5 w-5" /> 用户与角色
        </h2>
        <span className="text-sm text-muted-foreground">共 {total} 位用户</span>
      </div>

      <div className="relative max-w-sm">
        <Search className="absolute left-2.5 top-2.5 h-4 w-4 text-muted-foreground" />
        <Input
          placeholder="搜索用户名、昵称、角色或状态..."
          className="h-9 pl-8"
          value={keyword}
          onChange={(e) => {
            setKeyword(e.target.value)
            setPage(0)
          }}
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
                  <TableCell colSpan={columns.length} className="py-8 text-center text-muted-foreground">
                    加载中...
                  </TableCell>
                </TableRow>
              ) : items.length === 0 ? (
                <TableRow>
                  <TableCell colSpan={columns.length} className="py-8 text-center text-muted-foreground">
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

      {totalPages > 1 && (
        <div className="flex items-center justify-center gap-2">
          <Button
            variant="outline"
            size="sm"
            disabled={page === 0}
            onClick={() => setPage((p) => p - 1)}
          >
            <ChevronLeft className="h-4 w-4" />
          </Button>
          <span className="text-sm text-muted-foreground">
            第 {page + 1} / {totalPages} 页
          </span>
          <Button
            variant="outline"
            size="sm"
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
