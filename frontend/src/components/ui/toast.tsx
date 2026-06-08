// 消息提示组件 - 轻量级的 Toast 通知，包含 ToastProvider 和 useToast 钩子
import { createContext, useContext, useState, useCallback, useRef } from 'react'
import { motion, AnimatePresence } from 'framer-motion'
import { X } from 'lucide-react'

interface Toast {
  id: number
  message: string
}

interface ToastContextValue {
  showToast: (message: string, durationMs?: number) => void
}

const ToastContext = createContext<ToastContextValue | null>(null)

export function ToastProvider({ children }: { children: React.ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([])
  const counterRef = useRef(0)

  const showToast = useCallback((message: string, durationMs = 3600) => {
    const id = ++counterRef.current
    setToasts((prev) => [...prev, { id, message }])
    setTimeout(() => {
      setToasts((prev) => prev.filter((t) => t.id !== id))
    }, durationMs)
  }, [])

  return (
    <ToastContext.Provider value={{ showToast }}>
      {children}
      <div className="fixed left-1/2 top-6 z-50 flex -translate-x-1/2 flex-col items-center gap-2 px-4 md:top-16">
        <AnimatePresence>
          {toasts.map((toast) => (
            <motion.div
              key={toast.id}
              initial={{ opacity: 0, y: -16, scale: 0.95 }}
              animate={{ opacity: 1, y: 0, scale: 1 }}
              exit={{ opacity: 0, y: -10, scale: 0.95 }}
              className="flex max-w-[min(92vw,28rem)] items-center gap-3 rounded-lg border bg-card px-4 py-3 shadow-lg"
            >
              <p className="text-sm">{toast.message}</p>
              <button
                onClick={() => setToasts((prev) => prev.filter((t) => t.id !== toast.id))}
                className="shrink-0 text-muted-foreground hover:text-foreground"
              >
                <X className="h-4 w-4" />
              </button>
            </motion.div>
          ))}
        </AnimatePresence>
      </div>
    </ToastContext.Provider>
  )
}

export function useToast() {
  const ctx = useContext(ToastContext)
  if (!ctx) throw new Error('useToast must be used within ToastProvider')
  return ctx
}
