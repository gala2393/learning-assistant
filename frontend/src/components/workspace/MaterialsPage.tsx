import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { motion } from 'framer-motion'
import { MaterialUploadForm } from './MaterialUploadForm'
import { MaterialGrid } from './MaterialGrid'
import { queryClient } from '@/lib/query-client'
import {
  useMaterials,
  useImportWebMaterial,
  useDeleteMaterial,
  useUpdateMaterial,
  useReparseMaterial,
  getMaterialFile,
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
import { Search, Trash2, BookOpen, Loader2 } from 'lucide-react'
import { SOURCE_TYPE_LABELS, PARSE_STATUS_LABELS } from '@/constants'
import { formatDate, formatBytes, inferSourceType } from '@/lib/utils'
import type { Material } from '@/types'
import type { UploadProgress } from '@/api/materials'

export function MaterialsPage() {
  const navigate = useNavigate()
  const { showToast } = useToast()
  const { data: materials = [], isLoading } = useMaterials()
  const importWebMutation = useImportWebMaterial()
  const deleteMutation = useDeleteMaterial()
  const updateMutation = useUpdateMaterial()
  const reparseMutation = useReparseMaterial()

  const [keyword, setKeyword] = useState('')
  const [sourceTypeFilter, setSourceTypeFilter] = useState<string>('ALL')
  const [statusFilter, setStatusFilter] = useState<string>('ALL')
  const [selected, setSelected] = useState<Material | null>(null)
  const [editTarget, setEditTarget] = useState<Material | null>(null)
  const [deleteTarget, setDeleteTarget] = useState<Material | null>(null)
  const [editTitle, setEditTitle] = useState('')
  const [uploading, setUploading] = useState(false)
  const [uploadProgress, setUploadProgress] = useState<UploadProgress | null>(null)

  const debouncedKeyword = useDebounce(keyword, 300)

  const filtered = materials.filter((m) => {
    if (sourceTypeFilter !== 'ALL' && m.sourceType !== sourceTypeFilter) return false
    if (statusFilter !== 'ALL' && m.parseStatus !== statusFilter) return false
    if (debouncedKeyword) {
      const kw = debouncedKeyword.toLowerCase()
      return (m.title || '').toLowerCase().includes(kw) || (m.originalName || '').toLowerCase().includes(kw)
    }
    return true
  })

  const handleUpload = async (data: { title?: string; url?: string; file?: File }) => {
    if (data.file) {
      setUploading(true)
      setUploadProgress(null)
      try {
        await uploadMaterialInChunks({
          file: data.file,
          title: data.title,
          sourceType: inferSourceType(data.file.name),
        }, setUploadProgress)
        await queryClient.invalidateQueries({ queryKey: ['materials'] })
        showToast('资料上传成功')
      } catch (error) {
        const message = error instanceof Error ? error.message : '上传失败，请重试'
        showToast(message)
      } finally {
        setUploading(false)
        setUploadProgress(null)
      }
    } else if (data.url) {
      importWebMutation.mutate({ title: data.title, sourceUrl: data.url }, {
        onSuccess: () => showToast('网页导入成功'),
        onError: () => showToast('导入失败，请重试'),
      })
    }
  }

  const handleEdit = (m: Material) => {
    setEditTarget(m)
    setEditTitle(m.title)
  }

  const handleEditSave = () => {
    if (editTarget && editTitle.trim()) {
      updateMutation.mutate({ id: editTarget.id, payload: { title: editTitle.trim() } }, {
        onSuccess: () => { setEditTarget(null); showToast('资料已更新') },
        onError: () => showToast('更新失败'),
      })
    }
  }

  const handleDelete = (m: Material) => {
    setDeleteTarget(m)
  }

  const handleDeleteConfirm = () => {
    if (deleteTarget) {
      deleteMutation.mutate(deleteTarget.id, {
        onSuccess: () => {
          setDeleteTarget(null)
          if (selected?.id === deleteTarget.id) setSelected(null)
          showToast('资料已删除')
        },
        onError: () => showToast('删除失败'),
      })
    }
  }

  const handleContinueReading = (m: Material) => {
    navigate(`/workspace/reader?materialId=${encodeURIComponent(m.id)}`)
  }

  const handleOpenFile = async (m: Material) => {
    try {
      const { blob } = await getMaterialFile(m.id)
      const url = URL.createObjectURL(blob)
      window.open(url, '_blank')
      setTimeout(() => URL.revokeObjectURL(url), 60000)
    } catch {
      // silently fail
    }
  }

  const handleReparse = (m: Material) => {
    reparseMutation.mutate(m.id, {
      onSuccess: (updated) => {
        setSelected((current) => current?.id === updated.id ? updated : current)
        showToast('资料已重新解析')
      },
      onError: (error) => showToast(error instanceof Error ? error.message : '重新解析失败'),
    })
  }

  const isBusy = uploading || importWebMutation.isPending

  return (
    <motion.div
      className="flex flex-col h-full"
      initial={{ opacity: 0, y: 12 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.3 }}
    >
      <div className="flex items-center justify-between px-6 pt-4 pb-2">
        <h2 className="text-lg font-semibold flex items-center gap-2">
          <BookOpen className="h-5 w-5" /> 资料管理
        </h2>
        <span className="text-sm text-muted-foreground">共 {filtered.length} 份资料</span>
      </div>

      <div className="flex flex-1 gap-4 px-6 pb-4 overflow-hidden">
        {/* Left: Upload form */}
        <div className="w-72 shrink-0 overflow-auto">
          <MaterialUploadForm onSubmit={handleUpload} loading={isBusy} progress={uploadProgress} />
        </div>

        {/* Right: Material grid with filters */}
        <div className="flex-1 flex flex-col overflow-hidden">
          {/* Filters */}
          <div className="flex items-center gap-2 mb-3 flex-wrap">
            <div className="relative flex-1 min-w-[180px]">
              <Search className="absolute left-2.5 top-2.5 h-4 w-4 text-muted-foreground" />
              <Input
                placeholder="搜索标题..."
                className="pl-8 h-9"
                value={keyword}
                onChange={(e) => setKeyword(e.target.value)}
              />
            </div>
            <Select value={sourceTypeFilter} onValueChange={setSourceTypeFilter}>
              <SelectTrigger className="w-[120px] h-9">
                <SelectValue placeholder="类型" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="ALL">全部类型</SelectItem>
                {Object.entries(SOURCE_TYPE_LABELS).map(([k, v]) => (
                  <SelectItem key={k} value={k}>{v}</SelectItem>
                ))}
              </SelectContent>
            </Select>
            <Select value={statusFilter} onValueChange={setStatusFilter}>
              <SelectTrigger className="w-[120px] h-9">
                <SelectValue placeholder="状态" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="ALL">全部状态</SelectItem>
                {Object.entries(PARSE_STATUS_LABELS).map(([k, v]) => (
                  <SelectItem key={k} value={k}>{v}</SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>

          {/* Grid */}
          <div className="flex-1 overflow-auto">
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

          {/* Selected detail strip */}
          {selected && (
            <div className="mt-3 p-3 rounded-lg border bg-muted/30 flex items-center justify-between gap-4">
              <div className="flex items-center gap-3 min-w-0">
                <BookOpen className="h-4 w-4 shrink-0 text-muted-foreground" />
                <div className="min-w-0">
                  <p className="text-sm font-medium truncate">{selected.title || selected.originalName}</p>
                  <p className="text-xs text-muted-foreground">
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
              <Button variant="ghost" size="sm" onClick={() => setSelected(null)}>
                取消选择
              </Button>
            </div>
          )}
        </div>
      </div>

      {/* Edit dialog */}
      <Dialog open={!!editTarget} onOpenChange={(v) => !v && setEditTarget(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>编辑资料</DialogTitle>
            <DialogDescription>修改资料标题</DialogDescription>
          </DialogHeader>
          <Input value={editTitle} onChange={(e) => setEditTitle(e.target.value)} placeholder="标题" />
          <DialogFooter>
            <Button variant="outline" onClick={() => setEditTarget(null)}>取消</Button>
            <Button onClick={handleEditSave} disabled={updateMutation.isPending}>
              {updateMutation.isPending ? '保存中...' : '保存'}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* Delete dialog */}
      <Dialog open={!!deleteTarget} onOpenChange={(v) => !v && setDeleteTarget(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>确认删除</DialogTitle>
            <DialogDescription>
              确定要删除资料「{deleteTarget?.title || deleteTarget?.originalName}」吗？此操作不可撤销。
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button variant="outline" onClick={() => setDeleteTarget(null)}>取消</Button>
            <Button variant="destructive" onClick={handleDeleteConfirm} disabled={deleteMutation.isPending}>
              <Trash2 className="h-4 w-4 mr-1" />
              {deleteMutation.isPending ? '删除中...' : '确认删除'}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </motion.div>
  )
}
