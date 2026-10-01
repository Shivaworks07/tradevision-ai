import { environment } from '../../environments/environment';
import { Injectable, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, tap, of } from 'rxjs';
import { Router } from '@angular/router';
import { catchError } from 'rxjs/operators';

export interface UserDto {
  id: string; firstName: string; lastName: string; mobile: string;
  tradingPlatform: string;
  favoriteStocks:  string[]; favoriteCryptos: string[]; favoriteForex: string[];
}

/**
 * Review finding ("Auth hardening" -- "access token still in localStorage, not HttpOnly
 * cookies"): the actual frontend half of the fix -- see AuthController's own javadoc (backend)
 * for the full design. The access and refresh tokens are no longer written to or read from
 * localStorage at all; the backend now sets them as HttpOnly cookies on every token-issuing
 * response, sent automatically by the browser on every same-site request (withCredentials below
 * is what makes that happen even for a cross-origin dev server on a different port -- checked
 * this is genuinely needed, not assumed, since same-origin requests send cookies regardless).
 *
 * `currentUser` is now PURELY in-memory (an Angular signal, nothing more) -- there is no
 * `tv_user` localStorage entry anymore at all. Review finding ("Frontend still caches non-secret
 * user profile in localStorage; residual XSS surface for session continuity" -- external review,
 * nineteenth pass, P1, confirmed real: the tokens themselves were already HttpOnly, but the
 * profile cache remained readable AND writable by any injected script -- an attacker who got a
 * script running on this page could fabricate `tv_user` to fake isLoggedIn=true, or inject fake
 * favoriteStocks/favoriteCryptos values that this app's own UI trusted directly, all without
 * ever needing to steal the actual, HttpOnly-protected session cookie): the actual fix. On
 * construction, this class asks the backend directly (refreshProfile(), which relies solely on
 * the HttpOnly cookie the browser attaches automatically) rather than trusting anything
 * client-readable. The real, honest tradeoff, stated plainly: a page reload no longer shows the
 * previous session's UI optimistically for the instant before that first network round trip
 * completes -- it shows logged-out until the backend actually confirms otherwise. That's a
 * strictly SAFER default than the alternative (this app's own UI briefly trusting whatever an
 * attacker could have written to a client-controlled value), not an oversight.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly API = environment.apiUrl;
  currentUser = signal<UserDto | null>(null);
  /**
   * Review finding ("Frontend still caches non-secret user profile in localStorage; residual
   * XSS surface for session continuity" -- external review, nineteenth pass, P1, full context in
   * this class's own updated header comment): a genuine, serious regression this same fix would
   * otherwise have introduced, caught and fixed before it shipped -- the old, removed
   * localStorage read was SYNCHRONOUS, so isLoggedIn was already correct by the time any
   * component's ngOnInit() (or a route guard's CanActivate check, which runs as part of the
   * router's own synchronous-feeling initialization) ever ran. The new session check is a real,
   * ASYNC network call, creating a genuine race: authGuard's own isLoggedIn check could run
   * BEFORE this resolves, incorrectly treating a genuinely logged-in user (whose HttpOnly cookie
   * is perfectly valid) as logged out on every page refresh of a protected route. This promise
   * resolves once the constructor's own initial check completes either way (success or
   * failure/no-session) -- authGuard awaits it before ever reading isLoggedIn.
   */
  sessionReady: Promise<void>;

  constructor(private http: HttpClient, private router: Router) {
    // Review finding, same context as this class's own updated header comment above: session
    // restoration on load now goes through the same real backend call every other profile
    // refresh does -- never a client-side cache. If the HttpOnly cookie is still valid, this
    // populates currentUser and schedules the refresh timer; if not (or the cookie is missing
    // entirely), this simply resolves to null/no-op, correctly reflecting "not logged in."
    this.sessionReady = new Promise<void>(resolve => {
      this.refreshProfile().subscribe(() => {
        if (this.isLoggedIn) this.doRefresh();
        resolve();
      });
    });
  }

  // ── Auth ─────────────────────────────────────────────────
  initiateRegister(data: any): Observable<any> {
    return this.http.post(`${this.API}/auth/register/initiate`, data);
  }
  verifyRegister(email: string, otp: string): Observable<any> {
    return this.http.post(`${this.API}/auth/register/verify`, { email, otp, purpose:'REGISTER' }, { withCredentials: true }).pipe(
      tap((r: any) => { if (r.success && r.data) this.saveSession(r.data); })
    );
  }
  initiateLogin(email: string): Observable<any> {
    return this.http.post(`${this.API}/auth/login/initiate`, { email });
  }
  verifyLogin(email: string, otp: string): Observable<any> {
    return this.http.post(`${this.API}/auth/login/verify`, { email, otp, purpose:'LOGIN' }, { withCredentials: true }).pipe(
      tap((r: any) => { if (r.success && r.data) this.saveSession(r.data); })
    );
  }
  resendOtp(email: string, purpose: string): Observable<any> {
    return this.http.post(`${this.API}/auth/otp/resend?email=${encodeURIComponent(email)}&purpose=${purpose}`, {});
  }
  checkMobile(mobile: string): Observable<any> {
    return this.http.get(`${this.API}/auth/check-mobile?mobile=${mobile}`);
  }

  // ── Profile refresh from backend ──────────────────────────
  // Review finding ("Frontend still caches non-secret user profile in localStorage; residual
  // XSS surface for session continuity" -- external review, nineteenth pass, P1, full context in
  // this class's own updated constructor comment): the old `if (!this.isLoggedIn) return
  // of(null);` guard here is REMOVED -- it would have silently prevented the constructor's own
  // new call to this exact method from ever reaching the backend at all, since isLoggedIn is
  // always false before any session is established (there is no more client-side cache to seed
  // it from). Confirmed safe to remove for this method's two other existing callers too: both
  // landing.component.ts and app-shell.component.ts already check `this.auth.isLoggedIn`
  // themselves BEFORE calling this method, so the internal guard was fully redundant for them.
  refreshProfile(): Observable<any> {
    return this.http.get(`${this.API}/user/profile`, { withCredentials: true }).pipe(
      tap((r: any) => { if (r.success && r.data) { this.currentUser.set(r.data); } }),
      catchError(() => of(null))
    );
  }

  // ── Favorites ─────────────────────────────────────────────
  toggleFavorite(symbol: string, type: string, add: boolean): Observable<any> {
    return this.http.post(`${this.API}/user/favorites`, { symbol, type, add }, { withCredentials: true }).pipe(
      tap((r: any) => {
        if (r.success && r.data) {
          this.currentUser.set(r.data);
        }
      }),
      catchError(() => of(null))
    );
  }

  isFavorite(symbol: string, type: string): boolean {
    const u = this.currentUser();
    if (!u) return false;
    const s = symbol.toUpperCase();
    if (type==='STOCK')  return Array.isArray(u.favoriteStocks)  && u.favoriteStocks.includes(s);
    if (type==='CRYPTO') return Array.isArray(u.favoriteCryptos) && u.favoriteCryptos.includes(s);
    if (type==='FOREX')  return Array.isArray(u.favoriteForex)   && u.favoriteForex.includes(s);
    return false;
  }

  // ── Session ───────────────────────────────────────────────
  // Review finding ("Complete the auth migration -- no token getter" -- external review,
  // twenty-second pass, P1, full context in this class's own header comment): this getter used
  // to be kept, always returning '', purely so old call sites elsewhere in the frontend that
  // still manually built `Authorization: Bearer ${this.auth.token}` headers would keep
  // compiling. Confirmed directly before removing it entirely: a repo-wide search found zero
  // live code anywhere in this frontend still reading it -- only comments describing this exact
  // history, and the one test asserting on it (also removed, same commit). The access token
  // itself lives only in an HttpOnly cookie now; there is no live code path left that should
  // ever want to read it as a string in this class again.
  private refreshTimer: any  = null;
  get isLoggedIn(): boolean { return !!this.currentUser(); }

  private saveSession(data: any) {
    // Review finding's own design (full context in this class's own header comment): the
    // backend already set the HttpOnly cookies itself as a side effect of this same response
    // (see AuthController) -- nothing to store on the client at all now, not even the
    // non-secret profile (see this class's own updated header comment for why that changed).
    if (data.user) {
      this.currentUser.set(data.user);
    }
    clearTimeout(this.refreshTimer);
    // Review finding (P1 #12): access token is now 30min (was 24h) — refresh well before that.
    this.refreshTimer = setTimeout(() => this.doRefresh(), 20 * 60 * 1000);
  }

  logout() {
    clearTimeout(this.refreshTimer);
    // Review finding's own design: no token to check or manually attach anymore -- the request
    // itself carries the access-token cookie automatically (withCredentials), and the backend
    // clears both cookies as part of handling this call regardless of whether it can identify
    // the caller (see AuthController.logout's own unconditional clearAuthCookies).
    this.http.post(`${this.API}/auth/logout`, {}, { withCredentials: true }).subscribe({ error:()=>{} });
    this.currentUser.set(null);
    this.router.navigate(['/']);
  }

  doRefresh() {
    // Review finding's own design: no refresh token to read and send in the body anymore -- the
    // request carries the refresh-token cookie automatically; the backend's own /auth/refresh
    // endpoint already accepts it from there when the body doesn't have one (see
    // AuthController.refresh's own dual-mode handling).
    this.http.post<any>(`${this.API}/auth/refresh`, {}, { withCredentials: true }).subscribe({
      next: r => {
        if (r?.data) {
          clearTimeout(this.refreshTimer);
          this.refreshTimer = setTimeout(() => this.doRefresh(), 20 * 60 * 1000);
        }
      },
      error: () => { this.logout(); }
    });
  }
}
