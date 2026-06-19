import { expect, test } from '@playwright/test'

const SESSION_KEY = 'learning-assistant.frontend.session'
const MATERIAL_ID = 'material-1'

type MaterialChunk = {
  id: string
  materialId: string
  chunkIndex: number
  chunkText: string
  pageNo: number
  sectionTitle: string
  excerpt: string
}

function ok(data: unknown) {
  return {
    status: 200,
    contentType: 'application/json; charset=utf-8',
    body: JSON.stringify({ code: 0, message: 'ok', data }),
  }
}

function buildChunks(): MaterialChunk[] {
  return Array.from({ length: 60 }, (_, index) => {
    const pageNo = index + 1
    return {
      id: `chunk-${pageNo}`,
      materialId: MATERIAL_ID,
      chunkIndex: pageNo,
      chunkText: `这是第 ${pageNo} 页的正文内容，用于阅读器恢复和来源跳转验收。`,
      pageNo,
      sectionTitle: `第 ${pageNo} 节`,
      excerpt: `第 ${pageNo} 页摘录`,
    }
  })
}

const chunks = buildChunks()

const material = {
  id: MATERIAL_ID,
  title: '学生手册',
  sourceType: 'TXT',
  originalName: 'student-handbook.txt',
  sourceUrl: '',
  fileSize: 4096,
  parseStatus: 'SUCCESS',
  parseProgressPercent: 100,
  parseStage: null,
  parseMessage: null,
  uploadStatus: 'UPLOADED',
  textStatus: 'READY',
  indexStatus: 'READY',
  ocrStatus: 'DISABLED',
  processingProgressPercent: 100,
  processingStage: 'READY',
  processingMessage: '资料已可用',
  indexedChunkCount: chunks.length,
  textPageCount: chunks.length,
  summaryStatus: 'SUCCESS',
  previewStatus: 'NONE',
  previewError: null,
  pageCount: chunks.length,
  chunkCount: chunks.length,
  createdAt: '2026-06-19 10:00:00',
  updatedAt: '2026-06-19 10:00:00',
}

const latestHistory = {
  id: 'history-1',
  conversationId: 'conversation-1',
  title: '最近一次资料问答',
  question: '第五十四页讲了什么？',
  answer: '第五十四页主要讲阅读器恢复和来源定位。',
  createdAt: '2026-06-19 10:05:00',
  favoriteId: null,
  favorite: false,
  pinned: false,
  sources: [
    {
      materialId: MATERIAL_ID,
      chunkId: 'chunk-54',
      materialTitle: '学生手册',
      pageNo: 54,
      excerpt: '这是第 54 页的正文内容，用于阅读器恢复和来源跳转验收。',
      score: 0.92,
    },
  ],
}

async function mockApi(page: import('@playwright/test').Page) {
  await page.route('**/*', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const pathname = url.pathname

    // 只接管真正的后端接口，避免把 /src/api/*.ts 这类前端模块请求误拦截掉。
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
        usedToday: 1,
        remainingToday: 19,
        unlimited: false,
      }))
      return
    }

    if (pathname === '/api/rag/history') {
      await route.fulfill(ok([]))
      return
    }

    if (pathname === '/api/favorites') {
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

    if (pathname === '/api/materials') {
      await route.fulfill(ok([material]))
      return
    }

    if (pathname === `/api/materials/${MATERIAL_ID}/chunks`) {
      await route.fulfill(ok(chunks))
      return
    }

    if (pathname === `/api/materials/${MATERIAL_ID}/pages`) {
      // 这两条用例主要验证阅读位置和来源跳转，不依赖真实 PDF 预览。
      await route.fulfill(ok([]))
      return
    }

    if (pathname === `/api/rag/history/materials/${MATERIAL_ID}/latest`) {
      await route.fulfill(ok(latestHistory))
      return
    }

    if (pathname === `/api/rag/history/materials/${MATERIAL_ID}`) {
      await route.fulfill(ok([latestHistory]))
      return
    }

    if (pathname === '/api/rag/suggest-questions') {
      await route.fulfill(ok([]))
      return
    }

    await route.fulfill({
      status: 404,
      contentType: 'application/json; charset=utf-8',
      body: JSON.stringify({ code: 404, message: `unmocked api: ${pathname}`, data: null }),
    })
  })
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

  await mockApi(page)
})

test('阅读器里的来源卡片可以跳到正确页码', async ({ page }) => {
  await page.goto(`/workspace/reader?materialId=${MATERIAL_ID}&chunkId=chunk-6&pageNo=6`)

  await expect(page.getByTestId('reader-page')).toBeVisible()
  await expect(page.getByTestId('reader-current-page')).toContainText('P6')
  await expect(page.getByTestId('source-card-chunk-54')).toBeVisible()

  await page.getByTestId('source-card-chunk-54').click()

  await expect(page).toHaveURL(new RegExp(`/workspace/reader\\?materialId=${MATERIAL_ID}.*chunkId=chunk-54.*pageNo=54`))
  await expect(page.getByTestId('reader-current-page')).toContainText('P54')
})

test('切到别的模块再回阅读器时恢复上次阅读位置', async ({ page }) => {
  await page.goto(`/workspace/reader?materialId=${MATERIAL_ID}&chunkId=chunk-54&pageNo=54`)

  await expect(page.getByTestId('reader-current-page')).toContainText('P54')

  await page.getByRole('button', { name: /资料管理/ }).click()
  await expect(page).toHaveURL(/\/workspace\/materials/)

  await page.getByRole('button', { name: /边读边问/ }).click()

  await expect(page).toHaveURL(new RegExp(`/workspace/reader\\?materialId=${MATERIAL_ID}.*chunkId=chunk-54.*pageNo=54`))
  await expect(page.getByTestId('reader-current-page')).toContainText('P54')
})
