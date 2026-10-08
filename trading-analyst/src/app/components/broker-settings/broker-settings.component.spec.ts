import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { RouterTestingModule } from '@angular/router/testing';
import { environment } from '../../../environments/environment';
import { BrokerSettingsComponent } from './broker-settings.component';
import { provideHttpClient, withInterceptorsFromDi } from '@angular/common/http';

/**
 * Covers saveRiskProfile()'s round-trip of maxSymbolExposureQuote/correlationGroups/
 * correlationGroupCaps: RiskProfileService.doUpsert on the backend unconditionally $sets
 * these fields from whatever the request DTO contains, so a save must always echo back
 * their most recently loaded values, not the backend's bare disabled/empty defaults and
 * not this component's initial (pre-load) field values — even when the save is only
 * meant to change an unrelated field like autoTradeEnabled.
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

  // Covers that the connect form's connectMode field genuinely reaches the outgoing
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

  it('connect: sends mode PAPER when the user selects Paper', () => {
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
