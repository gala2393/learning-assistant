/**
 * MaterialsPage -- 资料管理页面
 *
 * 【路由】/workspace/materials
 *
 * 【用途】
 * 展示和管理用户的所有学习资料。提供资料的上传、搜索、筛选、
 * 编辑、删除、重新解析、继续阅读、查看原文件等功能。
 *
 * 【页面布局】
 * - 顶部：标题栏 + 资料计数 + 移动端导入按钮
 * - 左侧：上传表单（MaterialUploadForm），移动端可折叠
 * - 右侧上方：搜索框 + 类型筛选 + 状态筛选
 * - 右侧中部：资料卡片网格（MaterialGrid）
 * - 右侧底部：选中资料的摘要信息栏
 *
 * 【数据流】
 * 1. useMaterials() 获取资料列表（React Query 缓存）
 * 2. 搜索使用 useDebounce 300ms 防抖
 * 3. 上传使用 uploadMaterialInChunks() 分片上传，通过回调更新进度
 * 4. 上传/编辑/删除/重新解析均通过对应的 mutation 执行
 * 5. 操作成功后自动刷新列表缓存
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
import { useAuth } from '@/context/AuthContext'
import { LOGIN_REQUIRED_MESSAGE, redirectToLogin } from '@/lib/auth-gate'
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
import type { UploadProgress, UploadProgressItem } from '@/api/materials'

export function MaterialsPage() {
  const navigate = useNavigate()
  const { showToast } = useToast()
  const { isAuthenticated } = useAuth()

  const requireLogin = () => {
    if (isAuthenticated) return true
    showToast(LOGIN_REQUIRED_MESSAGE, 2000)
    redirectToLogin()
    return false
  }

  // === 数据获取 ===
  /** 获取资料列表（React Query，自动缓存和刷新） */
  const { data: materials = [], isLoading } = useMaterials()
  /** 删除资料 mutation */
  const deleteMutation = useDeleteMaterial()
  /** 更新资料 mutation */
  const updateMutation = useUpdateMaterial()
  /** 重新解析资料 mutation */
  const reparseMutation = useReparseMaterial()

  // === 状态管理 ===
  /** 搜索关键词（输入框即时值） */
  const [keyword, setKeyword] = useState('')
  /** 资料类型过滤条件（'ALL' 表示不过滤） */
  const [sourceTypeFilter, setSourceTypeFilter] = useState<string>('ALL')
  /** 解析状态过滤条件（'ALL' 表示不过滤） */
  const [statusFilter, setStatusFilter] = useState<string>('ALL')
  /** 当前选中的资料（用于底部摘要栏和操作） */
  const [selected, setSelected] = useState<Material | null>(null)
  /** 编辑弹窗的目标资料 */
  const [editTarget, setEditTarget] = useState<Material | null>(null)
  /** 删除确认弹窗的目标资料 */
  const [deleteTarget, setDeleteTarget] = useState<Material | null>(null)
  /** 编辑弹窗中的标题输入值 */
  const [editTitle, setEditTitle] = useState('')
  /** 是否正在上传资料 */
  const [uploading, setUploading] = useState(false)
  /** 单文件上传进度（兼容旧接口） */
  const [uploadProgress, setUploadProgress] = useState<UploadProgress | null>(null)
  /** 多文件上传进度列表（每个文件独立进度） */
  const [uploadProgressItems, setUploadProgressItems] = useState<UploadProgressItem[]>([])
  /** 移动端上传表单展开/收起状态 */
  const [uploadOpen, setUploadOpen] = useState(false)

  // === 搜索防抖 ===
  /** 搜索关键词防抖（300ms 延迟，避免每次按键都触发过滤） */
  const debouncedKeyword = useDebounce(keyword, 300)

  // === 过滤逻辑 ===
  /**
   * 综合过滤：按类型 + 状态 + 关键词三重过滤
   * 关键词匹配标题或原始文件名（大小写不敏感）
   */
  const filtered = materials.filter((material) => {
    if (sourceTypeFilter !== 'ALL' && material.sourceType !== sourceTypeFilter) return false
    if (statusFilter !== 'ALL' && material.parseStatus !== statusFilter) return false
    if (debouncedKeyword) {
      // 关键词只做前端过滤，标题和原始文件名任一命中即可保留。
      const kw = debouncedKeyword.toLowerCase()
      return (material.title || '').toLowerCase().includes(kw) || (material.originalName || '').toLowerCase().includes(kw)
    }
    return true
  })

  // === 上传处理 ===

  /**
   * handleUpload -- 处理文件上传
   *
   * 流程：
   * 1. 从 data 中提取文件列表
   * 2. 初始化进度状态
   * 3. 对每个文件并行调用 uploadMaterialInChunks（分片上传）
   * 4. 通过回调更新每个文件的独立进度
   * 5. 上传成功后标记为完成，失败后标记错误
   * 6. 全部完成后刷新资料列表，关闭弹窗
   */
  const handleUpload = async (data: { title?: string; file?: File; files?: File[] }) => {
    if (!requireLogin()) return
    const files = data.files?.length ? data.files : data.file ? [data.file] : []
    if (files.length === 0) return
    // 进入上传前重置旧进度，避免上一次失败项继续显示到新任务里。
    setUploading(true)
    setUploadProgress(null)
    setUploadProgressItems(createUploadProgressItems(files))
    try {
      // 并行上传所有文件
      const results = await Promise.allSettled(files.map((file, index) => {
        const id = uploadProgressItemId(file, index)
        return uploadMaterialInChunks({
          file,
          title: files.length === 1 ? data.title : undefined,
          sourceType: inferSourceType(file.name),  // 根据文件扩展名推断类型
        }, (progress) => {
          // 上传过程中的进度回调
          setUploadProgressItems((current) => updateUploadProgressItem(current, id, progress))
        }).then((session) => {
          // 上传成功，标记为完成
          setUploadProgressItems((current) => updateUploadProgressItem(current, id, {
            phase: 'processing',
            percent: 100,
            uploadedChunks: Math.max(1, Math.ceil(file.size / (5 * 1024 * 1024))),
            totalChunks: Math.max(1, Math.ceil(file.size / (5 * 1024 * 1024))),
            stage: '解析完成',
            message: '资料已上传完成',
          }, 'success'))
          return session
        }).catch((error) => {
          // 上传失败，标记错误
          setUploadProgressItems((current) => updateUploadProgressItem(current, id, null, 'error', error instanceof Error ? error.message : '上传失败'))
          throw error
        })
      }))
      const failed = results.filter((result) => result.status === 'rejected')
      // 刷新资料列表缓存
      await queryClient.invalidateQueries({ queryKey: ['materials'] })
      if (failed.length > 0) {
        // 部分失败时不关闭上传区域，保留每个文件的状态方便用户判断是否重试。
        showToast(`${failed.length}/${files.length} 份资料上传失败，请查看进度列表`)
        return
      }
      setUploadOpen(false)
      showToast(files.length > 1 ? `已上传 ${files.length} 份资料` : '资料上传成功')
    } catch (error) {
      const message = error instanceof Error ? error.message : '上传失败，请重试'
      showToast(message)
    } finally {
      setUploading(false)
      setUploadProgress(null)
    }
  }

  // === 编辑处理 ===

  /** 打开编辑弹窗，初始化标题输入为当前资料标题 */
  const handleEdit = (material: Material) => {
    setEditTarget(material)
    setEditTitle(material.title)
  }

  /** 保存编辑结果（更新资料标题） */
  const handleEditSave = () => {
    if (!requireLogin()) return
    if (editTarget && editTitle.trim()) {
      // 空标题不提交，避免把资料名称更新成不可见文本。
      updateMutation.mutate({ id: editTarget.id, payload: { title: editTitle.trim() } }, {
        onSuccess: () => {
          setEditTarget(null)
          showToast('资料已更新')
        },
        onError: () => showToast('更新失败'),
      })
    }
  }

  // === 删除处理 ===

  /** 打开删除确认弹窗 */
  const handleDelete = (material: Material) => {
    setDeleteTarget(material)
  }

  /** 确认删除资料 */
  const handleDeleteConfirm = () => {
    if (!requireLogin()) return
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

  // === 阅读和原文件 ===

  /** 跳转到阅读器页面，继续阅读指定资料 */
  const handleContinueReading = (material: Material) => {
    navigate(`/workspace/reader?materialId=${encodeURIComponent(material.id)}`)
  }

  /**
   * 打开资料原文件
   * 1. 先打开一个空白窗口
   * 2. 通过后端接口获取临时访问链接（ticket，有时效性）
   * 3. 设置空白窗口的 location 为 ticket URL
   * 4. 如果浏览器拦截了新窗口，提示用户允许弹窗
   */
  const handleOpenFile = async (material: Material) => {
    if (!requireLogin()) return
    // 先打开空白页，再异步写入 ticket URL，降低浏览器拦截新窗口的概率。
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

  /** 触发资料重新解析（重新从源文件提取文本和切片） */
  const handleReparse = (material: Material) => {
    if (!requireLogin()) return
    reparseMutation.mutate(material.id, {
      onSuccess: (updated) => {
        // 如果正在查看该资料，更新选中状态为最新数据
        // 列表会通过 query 缓存刷新，底部摘要栏需要同步本地 selected。
        setSelected((current) => current?.id === updated.id ? updated : current)
        showToast('资料已重新解析')
      },
      onError: (error) => showToast(error instanceof Error ? error.message : '重新解析失败'),
    })
  }

  /** 是否处于忙碌状态（上传中时禁用其他操作） */
  const isBusy = uploading

  // === 渲染 ===
  return (
    <motion.div
      className="flex h-full flex-col"
      initial={{ opacity: 0, y: 12 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.3 }}
    >
      {/* ---- 页面标题栏 ---- */}
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
            onClick={() => {
              if (!requireLogin()) return
              setUploadOpen((open) => !open)
            }}
          >
            <Upload className="h-3.5 w-3.5" />
            导入
          </Button>
        </div>
      </div>

      {/* ---- 主体内容区：左侧上传表单 + 右侧资料列表 ---- */}
      <div className="flex flex-1 flex-col gap-3 overflow-hidden px-1 pb-2 md:flex-row md:gap-4 md:px-6 md:pb-4">
        {/* ---- 上传表单（移动端可折叠，默认隐藏） ---- */}
        <div className={cn('shrink-0 overflow-auto md:block md:max-h-none md:w-72', uploadOpen || uploading ? 'max-h-[52dvh]' : 'hidden')}>
          <MaterialUploadForm onSubmit={handleUpload} loading={isBusy} progress={uploadProgress} progressItems={uploadProgressItems} />
        </div>

        {/* ---- 资料列表区域 ---- */}
          <div className="flex min-h-0 flex-1 flex-col overflow-hidden">
            {/* ---- 搜索和过滤栏 ---- */}
            <div className="mb-2 flex flex-wrap items-center gap-2 md:mb-3">
              {/* 搜索输入框（带搜索图标） */}
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

            {/* ---- 资料卡片网格 ---- */}
            <div className="min-h-0 flex-1 overflow-auto pb-2">
              {isLoading ? (
                /* 加载中状态 */
                <div className="flex items-center justify-center py-12">
                  <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
                </div>
              ) : (
                /* 资料网格（传递所有操作回调） */
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

            {/* ---- 选中资料的摘要信息栏（底部固定） ---- */}
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

      {/* ---- 编辑资料弹窗 ---- */}
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

      {/* ---- 删除确认弹窗 ---- */}
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

// ========== 上传进度辅助函数 ==========

/** 生成文件的唯一进度条 ID（索引+文件名+大小+修改时间） */
function uploadProgressItemId(file: File, index: number) {
  return `${index}-${file.name}-${file.size}-${file.lastModified}`
}

/** 为多个文件创建初始进度条状态（全部为 pending 状态，0%） */
function createUploadProgressItems(files: File[]): UploadProgressItem[] {
  return files.map((file, index) => ({
    id: uploadProgressItemId(file, index),
    fileName: file.name,
    fileSize: file.size,
    status: 'pending',
    phase: 'uploading',
    percent: 0,
    uploadedChunks: 0,
    totalChunks: Math.max(1, Math.ceil(file.size / (5 * 1024 * 1024))),  // 分片大小 5MB
    message: '等待上传',
  }))
}

/**
 * 更新指定文件的进度条状态
 *
 * @param items - 所有文件的进度列表
 * @param id - 要更新的文件 ID
 * @param progress - 新的进度信息（null 表示只更新 status/error）
 * @param status - 覆盖状态（如 'success'、'error'）
 * @param error - 错误信息
 * @returns 更新后的进度列表（不可变更新）
 */
function updateUploadProgressItem(
  items: UploadProgressItem[],
  id: string,
  progress: UploadProgress | null,
  status?: UploadProgressItem['status'],
  error?: string,
): UploadProgressItem[] {
  return items.map((item) => {
    if (item.id !== id) return item
    if (!progress) {
      return {
        ...item,
        status: status || item.status,
        error: error || item.error,
      }
    }
    return {
      ...item,
      ...progress,
      status: status || (progress.phase === 'processing' ? 'processing' : 'uploading'),
      error: null,
    }
  })
}
