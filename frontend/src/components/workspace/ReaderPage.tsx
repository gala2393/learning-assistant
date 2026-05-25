import { useEffect, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { motion } from 'framer-motion'
import { ReaderToc } from './ReaderToc'
import { ReaderPaper } from './ReaderPaper'
import { ReaderAsk } from './ReaderAsk'
import { useMaterials, useMaterialChunks, useMaterialPages, getMaterialFile } from '@/api/materials'
import { FileText } from 'lucide-react'

export function ReaderPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const { data: materials = [] } = useMaterials()
  const [selectedMaterialId, setSelectedMaterialId] = useState<string | null>(() => searchParams.get('materialId'))
  const [selectedChunkIndex, setSelectedChunkIndex] = useState(0)

  const { data: chunks = [] } = useMaterialChunks(selectedMaterialId)
  const { data: pages = [] } = useMaterialPages(selectedMaterialId)

  const selectedMaterial = materials.find((m) => m.id === selectedMaterialId) || null
  const currentChunk = chunks[selectedChunkIndex] || null

  useEffect(() => {
    const materialId = searchParams.get('materialId')
    if (materialId && materialId !== selectedMaterialId) {
      setSelectedMaterialId(materialId)
      setSelectedChunkIndex(0)
    }
  }, [searchParams, selectedMaterialId])

  useEffect(() => {
    const chunkId = searchParams.get('chunkId')
    if (!chunkId || chunks.length === 0) return
    const index = chunks.findIndex((chunk) => String(chunk.id) === chunkId)
    if (index >= 0 && index !== selectedChunkIndex) {
      setSelectedChunkIndex(index)
    }
  }, [chunks, searchParams, selectedChunkIndex])

  useEffect(() => {
    if (!selectedMaterialId || !currentChunk) return
    const currentMaterialParam = searchParams.get('materialId')
    const currentChunkParam = searchParams.get('chunkId')
    if (currentMaterialParam === selectedMaterialId && currentChunkParam === String(currentChunk.id)) return
    setSearchParams({ materialId: selectedMaterialId, chunkId: String(currentChunk.id) }, { replace: true })
  }, [currentChunk, searchParams, selectedMaterialId, setSearchParams])

  const handleSelectMaterial = (id: string) => {
    setSelectedMaterialId(id)
    setSelectedChunkIndex(0)
    setSearchParams({ materialId: id }, { replace: false })
  }

  const handleSelectChunk = (index: number) => {
    setSelectedChunkIndex(index)
    const targetChunk = chunks[index]
    if (selectedMaterialId && targetChunk) {
      setSearchParams({ materialId: selectedMaterialId, chunkId: String(targetChunk.id) }, { replace: false })
    }
  }

  const handlePrev = () => {
    handleSelectChunk(Math.max(0, selectedChunkIndex - 1))
  }

  const handleNext = () => {
    handleSelectChunk(Math.min(chunks.length - 1, selectedChunkIndex + 1))
  }

  const handleOpenFile = async () => {
    if (!selectedMaterial) return
    try {
      const { blob } = await getMaterialFile(selectedMaterial.id)
      const url = URL.createObjectURL(blob)
      window.open(url, '_blank')
      setTimeout(() => URL.revokeObjectURL(url), 60000)
    } catch {
      // silently fail
    }
  }

  return (
    <motion.div
      className="flex h-full"
      initial={{ opacity: 0 }}
      animate={{ opacity: 1 }}
      transition={{ duration: 0.3 }}
    >
      <ReaderToc
        materials={materials}
        chunks={chunks}
        selectedMaterialId={selectedMaterialId}
        selectedChunkIndex={selectedChunkIndex}
        onSelectMaterial={handleSelectMaterial}
        onSelectChunk={handleSelectChunk}
      />

      <div className="flex-1 flex flex-col overflow-hidden">
        {currentChunk ? (
          <ReaderPaper
            chunk={currentChunk}
            chunks={chunks}
            pages={pages}
            material={selectedMaterial}
            progress={chunks.length > 0 ? (selectedChunkIndex + 1) / chunks.length : 0}
            canPrev={selectedChunkIndex > 0}
            canNext={selectedChunkIndex < chunks.length - 1}
            onPrev={handlePrev}
            onNext={handleNext}
            onSelectChunk={handleSelectChunk}
            onOpenFile={selectedMaterial ? handleOpenFile : undefined}
          />
        ) : (
          <div className="flex-1 flex items-center justify-center text-muted-foreground">
            <div className="text-center">
              <FileText className="h-10 w-10 mx-auto mb-3 opacity-40" />
              <p className="text-sm">选择一份资料开始阅读</p>
            </div>
          </div>
        )}
      </div>

      <ReaderAsk
        material={selectedMaterial}
        chunk={currentChunk}
        chunks={chunks}
        onNavigateToChunk={handleSelectChunk}
      />
    </motion.div>
  )
}
