import { motion } from 'framer-motion'
import { useAdminStats } from '@/api/admin'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { Users, BookOpen, MessageSquare, Star, ScrollText, LayoutDashboard } from 'lucide-react'

const statCards = [
  { key: 'userCount', label: '用户数', icon: Users, color: 'text-blue-500' },
  { key: 'materialCount', label: '资料数', icon: BookOpen, color: 'text-green-500' },
  { key: 'questionCount', label: '问答数', icon: MessageSquare, color: 'text-purple-500' },
  { key: 'favoriteCount', label: '收藏数', icon: Star, color: 'text-amber-500' },
  { key: 'logCount', label: '日志数', icon: ScrollText, color: 'text-red-500' },
] as const

export function DashboardPage() {
  const { data: stats, isLoading } = useAdminStats()

  return (
    <motion.div
      className="p-6 space-y-6"
      initial={{ opacity: 0, y: 12 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.3 }}
    >
      <div className="flex items-center gap-2">
        <LayoutDashboard className="h-5 w-5" />
        <h2 className="text-lg font-semibold">管理员总览</h2>
      </div>

      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-5 gap-4">
        {statCards.map((card) => {
          const Icon = card.icon
          const value = stats ? (stats as any)[card.key] : undefined

          return (
            <Card key={card.key}>
              <CardHeader className="pb-2">
                <div className="flex items-center justify-between">
                  <CardTitle className="text-sm font-medium text-muted-foreground">
                    {card.label}
                  </CardTitle>
                  <Icon className={`h-5 w-5 ${card.color}`} />
                </div>
              </CardHeader>
              <CardContent>
                {isLoading ? (
                  <Skeleton className="h-8 w-20" />
                ) : (
                  <p className="text-2xl font-bold">{value ?? '-'}</p>
                )}
              </CardContent>
            </Card>
          )
        })}
      </div>
    </motion.div>
  )
}
