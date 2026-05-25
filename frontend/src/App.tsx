import { Outlet } from 'react-router-dom'
import { ToastProvider } from '@/components/ui/toast'

export default function App() {
  return (
    <ToastProvider>
      <Outlet />
    </ToastProvider>
  )
}
