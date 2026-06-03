import { useState } from 'react'
import { Outlet, useLocation, useNavigate } from 'react-router-dom'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { ADMIN_SECTIONS, WORKSPACE_SECTIONS } from '@/constants'
import { useAuth } from '@/context/AuthContext'
import { cn } from '@/lib/utils'
import {
  BookOpen,
  ClipboardCheck,
  Clock,
  FileText,
  Folder,
  LayoutDashboard,
  LogOut,
  Menu,
  MessageSquare,
  Moon,
  ScrollText,
  Settings,
  Sparkles,
  Star,
  Sun,
  Users,
} from 'lucide-react'
import { ProfileDialog } from './ProfileDialog'
import { Sidebar } from './Sidebar'
import { TopBar } from './TopBar'
import { UserAvatar } from './UserAvatar'

const iconMap: Record<string, React.ElementType> = {
  'message-square': MessageSquare,
  'book-open': BookOpen,
  'file-text': FileText,
  clock: Clock,
  star: Star,
  sparkles: Sparkles,
  'layout-dashboard': LayoutDashboard,
  users: Users,
  folder: Folder,
  'scroll-text': ScrollText,
  'clipboard-check': ClipboardCheck,
}

export function AppShell() {
  const location = useLocation()
  const navigate = useNavigate()
  const { session, isAdmin, logout } = useAuth()
  const [mobileMenuOpen, setMobileMenuOpen] = useState(false)
  const [profileOpen, setProfileOpen] = useState(false)
  const isChat = location.pathname === '/workspace/chat'
  const mobileSections = WORKSPACE_SECTIONS.slice(0, 4)

  const goTo = (path: string) => {
    navigate(path)
    setMobileMenuOpen(false)
  }

  const setTheme = (theme: 'light' | 'dark') => {
    document.documentElement.classList.toggle('dark', theme === 'dark')
    localStorage.setItem('learning-assistant.theme', theme)
  }

  return (
    <div className="flex h-[100dvh] overflow-hidden bg-[#f5f6f8] text-[#202124] dark:bg-[#111318] dark:text-slate-100">
      <div className="hidden md:block">
        <Sidebar />
      </div>
      <section className="flex min-w-0 flex-1 flex-col overflow-hidden bg-white dark:bg-[#171a21] md:m-1 md:ml-0 md:rounded-xl md:border md:border-[#e2e4e8] md:dark:border-slate-800">
        <TopBar onOpenMobileMenu={() => setMobileMenuOpen(true)} />
        <main className={isChat ? 'min-h-0 flex-1 overflow-hidden pb-16 md:pb-0' : 'min-h-0 flex-1 overflow-y-auto p-3 pb-20 md:p-6 md:pb-6'}>
          <Outlet />
        </main>
      </section>

      <Dialog open={mobileMenuOpen} onOpenChange={setMobileMenuOpen}>
        <DialogContent className="left-0 top-0 h-[100dvh] max-w-[22rem] translate-x-0 translate-y-0 content-start gap-0 overflow-hidden rounded-none border-y-0 border-l-0 bg-[#f7f8fa] p-0 shadow-2xl data-[state=closed]:slide-out-to-left data-[state=closed]:slide-out-to-top-0 data-[state=open]:slide-in-from-left data-[state=open]:slide-in-from-top-0 dark:bg-[#111318] sm:rounded-none">
          <DialogHeader className="border-b border-slate-200 px-4 py-4 text-left dark:border-slate-800">
            <DialogTitle className="text-base">功能菜单</DialogTitle>
          </DialogHeader>
          <div className="flex min-h-0 flex-1 flex-col overflow-y-auto p-4">
            <Button
              variant="outline"
              className="mb-4 h-11 justify-start gap-2 rounded-lg border-[#e1e4e8] bg-white px-3 text-sm font-medium shadow-sm dark:border-slate-800 dark:bg-[#171a21]"
              onClick={() => goTo('/workspace/chat?new=1')}
            >
              <MessageSquare className="h-4 w-4" />
              新建对话
            </Button>

            <div className="mb-2 px-1 text-xs font-medium text-muted-foreground">工作区</div>
            <div className="grid gap-1">
              {WORKSPACE_SECTIONS.map((section) => {
                const Icon = iconMap[section.icon] || FileText
                const active = location.pathname === section.path
                return (
                  <button
                    key={section.path}
                    type="button"
                    onClick={() => goTo(section.path)}
                    className={cn(
                      'flex h-11 items-center gap-3 rounded-lg px-3 text-sm font-medium transition-colors',
                      active
                        ? 'bg-[#e9eaec] text-[#202124] dark:bg-white/10 dark:text-white'
                        : 'text-[#3f4247] hover:bg-[#eceef1] dark:text-slate-300 dark:hover:bg-white/[0.08]',
                    )}
                  >
                    <Icon className="h-4 w-4 shrink-0" />
                    <span className="truncate">{section.label}</span>
                  </button>
                )
              })}
            </div>

            {isAdmin && (
              <>
                <div className="mb-2 mt-5 px-1 text-xs font-medium text-muted-foreground">管理员后台</div>
                <div className="grid gap-1">
                  {ADMIN_SECTIONS.map((section) => {
                    const Icon = iconMap[section.icon] || LayoutDashboard
                    const active = location.pathname === section.path
                    return (
                      <button
                        key={section.path}
                        type="button"
                        onClick={() => goTo(section.path)}
                        className={cn(
                          'flex h-11 items-center gap-3 rounded-lg px-3 text-sm font-medium transition-colors',
                          active
                            ? 'bg-[#e9eaec] text-[#202124] dark:bg-white/10 dark:text-white'
                            : 'text-[#3f4247] hover:bg-[#eceef1] dark:text-slate-300 dark:hover:bg-white/[0.08]',
                        )}
                      >
                        <Icon className="h-4 w-4 shrink-0" />
                        <span className="truncate">{section.label}</span>
                      </button>
                    )
                  })}
                </div>
              </>
            )}

            <div className="mt-5 grid grid-cols-2 gap-1 rounded-lg bg-[#eceef1] p-1 dark:bg-white/[0.08]">
              <button
                type="button"
                onClick={() => setTheme('light')}
                className="flex h-9 items-center justify-center gap-1.5 rounded-md bg-white text-xs font-medium text-foreground shadow-sm dark:bg-transparent dark:text-muted-foreground"
              >
                <Sun className="h-3.5 w-3.5" />
                浅色
              </button>
              <button
                type="button"
                onClick={() => setTheme('dark')}
                className="flex h-9 items-center justify-center gap-1.5 rounded-md text-xs font-medium text-muted-foreground dark:bg-slate-900 dark:text-foreground dark:shadow-sm"
              >
                <Moon className="h-3.5 w-3.5" />
                深色
              </button>
            </div>

            <div className="mt-auto flex items-center gap-2 pt-5">
              <button
                type="button"
                className="flex min-w-0 flex-1 items-center gap-3 rounded-lg px-1 py-1 text-left transition-colors hover:bg-[#eceef1] dark:hover:bg-white/[0.08]"
                onClick={() => {
                  setProfileOpen(true)
                  setMobileMenuOpen(false)
                }}
              >
                <UserAvatar session={session} />
                <div className="min-w-0 flex-1">
                  <div className="truncate text-sm font-medium">{session?.nickname || session?.username}</div>
                  <div className="text-xs text-muted-foreground">{session?.role === 'ADMIN' ? '管理员' : '普通用户'}</div>
                </div>
              </button>
              <Button
                variant="ghost"
                size="icon"
                className="h-9 w-9 shrink-0"
                onClick={() => {
                  setProfileOpen(true)
                  setMobileMenuOpen(false)
                }}
              >
                <Settings className="h-4 w-4" />
              </Button>
              <Button variant="ghost" size="icon" className="h-9 w-9 shrink-0" onClick={logout}>
                <LogOut className="h-4 w-4" />
              </Button>
            </div>
          </div>
        </DialogContent>
      </Dialog>
      <ProfileDialog open={profileOpen} onOpenChange={setProfileOpen} />

      <nav className="fixed inset-x-0 bottom-0 z-40 border-t border-slate-200 bg-white/95 px-1 pb-[max(env(safe-area-inset-bottom),0.25rem)] pt-1 shadow-[0_-10px_30px_rgba(15,23,42,0.08)] backdrop-blur dark:border-slate-800 dark:bg-[#171a21]/95 md:hidden">
        <div className="grid grid-cols-5 gap-1">
          {mobileSections.map((section) => {
            const Icon = iconMap[section.icon] || FileText
            const active = location.pathname === section.path
            return (
              <button
                key={section.path}
                type="button"
                onClick={() => navigate(section.path)}
                className={cn(
                  'flex min-w-0 flex-col items-center justify-center gap-0.5 rounded-lg px-1 py-1.5 text-[10px] transition-colors',
                  active
                    ? 'bg-[#eef0f2] text-[#111318] dark:bg-white/10 dark:text-white'
                    : 'text-slate-500 dark:text-slate-400',
                )}
              >
                <Icon className="h-4 w-4 shrink-0" />
                <span className="max-w-full truncate">{section.label}</span>
              </button>
            )
          })}
          <button
            type="button"
            onClick={() => setMobileMenuOpen(true)}
            className="flex min-w-0 flex-col items-center justify-center gap-0.5 rounded-lg px-1 py-1.5 text-[10px] text-slate-500 transition-colors hover:bg-[#eef0f2] dark:text-slate-400 dark:hover:bg-white/10"
          >
            <Menu className="h-4 w-4 shrink-0" />
            <span className="max-w-full truncate">菜单</span>
          </button>
        </div>
      </nav>
    </div>
  )
}
