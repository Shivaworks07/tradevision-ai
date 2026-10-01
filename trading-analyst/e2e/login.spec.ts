import { test, expect } from '@playwright/test';

/**
 * Review finding ("No E2E test suite" -- P2, full context in playwright.config.ts's own
 * header comment): a deliberately minimal starting point, not a full login flow. Stops short of
 * actually submitting the mobile number and verifying an OTP -- that would need a real,
 * reachable OTP delivery path to verify end-to-end, which is a separate, larger piece of test
 * infrastructure (a way to intercept/read the OTP that was actually sent) not attempted here.
 * This test only confirms the login page's initial state renders with the expected, real
 * elements -- selectors grounded in the actual login.component.html source, not guessed.
 *
 * UNVALIDATED: has not been run against a real, deployed instance of this application -- see
 * playwright.config.ts's own header comment for why (no live backend/MongoDB in the sandbox
 * this was written in).
 */
test.describe('Login page', () => {
  test('renders the mobile number field and Send OTP button', async ({ page }) => {
    await page.goto('/');

    // Grounded in login.component.html: `<input type="tel" [(ngModel)]="mobile" ...
    // placeholder="9876543210" maxlength="10">`
    const mobileInput = page.locator('input[type="tel"]');
    await expect(mobileInput).toBeVisible();
    await expect(mobileInput).toHaveAttribute('maxlength', '10');

    // Grounded in the same source: `<button class="btn-primary" (click)="sendOtp()" ...>
    // <span *ngIf="!loading">Send OTP →</span>`
    await expect(page.getByRole('button', { name: /Send OTP/ })).toBeVisible();
  });

  test('typing a mobile number enables submission without a client-side error shown prematurely', async ({ page }) => {
    await page.goto('/');

    const mobileInput = page.locator('input[type="tel"]');
    await mobileInput.fill('9876543210');

    // Grounded in the same source: `<span class="field-error" *ngIf="mobileError">` -- should
    // not be visible after a plausible-looking number is typed, before any submission attempt.
    await expect(page.locator('.field-error')).toHaveCount(0);
  });
});
