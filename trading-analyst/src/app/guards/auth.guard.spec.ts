import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { RouterTestingModule } from '@angular/router/testing';
import { Router } from '@angular/router';
import { environment } from '../../environments/environment';
import { AuthService } from '../services/auth.service';
import { authGuard } from './auth.guard';
import { provideHttpClient, withInterceptorsFromDi } from '@angular/common/http';

/**
 * Review finding ("Frontend still caches non-secret user profile in localStorage; residual XSS
 * surface for session continuity" -- external review, nineteenth pass, P1, full context in
 * AuthService's own sessionReady field comment): the actual test proving this guard's own real
 * fix -- awaiting sessionReady before ever reading isLoggedIn, closing a genuine race this same
 * pass would otherwise have introduced (the removed, synchronous localStorage read used to make
 * isLoggedIn already correct by the time this guard ran at all; the new session check is a real,
 * async network call).
 */
describe('authGuard', () => {
  let http: HttpTestingController;
  let router: Router;

  function flushProfile(loggedIn: boolean) {
    const req = http.expectOne(`${environment.apiUrl}/user/profile`);
    req.flush(loggedIn
      ? { success: true, data: { id: 'u1', mobile: '9999999999', firstName: 'Test', lastName: '', favoriteStocks: [], favoriteCryptos: [], favoriteForex: [] } }
      : { success: false });
  }

  beforeEach(() => {
    TestBed.configureTestingModule({
    imports: [RouterTestingModule],
    providers: [AuthService, provideHttpClient(withInterceptorsFromDi()), provideHttpClientTesting()]
});
    http = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
  });

  afterEach(() => http.verify());

  it('waits for sessionReady before deciding -- a genuinely logged-in user (whose session check is still in flight) is not redirected away prematurely', async () => {
    spyOn(router, 'navigate');
    const resultPromise = TestBed.runInInjectionContext(() => authGuard({} as any, {} as any));
    // The session check triggered by AuthService's own constructor is still pending at this
    // point -- flush it now, confirming a valid session, before the guard's own promise settles.
    flushProfile(true);

    // Pre-existing gap in this spec (unrelated to any P2 fix): AuthService's own constructor
    // calls doRefresh() immediately once a valid session is confirmed (see AuthService's own
    // doRefresh commentary -- it eagerly rotates the token rather than waiting for the 20-minute
    // timer on session restore), firing a real POST /api/auth/refresh this test previously never
    // flushed. That left an unmatched open request for HttpTestingController.verify() to trip
    // over in afterEach -- a genuine test bug, not a product bug, since the guard itself never
    // reads or waits on this call. Flushed here so the harness's own "no open requests" check
    // reflects AuthService's actual, intended behavior instead of failing on it.
    const refreshReq = http.expectOne(`${environment.apiUrl}/auth/refresh`);
    refreshReq.flush({ success: true, data: { accessToken: 'irrelevant-to-this-guard' } });

    const result = await resultPromise;

    expect(result).toBeTrue();
    expect(router.navigate).not.toHaveBeenCalled();
  });

  it('redirects to /login once sessionReady confirms there is genuinely no session', async () => {
    spyOn(router, 'navigate');
    const resultPromise = TestBed.runInInjectionContext(() => authGuard({} as any, {} as any));
    flushProfile(false);

    const result = await resultPromise;

    expect(result).toBeFalse();
    expect(router.navigate).toHaveBeenCalledWith(['/login']);
  });
});
