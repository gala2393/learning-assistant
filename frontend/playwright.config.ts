import { defineConfig, devices } from '@playwright/test'

/**
 * Playwright 只覆盖当前最容易回归的阅读器关键路径：
 * 1. 来源卡片跳转
 * 2. 切模块后的阅读上下文恢复
 *
 * 这里直接拉起本地 Vite 开发服务，避免额外引入一套测试专用打包流程。
 */
export default defineConfig({
  testDir: './e2e',
  timeout: 30_000,
  expect: {
    timeout: 10_000,
  },
  fullyParallel: false,
  retries: 0,
  reporter: 'list',
  use: {
    baseURL: 'http://127.0.0.1:4174',
    trace: 'on-first-retry',
    video: 'retain-on-failure',
  },
  projects: [
    {
      name: 'chromium',
      use: {
        ...devices['Desktop Chrome'],
      },
    },
  ],
  webServer: {
    command: 'npm.cmd run dev -- --host 127.0.0.1 --port 4174',
    url: 'http://127.0.0.1:4174',
    reuseExistingServer: true,
    timeout: 120_000,
  },
})
