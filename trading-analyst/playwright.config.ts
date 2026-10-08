/**
 * Playwright configuration for this project's end-to-end tests. E2E tests here are only
 * meaningful against a real, running full stack — the backend needs a live MongoDB instance
 * and a running Spring Boot process. This is a starting scaffold to build on, not yet a
 * comprehensive suite.
 */
import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  forbidOnly: !!process.env['CI'],
  retries: process.env['CI'] ? 2 : 0,
  workers: process.env['CI'] ? 1 : undefined,
  reporter: 'html',
  use: {
    // Assumes `ng serve` (dev) or a deployed instance is already running at this URL. This
    // config does not start the Angular dev server itself via webServer, since the backend
    // it needs to talk to must also be running separately and would need its own orchestration.
    baseURL: process.env['E2E_BASE_URL'] || 'http://localhost:4200',
    trace: 'on-first-retry',
  },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
  ],
});
