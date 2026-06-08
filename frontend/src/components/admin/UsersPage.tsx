/**
 * 用户管理页面 - UsersPage
 *
 * 路由：由 AdminLayout 渲染，路径通常为 /admin/users
 * 用途：管理员查看和管理系统中的所有用户，包括角色升降、账号禁用/启用等操作。
 *
 * 主要功能：
 * - 从后端 useAdminUsers 获取用户列表，支持分页和关键词搜索（防抖 300ms）
 * - 表格展示用户信息：用户名、昵称、角色、状态、注册时间
 * - 支持切换用户角色（管理员 <-> 普通用户）
 * - 支持切换用户状态（正常 <-> 禁用）
 * - 操作按钮在请求进行中自动禁用，防止重复提交
 */

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

/** TanStack Table 列辅助工具 */
const columnHelper = createColumnHelper<AdminUser>()
/** 每页显示条数 */
const PAGE_SIZE = 10

/**
 * UsersPage 组件
 * 无 Props，数据通过 useAdminUsers hook 从后端获取
 */
export function UsersPage() {
  // 当前页码（从 0 开始）
  const [page, setPage] = useState(0)
  // 搜索关键词
  const [keyword, setKeyword] = useState('')
  // 防抖关键词，输入 300ms 后才触发请求
  const debouncedKeyword = useDebounce(keyword, 300)

  // 从后端获取用户数据
  const { data, isLoading } = useAdminUsers({
    page,
    size: PAGE_SIZE,
    // 空字符串不传给后端，避免把“无搜索”误当成一个关键词条件。
    keyword: debouncedKeyword || undefined,
  })

  // 修改用户角色的 mutation hook
  const updateRoleMutation = useUpdateAdminUserRole()
  // 修改用户状态的 mutation hook
  const updateStatusMutation = useUpdateAdminUserStatus()

  const items = data?.items ?? []
  const total = data?.total ?? 0
  const totalPages = Math.ceil(total / PAGE_SIZE)
  // 是否有操作正在进行（任一 mutation 处于 pending 状态时禁用所有按钮）
  const actionPending = updateRoleMutation.isPending || updateStatusMutation.isPending

  /**
   * 切换用户角色（管理员 <-> 普通用户）
   * @param user - 当前用户对象
   */
  const handleToggleRole = (user: AdminUser) => {
    const newRole = user.role === 'ADMIN' ? 'USER' : 'ADMIN'
    // 只提交目标角色，列表刷新交给 mutation 成功后的缓存失效处理。
    updateRoleMutation.mutate({ id: user.id, role: newRole })
  }

  /**
   * 切换用户状态（正常 <-> 禁用）
   * @param user - 当前用户对象
   */
  const handleToggleStatus = (user: AdminUser) => {
    const newStatus = user.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE'
    // 禁用/启用属于服务端权限状态，成功后由后端返回的列表作为最终真相。
    updateStatusMutation.mutate({ id: user.id, status: newStatus })
  }

  // 表格列定义
  const columns = useMemo(
    () => [
      // 用户名列
      columnHelper.accessor('username', {
        header: '用户名',
        cell: (info) => <span className="font-medium">{info.getValue()}</span>,
      }),
      // 昵称列
      columnHelper.accessor('nickname', {
        header: '昵称',
        cell: (info) => info.getValue() || '-',
      }),
      // 角色列：管理员显示默认样式，普通用户显示次要样式
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
      // 状态列：正常显示绿色，禁用显示红色
      columnHelper.accessor('status', {
        header: '状态',
        cell: (info) => (
          <Badge variant={info.getValue() === 'ACTIVE' ? 'success' : 'destructive'}>
            {info.getValue() === 'ACTIVE' ? '正常' : '禁用'}
          </Badge>
        ),
      }),
      // 注册时间列
      columnHelper.accessor('createdAt', {
        header: '注册时间',
        cell: (info) => <span className="text-xs text-muted-foreground">{formatDate(info.getValue())}</span>,
      }),
      // 操作列：角色切换 + 状态切换按钮
      columnHelper.display({
        id: 'actions',
        header: '操作',
        cell: (info) => {
          const user = info.row.original
          const disabled = user.status === 'DISABLED'
          return (
            <div className="flex flex-wrap gap-2">
              {/* 角色切换按钮：根据当前角色显示不同文字和图标 */}
              <Button
                variant="outline"
                size="sm"
                className="h-7 text-xs"
                onClick={() => handleToggleRole(user)}
                disabled={actionPending} // 操作进行中禁用
              >
                {user.role === 'ADMIN' ? (
                  <><User className="mr-1 h-3.5 w-3.5" /> 降为用户</>
                ) : (
                  <><Shield className="mr-1 h-3.5 w-3.5" /> 升为管理员</>
                )}
              </Button>
              {/* 状态切换按钮：禁用时显示"解除禁用"，正常时显示"禁止登录" */}
              <Button
                variant={disabled ? 'outline' : 'destructive'}
                size="sm"
                className="h-7 text-xs"
                onClick={() => handleToggleStatus(user)}
                disabled={actionPending} // 操作进行中禁用
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
    [actionPending], // 操作状态变化时重新计算列（更新按钮 disabled 状态）
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
      className="space-y-4 p-6"
      initial={{ opacity: 0, y: 12 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.3 }}
    >
      {/* ========== 页面顶部标题行 ========== */}
      <div className="flex items-center justify-between">
        <h2 className="flex items-center gap-2 text-lg font-semibold">
          <Users className="h-5 w-5" /> 用户与角色
        </h2>
        <span className="text-sm text-muted-foreground">共 {total} 位用户</span>
      </div>

      {/* ========== 搜索框 ========== */}
      <div className="relative max-w-sm">
        <Search className="absolute left-2.5 top-2.5 h-4 w-4 text-muted-foreground" />
        <Input
          placeholder="搜索用户名、昵称、角色或状态..."
          className="h-9 pl-8"
          value={keyword}
          onChange={(e) => {
            setKeyword(e.target.value)
            // 搜索条件变化后当前页可能不存在，回到第一页避免空页误导。
            setPage(0) // 搜索时重置到第一页
          }}
        />
      </div>

      {/* ========== 用户列表表格 ========== */}
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
                    暂无数据
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
        </CardContent>
      </Card>

      {/* ========== 分页控制（仅在超过 1 页时显示） ========== */}
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
