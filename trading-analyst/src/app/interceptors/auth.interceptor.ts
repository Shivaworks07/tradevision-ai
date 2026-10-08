import { HttpInterceptorFn, HttpErrorResponse } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, throwError } from 'rxjs';
import { environment } from '../../environments/environment';

/**
 * Centralizes auth handling for every request to this app's own API: attaches
 * `withCredentials` so the browser sends the HttpOnly session cookies (needed even on
 * same-origin production, and essential for a cross-origin local dev server on its own
 * port), and navigates to the login page on a 401.
 *
 * AuthService already does proactive token refresh (doRefresh(), scheduled well before
 * the access token's 30-minute expiry), so this interceptor does not duplicate that with
 * a reactive refresh-and-retry-on-401 flow. A 401 reaching here means the proactive refresh
 * didn't prevent it (clock skew, a revoked token, logged out elsewhere) — logging out is
 * the correct response, not silently retrying.
 *
 * The backend's CSRF cookie mechanism (SecurityConfig.csrfCookieFilter) (re-)writes the
 * XSRF-TOKEN cookie on every request that reaches it, for Angular's withXsrfConfiguration
 * interceptor to read. The very first state-changing request in a session can land before
 * that cookie exists yet, producing a bare, bodiless 403; the identical request then
 * succeeds immediately on retry. `isCsrfCookieTimingFailure` distinguishes this case from
 * a genuine business-logic 403: this backend always returns a JSON body via
 * `ApiResponse.error(...)` for its own controller-level 403s, while a filter-level CSRF
 * rejection short-circuits before any controller runs and so never attaches a body. Only
 * the bodiless case is retried, exactly once — a genuine, persistent 403 (on any HTTP
 * method, including non-idempotent ones) fails identically on retry and is surfaced to the
 * caller unchanged, never silently replayed.
 */
function isCsrfCookieTimingFailure(err: HttpErrorResponse): boolean {
  return err.error === null || err.error === undefined || err.error === '';
}

export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const router = inject(Router);
  const isOwnApi = req.url.startsWith(environment.apiUrl);

  let authedReq = req;
  if (isOwnApi) {
    authedReq = authedReq.clone({ withCredentials: true });
  }

  return next(authedReq).pipe(
    catchError(err => {
      if (isOwnApi && err instanceof HttpErrorResponse && err.status === 403 && isCsrfCookieTimingFailure(err)) {
        // Retry once: a genuine, persistent 403 fails identically on retry and is still
        // surfaced normally; only a one-off, first-request CSRF-cookie-timing 403 is
        // absorbed here. A bodied (genuine business-logic) 403 never reaches this branch
        // at all, regardless of HTTP method.
        return next(authedReq).pipe(
          catchError(retryErr => {
            if (isOwnApi && retryErr?.status === 401) {
              router.navigate(['/']);
            }
            return throwError(() => retryErr);
          })
        );
      }
      if (isOwnApi && err?.status === 401) {
        router.navigate(['/']);
      }
      return throwError(() => err);
    })
  );
};
