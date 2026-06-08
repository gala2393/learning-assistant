import { useEffect, useState } from 'react'
import { RotateCcw, X, ZoomIn, ZoomOut } from 'lucide-react'

/**
 * 图片预览弹窗使用的数据结构。
 *
 * src 通常是压缩后的 Base64 DataURL；这样用户刚粘贴/上传的图片不需要先落盘，
 * 就可以直接在聊天输入区和历史消息里放大查看。
 */
export type PreviewImage = {
  src: string
  alt: string
}

/**
 * ImagePreviewDialog -- 聊天图片的全屏预览层。
 *
 * 使用场景：
 * - ChatComposer 中预览“待发送”的图片。
 * - ChatThread 中预览已经发出的图片历史。
 *
 * 交互规则：
 * - 点击遮罩关闭，点击图片本身不关闭。
 * - Escape 关闭；+/- 调整缩放；0 恢复 100%。
 * - 缩放范围限制在 50% - 300%，避免图片被缩到不可见或放大到难以退出。
 */
export function ImagePreviewDialog({
  image,
  onClose,
}: {
  image: PreviewImage | null
  onClose: () => void
}) {
  const [scale, setScale] = useState(1)

  // 每次切换图片时恢复 100%，避免上一张图的缩放比例影响下一张。
  useEffect(() => {
    if (image) setScale(1)
  }, [image])

  // 预览层只在打开时监听快捷键；关闭后立即解绑，避免影响页面其他输入。
  useEffect(() => {
    if (!image) return
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose()
      if (event.key === '+' || event.key === '=') setScale((value) => Math.min(3, Number((value + 0.25).toFixed(2))))
      if (event.key === '-' || event.key === '_') setScale((value) => Math.max(0.5, Number((value - 0.25).toFixed(2))))
      if (event.key === '0') setScale(1)
    }
    window.addEventListener('keydown', handleKeyDown)
    return () => window.removeEventListener('keydown', handleKeyDown)
  }, [image, onClose])

  if (!image) return null

  /** 缩放值保留两位小数，避免连续点击时出现 1.7500000002 这类浮点显示。 */
  const zoomIn = () => setScale((value) => Math.min(3, Number((value + 0.25).toFixed(2))))
  const zoomOut = () => setScale((value) => Math.max(0.5, Number((value - 0.25).toFixed(2))))

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/80 px-4 py-5 backdrop-blur-sm"
      role="dialog"
      aria-modal="true"
      aria-label="图片预览"
      onClick={onClose}
    >
      <div className="absolute right-4 top-4 flex items-center gap-2 rounded-full border border-white/15 bg-slate-950/70 p-1.5 text-white shadow-2xl">
        <button
          type="button"
          className="flex h-9 w-9 items-center justify-center rounded-full transition hover:bg-white/10"
          onClick={(event) => { event.stopPropagation(); zoomOut() }}
          title="缩小"
          aria-label="缩小图片"
        >
          <ZoomOut className="h-4 w-4" />
        </button>
        <span className="min-w-12 text-center text-xs font-medium tabular-nums text-white/80">{Math.round(scale * 100)}%</span>
        <button
          type="button"
          className="flex h-9 w-9 items-center justify-center rounded-full transition hover:bg-white/10"
          onClick={(event) => { event.stopPropagation(); zoomIn() }}
          title="放大"
          aria-label="放大图片"
        >
          <ZoomIn className="h-4 w-4" />
        </button>
        <button
          type="button"
          className="flex h-9 w-9 items-center justify-center rounded-full transition hover:bg-white/10"
          onClick={(event) => { event.stopPropagation(); setScale(1) }}
          title="重置"
          aria-label="重置图片大小"
        >
          <RotateCcw className="h-4 w-4" />
        </button>
        <button
          type="button"
          className="flex h-9 w-9 items-center justify-center rounded-full transition hover:bg-white/10"
          onClick={(event) => { event.stopPropagation(); onClose() }}
          title="关闭"
          aria-label="关闭图片预览"
        >
          <X className="h-4 w-4" />
        </button>
      </div>
      <div className="max-h-full max-w-full overflow-auto" onClick={(event) => event.stopPropagation()}>
        <img
          src={image.src}
          alt={image.alt}
          className="block max-h-[86vh] max-w-[92vw] select-none rounded-lg object-contain shadow-2xl transition-transform duration-150"
          style={{ transform: `scale(${scale})`, transformOrigin: 'center center' }}
          draggable={false}
        />
      </div>
    </div>
  )
}
