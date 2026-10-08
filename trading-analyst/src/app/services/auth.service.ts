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
 * Manages the user's session using HttpOnly cookies set by the backend on every
 * token-issuing response — access and refresh tokens are never written to or read from
 * localStorage. `withCredentials` ensures the cookie is sent even for a cross-origin dev
 * server on a different port.
 *
 * `currentUser` is purely in-memory (an Angular signal): there is no persisted profile
 * cache either, since a client-readable cache could be forged or tampered with by any
 * injected script without needing the HttpOnly session cookie itself. On construction,
 * this class asks the backend directly (refreshProfile(), relying solely on the HttpOnly
 * cookie the browser attaches automatically) rather than trusting anything client-readable.
 * The tradeoff: a page reload shows logged-out for the instant before that first network
 * round trip completes, rather than optimistically showing the previous session's UI —
 * a safer default than trusting a client-controlled value.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly API = environment.apiUrl;
  currentUser = signal<UserDto | null>(null);
  /**
   * Since the session check on load is an async network call rather than a synchronous
   * cache read, authGuard's isLoggedIn check could otherwise run before that call resolves
   * and incorrectly treat a genuinely logged-in user as logged out on a page refresh. This
   * promise resolves once the constructor's initial check completes either way (success or
   * failure/no-session), and authGuard awaits it before ever reading isLoggedIn.
   */
  sessionReady: Promise<void>;

  constructor(private http: HttpClient, private router: Router) {
    // Session restoration on load always goes through a real backend call, never a
    // client-side cache. If the HttpOnly cookie is still valid, this populates currentUser
    // and schedules the refresh timer; if not (or the cookie is missing), this resolves to
    // null/no-op, correctly reflecting "not logged in."
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
  // Always calls the backend directly rather than gating on isLoggedIn first, since
  // isLoggedIn is false before any session is established and there is no client-side
  // cache to seed it from. landing.component.ts and app-shell.component.ts already check
  // `this.auth.isLoggedIn` themselves before calling this method where that matters.
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
  // The access token lives only in an HttpOnly cookie, never readable as a string here —
  // no request in this app should build its own Authorization header from it.
  private refreshTimer: any  = null;
  get isLoggedIn(): boolean { return !!this.currentUser(); }

  private saveSession(data: any) {
    // The backend already set the HttpOnly cookies as a side effect of this same response
    // (see AuthController) — nothing to store on the client besides the in-memory user signal.
    if (data.user) {
      this.currentUser.set(data.user);
    }
    clearTimeout(this.refreshTimer);
    // Access token is valid for 30 minutes — refresh well before it expires.
    this.refreshTimer = setTimeout(() => this.doRefresh(), 20 * 60 * 1000);
  }

  logout() {
    clearTimeout(this.refreshTimer);
    // The access-token cookie is attached automatically (withCredentials); the backend
    // clears both cookies as part of handling this call regardless of whether it can
    // identify the caller (see AuthController.logout's own unconditional clearAuthCookies).
    this.http.post(`${this.API}/auth/logout`, {}, { withCredentials: true }).subscribe({ error:()=>{} });
    this.currentUser.set(null);
    this.router.navigate(['/']);
  }

  doRefresh() {
    // The refresh-token cookie is attached automatically; the backend's /auth/refresh
    // endpoint accepts it from there when the request body doesn't carry one (see
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
