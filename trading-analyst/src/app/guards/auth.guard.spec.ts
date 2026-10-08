import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { RouterTestingModule } from '@angular/router/testing';
import { Router } from '@angular/router';
import { environment } from '../../environments/environment';
import { AuthService } from '../services/auth.service';
import { authGuard } from './auth.guard';
import { provideHttpClient, withInterceptorsFromDi } from '@angular/common/http';

/**
 * Covers authGuard's handling of the async session check: it awaits sessionReady before
 * ever reading isLoggedIn, since session restoration is a real network call rather than
 * a synchronous read.
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

    // AuthService's constructor calls doRefresh() immediately once a valid session is
    // confirmed, firing a real POST /api/auth/refresh that must be flushed here, or
    // HttpTestingController.verify() trips over an unmatched open request in afterEach.
    // The guard itself never reads or waits on this call.
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
