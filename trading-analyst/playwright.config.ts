/**
 * Review finding ("No E2E test suite" -- P2): confirmed real -- this project had NO end-to-end
 * testing framework configured at all before this file (neither Playwright nor Cypress was a
 * dependency). This is a deliberately minimal starting scaffold, not a comprehensive suite.
 *
 * HONEST LIMITATION, stated plainly and directly: E2E tests are only meaningful against a real,
 * running full stack -- this backend needs a live MongoDB instance and a compiled, running
 * Spring Boot process, neither of which exist in the sandbox this was written in (no MongoDB,
 * and no javac/mvn to even compile the backend -- the same limitation disclosed throughout this
 * session's backend work). This config and its one accompanying smoke test
 * (e2e/login.spec.ts) have NOT been run against a real deployment. The selectors used were
 * grounded in the actual login.component.html source (not guessed), but whether the full page
 * renders and behaves as expected against a real running instance is genuinely unverified.
 * Treat this as a starting scaffold to build on and run for real, not a working, trusted E2E
 * suite yet.
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
    // Assumes `ng serve` (dev) or a deployed instance is already running at this URL -- this
    // config deliberately does NOT try to start the Angular dev server itself via webServer,
    // since the real backend it needs to talk to also has to be running separately and this
    // config can't orchestrate that.
    baseURL: process.env['E2E_BASE_URL'] || 'http://localhost:4200',
    trace: 'on-first-retry',
  },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
  ],
});
