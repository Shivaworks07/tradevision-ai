import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from '../services/auth.service';

// Review finding ("Frontend still caches non-secret user profile in localStorage; residual XSS
// surface for session continuity" -- external review, nineteenth pass, P1, full context in
// AuthService's own sessionReady field comment): this guard now awaits sessionReady before ever
// reading isLoggedIn -- without this, a page refresh on a protected route could run this check
// BEFORE the new, async session-restoration call resolves, incorrectly redirecting a genuinely
// logged-in user (whose HttpOnly cookie is perfectly valid) to /login every time.
export const authGuard: CanActivateFn = async () => {
  const auth   = inject(AuthService);
  const router = inject(Router);
  await auth.sessionReady;
  if (auth.isLoggedIn) return true;
  router.navigate(['/login']);
  return false;
};
