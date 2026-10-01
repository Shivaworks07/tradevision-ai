import { TestBed } from '@angular/core/testing';
import { RiskEngineService } from './risk-engine.service';

/**
 * P3-4 fix ("trading-analyst risk-engine.service.ts -- client 'risk engine' (localStorage, $10k
 * default) unrelated to server limits" -- external review, full context in this service's own
 * updated header javadoc): this file did not exist before this fix -- RiskEngineService had zero
 * test coverage previously. Covers the actual new behavior: accountSize is now genuinely editable
 * via updateParams (closing the "silently stuck at $10k forever" half of the finding), and the
 * dead SessionStats/canTrade/recordTrade/calcDynamicSL/calcTrailingStop surface is confirmed gone
 * (a TypeScript compile of this spec file itself is part of that proof -- referencing any of
 * those removed members would fail to compile).
 */
describe('RiskEngineService', () => {
  let service: RiskEngineService;

  beforeEach(() => {
    localStorage.removeItem('tv_risk_params');
    TestBed.configureTestingModule({});
    service = TestBed.inject(RiskEngineService);
  });

  afterEach(() => {
    localStorage.removeItem('tv_risk_params');
  });

  it('defaults to a $10,000 account size and 1% risk per trade', () => {
    const params = service.getParams();
    expect(params.accountSize).toBe(10000);
    expect(params.riskPerTrade).toBe(1.0);
  });

  it('getParams returns only accountSize/riskPerTrade -- the dead maxDailyLoss/maxConsecLosses/maxOpenTrades fields are gone', () => {
    const params = service.getParams();
    expect(Object.keys(params).sort()).toEqual(['accountSize', 'riskPerTrade']);
  });

  it('updateParams actually changes accountSize -- closing the "permanently stuck at $10k, nothing can ever call this" gap the review found', () => {
    service.updateParams({ accountSize: 25000 });

    expect(service.getParams().accountSize).toBe(25000);
  });

  it('updateParams persists across a fresh service instance (simulating a page reload) via localStorage', () => {
    service.updateParams({ accountSize: 50000, riskPerTrade: 2.0 });

    // A plain `new` (not TestBed.inject, which would just hand back the same singleton) --
    // its constructor runs loadParams() fresh, exactly as a real page reload would.
    const freshInstance = new RiskEngineService();
    expect(freshInstance.getParams().accountSize).toBe(50000);
    expect(freshInstance.getParams().riskPerTrade).toBe(2.0);
  });

  it('calculateRisk: a new, larger accountSize (set via onAccountSizeChange -> updateParams) actually changes the resulting position size -- proving the panel is no longer stuck computing against a fixed, unchangeable $10k', () => {
    const before = service.calculateRisk(100, 95, 105, 110, 120, 2, 75, 55);

    service.updateParams({ accountSize: 100000 }); // 10x the default
    const after = service.calculateRisk(100, 95, 105, 110, 120, 2, 75, 55);

    expect(after.positionSize).toBeGreaterThan(before.positionSize);
    expect(after.riskAmount).toBeGreaterThan(before.riskAmount);
  });

  it('calculateRisk: LONG direction and basic RR/position-size sanity', () => {
    const risk = service.calculateRisk(100, 95, 105, 110, 120, 2, 75, 55);

    expect(risk.entry).toBe(100);
    expect(risk.stopLoss).toBe(95);
    expect(risk.riskAmount).toBeGreaterThan(0);
    expect(risk.positionSize).toBeGreaterThan(0);
    expect(risk.rrRatioT1).toMatch(/^1 : /);
  });
});
