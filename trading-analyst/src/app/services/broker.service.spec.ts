import { environment } from '../../environments/environment';
import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { BrokerService } from './broker.service';
import { provideHttpClient, withInterceptorsFromDi } from '@angular/common/http';

/**
 * Review finding (P1/🟠 #12 — "Frontend has only 3 spec files"): the review's own named gaps —
 * "Broker: TESTNET connection, LIVE connection, confirmation" and "Risk: daily loss, max
 * position, max concurrent, kill switch, LIVE authorization". Verifies the service layer sends
 * the correct request shape for each — the backend's own test suite (BrokerCredentialServiceTest,
 * RiskEngineServiceTest, etc.) covers whether the backend correctly VALIDATES/rejects these; this
 * covers that the frontend correctly ASKS.
 */
describe('BrokerService', () => {
  let service: BrokerService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
    imports: [],
    providers: [BrokerService, provideHttpClient(withInterceptorsFromDi()), provideHttpClientTesting()]
});
    service = TestBed.inject(BrokerService);
    http    = TestBed.inject(HttpTestingController);
    localStorage.setItem('tv_token', 'test-token');
  });

  afterEach(() => { http.verify(); localStorage.clear(); });

  it('should create', () => expect(service).toBeTruthy());

  // ── TESTNET connection ────────────────────────────────────
  it('connect: posts mode=TESTNET explicitly, matching the backend P0 #1 fix (mode is never implicit)', () => {
    service.connect('BINANCE', 'test-key', 'test-secret').subscribe();
    const req = http.expectOne(`${environment.apiUrl}/broker/connect`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ broker: 'BINANCE', apiKey: 'test-key', apiSecret: 'test-secret', mode: 'TESTNET' });
    req.flush({ success: true });
  });

  // P3-10 fix ("PAPER mode requires 'live authorization' and isn't selectable in UI" -- external
  // review, confirmed real by direct inspection: this method used to hardcode mode: 'TESTNET'
  // with no parameter for it at all, even though the backend's own /connect endpoint already
  // accepted PAPER). The actual regression test: mode is now a real, passable parameter.
  it('connect: posts mode=PAPER when explicitly requested -- the actual P3-10 fix', () => {
    service.connect('BINANCE', 'test-key', 'test-secret', 'PAPER').subscribe();
    const req = http.expectOne(`${environment.apiUrl}/broker/connect`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ broker: 'BINANCE', apiKey: 'test-key', apiSecret: 'test-secret', mode: 'PAPER' });
    req.flush({ success: true });
  });

  // ── LIVE connection (two-step) ─────────────────────────────
  it('requestLiveConnect: posts mode=LIVE to the dedicated live-connect endpoint, never the single-step /connect', () => {
    service.requestLiveConnect('BINANCE', 'live-key', 'live-secret').subscribe();
    const req = http.expectOne(`${environment.apiUrl}/broker/connect/live/request`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ broker: 'BINANCE', apiKey: 'live-key', apiSecret: 'live-secret', mode: 'LIVE' });
    req.flush({ success: true, data: { confirmToken: 'tok-123' } });
  });

  it('confirmLiveConnect: posts the exact confirmation token to the confirm endpoint', () => {
    service.confirmLiveConnect('tok-123').subscribe();
    const req = http.expectOne(`${environment.apiUrl}/broker/connect/live/confirm`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ confirmToken: 'tok-123' });
    req.flush({ success: true });
  });

  // ── Risk profile / auto-trade ───────────────────────────────
  it('saveRiskProfile: sends every configured risk cap (daily loss, max position, max concurrent) in one request', () => {
    const riskReq = {
      credentialId: 'cred1', autoTradeEnabled: true, enabledSymbols: ['BTCUSDT'],
      minConfidence: 75, maxPositionQuoteAmount: 500, maxConcurrentTrades: 2,
      dailyLossLimitQuote: 100, riskPerTradePercent: 1
    };
    service.saveRiskProfile(riskReq).subscribe();
    const req = http.expectOne(`${environment.apiUrl}/broker/risk-profile`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(riskReq);
    req.flush({ success: true });
  });

  /**
   * P1-20 fix ("Frontend risk-profile save silently wipes fields" -- full context in this
   * service's own updated saveRiskProfile javadoc): confirms the request shape genuinely accepts
   * and forwards maxSymbolExposureQuote/maxPriceDeviationPercent/circuitBreakerThreshold/
   * correlationGroups/correlationGroupCaps -- before this fix, TypeScript's own structural typing
   * would have silently DROPPED these on the wire even if a caller tried to pass them, since they
   * didn't exist on saveRiskProfile's own request type at all.
   */
  it('saveRiskProfile: forwards maxSymbolExposureQuote/maxPriceDeviationPercent/circuitBreakerThreshold/correlationGroups/correlationGroupCaps when provided, so a caller that has them can no longer have them silently dropped', () => {
    const riskReq = {
      credentialId: 'cred1', autoTradeEnabled: true, enabledSymbols: ['BTCUSDT'],
      minConfidence: 75, maxPositionQuoteAmount: 500, maxConcurrentTrades: 2,
      dailyLossLimitQuote: 100, riskPerTradePercent: 1,
      maxSymbolExposureQuote: 200, maxPriceDeviationPercent: 2.5, circuitBreakerThreshold: 5,
      correlationGroups: { 'majors': ['BTCUSDT', 'ETHUSDT'] },
      correlationGroupCaps: { 'majors': 300 }
    };
    service.saveRiskProfile(riskReq).subscribe();
    const req = http.expectOne(`${environment.apiUrl}/broker/risk-profile`);
    expect(req.request.body).toEqual(riskReq);
    req.flush({ success: true });
  });

  // ── LIVE auto-trade authorization (separate ceremony from connecting the credential) ──
  it('authorizeLiveAutoTrade: requires the exact confirmation phrase, never a bare boolean flag', () => {
    service.authorizeLiveAutoTrade('cred1').subscribe();
    const req = http.expectOne(`${environment.apiUrl}/broker/risk-profile/cred1/authorize-live-autotrade`);
    expect(req.request.body).toEqual({ confirm: 'I UNDERSTAND THIS ENABLES AUTONOMOUS LIVE TRADING' });
    req.flush({ success: true });
  });

  it('revokeLiveAutoTrade: posts to the revoke endpoint with no body needed', () => {
    service.revokeLiveAutoTrade('cred1').subscribe();
    const req = http.expectOne(`${environment.apiUrl}/broker/risk-profile/cred1/revoke-live-autotrade`);
    expect(req.request.method).toBe('POST');
    req.flush({ success: true });
  });

  // ── Kill switch ───────────────────────────────────────────
  it('halt: posts the optional reason through to the kill-switch endpoint', () => {
    service.halt('cred1', 'manual safety stop').subscribe();
    const req = http.expectOne(`${environment.apiUrl}/broker/risk-profile/cred1/halt`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ reason: 'manual safety stop' });
    req.flush({ success: true });
  });

  it('halt: works with no reason given (undefined, not a missing field crash)', () => {
    service.halt('cred1').subscribe();
    const req = http.expectOne(`${environment.apiUrl}/broker/risk-profile/cred1/halt`);
    expect(req.request.body).toEqual({ reason: undefined });
    req.flush({ success: true });
  });
});
