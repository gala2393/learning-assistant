/**
 * MaterialsPage - 资料管理页面
 *
 * 功能说明：
 * - 展示所有学习资料的列表（网格布局）
 * - 支持文件上传导入（分片上传，带进度条）
 * - 支持关键词搜索、按类型/状态过滤
 * - 支持资料的编辑、删除、重新解析操作
 * - 支持"继续阅读"跳转到阅读器、"原文件"打开原始文档
 * - 底部显示当前选中资料的摘要信息栏
 *
 * 数据流：
 * 1. useMaterials() 获取资料列表
 * 2. 搜索使用 useDebounce 300ms 防抖
 * 3. 上传使用 uploadMaterialInChunks() 分片上传，通过回调更新进度
 * 4. 上传/编辑/删除/重新解析均通过对应的 mutation 执行
 */
import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { motion } from 'framer-motion'
import { MaterialUploadForm } from './MaterialUploadForm'
import { MaterialGrid } from './MaterialGrid'
import { queryClient } from '@/lib/query-client'
import {
  useMaterials,
  useDeleteMaterial,
  useUpdateMaterial,
  useReparseMaterial,
  createMaterialFileTicket,
  uploadMaterialInChunks,
} from '@/api/materials'
import { useDebounce } from '@/hooks/useDebounce'
import { useToast } from '@/components/ui/toast'
import { Input } from '@/components/ui/input'
import { Button } from '@/components/ui/button'
import {
  Select, SelectTrigger, SelectValue, SelectContent, SelectItem,
} from '@/components/ui/select'
import {
  Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription, DialogFooter,
} from '@/components/ui/dialog'
import { Search, Trash2, BookOpen, Loader2, Upload } from 'lucide-react'
import { SOURCE_TYPE_LABELS, PARSE_STATUS_LABELS } from '@/constants'
import { formatDate, formatBytes, inferSourceType, cn } from '@/lib/utils'
import type { Material } from '@/types'
import type { UploadProgress } from '@/api/materials'

export function MaterialsPage() {
  const navigate = useNavigate()
  const { showToast } = useToast()
  // 获取资料列表和各种 mutation
  const { data: materials = [], isLoading } = useMaterials()
  const deleteMutation = useDeleteMaterial()
  const updateMutation = useUpdateMaterial()
  const reparseMutation = useReparseMaterial()

  // ---- 状态管理 ----
  const [keyword, setKeyword] = useState('')                    // 搜索关键词
  const [sourceTypeFilter, setSourceTypeFilter] = useState<string>('ALL')  // 资料类型过滤
  const [statusFilter, setStatusFilter] = useState<string>('ALL')          // 解析状态过滤
  const [selected, setSelected] = useState<Material | null>(null)          // 当前选中的资料
  const [editTarget, setEditTarget] = useState<Material | null>(null)      // 编辑弹窗目标
  const [deleteTarget, setDeleteTarget] = useState<Material | null>(null)  // 删除弹窗目标
  const [editTitle, setEditTitle] = useState('')                           // 编辑中的标题
  const [uploading, setUploading] = useState(false)                        // 是否正在上传
  const [uploadProgress, setUploadProgress] = useState<UploadProgress | null>(null)  // 上传进度
  const [uploadOpen, setUploadOpen] = useState(false)                      // 移动端上传表单展开状态

  // 搜索关键词防抖
  const debouncedKeyword = useDebounce(keyword, 300)

  // 综合过滤：类型 + 状态 + 关键词
  const filtered = materials.filter((material) => {
    if (sourceTypeFilter !== 'ALL' && material.sourceType !== sourceTypeFilter) return false
    if (statusFilter !== 'ALL' && material.parseStatus !== statusFilter) return false
    if (debouncedKeyword) {
      const kw = debouncedKeyword.toLowerCase()
      return (material.title || '').toLowerCase().includes(kw) || (material.originalName || '').toLowerCase().includes(kw)
    }
    return true
  })

  // ---- 上传处理 ----

  /**
   * 处理文件上传：使用分片上传策略
   * 1. 调用 uploadMaterialInChunks 进行分片上传
   * 2. 上传过程中通过 setUploadProgress 回调更新进度
   * 3. 上传成功后刷新资料列表并关闭弹窗
   */
  const handleUpload = async (data: { title?: string; file?: File }) => {
    if (!data.file) return
    setUploading(true)
    setUploadProgress(null)
    try {
      await uploadMaterialInChunks({
        file: data.file,
        title: data.title,
        sourceType: inferSourceType(data.file.name),  // 根据文件扩展名推断类型
      }, setUploadProgress)
      await queryClient.invalidateQueries({ queryKey: ['materials'] })  // 刷新列表
      setUploadOpen(false)
      showToast('资料上传成功')
    } catch (error) {
      const message = error instanceof Error ? error.message : '上传失败，请重试'
      showToast(message)
    } finally {
      setUploading(false)
      setUploadProgress(null)
    }
  }

  // ---- 编辑处理 ----

  /** 打开编辑弹窗，初始化标题输入 */
  const handleEdit = (material: Material) => {
    setEditTarget(material)
    setEditTitle(material.title)
  }

  /** 保存编辑结果 */
  const handleEditSave = () => {
    if (editTarget && editTitle.trim()) {
      updateMutation.mutate({ id: editTarget.id, payload: { title: editTitle.trim() } }, {
        onSuccess: () => {
          setEditTarget(null)
          showToast('资料已更新')
        },
        onError: () => showToast('更新失败'),
      })
    }
  }

  // ---- 删除处理 ----

  /** 打开删除确认弹窗 */
  const handleDelete = (material: Material) => {
    setDeleteTarget(material)
  }

  /** 确认删除资料 */
  const handleDeleteConfirm = () => {
    if (deleteTarget) {
      deleteMutation.mutate(deleteTarget.id, {
        onSuccess: () => {
          setDeleteTarget(null)
          // 如果正在查看被删除的资料，清除选中状态
          if (selected?.id === deleteTarget.id) setSelected(null)
          showToast('资料已删除')
        },
        onError: () => showToast('删除失败'),
      })
    }
  }

  // ---- 阅读和原文件 ----

  /** 跳转到阅读器页面，继续阅读指定资料 */
  const handleContinueReading = (material: Material) => {
    navigate(`/workspace/reader?materialId=${encodeURIComponent(material.id)}`)
  }

  /**
   * 打开资料原文件
   * 通过后端接口获取临时访问链接（ticket），然后在新窗口打开
   */
  const handleOpenFile = async (material: Material) => {
    const opened = window.open('', '_blank')
    try {
      const ticket = await createMaterialFileTicket(material.id)
      if (opened) {
        opened.location.href = ticket.url
      } else {
        showToast('浏览器拦截了新窗口，请允许弹窗后再试')
      }
    } catch (error) {
      opened?.close()
      showToast(error instanceof Error ? error.message : '原文件打开失败')
    }
  }

  /** 触发资料重新解析 */
  const handleReparse = (material: Material) => {
    reparseMutation.mutate(material.id, {
      onSuccess: (updated) => {
        // 如果正在查看该资料，更新选中状态为最新数据
        setSelected((current) => current?.id === updated.id ? updated : current)
        showToast('资料已重新解析')
      },
      onError: (error) => showToast(error instanceof Error ? error.message : '重新解析失败'),
    })
  }

  // 是否处于忙碌状态（上传中）
  const isBusy = uploading

  return (
    <motion.div
      className="flex h-full flex-col"
      initial={{ opacity: 0, y: 12 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.3 }}
    >
      {/* 页面标题栏 */}
      <div className="flex items-center justify-between gap-3 px-1 pb-2 pt-1 md:px-6 md:pt-4">
        <h2 className="flex min-w-0 items-center gap-2 truncate text-base font-semibold md:text-lg">
          <BookOpen className="h-5 w-5 shrink-0" /> 资料管理
        </h2>
        <div className="flex shrink-0 items-center gap-2">
          <span className="text-sm text-muted-foreground">共 {filtered.length} 份</span>
          {/* 移动端的导入按钮（切换上传表单的显示/隐藏） */}
          <Button
            type="button"
            size="sm"
            variant="outline"
            className="h-8 gap-1.5 px-2 md:hidden"
            onClick={() => setUploadOpen((open) => !open)}
          >
            <Upload className="h-3.5 w-3.5" />
            导入
          </Button>
        </div>
      </div>

      {/* 主体内容区：左侧上传表单 + 右侧资料列表 */}
      <div className="flex flex-1 flex-col gap-3 overflow-hidden px-1 pb-2 md:flex-row md:gap-4 md:px-6 md:pb-4">
        {/* 上传表单（移动端可折叠，默认隐藏） */}
        <div className={cn('shrink-0 overflow-auto md:block md:max-h-none md:w-72', uploadOpen || uploading ? 'max-h-[52dvh]' : 'hidden')}>
          <MaterialUploadForm onSubmit={handleUpload} loading={isBusy} progress={uploadProgress} />
        </div>

        {/* 资料列表区域 */}
        <div className="flex min-h-0 flex-1 flex-col overflow-hidden">
          {/* 搜索和过滤栏 */}
          <div className="mb-2 flex flex-wrap items-center gap-2 md:mb-3">
            <div className="relative min-w-full flex-1 sm:min-w-[180px]">
              <Search className="absolute left-2.5 top-2.5 h-4 w-4 text-muted-foreground" />
              <Input
                placeholder="搜索标题..."
                className="h-9 pl-8"
                value={keyword}
                onChange={(event) => setKeyword(event.target.value)}
              />
            </div>
            {/* 资料类型下拉筛选 */}
            <Select value={sourceTypeFilter} onValueChange={setSourceTypeFilter}>
              <SelectTrigger className="h-9 w-[calc(50vw-1rem)] sm:w-[120px]">
                <SelectValue placeholder="类型" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="ALL">全部类型</SelectItem>
                {Object.entries(SOURCE_TYPE_LABELS).map(([key, value]) => (
                  <SelectItem key={key} value={key}>{value}</SelectItem>
                ))}
              </SelectContent>
            </Select>
            {/* 解析状态下拉筛选 */}
            <Select value={statusFilter} onValueChange={setStatusFilter}>
              <SelectTrigger className="h-9 w-[calc(50vw-1rem)] sm:w-[120px]">
                <SelectValue placeholder="状态" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="ALL">全部状态</SelectItem>
                {Object.entries(PARSE_STATUS_LABELS).map(([key, value]) => (
                  <SelectItem key={key} value={key}>{value}</SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>

          {/* 资料卡片网格 */}
          <div className="min-h-0 flex-1 overflow-auto pb-2">
            {isLoading ? (
              <div className="flex items-center justify-center py-12">
                <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
              </div>
            ) : (
              <MaterialGrid
                materials={filtered}
                selectedId={selected?.id}
                onSelect={setSelected}
                onEdit={handleEdit}
                onDelete={handleDelete}
                onContinueReading={handleContinueReading}
                onOpenFile={handleOpenFile}
                onReparse={handleReparse}
                reparsingId={reparseMutation.isPending ? reparseMutation.variables || null : null}
              />
            )}
          </div>

          {/* 选中资料的摘要信息栏（底部固定） */}
          {selected && (
            <div className="mt-2 flex items-center justify-between gap-3 rounded-lg border bg-muted/30 p-2 md:mt-3 md:p-3">
              <div className="flex min-w-0 items-center gap-3">
                <BookOpen className="h-4 w-4 shrink-0 text-muted-foreground" />
                <div className="min-w-0">
                  <p className="truncate text-sm font-medium">{selected.title || selected.originalName}</p>
                  <p className="truncate text-xs text-muted-foreground">
                    {SOURCE_TYPE_LABELS[selected.sourceType] || selected.sourceType}
                    {' / '}
                    {PARSE_STATUS_LABELS[selected.parseStatus] || selected.parseStatus}
                    {' / '}
                    {selected.chunkCount} 片段
                    {selected.fileSize > 0 && ` / ${formatBytes(selected.fileSize)}`}
                    {' / '}
                    {formatDate(selected.createdAt)}
                  </p>
                </div>
              </div>
              <Button variant="ghost" size="sm" className="shrink-0" onClick={() => setSelected(null)}>
                取消
              </Button>
            </div>
          )}
        </div>
      </div>

      {/* 编辑资料弹窗 */}
      <Dialog open={!!editTarget} onOpenChange={(open) => !open && setEditTarget(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>编辑资料</DialogTitle>
            <DialogDescription>修改资料标题</DialogDescription>
          </DialogHeader>
          <Input value={editTitle} onChange={(event) => setEditTitle(event.target.value)} placeholder="标题" />
          <DialogFooter>
            <Button variant="outline" onClick={() => setEditTarget(null)}>取消</Button>
            <Button onClick={handleEditSave} disabled={updateMutation.isPending}>
              {updateMutation.isPending ? '保存中...' : '保存'}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* 删除确认弹窗 */}
      <Dialog open={!!deleteTarget} onOpenChange={(open) => !open && setDeleteTarget(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>确认删除</DialogTitle>
            <DialogDescription>
              确定要删除资料"{deleteTarget?.title || deleteTarget?.originalName}"吗？此操作不可撤销。
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button variant="outline" onClick={() => setDeleteTarget(null)}>取消</Button>
            <Button variant="destructive" onClick={handleDeleteConfirm} disabled={deleteMutation.isPending}>
              <Trash2 className="mr-1 h-4 w-4" />
              {deleteMutation.isPending ? '删除中...' : '确认删除'}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </motion.div>
  )
}
