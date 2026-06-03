import { useEffect, useMemo, useState } from 'react'
import { motion } from 'framer-motion'
import {
  AlertCircle,
  ClipboardCheck,
  FileDown,
  FolderOpen,
  History,
  Loader2,
  Play,
  Plus,
  Save,
  Timer,
  Trash2,
} from 'lucide-react'
import { useMaterials } from '@/api/materials'
import {
  useDeleteEvaluationSuite,
  useEvaluationSuiteDetail,
  useEvaluationSuiteRuns,
  useEvaluationSuites,
  useRunEvaluationSuite,
  useRunSavedEvaluationSuite,
  useSaveEvaluationSuite,
  useUpdateEvaluationSuite,
  useUpdateEvaluationSuiteSchedule,
} from '@/api/rag'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { Checkbox } from '@/components/ui/checkbox'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { Textarea } from '@/components/ui/textarea'
import { useToast } from '@/components/ui/toast'
import { cn } from '@/lib/utils'
import type { RagEvaluationCasePayload, RagEvaluationSuiteResult } from '@/types'

interface EvalCaseDraft {
  id: string
  question: string
  materialId: string
  expectedAnswerTerms: string
  expectedSourceTerms: string
}

const GENERAL_SCOPE = 'GENERAL'

function createClientId() {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  return `eval-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`
}

const emptyCase = (): EvalCaseDraft => ({
  id: createClientId(),
  question: '',
  materialId: GENERAL_SCOPE,
  expectedAnswerTerms: '',
  expectedSourceTerms: '',
})

const sampleCases = (): EvalCaseDraft[] => [
  {
    id: createClientId(),
    question: '这份资料的核心主题是什么？',
    materialId: GENERAL_SCOPE,
    expectedAnswerTerms: '核心主题, 使用场景',
    expectedSourceTerms: '',
  },
  {
    id: createClientId(),
    question: '请解释资料中的关键概念，并引用依据。',
    materialId: GENERAL_SCOPE,
    expectedAnswerTerms: '定义, 依据',
    expectedSourceTerms: '',
  },
]

function parseTerms(value: string) {
  return value
    .split(/\r?\n|,/)
    .map((item) => item.trim())
    .filter(Boolean)
}

function percent(value?: number | null) {
  return `${Math.round((Number.isFinite(value) ? Number(value) : 0) * 100)}%`
}

function scoreTone(value: number) {
  if (value >= 0.8) return 'bg-emerald-500'
  if (value >= 0.6) return 'bg-amber-500'
  return 'bg-rose-500'
}

function caseToDraft(item: RagEvaluationCasePayload): EvalCaseDraft {
  return {
    id: createClientId(),
    question: item.question || '',
    materialId: item.materialId == null ? GENERAL_SCOPE : String(item.materialId),
    expectedAnswerTerms: (item.expectedAnswerTerms || []).join(', '),
    expectedSourceTerms: (item.expectedSourceTerms || []).join(', '),
  }
}

function formatDate(value?: string | null) {
  if (!value) return '从未运行'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleString()
}

export function EvaluationPage() {
  const { data: materials = [] } = useMaterials()
  const { data: suites = [] } = useEvaluationSuites()
  const runSuite = useRunEvaluationSuite()
  const saveSuite = useSaveEvaluationSuite()
  const updateSuite = useUpdateEvaluationSuite()
  const deleteSuite = useDeleteEvaluationSuite()
  const runSavedSuite = useRunSavedEvaluationSuite()
  const updateSchedule = useUpdateEvaluationSuiteSchedule()
  const { showToast } = useToast()

  const [selectedSuiteId, setSelectedSuiteId] = useState<string | null>(null)
  const [suiteName, setSuiteName] = useState('RAG 回归评估套件')
  const [suiteDescription, setSuiteDescription] = useState('')
  const [scheduled, setScheduled] = useState(false)
  const [scheduleIntervalHours, setScheduleIntervalHours] = useState(24)
  const [cases, setCases] = useState<EvalCaseDraft[]>([emptyCase()])
  const [result, setResult] = useState<RagEvaluationSuiteResult | null>(null)

  const { data: suiteDetail } = useEvaluationSuiteDetail(selectedSuiteId)
  const { data: suiteRuns = [] } = useEvaluationSuiteRuns(selectedSuiteId)

  const parsedMaterials = useMemo(
    () => materials.filter((material) => material.parseStatus === 'SUCCESS' || material.parseStatus === 'PARSED'),
    [materials],
  )

  useEffect(() => {
    if (!suiteDetail) return
    setSuiteName(suiteDetail.name)
    setSuiteDescription(suiteDetail.description || '')
    setScheduled(suiteDetail.scheduled)
    setScheduleIntervalHours(suiteDetail.scheduleIntervalHours || 24)
    setCases(suiteDetail.cases.length > 0 ? suiteDetail.cases.map(caseToDraft) : [emptyCase()])
    setResult(suiteDetail.latestRun?.result || null)
  }, [suiteDetail])

  const readyCases = cases.filter((item) => item.question.trim())
  const selectedSuite = suites.find((item) => item.id === selectedSuiteId)
  const latestRun = suiteRuns[0] || suiteDetail?.latestRun || null
  const canRun = readyCases.length > 0 && !runSuite.isPending
  const canSave = suiteName.trim() && readyCases.length > 0 && !saveSuite.isPending && !updateSuite.isPending
  const canRunSaved = !!selectedSuiteId && !runSavedSuite.isPending
  const activeResult = result || latestRun || null

  const buildPayload = () => ({
    cases: readyCases.map((item) => ({
      question: item.question.trim(),
      materialId: item.materialId === GENERAL_SCOPE ? null : item.materialId,
      expectedAnswerTerms: parseTerms(item.expectedAnswerTerms),
      expectedSourceTerms: parseTerms(item.expectedSourceTerms),
    })),
  })

  const updateCase = (id: string, patch: Partial<EvalCaseDraft>) => {
    setCases((current) => current.map((item) => (item.id === id ? { ...item, ...patch } : item)))
  }

  const removeCase = (id: string) => {
    setCases((current) => (current.length === 1 ? [emptyCase()] : current.filter((item) => item.id !== id)))
  }

  const startNewSuite = () => {
    setSelectedSuiteId(null)
    setSuiteName('RAG 回归评估套件')
    setSuiteDescription('')
    setScheduled(false)
    setScheduleIntervalHours(24)
    setCases([emptyCase()])
    setResult(null)
  }

  const handleRunAdHoc = () => {
    if (!canRun) {
      showToast('请先添加至少一个评估问题。')
      return
    }

    runSuite.mutate(buildPayload(), {
      onSuccess: (data) => {
        setResult(data)
        showToast(`评估完成：通过 ${data.passedCases}/${data.totalCases} 个用例。`)
      },
      onError: (error) => showToast(error instanceof Error ? error.message : '评估失败。'),
    })
  }

  const handleSave = () => {
    if (!canSave) {
      showToast('请填写套件名称，并至少添加一个问题。')
      return
    }

    const payload = {
      name: suiteName.trim(),
      description: suiteDescription.trim(),
      ...buildPayload(),
    }

    if (selectedSuiteId) {
      updateSuite.mutate(
        { id: selectedSuiteId, payload },
        {
          onSuccess: (data) => {
            setSelectedSuiteId(data.id)
            showToast('评估套件已更新。')
          },
          onError: (error) => showToast(error instanceof Error ? error.message : '更新失败。'),
        },
      )
      return
    }

    saveSuite.mutate(payload, {
      onSuccess: (data) => {
        setSelectedSuiteId(data.id)
        showToast('评估套件已保存。')
      },
      onError: (error) => showToast(error instanceof Error ? error.message : '保存失败。'),
    })
  }

  const handleRunSaved = () => {
    if (!selectedSuiteId) {
      showToast('请先保存或选择一个评估套件。')
      return
    }

    runSavedSuite.mutate(selectedSuiteId, {
      onSuccess: (data) => {
        setResult(data.result || null)
        showToast(`已保存套件运行完成：通过 ${data.passedCases}/${data.totalCases} 个用例。`)
      },
      onError: (error) => showToast(error instanceof Error ? error.message : '运行已保存套件失败。'),
    })
  }

  const handleDelete = () => {
    if (!selectedSuiteId || !window.confirm('确定删除这个评估套件吗？')) return
    deleteSuite.mutate(selectedSuiteId, {
      onSuccess: () => {
        startNewSuite()
        showToast('评估套件已删除。')
      },
      onError: (error) => showToast(error instanceof Error ? error.message : '删除失败。'),
    })
  }

  const handleScheduleUpdate = () => {
    if (!selectedSuiteId) {
      showToast('请先保存套件，再启用定时回归。')
      return
    }
    updateSchedule.mutate(
      {
        id: selectedSuiteId,
        payload: {
          scheduled,
          intervalHours: scheduleIntervalHours,
        },
      },
      {
        onSuccess: (data) => {
          setScheduled(data.scheduled)
          setScheduleIntervalHours(data.scheduleIntervalHours)
          showToast(data.scheduled ? '定时回归已启用。' : '定时回归已关闭。')
        },
        onError: (error) => showToast(error instanceof Error ? error.message : '定时设置更新失败。'),
      },
    )
  }

  const exportResult = () => {
    if (!result) return
    const blob = new Blob([JSON.stringify(result, null, 2)], { type: 'application/json;charset=utf-8' })
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `rag-evaluation-${new Date().toISOString().slice(0, 10)}.json`
    link.click()
    URL.revokeObjectURL(url)
  }

  return (
    <motion.div
      className="flex h-full min-h-0 flex-col bg-[#f6f7f9] dark:bg-[#171a21]"
      initial={{ opacity: 0, y: 10 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.25 }}
    >
      <div className="border-b bg-white px-6 py-4 dark:border-slate-800 dark:bg-[#171a21]">
        <div className="flex flex-wrap items-start justify-between gap-4">
          <div>
            <div className="flex items-center gap-2">
              <span className="flex h-9 w-9 items-center justify-center rounded-lg bg-slate-900 text-white dark:bg-white dark:text-slate-950">
                <ClipboardCheck className="h-5 w-5" />
              </span>
              <h2 className="text-lg font-semibold text-slate-950 dark:text-white">RAG 评估</h2>
            </div>
            <p className="mt-2 max-w-2xl text-sm text-muted-foreground">
              创建可复用的回归评估套件，运行临时检查，并持续对比检索质量。
            </p>
            <div className="mt-3 flex flex-wrap gap-2 text-xs">
              <StatusPill label="已保存套件" value={suites.length} />
              <StatusPill label="当前用例" value={readyCases.length} />
              <StatusPill label="最近通过率" value={activeResult ? percent(activeResult.passRate) : '暂无'} />
              <StatusPill label="定时回归" value={selectedSuiteId && scheduled ? '已启用' : '未启用'} />
            </div>
          </div>
          <div className="flex flex-wrap items-center gap-2">
            <Button variant="outline" size="sm" onClick={startNewSuite}>
              <Plus className="mr-1.5 h-4 w-4" />
              新建
            </Button>
            <Button variant="outline" size="sm" disabled={!result} onClick={exportResult}>
              <FileDown className="mr-1.5 h-4 w-4" />
              导出
            </Button>
            <Button variant="outline" size="sm" disabled={!canSave} onClick={handleSave}>
              {saveSuite.isPending || updateSuite.isPending ? (
                <Loader2 className="mr-1.5 h-4 w-4 animate-spin" />
              ) : (
                <Save className="mr-1.5 h-4 w-4" />
              )}
              {selectedSuiteId ? '更新' : '保存'}
            </Button>
            <Button variant="outline" size="sm" disabled={!canRunSaved} onClick={handleRunSaved}>
              {runSavedSuite.isPending ? <Loader2 className="mr-1.5 h-4 w-4 animate-spin" /> : <History className="mr-1.5 h-4 w-4" />}
              运行已保存
            </Button>
            <Button size="sm" disabled={!canRun} onClick={handleRunAdHoc}>
              {runSuite.isPending ? <Loader2 className="mr-1.5 h-4 w-4 animate-spin" /> : <Play className="mr-1.5 h-4 w-4" />}
              运行草稿
            </Button>
          </div>
        </div>
      </div>

      <div className="grid min-h-0 flex-1 grid-cols-1 gap-4 overflow-auto p-6 xl:grid-cols-[300px_minmax(440px,560px)_1fr]">
        <aside className="min-w-0 space-y-3">
          <PanelTitle title="已保存套件" description="选择一个基准套件继续运行或编辑。" count={suites.length} />
          <div className="space-y-2">
            {suites.length === 0 ? (
              <div className="rounded-lg border border-dashed border-slate-300 bg-white p-4 text-sm text-muted-foreground dark:border-slate-800 dark:bg-slate-950/30">
                暂无已保存的评估套件。
              </div>
            ) : (
              suites.map((suite) => (
                <button
                  key={suite.id}
                  type="button"
                  onClick={() => setSelectedSuiteId(suite.id)}
                  className={cn(
                    'w-full rounded-lg border p-3 text-left shadow-sm transition hover:-translate-y-0.5 hover:border-slate-400 hover:shadow-md dark:hover:border-slate-600',
                    selectedSuiteId === suite.id
                      ? 'border-slate-900 bg-white ring-2 ring-slate-900/10 dark:border-slate-200 dark:bg-slate-900'
                      : 'border-slate-200 bg-white dark:border-slate-800 dark:bg-slate-950/30',
                  )}
                >
                  <div className="flex items-start justify-between gap-2">
                    <span className="line-clamp-2 text-sm font-medium text-slate-950 dark:text-white">{suite.name}</span>
                    <Badge variant={suite.lastPassRate != null && suite.lastPassRate >= 0.8 ? 'success' : 'outline'} className="shrink-0 text-[10px]">
                      {suite.lastPassRate == null ? '新建' : percent(suite.lastPassRate)}
                    </Badge>
                  </div>
                  <div className="mt-2 flex items-center justify-between text-xs text-muted-foreground">
                    <span>{suite.caseCount} 个用例</span>
                    <span>{suite.scheduled ? `下次 ${formatDate(suite.nextRunAt)}` : formatDate(suite.lastRunAt)}</span>
                  </div>
                </button>
              ))
            )}
          </div>
        </aside>

        <section className="min-w-0 space-y-3">
          <PanelTitle title="套件配置" description="定义评估范围、调度策略和保存方式。" />
          <div className="rounded-lg border border-slate-200 bg-white p-4 shadow-sm dark:border-slate-800 dark:bg-slate-950/30">
            <div className="space-y-3">
              <div className="space-y-1.5">
                <Label htmlFor="suite-name">套件名称</Label>
                <Input id="suite-name" value={suiteName} onChange={(event) => setSuiteName(event.target.value)} />
              </div>
              <div className="space-y-1.5">
                <Label htmlFor="suite-description">描述</Label>
                <Textarea
                  id="suite-description"
                  className="min-h-[72px] resize-none"
                  value={suiteDescription}
                  onChange={(event) => setSuiteDescription(event.target.value)}
                  placeholder="评估范围、基准目标或发布检查点。"
                />
              </div>
              {selectedSuiteId && (
                <div className="flex items-center justify-between gap-3 border-t pt-3 text-xs text-muted-foreground dark:border-slate-800">
                  <span className="inline-flex min-w-0 items-center gap-1.5">
                    <FolderOpen className="h-3.5 w-3.5 shrink-0" />
                    <span className="truncate">{selectedSuite?.name || selectedSuiteId}</span>
                  </span>
                  <Button variant="ghost" size="sm" className="h-7 px-2 text-destructive" onClick={handleDelete} disabled={deleteSuite.isPending}>
                    <Trash2 className="mr-1 h-3.5 w-3.5" />
                    删除
                  </Button>
                </div>
              )}
              <div className="border-t pt-3 dark:border-slate-800">
                <div className="flex flex-wrap items-center justify-between gap-3">
                  <label className="flex items-center gap-2 text-sm font-medium text-slate-900 dark:text-slate-100">
                    <Checkbox checked={scheduled} onCheckedChange={(checked) => setScheduled(checked === true)} disabled={!selectedSuiteId} />
                    定时回归
                  </label>
                  <div className="flex items-center gap-2">
                    <Timer className="h-4 w-4 text-muted-foreground" />
                    <Input
                      className="h-8 w-20"
                      type="number"
                      min={1}
                      max={720}
                      value={scheduleIntervalHours}
                      onChange={(event) => setScheduleIntervalHours(Math.max(1, Math.min(720, Number(event.target.value) || 24)))}
                      disabled={!selectedSuiteId}
                    />
                    <span className="text-xs text-muted-foreground">小时</span>
                    <Button
                      variant="outline"
                      size="sm"
                      className="h-8"
                      onClick={handleScheduleUpdate}
                      disabled={!selectedSuiteId || updateSchedule.isPending}
                    >
                      {updateSchedule.isPending && <Loader2 className="mr-1.5 h-4 w-4 animate-spin" />}
                      应用
                    </Button>
                  </div>
                </div>
                <p className="mt-2 text-xs text-muted-foreground">
                  下次运行：{selectedSuiteId && scheduled ? formatDate(suiteDetail?.nextRunAt) : '未启用'}
                </p>
              </div>
            </div>
          </div>

          <div className="flex items-end justify-between gap-3">
            <PanelTitle title="评估用例" description="每个问题会独立检索、回答并计算质量分。" />
            <div className="flex items-center gap-2">
              <Button variant="outline" size="sm" onClick={() => setCases(sampleCases())}>
                加载示例
              </Button>
              <Button variant="outline" size="sm" onClick={() => setCases((current) => [...current, emptyCase()])} disabled={cases.length >= 25}>
                <Plus className="mr-1.5 h-4 w-4" />
                添加
              </Button>
            </div>
          </div>

          <div className="space-y-3">
            {cases.map((item, index) => (
              <Card key={item.id} className="rounded-lg border-slate-200 shadow-sm dark:border-slate-800 dark:bg-slate-950/30">
                <CardContent className="space-y-3 p-4">
                  <div className="flex items-center justify-between">
                    <Badge variant="outline" className="text-[10px]">用例 {index + 1}</Badge>
                    <Button variant="ghost" size="icon" className="h-7 w-7" onClick={() => removeCase(item.id)} aria-label="删除用例">
                      <Trash2 className="h-4 w-4 text-muted-foreground" />
                    </Button>
                  </div>

                  <div className="space-y-1.5">
                    <Label htmlFor={`question-${item.id}`}>问题</Label>
                    <Textarea
                      id={`question-${item.id}`}
                      className="min-h-[84px] resize-none"
                      value={item.question}
                      onChange={(event) => updateCase(item.id, { question: event.target.value })}
                      placeholder="用于 RAG 回归验证的问题。"
                    />
                  </div>

                  <div className="space-y-1.5">
                    <Label>资料范围</Label>
                    <Select value={item.materialId} onValueChange={(value) => updateCase(item.id, { materialId: value })}>
                      <SelectTrigger>
                        <SelectValue />
                      </SelectTrigger>
                      <SelectContent>
                        <SelectItem value={GENERAL_SCOPE}>全部资料 / 通用检索</SelectItem>
                        {parsedMaterials.map((material) => (
                          <SelectItem key={material.id} value={String(material.id)}>
                            {material.title || material.originalName}
                          </SelectItem>
                        ))}
                      </SelectContent>
                    </Select>
                  </div>

                  <div className="grid gap-3 sm:grid-cols-2">
                    <div className="space-y-1.5">
                      <Label htmlFor={`answer-${item.id}`}>回答必须包含</Label>
                      <Input
                        id={`answer-${item.id}`}
                        value={item.expectedAnswerTerms}
                        onChange={(event) => updateCase(item.id, { expectedAnswerTerms: event.target.value })}
                        placeholder="用逗号或换行分隔"
                      />
                    </div>
                    <div className="space-y-1.5">
                      <Label htmlFor={`source-${item.id}`}>来源必须命中</Label>
                      <Input
                        id={`source-${item.id}`}
                        value={item.expectedSourceTerms}
                        onChange={(event) => updateCase(item.id, { expectedSourceTerms: event.target.value })}
                        placeholder="用逗号或换行分隔"
                      />
                    </div>
                  </div>
                </CardContent>
              </Card>
            ))}
          </div>
        </section>

        <section className="min-w-0 space-y-4">
          <PanelTitle title="结果看板" description="运行后查看通过率、忠实度、相关性和逐项缺失。" />
          <div className="grid gap-3 md:grid-cols-4">
            <Metric label="通过率" value={result ? percent(result.passRate) : percent(latestRun?.passRate)} tone={result?.passRate ?? latestRun?.passRate} />
            <Metric
              label="忠实度"
              value={result ? percent(result.averageFaithfulnessScore) : percent(latestRun?.averageFaithfulnessScore)}
              tone={result?.averageFaithfulnessScore ?? latestRun?.averageFaithfulnessScore}
            />
            <Metric
              label="相关性"
              value={result ? percent(result.averageContextRelevanceScore) : percent(latestRun?.averageContextRelevanceScore)}
              tone={result?.averageContextRelevanceScore ?? latestRun?.averageContextRelevanceScore}
            />
            <Metric label="综合分" value={result ? percent(result.averageOverallScore) : percent(latestRun?.averageOverallScore)} tone={result?.averageOverallScore ?? latestRun?.averageOverallScore} />
          </div>

          {!result ? (
            <div className="flex min-h-[320px] items-center justify-center rounded-lg border border-dashed border-slate-300 bg-white p-6 text-center text-sm text-muted-foreground dark:border-slate-800 dark:bg-slate-950/30">
              <div className="max-w-sm">
                <ClipboardCheck className="mx-auto mb-3 h-8 w-8 text-slate-400" />
                <p className="font-medium text-slate-800 dark:text-slate-100">等待运行评估</p>
                <p className="mt-1">运行草稿或已保存套件后，可查看每个用例的分数、判定结果和缺失关键词。</p>
              </div>
            </div>
          ) : (
            <ResultTable result={result} />
          )}

          {suiteRuns.length > 0 && (
            <div className="rounded-lg border border-slate-200 bg-white dark:border-slate-800 dark:bg-slate-950/30">
              <div className="flex items-center justify-between border-b px-4 py-3 dark:border-slate-800">
                <h3 className="text-sm font-semibold text-slate-900 dark:text-slate-100">运行历史</h3>
                <Badge variant="outline" className="text-[10px]">{suiteRuns.length}</Badge>
              </div>
              <div className="divide-y dark:divide-slate-800">
                {suiteRuns.slice(0, 8).map((run) => (
                  <button
                    key={run.id}
                    type="button"
                    className="flex w-full items-center justify-between gap-3 px-4 py-3 text-left hover:bg-slate-50 dark:hover:bg-slate-900/60"
                    onClick={() => setResult(run.result || null)}
                  >
                    <div className="min-w-0">
                      <p className="truncate text-sm font-medium text-slate-900 dark:text-slate-100">{formatDate(run.createdAt)}</p>
                      <p className="text-xs text-muted-foreground">通过 {run.passedCases}/{run.totalCases} 个用例</p>
                    </div>
                    <Badge variant={run.passRate >= 0.8 ? 'success' : run.passRate >= 0.6 ? 'warning' : 'destructive'} className="shrink-0 text-[10px]">
                      {percent(run.passRate)}
                    </Badge>
                  </button>
                ))}
              </div>
            </div>
          )}
        </section>

        <section className="xl:col-span-3">
          <UsageGuide />
        </section>
      </div>
    </motion.div>
  )
}

function StatusPill({ label, value }: { label: string; value: string | number }) {
  return (
    <span className="inline-flex items-center gap-1.5 rounded-full border border-slate-200 bg-slate-50 px-3 py-1 text-slate-600 dark:border-slate-800 dark:bg-slate-950/40 dark:text-slate-300">
      <span>{label}</span>
      <span className="font-semibold text-slate-950 dark:text-white">{value}</span>
    </span>
  )
}

function PanelTitle({ title, description, count }: { title: string; description?: string; count?: number }) {
  return (
    <div className="flex items-start justify-between gap-3">
      <div>
        <h3 className="text-sm font-semibold text-slate-900 dark:text-slate-100">{title}</h3>
        {description && <p className="mt-0.5 text-xs text-muted-foreground">{description}</p>}
      </div>
      {count != null && <Badge variant="outline" className="text-[10px]">{count}</Badge>}
    </div>
  )
}

function UsageGuide() {
  const steps = [
    ['1', '准备资料', '先上传并解析要测试的资料，确保资料状态为已解析。'],
    ['2', '添加用例', '填写问题、资料范围、回答必须包含的词、来源必须命中的词。'],
    ['3', '运行评估', '点击“运行草稿”做临时检查，确认无误后保存为回归套件。'],
    ['4', '复用回归', '改动检索、分块、向量库或提示词后，运行已保存套件检查是否退化。'],
  ]

  return (
    <div className="rounded-lg border border-slate-200 bg-white p-5 shadow-sm dark:border-slate-800 dark:bg-slate-950/30">
      <div className="mb-4 flex items-center justify-between gap-3">
        <div>
          <h3 className="text-sm font-semibold text-slate-950 dark:text-white">使用方法</h3>
          <p className="mt-1 text-xs text-muted-foreground">
            这个模块用于管理员验收 RAG 质量，不是普通聊天入口。重点看“是否找对资料”和“回答是否覆盖预期内容”。
          </p>
        </div>
        <Badge variant="outline" className="hidden text-[10px] sm:inline-flex">管理员工具</Badge>
      </div>
      <div className="grid gap-3 md:grid-cols-4">
        {steps.map(([index, title, body]) => (
          <div key={index} className="rounded-lg border border-slate-200 bg-slate-50 p-3 dark:border-slate-800 dark:bg-slate-900/40">
            <div className="mb-2 flex items-center gap-2">
              <span className="flex h-6 w-6 items-center justify-center rounded-full bg-slate-900 text-xs font-semibold text-white dark:bg-white dark:text-slate-950">
                {index}
              </span>
              <span className="text-sm font-medium text-slate-950 dark:text-white">{title}</span>
            </div>
            <p className="text-xs leading-5 text-muted-foreground">{body}</p>
          </div>
        ))}
      </div>
      <div className="mt-4 rounded-lg bg-slate-950 px-4 py-3 text-xs leading-5 text-slate-100 dark:bg-slate-900">
        示例：问题填“什么是 TCP 三次握手？”，回答必须包含填“SYN, ACK, 建立连接”，来源必须命中填“三次握手, TCP连接”。运行后如果来源未命中，说明检索策略需要调整。
      </div>
    </div>
  )
}

function Metric({ label, value, tone }: { label: string; value: string; tone?: number | null }) {
  return (
    <div className="rounded-lg border border-slate-200 bg-white p-4 shadow-sm dark:border-slate-800 dark:bg-slate-950/30">
      <p className="text-xs text-muted-foreground">{label}</p>
      <div className="mt-2 flex items-end justify-between gap-3">
        <span className="text-2xl font-semibold text-slate-950 dark:text-white">{value}</span>
        {tone != null && <span className={cn('mb-1 h-2 w-8 rounded-full', scoreTone(tone))} />}
      </div>
    </div>
  )
}

function ResultTable({ result }: { result: RagEvaluationSuiteResult }) {
  return (
    <Card className="rounded-lg shadow-none dark:border-slate-800 dark:bg-slate-950/30">
      <CardContent className="p-0">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead className="w-[70px]">结果</TableHead>
              <TableHead>问题</TableHead>
              <TableHead className="w-[120px]">忠实度</TableHead>
              <TableHead className="w-[120px]">上下文</TableHead>
              <TableHead className="w-[160px]">覆盖率</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {result.cases.map((item) => (
              <TableRow key={`${item.caseIndex}-${item.questionId || item.question}`}>
                <TableCell>
                  <Badge variant={item.passed ? 'success' : 'destructive'} className="text-[10px]">
                    {item.passed ? '通过' : '失败'}
                  </Badge>
                </TableCell>
                <TableCell>
                  <div className="space-y-1">
                    <p className="text-sm font-medium leading-5">{item.question}</p>
                    {(item.missingAnswerTerms.length > 0 || item.missingSourceTerms.length > 0) && (
                      <div className="flex items-start gap-1.5 text-xs text-amber-600">
                        <AlertCircle className="mt-0.5 h-3.5 w-3.5 shrink-0" />
                        <span>缺失：{[...item.missingAnswerTerms, ...item.missingSourceTerms].join(', ')}</span>
                      </div>
                    )}
                  </div>
                </TableCell>
                <TableCell><Score value={item.faithfulnessScore} /></TableCell>
                <TableCell><Score value={item.contextRelevanceScore} /></TableCell>
                <TableCell>
                  <div className="space-y-1 text-xs text-muted-foreground">
                    <div>回答 {percent(item.expectedAnswerCoverage)}</div>
                    <div>来源 {percent(item.expectedSourceCoverage)}</div>
                  </div>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </CardContent>
    </Card>
  )
}

function Score({ value }: { value: number }) {
  return (
    <div className="flex items-center gap-2">
      <div className="h-2 w-16 overflow-hidden rounded-full bg-slate-100 dark:bg-slate-800">
        <div className={cn('h-full rounded-full', scoreTone(value))} style={{ width: percent(value) }} />
      </div>
      <span className="text-xs text-muted-foreground">{percent(value)}</span>
    </div>
  )
}
