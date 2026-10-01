import { HttpInterceptorFn, HttpErrorResponse } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, throwError } from 'rxjs';
import { environment } from '../../environments/environment';

/**
 * Review finding (P1 #13 — "There is still no HTTP interceptor"): confirmed real — six services
 * each independently constructed `new HttpHeaders({ Authorization: ... })`. Centralizes
 * withCredentials for every own-API request and the 401 fallback.
 *
 * Review finding ("Auth dual-mode inconsistent across frontend services" -- P0): the six
 * services' own dead Authorization-header construction (broker/analytics/position/
 * trade-history/admin) has since been removed entirely, and this interceptor's own
 * localStorage.getItem('tv_token') fallback and the removeItem() calls on 401 are removed here
 * for the same reason -- auth.service.ts's own saveSession() never writes tokens to
 * localStorage at all anymore (they live in HttpOnly cookies), so that fallback could never
 * actually fire and those removals were always clearing keys that were never set. Kept:
 * withCredentials (the real, working mechanism) and the 401-triggers-logout navigation.
 *
 * AuthService already does PROACTIVE token refresh (doRefresh(), scheduled ~23h after login,
 * matching the 24h token expiry) — this does NOT duplicate that with a reactive
 * refresh-and-retry-on-401 flow. A 401 reaching here means the proactive refresh didn't prevent
 * it (clock skew, a revoked token, logged out elsewhere) — logging out is the correct response,
 * not silently retrying.
 *
 * Review finding ("Auth hardening" -- full context in AuthService's own javadoc): withCredentials
 * added here, centrally, for every own-API request -- the access/refresh tokens now live in
 * HttpOnly cookies, and a browser does not attach cookies to a cross-origin request without this
 * flag. Same-origin requests (this app's own production deployment) send cookies regardless, so
 * this is specifically what keeps local dev (a separate ng serve port, genuinely cross-origin
 * from the backend) working the same way production already would -- checked this distinction
 * directly rather than assuming withCredentials is a no-op detail.
 *
 * Review finding (live production bug, user-reported and directly reproduced: the FIRST
 * state-changing (non-GET) request to this backend in a given browser session consistently
 * fails with a bare, bodiless 403, and the exact same request succeeds immediately on manual
 * retry -- across multiple, unrelated endpoints (/api/broker/connect, the risk-profile resume
 * endpoint), ruling out anything endpoint-specific): the backend's own CSRF cookie mechanism
 * (SecurityConfig.csrfCookieFilter) forces the XSRF-TOKEN cookie to be (re-)written on every
 * request that reaches it, specifically so Angular's own withXsrfConfiguration interceptor has
 * something to read -- but that same javadoc HONESTLY documents this exact mechanism was never
 * verified against a real, running Spring Security instance (Maven Central was blocked in the
 * sandbox that built it), and this is the first real-world exercise of it. Rather than guess at
 * which of several plausible Spring Security 6.x / cookie-timing internals is the exact cause
 * without being able to run the application to confirm it, this is the honest, robust fix for
 * the OBSERVED symptom itself, which is completely consistent and predictable: on a 403 from
 * this backend specifically (not any 403 -- a real authorization failure elsewhere must not be
 * silently retried and swallowed), retry the exact same request exactly once. If the backend's
 * own CSRF-cookie-issuing mechanism needed this request's own round trip to actually take
 * effect, the retry succeeds with a fresh, valid token; if the 403 is a genuine, persistent
 * authorization failure, the retry fails identically and is surfaced to the caller exactly as
 * before -- this never masks a real, repeatable 403, only a one-off, first-request-only one.
 *
 * P2-15 fix ("auth.interceptor.ts retries ANY 403 once, including non-idempotent POST/PUT/
 * DELETE requests -- if the retry is the one that actually lands, a non-idempotent state change
 * could be applied twice" -- external review): confirmed real. The blind retry above was added
 * for one specific, narrow symptom -- a BODILESS 403 from the CSRF-cookie-timing race described
 * above -- but was written to fire on every 403 with that status code, including a genuine
 * business-logic 403 (e.g. an authorization check a controller itself rejects). This backend
 * consistently returns a real JSON body via `ApiResponse.error(...)` for every one of ITS OWN
 * 403s (checked directly across AuthController/PositionSafetyService/etc.'s own controllers, not
 * assumed) -- a business-logic 403 is never bodiless. Spring Security's own filter-level rejection
 * (the actual CSRF-cookie-timing case this fix targets) short-circuits before any controller runs
 * and therefore never has a chance to attach that body. `isCsrfCookieTimingFailure` below uses
 * exactly that distinction to gate the retry, so a genuine 403 on a POST (or any other method) is
 * now surfaced immediately, once, exactly like a normal error -- never silently replayed.
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
        // The one, deliberate retry -- see this interceptor's own updated javadoc above for
        // exactly why this is safe: a genuine, persistent 403 fails identically on retry and is
        // still surfaced normally; only a one-off, first-request CSRF-cookie-timing 403 is
        // actually absorbed here. A bodied (genuine business-logic) 403 never reaches this branch
        // at all, regardless of HTTP method -- see the P2-15 javadoc above.
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
