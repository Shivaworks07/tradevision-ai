import { TestBed } from '@angular/core/testing';
import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter, Router } from '@angular/router';
import { environment } from '../../environments/environment';
import { authInterceptor } from './auth.interceptor';

/**
 * Covers authInterceptor's 403-retry behavior: a bodiless (CSRF-cookie-timing) 403 is
 * retried exactly once, on any method including POST; a bodied (genuine business-logic)
 * 403 is surfaced immediately on a POST and never retried, avoiding a double submission.
 */
describe('authInterceptor', () => {
  let http: HttpClient;
  let httpMock: HttpTestingController;
  let router: Router;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
        provideRouter([])
      ]
    });
    http = TestBed.inject(HttpClient);
    httpMock = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
  });

  afterEach(() => httpMock.verify());

  it('retries a bodiless 403 (CSRF-cookie-timing) exactly once, and returns success if the retry succeeds -- even on a POST', done => {
    http.post(`${environment.apiUrl}/broker/connect`, { apiKey: 'x' }).subscribe({
      next: res => {
        expect(res).toEqual({ success: true });
        done();
      },
      error: () => fail('expected the retry to succeed, not error out')
    });

    const first = httpMock.expectOne(`${environment.apiUrl}/broker/connect`);
    // A genuinely bodiless 403, exactly as Spring Security's own filter-level CSRF-cookie-timing
    // rejection produces (no controller ever ran, so no ApiResponse.error(...) body was attached).
    first.flush(null, { status: 403, statusText: 'Forbidden' });

    const retry = httpMock.expectOne(`${environment.apiUrl}/broker/connect`);
    retry.flush({ success: true });
  });

  it('does NOT retry a genuine business-logic 403 (a real JSON error body) on a POST -- surfaces it immediately instead of risking a double submission', done => {
    spyOn(router, 'navigate');
    http.post(`${environment.apiUrl}/risk-profile/resume`, {}).subscribe({
      next: () => fail('expected this to error, not succeed'),
      error: err => {
        expect(err.status).toBe(403);
        expect(err.error).toEqual({ success: false, message: 'Not authorized to resume this profile' });
        done();
      }
    });

    // This backend's own consistent shape for a genuine controller-level rejection --
    // ApiResponse.error(...) -- carries a real JSON body, unlike the CSRF-timing case above.
    const req = httpMock.expectOne(`${environment.apiUrl}/risk-profile/resume`);
    req.flush({ success: false, message: 'Not authorized to resume this profile' }, { status: 403, statusText: 'Forbidden' });

    // The one, deliberate retry must never have been issued for a bodied 403.
    httpMock.expectNone(`${environment.apiUrl}/risk-profile/resume`);
  });

  it('does NOT retry a genuine business-logic 403 on a GET either -- the fix gates on body presence, not HTTP method', done => {
    http.get(`${environment.apiUrl}/admin/reports`).subscribe({
      next: () => fail('expected this to error, not succeed'),
      error: err => {
        expect(err.status).toBe(403);
        done();
      }
    });

    const req = httpMock.expectOne(`${environment.apiUrl}/admin/reports`);
    req.flush({ success: false, message: 'Admins only' }, { status: 403, statusText: 'Forbidden' });

    httpMock.expectNone(`${environment.apiUrl}/admin/reports`);
  });

  it('a persistent bodiless 403 (retry also fails) is still surfaced to the caller after the one retry, not swallowed', done => {
    http.post(`${environment.apiUrl}/broker/connect`, {}).subscribe({
      next: () => fail('expected this to error after both attempts fail'),
      error: err => {
        expect(err.status).toBe(403);
        done();
      }
    });

    const first = httpMock.expectOne(`${environment.apiUrl}/broker/connect`);
    first.flush(null, { status: 403, statusText: 'Forbidden' });

    const retry = httpMock.expectOne(`${environment.apiUrl}/broker/connect`);
    retry.flush(null, { status: 403, statusText: 'Forbidden' });
  });

  it('a request to a third-party (non-own-API) URL is never retried or redirected on 403', done => {
    http.get('https://example.com/unrelated').subscribe({
      next: () => fail('expected this to error'),
      error: err => {
        expect(err.status).toBe(403);
        done();
      }
    });

    const req = httpMock.expectOne('https://example.com/unrelated');
    req.flush(null, { status: 403, statusText: 'Forbidden' });

    httpMock.expectNone('https://example.com/unrelated');
  });
});
