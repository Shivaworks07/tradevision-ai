import { TestBed } from '@angular/core/testing';
import { TaEngineService, TradeCall } from './ta-engine.service';

/**
 * Review finding ("Frontend test coverage" -- P2): this file did not exist before this pass --
 * TaEngineService's own updateMLFromOutcome (the adaptive weight-learning loop) had zero test
 * coverage on the frontend side, despite MLWeightServiceTest.java on the backend faithfully
 * porting and testing the exact same algorithm this session. These tests deliberately mirror
 * that backend test suite's own cases -- same learning rate, same win/loss asymmetry, same
 * clamps, same warm-up threshold -- so both sides are independently verified against the same
 * expected values, not just internally self-consistent with each other.
 */
describe('TaEngineService - ML weight learning', () => {
  let service: TaEngineService;

  beforeEach(() => {
    localStorage.removeItem('tv_ml_memory');
    TestBed.configureTestingModule({});
    service = TestBed.inject(TaEngineService);
  });

  afterEach(() => {
    localStorage.removeItem('tv_ml_memory');
  });

  function call(overrides: Partial<TradeCall>): Partial<TradeCall> {
    return { direction: 'LONG', rsi: 50, macdBull: false, patterns: [], volumeRatio: 1, ...overrides };
  }

  it('getMLMemory: a symbol with no recorded outcomes yet returns null, not a fabricated default', () => {
    expect(service.getMLMemory('BTCUSDT', 'CRYPTO')).toBeNull();
  });

  it('updateMLFromOutcome: below the 5-call warm-up threshold, totalCalls/wins/winRate update but no weight is adjusted', () => {
    for (let i = 0; i < 4; i++) {
      service.updateMLFromOutcome('BTCUSDT', 'CRYPTO', 'HIT_T1', call({ direction: 'LONG', rsi: 30, macdBull: true, patterns: ['Bullish Hammer'], volumeRatio: 2.0 }));
    }

    const mem = service.getMLMemory('BTCUSDT', 'CRYPTO')!;
    expect(mem.totalCalls).toBe(4);
    expect(mem.wins).toBe(4);
    expect(mem.winRate).toBe(100);
    expect(mem.weights.rsi).toBe(1.0);
    expect(mem.weights.macd).toBe(1.0);
    expect(mem.weights.patterns).toBe(1.0);
    expect(mem.weights.volume).toBe(1.0);
  });

  it('updateMLFromOutcome: RSI bullish signal + LONG + win -- rsi weight increases by exactly the learning rate (0.05) -- the same case MLWeightServiceTest.java verifies on the backend port', () => {
    for (let i = 0; i < 4; i++) {
      service.updateMLFromOutcome('BTCUSDT', 'CRYPTO', 'HIT_SL', call({ direction: 'SHORT', rsi: 50, macdBull: true }));
    }
    service.updateMLFromOutcome('BTCUSDT', 'CRYPTO', 'HIT_T1', call({ direction: 'LONG', rsi: 30, macdBull: false }));

    const mem = service.getMLMemory('BTCUSDT', 'CRYPTO')!;
    expect(mem.totalCalls).toBe(5);
    expect(mem.weights.rsi).toBeCloseTo(1.05, 5);
    expect(mem.weights.macd).toBe(1.0);
  });

  it('updateMLFromOutcome: RSI bullish signal + LONG + loss -- rsi weight decreases by half the learning rate (0.025)', () => {
    for (let i = 0; i < 4; i++) {
      service.updateMLFromOutcome('BTCUSDT', 'CRYPTO', 'HIT_T1', call({ direction: 'LONG', rsi: 50, macdBull: true }));
    }
    service.updateMLFromOutcome('BTCUSDT', 'CRYPTO', 'HIT_SL', call({ direction: 'LONG', rsi: 30, macdBull: true }));

    const mem = service.getMLMemory('BTCUSDT', 'CRYPTO')!;
    expect(mem.weights.rsi).toBeCloseTo(0.975, 5);
  });

  it('updateMLFromOutcome: a weight already at the maximum (2.0) does not exceed it on another win', () => {
    for (let i = 0; i < 4; i++) {
      service.updateMLFromOutcome('BTCUSDT', 'CRYPTO', 'HIT_SL', call({ direction: 'LONG', rsi: 50 }));
    }
    for (let i = 0; i < 25; i++) {
      service.updateMLFromOutcome('BTCUSDT', 'CRYPTO', 'HIT_T1', call({ direction: 'LONG', rsi: 30 }));
    }

    const mem = service.getMLMemory('BTCUSDT', 'CRYPTO')!;
    expect(mem.weights.rsi).toBe(2.0);
  });

  it('updateMLFromOutcome: a weight already at the minimum (0.3) does not go below it on another loss', () => {
    for (let i = 0; i < 4; i++) {
      service.updateMLFromOutcome('BTCUSDT', 'CRYPTO', 'HIT_T1', call({ direction: 'LONG', rsi: 50 }));
    }
    for (let i = 0; i < 60; i++) {
      service.updateMLFromOutcome('BTCUSDT', 'CRYPTO', 'HIT_SL', call({ direction: 'LONG', rsi: 30 }));
    }

    const mem = service.getMLMemory('BTCUSDT', 'CRYPTO')!;
    expect(mem.weights.rsi).toBe(0.3);
  });

  it('updateMLFromOutcome: preserves the real pattern-matching quirk -- a "Bearish Engulfing" pattern still counts as bullish by substring match, since hasBullPat only checks whether the pattern name CONTAINS a bullish substring, not the pattern\'s actual direction', () => {
    for (let i = 0; i < 4; i++) {
      service.updateMLFromOutcome('BTCUSDT', 'CRYPTO', 'HIT_SL', call({ direction: 'LONG', rsi: 50 }));
    }
    service.updateMLFromOutcome('BTCUSDT', 'CRYPTO', 'HIT_T1', call({ direction: 'LONG', rsi: 50, patterns: ['Bearish Engulfing'] }));

    const mem = service.getMLMemory('BTCUSDT', 'CRYPTO')!;
    expect(mem.weights.patterns).toBeCloseTo(1.05, 5);
  });

  it('updateMLFromOutcome: different symbols are tracked completely independently', () => {
    for (let i = 0; i < 5; i++) {
      service.updateMLFromOutcome('BTCUSDT', 'CRYPTO', 'HIT_T1', call({ direction: 'LONG', rsi: 30 }));
    }

    expect(service.getMLMemory('ETHUSDT', 'CRYPTO')).toBeNull();
  });

  it('updateMLFromOutcome: a result that is neither a HIT_T win nor HIT_SL (e.g. EXPIRED) increments neither wins nor losses', () => {
    service.updateMLFromOutcome('BTCUSDT', 'CRYPTO', 'EXPIRED', call({}));

    const mem = service.getMLMemory('BTCUSDT', 'CRYPTO')!;
    expect(mem.totalCalls).toBe(1);
    expect(mem.wins).toBe(0);
    expect(mem.losses).toBe(0);
  });
});
