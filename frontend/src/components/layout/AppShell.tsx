import { Outlet, useLocation } from 'react-router-dom'
import { Sidebar } from './Sidebar'
import { TopBar } from './TopBar'

export function AppShell() {
  const location = useLocation()
  const isChat = location.pathname === '/workspace/chat'

  return (
    <div className="flex h-screen overflow-hidden bg-[#f5f6f8] text-[#202124] dark:bg-[#111318] dark:text-slate-100">
      <Sidebar />
      <section className="m-1 ml-0 flex min-w-0 flex-1 flex-col overflow-hidden rounded-xl border border-[#e2e4e8] bg-white dark:border-slate-800 dark:bg-[#171a21]">
        <TopBar />
        <main className={isChat ? 'min-h-0 flex-1 overflow-hidden' : 'min-h-0 flex-1 overflow-y-auto p-6'}>
          <Outlet />
        </main>
      </section>
    </div>
  )
}
