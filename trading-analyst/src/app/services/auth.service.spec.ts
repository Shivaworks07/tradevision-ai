import { environment } from '../../environments/environment';
import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { AuthService } from './auth.service';
import { RouterTestingModule } from '@angular/router/testing';
import { provideHttpClient, withInterceptorsFromDi } from '@angular/common/http';

/**
 * Review finding ("Frontend still caches non-secret user profile in localStorage; residual XSS
 * surface for session continuity" -- external review, nineteenth pass, P1): rewritten for the
 * actual new behavior -- see AuthService's own updated header javadoc for the full design.
 * currentUser is now PURELY in-memory; there is no tv_user localStorage entry at all anymore.
 * The constructor itself now calls refreshProfile() (a real GET to /user/profile) to establish
 * session state on construction, so every test's own beforeEach must flush that request before
 * doing anything else -- flushWithNoSession() below does this with a "not logged in" response,
 * the correct default for a fresh TestBed with no prior session.
 */
describe('AuthService', () => {
  let service: AuthService;
  let http: HttpTestingController;

  function flushWithNoSession() {
    const req = http.expectOne(`${environment.apiUrl}/user/profile`);
    req.flush({ success: false });
  }

  beforeEach(() => {
    TestBed.configureTestingModule({
    imports: [RouterTestingModule],
    providers: [AuthService, provideHttpClient(withInterceptorsFromDi()), provideHttpClientTesting()]
});
    service = TestBed.inject(AuthService);
    http    = TestBed.inject(HttpTestingController);
    flushWithNoSession();
  });

  afterEach(() => { http.verify(); });

  it('should create', () => expect(service).toBeTruthy());

  it('isLoggedIn returns false when there is no session at all -- confirmed by the constructor\'s own refreshProfile() call above, not assumed from a stale cache', () => {
    expect(service.isLoggedIn).toBeFalse();
  });

  it('should call initiate login endpoint', () => {
    service.initiateLogin('9999999999').subscribe();
    const req = http.expectOne(`${environment.apiUrl}/auth/login/initiate`);
    expect(req.request.method).toBe('POST');
    // Review finding ("Frontend test coverage" -- P2): this test was checking a stale
    // expectation -- AuthService.initiateLogin actually sends { email: ... } (confirmed by
    // reading the real service code, not assumed), and the backend's own AuthController accepts
    // EITHER "email" or "mobile" as the request key (req.getOrDefault("email",
    // req.get("mobile"))), so this was never a production-breaking mismatch, just a test that
    // had drifted from the code it was meant to verify.
    expect(req.request.body).toEqual({ email: '9999999999' });
    req.flush({ success: true });
  });

  it('login verify: sets currentUser from the response but never writes anything to localStorage -- the backend already set the HttpOnly cookies as a side effect of this same response', () => {
    service.verifyLogin('9999999999', '123456').subscribe();
    const req = http.expectOne(`${environment.apiUrl}/auth/login/verify`);
    expect(req.request.withCredentials).toBeTrue();
    req.flush({ success: true, data: { accessToken: 'abc123', refreshToken: 'ref456', user: { id:'u1', mobile:'9999999999', firstName:'Test', lastName:'', favoriteStocks:[], favoriteCryptos:[], favoriteForex:[] } } });
    expect(localStorage.getItem('tv_user')).toBeNull();
    expect(localStorage.getItem('tv_token')).toBeNull();
    expect(localStorage.getItem('tv_refresh_token')).toBeNull();
    expect(service.isLoggedIn).toBeTrue();
    expect(service.currentUser()?.id).toBe('u1');
  });

  it('should clear currentUser on logout, and send the logout request with credentials so the cookie is included', () => {
    service.verifyLogin('9999999999', '123456').subscribe();
    http.expectOne(`${environment.apiUrl}/auth/login/verify`)
      .flush({ success: true, data: { user: { id:'u1', mobile:'9999999999', firstName:'Test', lastName:'', favoriteStocks:[], favoriteCryptos:[], favoriteForex:[] } } });
    expect(service.isLoggedIn).toBeTrue();

    service.logout();
    const req = http.expectOne(r => r.url.includes('logout'));
    expect(req.request.withCredentials).toBeTrue();
    req.flush({ success:true });
    expect(localStorage.getItem('tv_user')).toBeNull();
    expect(service.isLoggedIn).toBeFalse();
  });

  it('should call resend OTP endpoint', () => {
    service.resendOtp('9999999999', 'LOGIN').subscribe();
    const req = http.expectOne(r => r.url.includes('otp/resend'));
    expect(req.request.method).toBe('POST');
    req.flush({ success:true });
  });

  // Review finding (P1/🟠 #12 — "Frontend has only 3 spec files" — "At minimum I'd cover:
  // Authentication: login, refresh, logout, 401"): the review's own named gaps.
  it('doRefresh: sends an empty body with credentials -- the refresh-token cookie carries the actual token now, not a body field read from localStorage', () => {
    service.doRefresh();
    const req = http.expectOne(r => r.url.includes('/auth/refresh'));
    expect(req.request.body).toEqual({});
    expect(req.request.withCredentials).toBeTrue();
    req.flush({ success: true, data: { accessToken: 'new-access', refreshToken: 'new-refresh' } });
    // Nothing to assert in localStorage -- the backend already rotated the HttpOnly cookies as
    // a side effect of this same response; this call has nothing further to store.
  });

  it('doRefresh: a failed refresh (e.g. the refresh-token cookie itself is expired or already used) logs the user out', () => {
    service.verifyLogin('9999999999', '123456').subscribe();
    http.expectOne(`${environment.apiUrl}/auth/login/verify`)
      .flush({ success: true, data: { user: { id:'u1', mobile:'9999999999', firstName:'Test', lastName:'', favoriteStocks:[], favoriteCryptos:[], favoriteForex:[] } } });
    expect(service.isLoggedIn).toBeTrue();

    service.doRefresh();
    const refreshReq = http.expectOne(r => r.url.includes('/auth/refresh'));
    refreshReq.error(new ProgressEvent('error'), { status: 401, statusText: 'Unauthorized' });
    // logout() itself fires a best-effort POST to /auth/logout — flush it so http.verify() in
    // afterEach doesn't fail on an unhandled request.
    http.match(r => r.url.includes('/auth/logout')).forEach(r => r.flush({ success: true }));
    expect(localStorage.getItem('tv_user')).toBeNull();
    expect(service.isLoggedIn).toBeFalse();
  });
});
