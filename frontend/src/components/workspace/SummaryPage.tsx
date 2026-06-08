/**
 * SummaryPage - 资料总结页面
 */
import { useEffect, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { motion } from 'framer-motion'
import { useMaterials } from '@/api/materials'
import { useSummarizeMaterial, useMaterialSummaryHistory, useUpdateSummaryNote } from '@/api/rag'
import { useAuth } from '@/context/AuthContext'
import { useToast } from '@/components/ui/toast'
import { LOGIN_REQUIRED_MESSAGE, redirectToLogin } from '@/lib/auth-gate'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { ScrollArea } from '@/components/ui/scroll-area'
import { Separator } from '@/components/ui/separator'
import { Skeleton } from '@/components/ui/skeleton'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Textarea } from '@/components/ui/textarea'
import { formatDate, truncate } from '@/lib/utils'
import {
  BookOpen, Clock, ExternalLink, FileText, Layers3, Loader2, PencilLine, Save,
  Sparkles,
} from 'lucide-react'
import type { SummaryResult, SummarySource, SummaryType } from '@/types'

const SUMMARY_TYPES: Array<{ value: SummaryType; label: string; description: string }> = [
  { value: 'GENERAL', label: '通用结构化', description: '适合报告、文档、网页、学习资料等大多数内容' },
  { value: 'BRIEF', label: '简洁摘要', description: '快速抓住主要信息' },
  { value: 'DETAILED', label: '详细总结', description: '保留更多背景、细节和限制' },
  { value: 'OUTLINE', label: '章节提纲', description: '按大标题、小标题梳理结构' },
  { value: 'REVIEW', label: '复习巩固', description: '偏知识点、易错点和可追问问题' },
  { value: 'ACTION', label: '行动清单', description: '偏结论、风险、待办和决策依据' },
]

export function SummaryPage() {
  const navigate = useNavigate()
  const { isAuthenticated } = useAuth()
  const { showToast } = useToast()
  const { data: materials = [] } = useMaterials()
  const [selectedMaterialId, setSelectedMaterialId] = useState<string | null>(null)
  const [summaryType, setSummaryType] = useState<SummaryType>('GENERAL')
  const [noteDraft, setNoteDraft] = useState('')

  const { data: summaryHistory = [], isLoading: summaryLoading } = useMaterialSummaryHistory(selectedMaterialId)
  const summarizeMutation = useSummarizeMaterial()
  const updateNoteMutation = useUpdateSummaryNote()

  const selectedMaterial = materials.find((m) => m.id === selectedMaterialId) || null
  const latestSummary = summaryHistory[0] || null
  const selectedType = SUMMARY_TYPES.find((item) => item.value === summaryType) || SUMMARY_TYPES[0]

  useEffect(() => {
    setNoteDraft(latestSummary?.userNote || latestSummary?.summary || '')
  }, [latestSummary?.summaryId])

  const sourcesByChunk = useMemo(() => {
    const sources = latestSummary?.sources || []
    return new Map(sources.map((source) => [source.chunkId, source]))
  }, [latestSummary])

  const handleGenerate = () => {
    if (!isAuthenticated) {
      showToast(LOGIN_REQUIRED_MESSAGE, 2000)
      redirectToLogin()
      return
    }
    if (selectedMaterialId) {
      summarizeMutation.mutate({ materialId: selectedMaterialId, summaryType })
    }
  }

  const handleSaveNote = () => {
    if (!latestSummary) return
    updateNoteMutation.mutate(
      { summaryId: latestSummary.summaryId, userNote: noteDraft },
      { onSuccess: () => showToast('整理版已保存', 1600) }
    )
  }

  const openSource = (source: SummarySource) => {
    const params = new URLSearchParams({ materialId: source.materialId, chunkId: source.chunkId })
    if (source.pageNo && source.pageNo > 0) params.set('pageNo', String(source.pageNo))
    params.set('view', 'smart')
    navigate(`/workspace/reader?${params.toString()}`)
  }

  return (
    <motion.div className="flex h-full bg-background" initial={{ opacity: 0 }} animate={{ opacity: 1 }} transition={{ duration: 0.25 }}>
      <aside className="w-64 border-r flex flex-col h-full bg-muted/20">
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
                className="w-full justify-start text-xs h-8"
                onClick={() => setSelectedMaterialId(m.id)}
              >
                <span className="truncate">{m.title || m.originalName}</span>
              </Button>
            ))}
          </div>
        </ScrollArea>
      </aside>

      <main className="flex-1 flex flex-col overflow-hidden">
        {selectedMaterial ? (
          <>
            <div className="border-b px-6 py-4">
              <div className="flex flex-wrap items-center justify-between gap-3">
                <div className="min-w-0">
                  <h3 className="text-sm font-semibold truncate">{selectedMaterial.title || selectedMaterial.originalName}</h3>
                  <p className="text-xs text-muted-foreground">
                    {selectedMaterial.chunkCount} 个片段 · {summaryHistory.length} 条总结记录
                  </p>
                </div>
                <div className="flex items-center gap-2">
                  <Select value={summaryType} onValueChange={(value) => setSummaryType(value as SummaryType)}>
                    <SelectTrigger className="h-9 w-36">
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      {SUMMARY_TYPES.map((item) => (
                        <SelectItem key={item.value} value={item.value}>{item.label}</SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                  <Button size="sm" onClick={handleGenerate} disabled={summarizeMutation.isPending}>
                    {summarizeMutation.isPending ? <Loader2 className="h-4 w-4 mr-1 animate-spin" /> : <Sparkles className="h-4 w-4 mr-1" />}
                    生成总结
                  </Button>
                </div>
              </div>
              <p className="mt-2 text-xs text-muted-foreground">{selectedType.description}</p>
            </div>

            <ScrollArea className="flex-1">
              <div className="grid gap-4 px-6 py-4 xl:grid-cols-[minmax(0,1fr)_340px]">
                <section className="space-y-4 min-w-0">
                  {summaryLoading ? (
                    <SummarySkeleton />
                  ) : latestSummary ? (
                    <SummaryContent summary={latestSummary} sourcesByChunk={sourcesByChunk} onOpenSource={openSource} />
                  ) : (
                    <EmptySummary />
                  )}

                  {summaryHistory.length > 1 && (
                    <HistoryList summaries={summaryHistory.slice(1)} />
                  )}
                </section>

                <aside className="space-y-4 min-w-0">
                  <Card>
                    <CardHeader className="pb-2">
                      <CardTitle className="text-sm flex items-center gap-2">
                        <PencilLine className="h-4 w-4" /> 我的整理版
                      </CardTitle>
                    </CardHeader>
                    <CardContent className="space-y-3">
                      <Textarea
                        value={noteDraft}
                        onChange={(event) => setNoteDraft(event.target.value)}
                        className="min-h-[260px] resize-none leading-6"
                        placeholder="可以在这里把 AI 总结改成自己的版本。"
                        disabled={!latestSummary}
                      />
                      <Button className="w-full" size="sm" disabled={!latestSummary || updateNoteMutation.isPending} onClick={handleSaveNote}>
                        {updateNoteMutation.isPending ? <Loader2 className="h-4 w-4 mr-1 animate-spin" /> : <Save className="h-4 w-4 mr-1" />}
                        保存整理版
                      </Button>
                    </CardContent>
                  </Card>

                  <Card>
                    <CardHeader className="pb-2">
                      <CardTitle className="text-sm flex items-center gap-2">
                        <ExternalLink className="h-4 w-4" /> 来源定位
                      </CardTitle>
                    </CardHeader>
                    <CardContent>
                      {latestSummary?.sources?.length ? (
                        <div className="space-y-2">
                          {latestSummary.sources.slice(0, 8).map((source) => (
                            <SourceButton key={source.chunkId} source={source} onOpen={openSource} />
                          ))}
                        </div>
                      ) : (
                        <p className="text-xs text-muted-foreground">生成后会显示可跳转来源。</p>
                      )}
                    </CardContent>
                  </Card>
                </aside>
              </div>
            </ScrollArea>
          </>
        ) : (
          <div className="flex-1 flex items-center justify-center text-muted-foreground">
            <div className="text-center">
              <Sparkles className="h-10 w-10 mx-auto mb-3 opacity-40" />
              <p className="text-sm">选择一份资料，开始生成通用结构化总结</p>
            </div>
          </div>
        )}
      </main>
    </motion.div>
  )
}

function SummaryContent({
  summary, sourcesByChunk, onOpenSource,
}: {
  summary: SummaryResult
  sourcesByChunk: Map<string, SummarySource>
  onOpenSource: (source: SummarySource) => void
}) {
  const sections = summary.sections?.length ? summary.sections : [{ title: '核心摘要', items: [summary.summary], sources: [] }]
  return (
    <Card>
      <CardHeader className="pb-3">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <CardTitle className="text-base flex items-center gap-2">
            <Layers3 className="h-4 w-4" /> 最新总结
          </CardTitle>
          <div className="flex items-center gap-2 text-[10px] text-muted-foreground">
            <Badge variant="secondary" className="text-[10px]">{summary.sourceCount} 个来源</Badge>
            {summary.modelName && <Badge variant="outline" className="text-[10px]">{summary.modelName}</Badge>}
            <Clock className="h-3 w-3" /> {formatDate(summary.createdAt)}
          </div>
        </div>
      </CardHeader>
      <CardContent className="space-y-4">
        <p className="text-sm leading-7 whitespace-pre-wrap">{summary.summary}</p>
        <Separator />
        <div className="space-y-4">
          {sections.map((section) => (
            <div key={section.title} className="space-y-2">
              <h4 className="text-sm font-semibold">{section.title}</h4>
              <ul className="space-y-2">
                {section.items.map((item, index) => (
                  <li key={`${section.title}-${index}`} className="text-sm leading-7 text-foreground/90">
                    <span className="mr-2 text-muted-foreground">{index + 1}.</span>{item}
                  </li>
                ))}
              </ul>
              {section.sources?.length ? (
                <div className="flex flex-wrap gap-1.5 pt-1">
                  {section.sources.slice(0, 4).map((source) => {
                    const fullSource = sourcesByChunk.get(source.chunkId) || source
                    return (
                      <Button key={source.chunkId} variant="outline" size="sm" className="h-7 px-2 text-[11px]" onClick={() => onOpenSource(fullSource)}>
                        <ExternalLink className="h-3 w-3 mr-1" />
                        {source.pageNo ? `第 ${source.pageNo} 页` : `片段 ${(source.chunkIndex ?? 0) + 1}`}
                      </Button>
                    )
                  })}
                </div>
              ) : null}
            </div>
          ))}
        </div>
      </CardContent>
    </Card>
  )
}

function SourceButton({ source, onOpen }: { source: SummarySource; onOpen: (source: SummarySource) => void }) {
  return (
    <button
      type="button"
      className="w-full rounded-md border bg-background px-3 py-2 text-left transition-colors hover:bg-muted"
      onClick={() => onOpen(source)}
    >
      <div className="flex items-center justify-between gap-2">
        <span className="text-xs font-medium truncate">{source.title || '来源片段'}</span>
        <ExternalLink className="h-3.5 w-3.5 text-muted-foreground shrink-0" />
      </div>
      <p className="mt-1 text-[11px] text-muted-foreground line-clamp-2 leading-5">
        {source.pageNo ? `第 ${source.pageNo} 页 · ` : ''}{truncate(source.excerpt, 120)}
      </p>
    </button>
  )
}

function HistoryList({ summaries }: { summaries: SummaryResult[] }) {
  return (
    <>
      <Separator />
      <div>
        <h4 className="mb-3 text-sm font-medium">历史总结</h4>
        <div className="space-y-3">
          {summaries.map((summary) => (
            <Card key={summary.summaryId}>
              <CardContent className="py-3 px-4">
                <div className="flex items-center gap-2 text-[10px] text-muted-foreground">
                  <Clock className="h-3 w-3" /> {formatDate(summary.createdAt)}
                  {summary.summaryType && <Badge variant="outline" className="text-[10px] px-1 py-0">{summary.summaryType}</Badge>}
                </div>
                <p className="mt-2 text-xs text-muted-foreground line-clamp-3 leading-relaxed">
                  {truncate(summary.summary, 220)}
                </p>
              </CardContent>
            </Card>
          ))}
        </div>
      </div>
    </>
  )
}

function SummarySkeleton() {
  return (
    <Card>
      <CardContent className="space-y-3 p-4">
        <Skeleton className="h-4 w-1/3" />
        <Skeleton className="h-4 w-full" />
        <Skeleton className="h-4 w-5/6" />
        <Skeleton className="h-24 w-full" />
      </CardContent>
    </Card>
  )
}

function EmptySummary() {
  return (
    <Card>
      <CardContent className="flex flex-col items-center justify-center py-14 text-center text-muted-foreground">
        <FileText className="h-10 w-10 mb-3 opacity-40" />
        <p className="text-sm">点击「生成总结」开始。</p>
      </CardContent>
    </Card>
  )
}
