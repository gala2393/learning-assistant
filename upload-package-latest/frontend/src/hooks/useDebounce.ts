/**
 * useDebounce - 防抖 Hook
 *
 * 功能说明：
 * - 将频繁变化的值延迟指定时间后才更新
 * - 典型用途：搜索框输入时，等用户停止输入 300ms 后才发起请求
 *
 * 原理：
 * - 每次 value 变化时重置定时器
 * - 只有在 delay 毫秒内没有新的变化时，才更新 debouncedValue
 * - 组件卸载时自动清理定时器，防止内存泄漏
 *
 * @param value - 需要做防抖处理的值
 * @param delay - 延迟时间（毫秒）
 * @returns 防抖后的值
 */
import { useState, useEffect } from 'react'

export function useDebounce<T>(value: T, delay: number): T {
  // 存储防抖后的值，初始值为传入的 value
  const [debouncedValue, setDebouncedValue] = useState(value)

  useEffect(() => {
    // 设置延迟定时器：delay 毫秒后更新值
    const timer = setTimeout(() => setDebouncedValue(value), delay)
    // 清理函数：value 或 delay 变化时取消上一个定时器
    return () => clearTimeout(timer)
  }, [value, delay])

  return debouncedValue
}
