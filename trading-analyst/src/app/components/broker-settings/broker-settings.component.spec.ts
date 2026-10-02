import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { RouterTestingModule } from '@angular/router/testing';
import { environment } from '../../../environments/environment';
import { BrokerSettingsComponent } from './broker-settings.component';
import { provideHttpClient, withInterceptorsFromDi } from '@angular/common/http';

/**
 * P1-20 fix ("Frontend risk-profile save silently wipes fields" -- external review, confirmed
 * real by direct inspection of RiskProfileService.doUpsert on the backend before any fix was
 * attempted: it unconditionally $sets maxSymbolExposureQuote/correlationGroups/
 * correlationGroupCaps from whatever the request DTO contains, and this component's own
 * saveRiskProfile() used to build that request from only 12 of the profile's own fields --
 * meaning ANY save from this settings page, even one only meant to flip autoTradeEnabled, would
 * silently reset those three fields to the backend's own bare disabled/empty defaults). This is
 * the actual regression test for that bug: load a profile that already has these three
 * configured, save without touching them, and assert the outgoing request still carries their
 * original values -- not the backend's own defaults, and not this component's own initial
 * (pre-load) field values.
 */
describe('BrokerSettingsComponent', () => {
  let component: BrokerSettingsComponent;
  let fixture: ComponentFixture<BrokerSettingsComponent>;
  let http: HttpTestingController;

  const credential = {
    id: 'cred1', broker: 'BINANCE' as const, mode: 'TESTNET' as const,
    keyHint: 'abcd', connectedAt: '', lastValidatedAt: ''
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
    imports: [BrokerSettingsComponent, RouterTestingModule],
    providers: [provideHttpClient(withInterceptorsFromDi()), provideHttpClientTesting()]
}).compileComponents();
    fixture = TestBed.createComponent(BrokerSettingsComponent);
    component = fixture.componentInstance;
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('should create', () => {
    fixture.detectChanges();
    http.expectOne(`${environment.apiUrl}/broker/list`).flush({ success: true, data: [] });
    expect(component).toBeTruthy();
  });

  it('saveRiskProfile: a save that never touches maxSymbolExposureQuote/correlationGroups/correlationGroupCaps still sends back the exact values most recently loaded for them, not the backend\'s own bare defaults', () => {
    // Load a profile with these three fields genuinely configured to non-default values.
    component.select(credential);
    const getReq = http.expectOne(`${environment.apiUrl}/broker/risk-profile/cred1`);
    getReq.flush({
      success: true, data: {
        id: 'rp1', credentialId: 'cred1', autoTradeEnabled: false,
        enabledSymbols: ['BTCUSDT'], minConfidence: 75,
        maxPositionQuoteAmount: 50, maxConcurrentTrades: 1,
        dailyLossLimitQuote: 25, dailyRealizedLossQuote: 0, riskPerTradePercent: 1,
        maxTotalExposureQuote: 100, maxPriceDeviationPercent: 1.5,
        consecutiveOrderFailures: 0, circuitBreakerThreshold: 3,
        maxDrawdownPercent: 10, maxOrdersPerHour: 20, maxConsecutiveAutoTradeLosses: 3,
        maxSymbolExposureQuote: 40, // deliberately configured, non-default
        correlationGroups: { 'majors': ['BTCUSDT', 'ETHUSDT'] }, // deliberately configured
        correlationGroupCaps: { 'majors': 60 }, // deliberately configured
        tradingHalted: false, haltReason: '', liveAutoTradeAuthorized: false
      }
    });
    http.match(() => true).forEach(r => r.flush({ success: true, data: [] })); // balance/history/etc., irrelevant here

    // Now save WITHOUT touching maxSymbolExposureQuote/correlationGroups/correlationGroupCaps at
    // all -- e.g. a user who only came here to flip autoTradeEnabled.
    component.autoTradeEnabled = true;
    component.saveRiskProfile();

    const saveReq = http.expectOne(`${environment.apiUrl}/broker/risk-profile`);
    expect(saveReq.request.body.maxSymbolExposureQuote).toBe(40);
    expect(saveReq.request.body.correlationGroups).toEqual({ 'majors': ['BTCUSDT', 'ETHUSDT'] });
    expect(saveReq.request.body.correlationGroupCaps).toEqual({ 'majors': 60 });
    saveReq.flush({ success: true, data: {} });
  });

  // P3-10 fix ("PAPER mode requires 'live authorization' and isn't selectable in UI" -- external
  // review, confirmed real by direct inspection: connect() always hardcoded mode: 'TESTNET', so
  // there was no way to ever create a PAPER credential from this settings page, even though the
  // backend's own /connect endpoint already fully supported it). These two tests are the actual
  // regression coverage: the connect form's own connectMode field genuinely reaches the outgoing
  // request, for both values it can take.
  it('connect: defaults to TESTNET when the mode selector is left untouched', () => {
    fixture.detectChanges();
    http.expectOne(`${environment.apiUrl}/broker/list`).flush({ success: true, data: [] });

    component.connectApiKey = 'key1';
    component.connectApiSecret = 'secret1';
    component.connect();

    const req = http.expectOne(`${environment.apiUrl}/broker/connect`);
    expect(req.request.body.mode).toBe('TESTNET');
    req.flush({ success: true, message: 'Connected.', data: credential });
    http.match(() => true).forEach(r => r.flush({ success: true, data: [] }));
  });

  it('connect: sends mode PAPER when the user selects Paper -- the actual P3-10 fix, since this was previously impossible from the UI at all', () => {
    fixture.detectChanges();
    http.expectOne(`${environment.apiUrl}/broker/list`).flush({ success: true, data: [] });

    component.connectMode = 'PAPER';
    component.connectApiKey = 'key1';
    component.connectApiSecret = 'secret1';
    component.connect();

    const req = http.expectOne(`${environment.apiUrl}/broker/connect`);
    expect(req.request.body.mode).toBe('PAPER');
    req.flush({ success: true, message: 'Connected.', data: { ...credential, mode: 'PAPER' } });
    http.match(() => true).forEach(r => r.flush({ success: true, data: [] }));
  });
});
