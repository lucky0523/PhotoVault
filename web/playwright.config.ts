import { defineConfig, devices } from '@playwright/test'

export default defineConfig({
  testDir: './tests/theme',
  testMatch: 'theme.spec.ts',
  fullyParallel: false,
  workers: 1,
  timeout: 30_000,
  expect: { timeout: 5_000 },
  use: {
    baseURL: 'http://127.0.0.1:4173',
    trace: 'retain-on-failure',
    ...devices['Desktop Chrome'],
  },
  webServer: {
    command: 'npm run dev -- --host 127.0.0.1 --port 4173 --strictPort',
    url: 'http://127.0.0.1:4173/tests/theme/theme-fixture.html',
    reuseExistingServer: false,
    timeout: 120_000,
  },
})
