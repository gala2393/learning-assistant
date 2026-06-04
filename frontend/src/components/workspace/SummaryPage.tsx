/**
 * SummaryPage - 知识总结页面
 *
 * 功能说明：
 * - 左侧展示资料列表，右侧展示 AI 生成的知识总结
 * - 支持选择资料后点击"生成总结"按钮，调用 AI 生成摘要
 * - 展示最新总结和历史总结记录
 *
 * 数据流：
 * 1. useMaterials() 获取所有资料列表
 * 2. 用户选择资料后，useMaterialSummaryHistory() 获取该资料的总结历史
 * 3. 点击"生成总结"通过 useSummarizeMaterial() mutation 触发 AI 生成
 */
import { useState } from 'react'
import { motion } from 'framer-motion'
import { useMaterials } from '@/api/materials'
import { useSummarizeMaterial, useMaterialSummaryHistory } from '@/api/rag'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { ScrollArea } from '@/components/ui/scroll-area'
import { Separator } from '@/components/ui/separator'
import { Skeleton } from '@/components/ui/skeleton'
import { formatDate, truncate, cn } from '@/lib/utils'
import { Sparkles, BookOpen, Loader2, Clock } from 'lucide-react'
import type { SummaryResult } from '@/types'

export function SummaryPage() {
  // 获取所有资料列表
  const { data: materials = [] } = useMaterials()
  // 当前选中的资料 ID
  const [selectedMaterialId, setSelectedMaterialId] = useState<string | null>(null)

  // 获取选中资料的总结历史记录
  const { data: summaryHistory = [], isLoading: summaryLoading } = useMaterialSummaryHistory(selectedMaterialId)
  // 生成总结的 mutation
  const summarizeMutation = useSummarizeMaterial()

  // 当前选中的资料对象
  const selectedMaterial = materials.find((m) => m.id === selectedMaterialId) || null

  // 触发生成总结
  const handleGenerate = () => {
    if (selectedMaterialId) {
      summarizeMutation.mutate(selectedMaterialId)
    }
  }

  // 最新一条总结（列表第一项）
  const latestSummary = summaryHistory[0] || null

  return (
    <motion.div
      className="flex h-full"
      initial={{ opacity: 0 }}
      animate={{ opacity: 1 }}
      transition={{ duration: 0.3 }}
    >
      {/* 左侧：资料选择列表 */}
      <div className="w-60 border-r flex flex-col h-full bg-muted/20">
        <div className="p-3 border-b">
          <p className="text-xs font-semibold text-muted-foreground flex items-center gap-1">
            <BookOpen className="h-3.5 w-3.5" /> 选择资料
          </p>
        </div>
        <ScrollArea className="flex-1 p-2">
          <div className="space-y-1">
            {materials.map((m) => (
              <Button
                key={m.id}
                variant={m.id === selectedMaterialId ? 'secondary' : 'ghost'}
                size="sm"
                className="w-full justify-start text-xs h-7"
                onClick={() => setSelectedMaterialId(m.id)}
              >
                {truncate(m.title || m.originalName, 20)}
              </Button>
            ))}
          </div>
        </ScrollArea>
      </div>

      {/* 右侧：总结内容展示 */}
      <div className="flex-1 flex flex-col overflow-hidden">
        {selectedMaterial ? (
          <>
            {/* 标题栏和生成按钮 */}
            <div className="flex items-center justify-between px-6 py-3 border-b">
              <div>
                <h3 className="text-sm font-medium">{selectedMaterial.title || selectedMaterial.originalName}</h3>
                <p className="text-xs text-muted-foreground">共 {summaryHistory.length} 条总结记录</p>
              </div>
              <Button
                size="sm"
                onClick={handleGenerate}
                disabled={summarizeMutation.isPending}  // 生成中禁用按钮
              >
                {summarizeMutation.isPending ? (
                  <>
                    <Loader2 className="h-4 w-4 mr-1 animate-spin" /> 生成中...
                  </>
                ) : (
                  <>
                    <Sparkles className="h-4 w-4 mr-1" /> 生成总结
                  </>
                )}
              </Button>
            </div>

            <ScrollArea className="flex-1 px-6 py-4">
              {/* 加载骨架屏 */}
              {summaryLoading ? (
                <div className="space-y-2">
                  <Skeleton className="h-4 w-3/4" />
                  <Skeleton className="h-4 w-full" />
                  <Skeleton className="h-4 w-5/6" />
                </div>
              ) : latestSummary ? (
                /* 最新总结卡片 */
                <Card className="mb-4">
                  <CardHeader className="pb-2">
                    <div className="flex items-center justify-between">
                      <CardTitle className="text-base">最新总结</CardTitle>
                      <Badge variant="secondary" className="text-xs">
                        {latestSummary.sourceCount} 个来源
                      </Badge>
                    </div>
                  </CardHeader>
                  <CardContent>
                    <div className="text-sm leading-7 whitespace-pre-wrap">
                      {latestSummary.summary}
                    </div>
                    {/* 时间和模型信息 */}
                    <div className="flex items-center gap-2 mt-3 text-[10px] text-muted-foreground">
                      <Clock className="h-3 w-3" />
                      {formatDate(latestSummary.createdAt)}
                      {latestSummary.modelName && (
                        <Badge variant="outline" className="text-[10px] px-1 py-0">
                          {latestSummary.modelName}
                        </Badge>
                      )}
                    </div>
                  </CardContent>
                </Card>
              ) : (
                /* 无总结时的引导提示 */
                <p className="text-center text-muted-foreground py-8 text-sm">
                  点击「生成总结」开始
                </p>
              )}

              {/* 历史总结时间线（当有 2 条以上记录时显示） */}
              {summaryHistory.length > 1 && (
                <>
                  <Separator className="my-4" />
                  <h4 className="text-sm font-medium mb-3">历史总结</h4>
                  <div className="space-y-3">
                    {summaryHistory.slice(1).map((s) => (
                      <Card key={s.summaryId}>
                        <CardContent className="py-3 px-4">
                          <p className="text-xs text-muted-foreground line-clamp-3 leading-relaxed">
                            {truncate(s.summary, 200)}
                          </p>
                          <div className="flex items-center gap-2 mt-2 text-[10px] text-muted-foreground">
                            <Clock className="h-3 w-3" />
                            {formatDate(s.createdAt)}
                          </div>
                        </CardContent>
                      </Card>
                    ))}
                  </div>
                </>
              )}
            </ScrollArea>
          </>
        ) : (
          /* 未选择资料时的占位提示 */
          <div className="flex-1 flex items-center justify-center text-muted-foreground">
            <div className="text-center">
              <Sparkles className="h-10 w-10 mx-auto mb-3 opacity-40" />
              <p className="text-sm">选择一份资料，开始生成知识总结</p>
            </div>
          </div>
        )}
      </div>
    </motion.div>
  )
}
