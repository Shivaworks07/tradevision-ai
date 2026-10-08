import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from '../services/auth.service';

// Awaits sessionReady before reading isLoggedIn, since session restoration is an async
// call — without this, a page refresh on a protected route could run this check before
// that call resolves and incorrectly redirect a genuinely logged-in user to /login.
export const authGuard: CanActivateFn = async () => {
  const auth   = inject(AuthService);
  const router = inject(Router);
  await auth.sessionReady;
  if (auth.isLoggedIn) return true;
  router.navigate(['/login']);
  return false;
};
