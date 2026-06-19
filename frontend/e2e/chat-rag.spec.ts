import { expect, test } from '@playwright/test'

const SESSION_KEY = 'learning-assistant.frontend.session'

function ok(data: unknown) {
  return {
    status: 200,
    contentType: 'application/json; charset=utf-8',
    body: JSON.stringify({ code: 0, message: 'ok', data }),
  }
}

async function mockChatPageApis(page: import('@playwright/test').Page, options?: {
  temporaryMaterial?: Record<string, unknown>
  historyItems?: unknown[]
  historyDetailById?: Record<string, unknown>
}) {
  const temporaryMaterial = options?.temporaryMaterial || {
    id: 'temporary-1',
    title: '项目速记',
    originalName: 'notes.txt',
    sourceType: 'TXT',
    text: '这里是临时资料正文，包含系统设计、缓存策略和接口说明。',
    excerpt: '这里是临时资料正文',
    fileSize: 128,
    contextStored: true,
    files: [{ name: 'notes.txt', size: 128, type: 'TXT' }],
  }
  const historyItems = options?.historyItems || []
  const historyDetailById = options?.historyDetailById || {}

  await page.route('**/*', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const pathname = url.pathname

    if (!pathname.startsWith('/api/')) {
      await route.continue()
      return
    }

    if (pathname === '/api/auth/me') {
      await route.fulfill(ok({
        id: 'user-1',
        username: 'tester',
        nickname: 'Tester',
        avatar: '',
        role: 'USER',
      }))
      return
    }

    if (pathname === '/api/rag/usage') {
      await route.fulfill(ok({
        dailyLimit: 20,
        usedToday: 2,
        remainingToday: 18,
        unlimited: false,
      }))
      return
    }

    if (pathname === '/api/rag/history') {
      await route.fulfill(ok(historyItems))
      return
    }

    if (pathname.startsWith('/api/rag/history/')) {
      const historyId = pathname.split('/').pop() || ''
      if (historyDetailById[historyId]) {
        await route.fulfill(ok(historyDetailById[historyId]))
        return
      }
      await route.fulfill({
        status: 404,
        contentType: 'application/json; charset=utf-8',
        body: JSON.stringify({ code: 404, message: `unmocked history detail: ${historyId}`, data: null }),
      })
      return
    }

    if (pathname === '/api/favorites') {
      await route.fulfill(ok([]))
      return
    }

    if (pathname === '/api/materials') {
      await route.fulfill(ok([]))
      return
    }

    if (pathname === '/api/llm/user-config') {
      await route.fulfill(ok({
        enabled: false,
        baseUrl: '',
        model: '',
        hasApiKey: false,
        activeLabel: '',
        activeConfigId: null,
        configs: [],
      }))
      return
    }

    if (pathname === '/api/llm/status') {
      await route.fulfill(ok({
        enabled: true,
        configured: true,
        message: 'LLM 已连接',
      }))
      return
    }

    if (pathname === '/api/materials/temporary') {
      await route.fulfill(ok(temporaryMaterial))
      return
    }

    await route.fulfill({
      status: 404,
      contentType: 'application/json; charset=utf-8',
      body: JSON.stringify({ code: 404, message: `unmocked api: ${pathname}`, data: null }),
    })
  })
}

async function installStreamMock(page: import('@playwright/test').Page, responses: Array<{
  events: Array<
    | { type: 'status'; delayMs: number; data: { stage?: string; message?: string } }
    | { type: 'sources'; delayMs: number; data: { sources: Array<Record<string, unknown>> } }
    | { type: 'retrieval_debug'; delayMs: number; data: { items: Array<Record<string, unknown>> } }
    | { type: 'chunk'; delayMs: number; delta: string }
    | { type: 'done'; delayMs: number; data: { questionId: string; conversationId?: string; answer?: string; continuable?: boolean; continuationHint?: string | null } }
  >
}>) {
  await page.addInitScript((streamPlans) => {
    const originalFetch = window.fetch.bind(window)
    const encoder = new TextEncoder()
    const plans = streamPlans
    const payloads: unknown[] = []

    Object.defineProperty(window, '__chatStreamPayloads', {
      configurable: true,
      enumerable: false,
      writable: true,
      value: payloads,
    })

    window.fetch = async (input, init) => {
      const requestUrl = typeof input === 'string' ? input : input instanceof Request ? input.url : String(input)
      const pathname = new URL(requestUrl, window.location.origin).pathname
      if (!pathname.endsWith('/api/rag/chat/stream')) {
        return originalFetch(input, init)
      }

      const requestBody = typeof init?.body === 'string' ? JSON.parse(init.body) : null
      payloads.push(requestBody)
      const plan = plans[Math.min(payloads.length - 1, plans.length - 1)]
      const signal = init?.signal ?? null
      const timers: number[] = []

      const stream = new ReadableStream({
        start(controller) {
          const sendFrame = (eventName: string, data: unknown) => {
            controller.enqueue(encoder.encode(`event: ${eventName}\ndata: ${JSON.stringify(data)}\n\n`))
          }

          let elapsed = 0
          for (const event of plan.events) {
            elapsed += event.delayMs
            const timer = window.setTimeout(() => {
              if (signal?.aborted) return
              if (event.type === 'status') {
                sendFrame('status', event.data)
                return
              }
              if (event.type === 'sources') {
                sendFrame('sources', event.data)
                return
              }
              if (event.type === 'retrieval_debug') {
                sendFrame('retrieval_debug', event.data)
                return
              }
              if (event.type === 'chunk') {
                sendFrame('chunk', { delta: event.delta })
                return
              }
              sendFrame('done', event.data)
              controller.close()
            }, elapsed)
            timers.push(timer)
          }

          if (signal) {
            signal.addEventListener('abort', () => {
              timers.forEach((timer) => window.clearTimeout(timer))
              try {
                controller.close()
              } catch {
                // 已关闭时无需处理
              }
            }, { once: true })
          }
        },
        cancel() {
          timers.forEach((timer) => window.clearTimeout(timer))
        },
      })

      return new Response(stream, {
        status: 200,
        headers: {
          'Content-Type': 'text/event-stream; charset=utf-8',
          'Cache-Control': 'no-cache',
        },
      })
    }
  }, responses)
}

test.beforeEach(async ({ page }) => {
  await page.addInitScript(([sessionKey]) => {
    window.localStorage.setItem(sessionKey, JSON.stringify({
      id: 'user-1',
      username: 'tester',
      nickname: 'Tester',
      role: 'USER',
      token: 'test-token',
    }))
  }, [SESSION_KEY])
})

test('临时资料首轮发送后进入上下文，第二轮不再重复作为待发送附件', async ({ page }) => {
  await installStreamMock(page, [
    {
      events: [
        { type: 'status', delayMs: 20, data: { stage: 'searching', message: '正在检索临时资料' } },
        { type: 'chunk', delayMs: 40, delta: '第一轮回答：已读取 notes.txt。' },
        { type: 'done', delayMs: 20, data: { questionId: 'q-1', conversationId: 'conv-1', answer: '第一轮回答：已读取 notes.txt。' } },
      ],
    },
    {
      events: [
        { type: 'status', delayMs: 20, data: { stage: 'generating', message: '继续基于会话上下文回答' } },
        { type: 'chunk', delayMs: 40, delta: '第二轮回答：继续基于上一轮临时资料。' },
        { type: 'done', delayMs: 20, data: { questionId: 'q-2', conversationId: 'conv-1', answer: '第二轮回答：继续基于上一轮临时资料。' } },
      ],
    },
  ])
  await mockChatPageApis(page)

  await page.goto('/workspace/chat')

  await page.getByTestId('chat-attachment-input').setInputFiles({
    name: 'notes.txt',
    mimeType: 'text/plain',
    buffer: Buffer.from('这里是临时资料正文，包含系统设计、缓存策略和接口说明。', 'utf8'),
  })

  await expect(page.getByTestId('chat-temporary-attachments')).toBeVisible()
  await expect(page.getByTestId('chat-temporary-attachments')).toContainText('notes.txt')

  await page.getByTestId('chat-input').fill('请先总结这份资料')
  await page.getByTestId('chat-submit-button').click()

  await expect(page.getByText('第一轮回答：已读取 notes.txt。')).toBeVisible()
  await expect(page.getByTestId('chat-temporary-attachments')).toHaveCount(0)

  await page.getByTestId('chat-input').fill('继续展开讲讲')
  await page.getByTestId('chat-submit-button').click()

  await expect(page.getByText('第二轮回答：继续基于上一轮临时资料。')).toBeVisible()

  const payloads = await page.evaluate(() => (window as Window & { __chatStreamPayloads?: unknown[] }).__chatStreamPayloads || [])
  expect(payloads).toHaveLength(2)
  expect((payloads[0] as { temporaryMaterial?: { originalName?: string } }).temporaryMaterial?.originalName).toBe('notes.txt')
  expect((payloads[1] as { temporaryMaterial?: unknown }).temporaryMaterial ?? null).toBeNull()
  expect((payloads[1] as { conversationId?: string }).conversationId).toBe('conv-1')
})

test('暂停输出会中断流式回答，并保留已收到内容', async ({ page }) => {
  await installStreamMock(page, [
    {
      events: [
        { type: 'status', delayMs: 20, data: { stage: 'generating', message: '正在生成回答' } },
        { type: 'chunk', delayMs: 40, delta: '第一段已输出。' },
        { type: 'chunk', delayMs: 600, delta: '第二段不应再出现。' },
        { type: 'done', delayMs: 20, data: { questionId: 'q-stop', conversationId: 'conv-stop', answer: '第一段已输出。第二段不应再出现。' } },
      ],
    },
  ])
  await mockChatPageApis(page)

  await page.goto('/workspace/chat')

  await page.getByTestId('chat-input').fill('请给我一个较长回答')
  await page.getByTestId('chat-submit-button').click()

  await expect(page.getByText('第一段已输出。')).toBeVisible()
  await page.getByTestId('chat-submit-button').click()

  await expect(page.getByText('已暂停输出')).toBeVisible()
  await page.waitForTimeout(900)
  await expect(page.getByText('第二段不应再出现。')).toHaveCount(0)
})

test('资料来源会展示检索解释信息', async ({ page }) => {
  await installStreamMock(page, [
    {
      events: [
        { type: 'status', delayMs: 20, data: { stage: 'searching', message: '正在检索资料依据' } },
        {
          type: 'sources',
          delayMs: 20,
          data: {
            sources: [
              {
                materialId: 'material-1',
                chunkId: 'chunk-1',
                materialTitle: 'SQL 学习手册',
                pageNo: 55,
                excerpt: 'JOIN connects rows from multiple tables.',
                score: 0.91,
              },
            ],
          },
        },
        {
          type: 'retrieval_debug',
          delayMs: 20,
          data: {
            items: [
              {
                materialId: 'material-1',
                chunkId: 'chunk-1',
                materialTitle: 'SQL 学习手册',
                pageNo: 55,
                chunkIndex: 12,
                routes: ['BM25', 'RERANK'],
                rawScore: 0.62,
                rerankScore: 0.93,
                finalScore: 0.91,
                selected: true,
                reason: '命中正文 JOIN 定义',
                selectedReason: '正文片段直接支撑回答',
                penaltyReason: '目录页已降权',
              },
            ],
          },
        },
        { type: 'chunk', delayMs: 40, delta: 'JOIN 用于连接多张表的相关行。' },
        {
          type: 'done',
          delayMs: 20,
          data: {
            questionId: 'q-debug',
            conversationId: 'conv-debug',
            answer: 'JOIN 用于连接多张表的相关行。',
          },
        },
      ],
    },
  ])
  await mockChatPageApis(page)

  await page.goto('/workspace/chat')
  await page.getByTestId('chat-input').fill('JOIN 是什么？')
  await page.getByTestId('chat-submit-button').click()

  await expect(page.getByText('JOIN 用于连接多张表的相关行。')).toBeVisible()
  await expect(page.getByText('SQL 学习手册')).toBeVisible()
  await expect(page.getByText('BM25')).toBeVisible()
  await expect(page.getByText('RERANK')).toBeVisible()
  await expect(page.getByText('最终分 91%')).toBeVisible()
  await expect(page.getByText('入选原因：正文片段直接支撑回答')).toBeVisible()
  await expect(page.getByText('降权说明：目录页已降权')).toBeVisible()
})

test('通过历史记录恢复会话后仍能看到检索解释信息', async ({ page }) => {
  const historyItem = {
    id: 'q-history',
    conversationId: 'conv-history',
    title: 'JOIN 是什么',
    question: 'JOIN 是什么？',
    answer: 'JOIN 用于连接多张表的相关行。',
    createdAt: '2026-06-19 12:00:00',
    favoriteId: null,
    favorite: false,
    pinned: false,
  }
  const historyDetail = {
    ...historyItem,
    messages: [
      { id: 'q-history', role: 'user', text: 'JOIN 是什么？', images: [], temporaryMaterial: null },
      { id: 'q-history', role: 'assistant', text: 'JOIN 用于连接多张表的相关行。', images: [], temporaryMaterial: null },
    ],
    sources: [
      {
        materialId: 'material-1',
        chunkId: 'chunk-1',
        materialTitle: 'SQL 学习手册',
        pageNo: 55,
        excerpt: 'JOIN connects rows from multiple tables.',
        score: 0.91,
      },
    ],
    retrievalDebug: [
      {
        materialId: 'material-1',
        chunkId: 'chunk-1',
        materialTitle: 'SQL 学习手册',
        pageNo: 55,
        chunkIndex: 12,
        routes: ['BM25', 'RERANK'],
        rawScore: 0.62,
        rerankScore: 0.93,
        finalScore: 0.91,
        selected: true,
        reason: '命中正文 JOIN 定义',
        selectedReason: '正文片段直接支撑回答',
        penaltyReason: '目录页已降权',
      },
    ],
  }

  await mockChatPageApis(page, {
    historyItems: [historyItem],
    historyDetailById: {
      'q-history': historyDetail,
    },
  })

  await page.goto('/workspace/chat?historyId=q-history')

  await expect(page.getByText('JOIN 用于连接多张表的相关行。')).toBeVisible()
  await expect(page.getByText('SQL 学习手册')).toBeVisible()
  await expect(page.getByText('BM25')).toBeVisible()
  await expect(page.getByText('RERANK')).toBeVisible()
  await expect(page.getByText('最终分 91%')).toBeVisible()
  await expect(page.getByText('入选原因：正文片段直接支撑回答')).toBeVisible()
})
