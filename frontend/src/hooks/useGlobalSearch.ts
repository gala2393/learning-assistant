/**
 * useGlobalSearch - 全局搜索弹窗快捷键 Hook
 *
 * 功能说明：
 * - 监听键盘快捷键 Ctrl+K (Windows) 或 Cmd+K (Mac) 来打开/关闭全局搜索
 * - 提供 open 状态、setOpen 和 close 方法供组件使用
 *
 * 使用示例：
 *   const { open, setOpen, close } = useGlobalSearch()
 *   // 然后将 open/close 传给 GlobalSearch 组件
 */
import { useState, useEffect, useCallback } from 'react'

export function useGlobalSearch() {
  // 搜索弹窗的打开/关闭状态
  const [open, setOpen] = useState(false)

  useEffect(() => {
    const down = (e: KeyboardEvent) => {
      // 检测 Ctrl+K 或 Cmd+K 快捷键
      if (e.key === 'k' && (e.metaKey || e.ctrlKey)) {
        e.preventDefault()
        setOpen((prev) => !prev)  // 切换打开/关闭状态
      }
    }
    document.addEventListener('keydown', down)
    return () => document.removeEventListener('keydown', down)
  }, [])

  // 关闭搜索弹窗的稳定引用（避免不必要的重渲染）
  const close = useCallback(() => setOpen(false), [])

  return { open, setOpen, close }
}
