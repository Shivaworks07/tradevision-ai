import { test, expect } from '@playwright/test';

/**
 * Smoke tests for the login page's initial render. Stops short of actually submitting the
 * mobile number and verifying an OTP, since that needs a real, reachable OTP delivery path
 * (a way to intercept/read the OTP that was actually sent) to verify end-to-end. Selectors
 * are grounded in the actual login.component.html source.
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
