import { defineConfig } from '@playwright/test';

const listing = process.argv.includes('--list');
if (!listing && (process.env['GITHUB_ACTIONS'] !== 'true' || process.env['CI'] !== 'true'
    || !/^credpay-ci-\d+-\d+$/.test(process.env['COMPOSE_PROJECT_NAME'] ?? ''))) {
  throw new Error('A jornada automatizada exige o projeto Compose descartável do GitHub CI.');
}

export default defineConfig({
  testDir: './e2e',
  forbidOnly: true,
  retries: 0,
  workers: 1,
  timeout: 45000,
  globalTimeout: 180000,
  expect: { timeout: 15000 },
  reporter: listing ? [['list']] : [['./e2e/safe-reporter.ts']],
  outputDir: '../.local/frontend/browser-results',
  use: { baseURL: 'http://127.0.0.1:4300', browserName: 'chromium', trace: 'off', video: 'off', screenshot: 'off' },
  projects: [
    {name: 'desktop', use: {viewport: {width: 1280, height: 900}}},
    {name: 'mobile', use: {viewport: {width: 390, height: 844}, isMobile: true, hasTouch: true}}
  ],
  webServer: {
    command: 'node node_modules/@angular/cli/bin/ng.js serve --host 127.0.0.1 --port 4300',
    url: 'http://127.0.0.1:4300',
    reuseExistingServer: false,
    timeout: 60000,
    stdout: 'ignore', stderr: 'ignore'
  }
});
