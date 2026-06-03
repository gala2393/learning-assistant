import { MaterialCard } from './MaterialCard'
import type { Material } from '@/types'

interface MaterialGridProps {
  materials: Material[]
  selectedId?: string
  onSelect?: (m: Material) => void
  onEdit?: (m: Material) => void
  onDelete?: (m: Material) => void
  onContinueReading?: (m: Material) => void
  onOpenFile?: (m: Material) => void
  onReparse?: (m: Material) => void
  reparsingId?: string | null
}

export function MaterialGrid({ materials, selectedId, onSelect, onEdit, onDelete, onContinueReading, onOpenFile, onReparse, reparsingId }: MaterialGridProps) {
  if (materials.length === 0) {
    return (
      <div className="flex items-center justify-center py-12 text-muted-foreground">
        暂无资料，请先导入。
      </div>
    )
  }

  return (
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
          reparsing={reparsingId === m.id}
        />
      ))}
    </div>
  )
}
