/**
 * MaterialGrid - 资料卡片网格布局组件
 *
 * 功能说明：
 * - 将资料列表以响应式网格方式渲染（1/2/3列自适应）
 * - 空列表时显示"暂无资料"提示
 * - 将所有交互事件（选中、编辑、删除、继续阅读、打开原文件、重新解析）透传给 MaterialCard
 *
 * Props 说明见 MaterialGridProps 接口。
 */
import { MaterialCard } from './MaterialCard'
import type { Material } from '@/types'

interface MaterialGridProps {
  materials: Material[]           // 资料列表数据
  selectedId?: string             // 当前选中的资料 ID（用于高亮）
  onSelect?: (m: Material) => void  // 点击选中回调
  onEdit?: (m: Material) => void    // 编辑回调
  onDelete?: (m: Material) => void  // 删除回调
  onContinueReading?: (m: Material) => void  // 继续阅读回调
  onOpenFile?: (m: Material) => void         // 打开原文件回调
  onReparse?: (m: Material) => void          // 重新解析回调
  reparsingId?: string | null      // 当前正在重新解析的资料 ID
}

export function MaterialGrid({ materials, selectedId, onSelect, onEdit, onDelete, onContinueReading, onOpenFile, onReparse, reparsingId }: MaterialGridProps) {
  // 空列表：显示引导提示
  if (materials.length === 0) {
    return (
      <div className="flex items-center justify-center py-12 text-muted-foreground">
        暂无资料，请先导入。
      </div>
    )
  }

  return (
    // 响应式网格：小屏1列，中屏2列，大屏3列
    <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 xl:grid-cols-3">
      {materials.map((m) => (
        <MaterialCard
          key={m.id}
          material={m}
          selected={m.id === selectedId}
          onSelect={onSelect}
          onEdit={onEdit}
          onDelete={onDelete}
          onContinueReading={onContinueReading}
          onOpenFile={onOpenFile}
          onReparse={onReparse}
          reparsing={reparsingId === m.id}  // 仅当前正在解析的卡片显示加载状态
        />
      ))}
    </div>
  )
}
