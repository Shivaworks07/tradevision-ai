import { environment } from '../../environments/environment';
import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { AuthService } from './auth.service';
import { RouterTestingModule } from '@angular/router/testing';
import { provideHttpClient, withInterceptorsFromDi } from '@angular/common/http';

/**
 * Covers AuthService's session handling. currentUser is purely in-memory, with no
 * persisted profile cache. The constructor calls refreshProfile() (a real GET to
 * /user/profile) to establish session state on construction, so every test's beforeEach
 * must flush that request first — flushWithNoSession() does this with a "not logged in"
 * response, the correct default for a fresh TestBed with no prior session.
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

  it('isLoggedIn returns false when there is no session', () => {
    expect(service.isLoggedIn).toBeFalse();
  });

  it('should call initiate login endpoint', () => {
    service.initiateLogin('9999999999').subscribe();
    const req = http.expectOne(`${environment.apiUrl}/auth/login/initiate`);
    expect(req.request.method).toBe('POST');
    // AuthService.initiateLogin sends { email: ... }; the backend's AuthController accepts
    // either "email" or "mobile" as the request key.
    expect(req.request.body).toEqual({ email: '9999999999' });
    req.flush({ success: true });
  });

  it('login verify: sets currentUser from the response but never writes anything to localStorage, since the backend sets the HttpOnly cookies as a side effect of this same response', () => {
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

  it('doRefresh: sends an empty body with credentials, since the refresh-token cookie carries the token rather than a body field', () => {
    service.doRefresh();
    const req = http.expectOne(r => r.url.includes('/auth/refresh'));
    expect(req.request.body).toEqual({});
    expect(req.request.withCredentials).toBeTrue();
    req.flush({ success: true, data: { accessToken: 'new-access', refreshToken: 'new-refresh' } });
    // Nothing to assert in localStorage -- the backend rotates the HttpOnly cookies as a
    // side effect of this same response; this call has nothing further to store.
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
