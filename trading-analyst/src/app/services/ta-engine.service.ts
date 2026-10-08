import { Injectable } from '@angular/core';

// Forward declarations (avoid circular imports)
export interface SMCBias { bias: string; biasStrength: number; entrySetup: string | null; structureBreaks: any[]; orderBlocks: any[]; }
export interface OFBias  { overallBias: string; score: number; reasons: string[]; }
export interface VPBias  { priceLocation: string; poc: number; vah: number; val: number; bias: string; nearestHVN: any; }
export interface RegimeBias { regime: string; label: string; emoji: string; color: string; confidence: number; riskMultiplier: number; slMultiplier: number; weightAdjustments: any; strategy: any; warnings: string[]; }

export interface OHLCV { time:number; open:number; high:number; low:number; close:number; volume:number; }

export interface MTFContext {
  tf: string;
  trend: 'STRONG_UP'|'UP'|'SIDEWAYS'|'DOWN'|'STRONG_DOWN';
  rsi: number;
  macdBull: boolean;
  aboveEma50: boolean;
  aboveEma200: boolean;
  score: number; // -100 to +100
}

// ML win-rate memory — stored per symbol, updated from DB outcomes
export interface MLMemory {
  symbol: string;
  totalCalls: number;
  wins: number;
  losses: number;
  winRate: number;
  // Which indicator combinations work best for this asset
  bestIndicators: { indicator: string; winRate: number; sampleSize: number }[];
  // Indicator weights learned from history (defaults → adjusted by win/loss)
  weights: {
    trend: number;    // default 1.0
    rsi: number;
    macd: number;
    bb: number;
    stoch: number;
    volume: number;
    patterns: number;
    adx: number;
    williamsR: number;
    obv: number;
    divergence: number;
    mtfAlignment: number;
  };
  lastUpdated: number;
}

export interface TradeCall {
  direction: 'LONG'|'SHORT'|'WAIT';
  signal: 'STRONG BUY'|'BUY'|'NEUTRAL'|'SELL'|'STRONG SELL';
  confidence: number;
  entry: number; stopLoss: number;
  target1: number; target2: number; target3: number;
  atr: number; atrPct: number;
  rsi: number; rsiZone: string;
  macdLine: number; macdSignal: number; macdHist: number; macdBull: boolean;
  ema9: number; ema20: number; ema50: number; ema200: number; trendEMA: string;
  bbUpper: number; bbMid: number; bbLower: number; bbSignal: string;
  vwap: number; vwapSignal: string;
  stochK: number; stochD: number; stochZone: string;
  adx: number; adxTrend: string;
  williamsR: number; williamsZone: string;
  obv: number; obvSignal: string;
  volumeSignal: string; volumeRatio: number;
  patterns: string[];
  supports: number[]; resistances: number[];
  rrRatio: string; riskPct: number;
  timeframe: string; risk: 'LOW'|'MEDIUM'|'HIGH';
  summary: string; lastUpdated: string;
  bullReasons: string[]; bearReasons: string[];
  symbol?: string;
  // MTF context
  mtfContext?: MTFContext[];
  mtfAlignment?: string;
  // Phase 2 engines
  regime?: string;
  regimeLabel?: string;
  regimeEmoji?: string;
  regimeColor?: string;
  regimeConfidence?: number;
  regimeStrategy?: string;
  regimeWarnings?: string[];
  // Phase 1 engines
  smcBias?: string;
  smcSetup?: string | null;
  smcBiasStrength?: number;
  ofBias?: string;
  ofScore?: number;
  ofReasons?: string[];
  vpLocation?: string;
  vpPoc?: number;
  vpVah?: number;
  vpVal?: number;
  // ML
  mlWinRate?: number;
  mlSampleSize?: number;
  mlAdjusted?: boolean;
  // ML evolution
  featureVersion?: number;
  rawCandles?: number[][];
  decisionWeights?: Record<string, number>;
  indicatorScores?: Record<string, number>;  // per-indicator contribution
  adjustments?: Record<string, number>;      // regime/ML adjustments applied
  // AI Score Breakdown (features 3, 8)
  scoreBreakdown?: {
    trend:            number;  // 0-100
    momentum:         number;  // RSI + MACD + Stoch + Williams
    volume:           number;  // OBV + Volume ratio
    candlestick:      number;  // patterns score
    supportResistance: number; // BB + VWAP + S/R levels
    overall:          number;  // final confidence
  };
  signalStrength?: 'Very Weak'|'Weak'|'Moderate'|'Strong'|'Very Strong';
  expectedHolding?: string;    // e.g. "2-5 days", "4-12 hours"
  expectedMove?: string;       // e.g. "+4-6%"

  // Data quality
  qualityScore?: number;         // 0-100, how complete the feature set is
  missingFeatures?: number;      // count of missing indicator values
  candleCount?: number;          // candles available at signal time
}

@Injectable({ providedIn: 'root' })
export class TaEngineService {

  // ── ML Memory Store (localStorage-backed) ─────────────────
  // This store only affects what gets displayed to the user and what gets submitted as the
  // client's claimed signal, which the backend treats as advisory, not authoritative. Every
  // execution-relevant decision (server signal computation, sizing, the SL/ATR validation
  // gate, the min-confidence check) uses ServerSignalEngine's own independent Java computation
  // instead — this store must never become an input to anything the backend trusts for
  // real execution.
  private mlStore: Map<string, MLMemory> = new Map();

  constructor() { this.loadMLMemory(); }

  private loadMLMemory() {
    try {
      const raw = localStorage.getItem('tv_ml_memory');
      if (raw) {
        const obj = JSON.parse(raw);
        Object.entries(obj).forEach(([k,v]) => this.mlStore.set(k, v as MLMemory));
      }
    } catch {}
  }

  private saveMLMemory() {
    try {
      const obj: Record<string,MLMemory> = {};
      this.mlStore.forEach((v,k) => { obj[k] = v; });
      localStorage.setItem('tv_ml_memory', JSON.stringify(obj));
    } catch {}
  }

  // Called from call-history when a call result is marked
  updateMLFromOutcome(symbol: string, market: string, result: string, call: Partial<TradeCall>) {
    const key = `${market}:${symbol}`;
    let mem = this.mlStore.get(key) || this.defaultMLMemory(symbol);

    const isWin = result.startsWith('HIT_T');
    mem.totalCalls++;
    if (isWin) mem.wins++; else if (result === 'HIT_SL') mem.losses++;
    mem.winRate = mem.totalCalls > 0 ? (mem.wins / mem.totalCalls) * 100 : 50;

    // Adjust weights based on which indicators predicted correctly
    if (mem.totalCalls >= 5) {
      const lr = 0.05; // learning rate
      const reward = isWin ? 1 : -0.5;

      // RSI signal contribution
      const rsiVal = call.rsi || 50;
      const rsiBullSignal = rsiVal < 40;
      const rsiBearSignal = rsiVal > 60;
      const wasLong = call.direction === 'LONG';
      if ((rsiBullSignal && wasLong && isWin) || (rsiBearSignal && !wasLong && isWin))
        mem.weights.rsi = Math.min(2.0, mem.weights.rsi + lr);
      else if ((rsiBullSignal && wasLong && !isWin) || (rsiBearSignal && !wasLong && !isWin))
        mem.weights.rsi = Math.max(0.3, mem.weights.rsi - lr * 0.5);

      // MACD contribution
      const macdAligned = (call.macdBull && wasLong) || (!call.macdBull && !wasLong);
      if (macdAligned && isWin)  mem.weights.macd = Math.min(2.0, mem.weights.macd + lr);
      else if (macdAligned && !isWin) mem.weights.macd = Math.max(0.3, mem.weights.macd - lr * 0.5);

      // Pattern contribution
      const hasBullPat = call.patterns?.some(p => ['Engulfing','Star','Soldiers','Hammer','Marubozu'].some(b => p.includes(b)));
      if (hasBullPat && isWin)  mem.weights.patterns = Math.min(2.0, mem.weights.patterns + lr);
      else if (hasBullPat && !isWin) mem.weights.patterns = Math.max(0.3, mem.weights.patterns - lr * 0.5);

      // Volume contribution
      const highVol = (call.volumeRatio || 1) > 1.5;
      if (highVol && isWin)  mem.weights.volume = Math.min(2.0, mem.weights.volume + lr);
      else if (highVol && !isWin) mem.weights.volume = Math.max(0.3, mem.weights.volume - lr * 0.5);
    }

    mem.lastUpdated = Date.now();
    this.mlStore.set(key, mem);
    this.saveMLMemory();
    console.log(`[ML] Updated ${key}: WR=${mem.winRate.toFixed(0)}%, n=${mem.totalCalls}`);
  }

  // Sync ML memory from backend call history
  syncMLFromHistory(symbol: string, market: string, calls: any[]) {
    const key = `${market}:${symbol}`;
    const resolved = calls.filter(c => c.result !== 'PENDING' && c.result !== 'EXPIRED');
    if (!resolved.length) return;

    let mem = this.mlStore.get(key) || this.defaultMLMemory(symbol);
    mem.totalCalls = resolved.length;
    mem.wins   = resolved.filter(c => c.result?.startsWith('HIT_T')).length;
    mem.losses = resolved.filter(c => c.result === 'HIT_SL').length;
    mem.winRate = mem.totalCalls > 0 ? (mem.wins / mem.totalCalls) * 100 : 50;
    mem.lastUpdated = Date.now();
    this.mlStore.set(key, mem);
    this.saveMLMemory();
  }

  getMLMemory(symbol: string, market: string): MLMemory | null {
    return this.mlStore.get(`${market}:${symbol}`) || null;
  }

  private defaultMLMemory(symbol: string): MLMemory {
    return {
      symbol, totalCalls:0, wins:0, losses:0, winRate:50,
      bestIndicators: [],
      weights: { trend:1.0, rsi:1.0, macd:1.0, bb:1.0, stoch:1.0, volume:1.0, patterns:1.0, adx:1.0, williamsR:1.0, obv:1.0, divergence:1.3, mtfAlignment:1.5 },
      lastUpdated: Date.now()
    };
  }

  // ── Main MTF analyze (uses higher TF candles for context) ──
  analyzeWithMTF(
    candles: OHLCV[],
    symbol: string,
    tf: string,
    higherTF1?: OHLCV[],
    higherTF2?: OHLCV[],
    market = 'CRYPTO',
    smcData?: SMCBias,
    ofData?:  OFBias,
    vpData?:  VPBias,
    regimeData?: RegimeBias
  ): TradeCall {
    const base = this.analyze(candles, symbol, tf, market);
    if (!higherTF1 && !higherTF2) return base;

    const mtfContexts: MTFContext[] = [];
    const htf1Tf = this.nextHigherTF(tf);
    const htf2Tf = this.nextHigherTF(htf1Tf);

    if (higherTF1 && higherTF1.length >= 30) {
      mtfContexts.push(this.buildMTFContext(higherTF1, htf1Tf));
    }
    if (higherTF2 && higherTF2.length >= 30) {
      mtfContexts.push(this.buildMTFContext(higherTF2, htf2Tf));
    }

    if (!mtfContexts.length) return base;

    // MTF alignment scoring
    const baseDir = base.direction;
    let alignScore = 0;
    let alignDesc = '';

    for (const ctx of mtfContexts) {
      const ctxBull = ctx.score > 15;
      const ctxBear = ctx.score < -15;
      if (baseDir === 'LONG' && ctxBull)       { alignScore += 25; alignDesc += `${ctx.tf} aligned bull · `; }
      else if (baseDir === 'SHORT' && ctxBear) { alignScore += 25; alignDesc += `${ctx.tf} aligned bear · `; }
      else if (baseDir === 'LONG' && ctxBear)  { alignScore -= 20; alignDesc += `${ctx.tf} AGAINST (bear) ⚠️ · `; }
      else if (baseDir === 'SHORT' && ctxBull) { alignScore -= 20; alignDesc += `${ctx.tf} AGAINST (bull) ⚠️ · `; }
      else                                      { alignDesc += `${ctx.tf} neutral · `; }
    }

    // Apply MTF modifier to confidence
    let newConf = base.confidence;
    const ml = this.getMLMemory(symbol, market);
    const mtfWeight = ml?.weights.mtfAlignment ?? 1.5;

    if (alignScore > 0) {
      newConf = Math.min(96, base.confidence + Math.round(alignScore * 0.4 * mtfWeight));
    } else if (alignScore < 0) {
      // If MTF is against us, reduce confidence significantly and possibly flip to WAIT
      newConf = Math.max(30, base.confidence + Math.round(alignScore * 0.5 * mtfWeight));
    }

    const mtfAlignment = alignScore > 30
      ? `✅ All timeframes aligned (${alignDesc.slice(0,-3)})`
      : alignScore > 0
      ? `🟡 Partial alignment (${alignDesc.slice(0,-3)})`
      : alignScore < -10
      ? `🔴 MTF conflict — lower confidence (${alignDesc.slice(0,-3)})`
      : `⚪ MTF neutral (${alignDesc.slice(0,-3)})`;

    // If MTF strongly against, change direction to WAIT
    let newDirection = base.direction;
    let newSignal    = base.signal;
    if (alignScore <= -30 && newConf < 48) {
      newDirection = 'WAIT';
      newSignal    = 'NEUTRAL';
      newConf = Math.max(35, newConf);
    }

    // ML win-rate adjustment
    let mlAdjusted = false;
    let mlNote = '';
    if (ml && ml.totalCalls >= 10) {
      const wr = ml.winRate;
      if (wr >= 65) {
        newConf = Math.min(96, newConf + 5);
        mlNote = ` ML: ${wr.toFixed(0)}% win rate on ${ml.totalCalls} calls.`;
        mlAdjusted = true;
      } else if (wr <= 35) {
        newConf = Math.max(30, newConf - 8);
        mlNote = ` ML: Only ${wr.toFixed(0)}% win rate — reduce position size.`;
        mlAdjusted = true;
      }
    }

    // Phase 2: Market Regime adjustments
    let regimeNote = '';
    if (regimeData) {
      const rm = regimeData.riskMultiplier;
      const w  = regimeData.weightAdjustments;

      // Regime-adjusted confidence
      if (regimeData.regime.includes('STRONG_BULL') && newDirection === 'LONG')  { newConf = Math.min(96, newConf + 8); regimeNote = ` ${regimeData.emoji} ${regimeData.label}.`; }
      if (regimeData.regime.includes('STRONG_BEAR') && newDirection === 'SHORT') { newConf = Math.min(96, newConf + 8); regimeNote = ` ${regimeData.emoji} ${regimeData.label}.`; }
      if (regimeData.regime === 'HIGH_VOLATILITY')      { newConf = Math.max(30, newConf - 10); regimeNote = ` ⚡ HIGH VOLATILITY — reduce size.`; }
      if (regimeData.regime === 'LOW_VOLATILITY_RANGE') { newConf = Math.max(30, newConf - 15); regimeNote = ` 😴 Low vol range — do not trade.`; }
      if (regimeData.regime === 'RANGING' && (newSignal === 'STRONG BUY' || newSignal === 'STRONG SELL')) {
        // Downgrade strong signals in ranging market
        newSignal = newDirection === 'LONG' ? 'BUY' : 'SELL';
        newConf   = Math.max(30, newConf - 12);
        regimeNote = ` ↔️ Ranging market — signal downgraded.`;
      }
      if (regimeData.regime === 'BREAKOUT_IMMINENT') regimeNote = ` 💥 BB squeeze — breakout imminent.`;
      if (regimeData.regime.startsWith('POST_BREAKOUT')) { newConf = Math.min(96, newConf + 6); regimeNote = ` 🎯 Post-breakout momentum.`; }
    }

    // Phase 1: SMC scoring
    let smcNote = '';
    if (smcData) {
      const smcBull = smcData.bias === 'BULLISH';
      const smcBear = smcData.bias === 'BEARISH';
      const smcStr  = smcData.biasStrength || 0;
      if (smcBull && newDirection === 'LONG')  { newConf = Math.min(96, newConf + Math.round(smcStr * 0.12)); smcNote += ` SMC: ${smcData.bias} (${smcStr}%).`; }
      if (smcBear && newDirection === 'SHORT') { newConf = Math.min(96, newConf + Math.round(smcStr * 0.12)); smcNote += ` SMC: ${smcData.bias} (${smcStr}%).`; }
      if (smcBull && newDirection === 'SHORT') { newConf = Math.max(30, newConf - 10); smcNote += ` ⚠️ SMC bullish vs SHORT.`; }
      if (smcBear && newDirection === 'LONG')  { newConf = Math.max(30, newConf - 10); smcNote += ` ⚠️ SMC bearish vs LONG.`; }
      if (smcData.entrySetup && smcData.bias !== 'NEUTRAL') smcNote += ` ${smcData.entrySetup}`;
    }

    // Phase 1: Order Flow scoring
    let ofNote = '';
    if (ofData) {
      const ofScore = ofData.score;
      if (ofScore >= 30 && newDirection === 'LONG')  { newConf = Math.min(96, newConf + 8); ofNote = ` OF: ${ofData.overallBias} (+${ofScore}).`; }
      if (ofScore <= -30 && newDirection === 'SHORT') { newConf = Math.min(96, newConf + 8); ofNote = ` OF: ${ofData.overallBias} (${ofScore}).`; }
      if (ofScore >= 30 && newDirection === 'SHORT')  { newConf = Math.max(30, newConf - 12); ofNote = ` ⚠️ Order flow bullish vs SHORT.`; }
      if (ofScore <= -30 && newDirection === 'LONG')   { newConf = Math.max(30, newConf - 12); ofNote = ` ⚠️ Order flow bearish vs LONG.`; }
    }

    // Phase 1: Volume Profile scoring
    let vpNote = '';
    if (vpData) {
      if (vpData.priceLocation === 'ABOVE_VAH' && newDirection === 'LONG')  { newConf = Math.min(96, newConf + 6); vpNote = ` VP: Above VAH — bullish.`; }
      if (vpData.priceLocation === 'BELOW_VAL' && newDirection === 'SHORT') { newConf = Math.min(96, newConf + 6); vpNote = ` VP: Below VAL — bearish.`; }
      if (vpData.priceLocation === 'ABOVE_VAH' && newDirection === 'SHORT') { newConf = Math.max(30, newConf - 6); }
      if (vpData.priceLocation === 'BELOW_VAL' && newDirection === 'LONG')  { newConf = Math.max(30, newConf - 6); }
    }

    newConf = Math.max(30, Math.min(96, Math.round(newConf)));

    return {
      ...base,
      direction:    newDirection,
      regime:       regimeData?.regime,
      regimeLabel:  regimeData?.label,
      regimeEmoji:  regimeData?.emoji,
      regimeColor:  regimeData?.color,
      regimeConfidence: regimeData?.confidence,
      regimeStrategy: regimeData?.strategy?.type,
      regimeWarnings: regimeData?.warnings,
      signal:       newSignal,
      confidence:   newConf,
      mtfContext:   mtfContexts,
      mtfAlignment,
      smcBias:      smcData?.bias,
      smcSetup:     smcData?.entrySetup,
      smcBiasStrength: smcData?.biasStrength,
      ofBias:       ofData?.overallBias,
      ofScore:      ofData?.score,
      ofReasons:    ofData?.reasons,
      vpLocation:   vpData?.priceLocation,
      vpPoc:        vpData?.poc,
      vpVah:        vpData?.vah,
      vpVal:        vpData?.val,
      mlWinRate:    ml?.winRate,
      mlSampleSize: ml?.totalCalls,
      mlAdjusted,
      summary: base.summary + (mtfAlignment ? ` MTF: ${mtfAlignment}.` : '') + regimeNote + smcNote + ofNote + vpNote + mlNote
    };
  }

  private buildMTFContext(candles: OHLCV[], tf: string): MTFContext {
    const closes = candles.map(c=>c.close);
    const n = closes.length;
    const price = closes[n-1];
    const ema20  = this.ema(closes, 20);
    const ema50  = this.ema(closes, 50);
    const ema200 = this.ema(closes, Math.min(200, n-1));
    const rsi    = this.wilderRsi(closes, 14);
    const macd   = this.macdFull(closes);
    const aboveEma50  = price > ema50;
    const aboveEma200 = price > ema200;
    const macdBull = macd.hist > 0;

    let score = 0;
    if (price > ema50 && ema20 > ema50) score += 30;
    else if (price > ema50)             score += 15;
    else if (price < ema50 && ema20 < ema50) score -= 30;
    else if (price < ema50)             score -= 15;
    if (price > ema200) score += 20; else score -= 20;
    if (rsi < 35)  score += 20; else if (rsi > 65) score -= 20;
    else if (rsi < 50) score += 8; else score -= 8;
    if (macdBull) score += 15; else score -= 15;

    const trend: MTFContext['trend'] =
      score > 50  ? 'STRONG_UP' :
      score > 20  ? 'UP' :
      score < -50 ? 'STRONG_DOWN' :
      score < -20 ? 'DOWN' : 'SIDEWAYS';

    return { tf, trend, rsi, macdBull, aboveEma50, aboveEma200, score };
  }

  nextHigherTF(tf: string): string {
    const map: Record<string,string> = { '1m':'15m','5m':'1h','15m':'4h','30m':'4h','1h':'4h','4h':'1d','1d':'1w' };
    return map[tf] || '4h';
  }

  // ── Core single-TF analyze ─────────────────────────────────
  analyze(candles: OHLCV[], symbol: string, tf = '1h', market = 'CRYPTO'): TradeCall {
    if (candles.length < 30) return this.emptyCall(candles, tf);

    const closes = candles.map(c=>c.close);
    const highs  = candles.map(c=>c.high);
    const lows   = candles.map(c=>c.low);
    const vols   = candles.map(c=>c.volume);
    const n = closes.length;
    const price = closes[n-1];

    const ml = this.getMLMemory(symbol, market);
    const W  = ml?.weights ?? this.defaultMLMemory(symbol).weights;

    const rsi14   = this.wilderRsi(closes, 14);
    const ema9    = this.ema(closes, 9);
    const ema20   = this.ema(closes, 20);
    const ema50   = this.ema(closes, 50);
    const ema200  = this.ema(closes, Math.min(200, n-1));
    const macd    = this.macdFull(closes);
    const prevMacd= this.macdFull(closes.slice(0,-1));
    const bb      = this.bollingerBands(closes, 20, 2);
    const atr14   = this.atr(highs, lows, closes, 14);
    const stoch   = this.stochastic(highs, lows, closes, 14, 3);
    const vwapVal = this.vwap(candles);
    const adxData = this.adx(highs, lows, closes, 14);
    const willR   = this.williamsR(highs, lows, closes, 14);
    const obvVal  = this.obv(closes, vols);
    const obvPrev = this.obv(closes.slice(0,-5), vols.slice(0,-5));
    const { supports, resistances } = this.srLevels(highs, lows);
    const patterns = this.detectPatterns(candles);
    const rsiArr   = this.rsiArray(closes, 14);
    const bullDiv  = this.bullishDivergence(closes, rsiArr);
    const bearDiv  = this.bearishDivergence(closes, rsiArr);

    const vol20Avg = vols.slice(-20).reduce((a,b)=>a+b,0)/20;
    const volRatio = vols[n-1]/(vol20Avg||1);
    const macdBull = macd.hist > 0;
    const macdCrossedUp   = macd.hist > 0 && prevMacd.hist <= 0;
    const macdCrossedDown = macd.hist < 0 && prevMacd.hist >= 0;
    const macdMomentumUp  = macd.hist > prevMacd.hist;

    const bullTrend = price>ema50 && ema20>ema50 && price>ema200;
    const bearTrend = price<ema50 && ema20<ema50 && price<ema200;
    const strongBullTrend = price>ema9 && ema9>ema20 && ema20>ema50 && ema50>ema200;
    const strongBearTrend = price<ema9 && ema9<ema20 && ema20<ema50 && ema50<ema200;
    const atBbLower = price <= bb.lower*1.008;
    const atBbUpper = price >= bb.upper*0.992;
    const bbSqueeze = (bb.upper-bb.lower)/bb.mid < 0.02;

    let bullScore = 0, bearScore = 0;
    const bullReasons: string[] = [];
    const bearReasons: string[] = [];
    const addBull = (pts:number, reason='') => { bullScore += pts; if(reason) bullReasons.push(reason); };
    const addBear = (pts:number, reason='') => { bearScore += pts; if(reason) bearReasons.push(reason); };

    // 1. TREND — ML weighted
    const trendW = W.trend;
    if (strongBullTrend)   addBull(25*trendW, '📈 Strong uptrend (all EMAs aligned)');
    else if (bullTrend)    addBull(15*trendW, '📈 Uptrend (EMA stack)');
    else if (price>ema20)  addBull(8*trendW, '');
    if (strongBearTrend)   addBear(25*trendW, '📉 Strong downtrend (all EMAs aligned)');
    else if (bearTrend)    addBear(15*trendW, '📉 Downtrend (EMA stack)');
    else if (price<ema20)  addBear(8*trendW, '');

    // 2. ADX — ML weighted
    const adxW = W.adx;
    if (adxData.adx > 25) {
      if (adxData.diPlus > adxData.diMinus) addBull(10*adxW, `ADX ${adxData.adx.toFixed(0)} strong bull momentum`);
      else                                   addBear(10*adxW, `ADX ${adxData.adx.toFixed(0)} strong bear momentum`);
    }

    // 3. RSI — ML weighted
    const rsiW = W.rsi;
    if (rsi14 < 25)        addBull(24*rsiW, `🟢 RSI ${rsi14.toFixed(0)} deeply oversold`);
    else if (rsi14 < 35)   addBull(18*rsiW, `RSI ${rsi14.toFixed(0)} oversold`);
    else if (rsi14<50 && bullTrend) addBull(8*rsiW, '');
    if (rsi14 > 75)        addBear(24*rsiW, `🔴 RSI ${rsi14.toFixed(0)} deeply overbought`);
    else if (rsi14 > 65)   addBear(18*rsiW, `RSI ${rsi14.toFixed(0)} overbought`);
    else if (rsi14>50 && bearTrend) addBear(8*rsiW, '');

    // 4. DIVERGENCE — highest ML weight by default
    const divW = W.divergence;
    if (bullDiv) addBull(20*divW, '🔔 Bullish RSI divergence — strong reversal signal');
    if (bearDiv) addBear(20*divW, '🔔 Bearish RSI divergence — strong reversal signal');

    // 5. MACD — ML weighted
    const macdW = W.macd;
    if (macdCrossedUp)                     addBull(22*macdW, '⚡ MACD bullish cross — fresh entry signal');
    else if (macdBull && macdMomentumUp)   addBull(14*macdW, 'MACD bullish & strengthening');
    else if (macdBull)                     addBull(7*macdW, '');
    if (macdCrossedDown)                   addBear(22*macdW, '⚡ MACD bearish cross — fresh short signal');
    else if (!macdBull && !macdMomentumUp) addBear(14*macdW, 'MACD bearish & weakening');
    else if (!macdBull)                    addBear(7*macdW, '');

    // 6. BOLLINGER BANDS — ML weighted
    const bbW = W.bb;
    if (atBbLower)  addBull(16*bbW, '📉 At BB lower band — oversold bounce zone');
    if (atBbUpper)  addBear(16*bbW, '📈 At BB upper band — overbought rejection zone');
    if (bbSqueeze)  { /* note only — breakout coming, direction unclear */ }

    // 7. STOCHASTIC — ML weighted
    const stochW = W.stoch;
    const stochOS = stoch.k < 25 && stoch.d < 30;
    const stochOB = stoch.k > 75 && stoch.d > 70;
    if (stochOS)                              addBull(13*stochW, `Stoch ${stoch.k.toFixed(0)} oversold`);
    else if (stoch.k>stoch.d && stoch.k<45)  addBull(7*stochW, 'Stoch bullish cross');
    if (stochOB)                              addBear(13*stochW, `Stoch ${stoch.k.toFixed(0)} overbought`);
    else if (stoch.k<stoch.d && stoch.k>55)  addBear(7*stochW, 'Stoch bearish cross');

    // 8. WILLIAMS %R — ML weighted
    const wrW = W.williamsR;
    if (willR < -80)      addBull(11*wrW, `Williams %R ${willR.toFixed(0)} — extreme oversold`);
    else if (willR < -60) addBull(5*wrW, '');
    if (willR > -20)      addBear(11*wrW, `Williams %R ${willR.toFixed(0)} — extreme overbought`);
    else if (willR > -40) addBear(5*wrW, '');

    // 9. VWAP
    if (price > vwapVal*1.002) addBull(8, 'Above VWAP — institutional bullish bias');
    else if (price < vwapVal*0.998) addBear(8, 'Below VWAP — institutional bearish bias');

    // 10. OBV — ML weighted
    const obvW = W.obv;
    if (obvVal > obvPrev*1.003)      addBull(11*obvW, '📊 OBV rising — accumulation (buying pressure)');
    else if (obvVal < obvPrev*0.997) addBear(11*obvW, '📊 OBV falling — distribution (selling pressure)');

    // 11. VOLUME — ML weighted
    const volW = W.volume;
    if (volRatio > 2.0 && macdBull)   addBull(12*volW, `🔥 ${volRatio.toFixed(1)}x volume confirms bullish move`);
    else if (volRatio>1.5 && macdBull) addBull(7*volW, '');
    if (volRatio > 2.0 && !macdBull)  addBear(12*volW, `🔥 ${volRatio.toFixed(1)}x volume confirms bearish move`);
    else if (volRatio>1.5 && !macdBull) addBear(7*volW, '');

    // 12. PATTERNS — ML weighted
    const patW = W.patterns;
    const bullPats = ['Hammer','Bullish Engulfing','Morning Star','Piercing','White Soldiers','Bullish Marubozu','Tweezer Bottom','Dragonfly'];
    const bearPats = ['Shooting Star','Bearish Engulfing','Evening Star','Dark Cloud','Black Crows','Bearish Marubozu','Tweezer Top','Gravestone'];
    patterns.forEach(p => {
      if (bullPats.some(b=>p.includes(b))) addBull(11*patW, p.replace(/[^\w\s\(\)]/g,'').trim());
      if (bearPats.some(b=>p.includes(b))) addBear(11*patW, p.replace(/[^\w\s\(\)]/g,'').trim());
    });

    // ── Decision with conflict detection ─────────────────────
    const net      = bullScore - bearScore;
    const conflict = bullScore > 30 && bearScore > 30 && Math.abs(net) < 25;

    let signal: TradeCall['signal'];
    let direction: TradeCall['direction'];
    let confidence: number;

    if (conflict) {
      signal='NEUTRAL'; direction='WAIT';
      confidence = Math.round(38 + Math.min(12, Math.abs(net)));
    } else if (net >= 55) {
      signal='STRONG BUY'; direction='LONG';
      confidence = Math.round(Math.min(95, 62 + net*0.28));
    } else if (net >= 22) {
      signal='BUY'; direction='LONG';
      confidence = Math.round(Math.min(80, 50 + net*0.38));
    } else if (net <= -55) {
      signal='STRONG SELL'; direction='SHORT';
      confidence = Math.round(Math.min(95, 62 + Math.abs(net)*0.28));
    } else if (net <= -22) {
      signal='SELL'; direction='SHORT';
      confidence = Math.round(Math.min(80, 50 + Math.abs(net)*0.38));
    } else {
      signal='NEUTRAL'; direction='WAIT';
      confidence = Math.round(38 + Math.min(12, Math.abs(net)));
    }

    // ML win-rate boost/penalty
    let mlAdjusted = false;
    if (ml && ml.totalCalls >= 10) {
      if (ml.winRate >= 65)      { confidence = Math.min(96, confidence+5); mlAdjusted=true; }
      else if (ml.winRate <= 35) { confidence = Math.max(30, confidence-8); mlAdjusted=true; }
    }

    confidence = Math.max(30, Math.min(96, Math.round(confidence)));
    const isBull = direction === 'LONG';

    // S/R aware SL
    const nearSup = supports.filter(s=>s<price*0.999).sort((a,b)=>b-a)[0];
    const nearRes = resistances.filter(r=>r>price*1.001).sort((a,b)=>a-b)[0];
    // SL: based on key S/R level OR 1.5x ATR minimum — never tighter
    const atrSl   = atr14 * 1.5;
    const srDist  = isBull ? (nearSup ? price - nearSup : atrSl) : (nearRes ? nearRes - price : atrSl);
    // Minimum 1.5x ATR, max 3x ATR — wide enough to breathe, tight enough to matter
    const slDist  = Math.max(atrSl, Math.min(atr14 * 3, srDist > 0 ? srDist * 1.1 : atrSl));
    const entry   = price;
    const sl      = isBull ? entry - slDist : entry + slDist;
    const R       = Math.abs(entry - sl);
    // T1 = 1.5R, T2 = 3R (must hit T2 to be worthwhile), T3 = 5R
    const t1 = isBull ? entry + R * 1.5 : entry - R * 1.5;
    const t2 = isBull ? entry + R * 3.0 : entry - R * 3.0;
    const t3 = isBull ? entry + R * 5.0 : entry - R * 5.0;
    const riskPct = (R / entry) * 100;
    const risk: 'LOW'|'MEDIUM'|'HIGH' = confidence>=72?'LOW':confidence>=55?'MEDIUM':'HIGH';

    const trendEMA = strongBullTrend?'📈 Strong Uptrend':strongBearTrend?'📉 Strong Downtrend':bullTrend?'📈 Uptrend':bearTrend?'📉 Downtrend':'↔️ Sideways';

    return {
      direction, signal, confidence,
      entry:this.round(entry), stopLoss:this.round(sl),
      target1:this.round(t1), target2:this.round(t2), target3:this.round(t3),
      rsi:+rsi14.toFixed(1), rsiZone:rsi14<30?'🟢 Oversold':rsi14>70?'🔴 Overbought':'⚪ Neutral',
      macdLine:this.round(macd.macd), macdSignal:this.round(macd.signal), macdHist:this.round(macd.hist), macdBull,
      ema9:this.round(ema9), ema20:this.round(ema20), ema50:this.round(ema50), ema200:this.round(ema200), trendEMA,
      bbUpper:this.round(bb.upper), bbMid:this.round(bb.mid), bbLower:this.round(bb.lower),
      bbSignal:atBbLower?'🟢 At Lower Band':atBbUpper?'🔴 At Upper Band':bbSqueeze?'⚡ Squeeze (Breakout Soon)':'⚪ Within Bands',
      vwap:this.round(vwapVal), vwapSignal:price>vwapVal?'🟢 Above VWAP':'🔴 Below VWAP',
      stochK:+stoch.k.toFixed(1), stochD:+stoch.d.toFixed(1), stochZone:stoch.k<25?'🟢 Oversold':stoch.k>75?'🔴 Overbought':'⚪ Neutral',
      adx:+adxData.adx.toFixed(1), adxTrend:adxData.adx>40?'💪 Very Strong':adxData.adx>25?'📶 Strong':adxData.adx>20?'↔️ Moderate':'😴 Weak/Ranging',
      williamsR:+willR.toFixed(1), williamsZone:willR<-80?'🟢 Oversold':willR>-20?'🔴 Overbought':'⚪ Neutral',
      obv:Math.round(obvVal), obvSignal:obvVal>obvPrev?'🟢 Rising (Accumulation)':'🔴 Falling (Distribution)',
      atr:this.round(atr14), atrPct:+riskPct.toFixed(2),
      volumeSignal:volRatio>2?'🔥 Very High':volRatio>1.5?'🟢 Above Avg':volRatio>0.8?'⚪ Average':'🔴 Low',
      volumeRatio:+volRatio.toFixed(2),
      patterns, supports:supports.slice(0,3).map(v=>this.round(v)), resistances:resistances.slice(0,3).map(v=>this.round(v)),
      rrRatio:`1 : 3.0`, riskPct:+riskPct.toFixed(2),
      timeframe:tf, risk,
      bullReasons:bullReasons.slice(0,6), bearReasons:bearReasons.slice(0,6),
      mlWinRate:ml?.winRate, mlSampleSize:ml?.totalCalls, mlAdjusted,
      featureVersion: 2,
      rawCandles: candles.slice(-50).map(c => [c.time, c.open, c.high, c.low, c.close, c.volume]),
      candleCount: candles.length,
      // AI Score Breakdown — computed from indicator contributions
      scoreBreakdown: (() => {
        const trend     = Math.min(100, Math.round(Math.abs(W.trend * (strongBullTrend||strongBearTrend ? 100 : bullTrend||bearTrend ? 70 : 40))));
        const momentum  = Math.min(100, Math.round(
          (rsi14<35||rsi14>65 ? 80 : rsi14<45||rsi14>55 ? 55 : 35) * W.rsi * 0.5 +
          (macdCrossedUp||macdCrossedDown ? 90 : macdBull ? 60 : 30) * W.macd * 0.3 +
          (stoch.k<25||stoch.k>75 ? 80 : 40) * W.stoch * 0.2
        ));
        const volume    = Math.min(100, Math.round(
          Math.min(100, (volRatio > 2 ? 90 : volRatio > 1.5 ? 70 : volRatio > 1 ? 50 : 30) * W.volume * 0.6 +
          (obvVal > obvPrev * 1.003 ? 85 : obvVal < obvPrev * 0.997 ? 75 : 40) * W.obv * 0.4)
        ));
        const candlestick = Math.min(100, Math.round(
          patterns.length > 0 ? Math.min(95, 50 + patterns.length * 15) * W.patterns : 35
        ));
        const sr        = Math.min(100, Math.round(
          (atBbLower||atBbUpper ? 85 : bbSqueeze ? 60 : 40) * W.bb * 0.4 +
          (Math.abs(price - vwapVal) / vwapVal < 0.002 ? 90 : price > vwapVal ? 65 : 55) * 0.3 +
          (bullDiv||bearDiv ? 90 : 40) * W.divergence * 0.3
        ));
        const overall = confidence;
        return { trend, momentum, volume, candlestick, supportResistance: sr, overall };
      })(),
      signalStrength: (confidence >= 85 ? 'Very Strong' : confidence >= 72 ? 'Strong' : confidence >= 58 ? 'Moderate' : confidence >= 45 ? 'Weak' : 'Very Weak') as any,
      expectedHolding: tf === '1m' ? '5-30 mins' : tf === '5m' ? '30-120 mins' : tf === '15m' ? '1-4 hours' : tf === '1h' ? '4-24 hours' : tf === '4h' ? '1-3 days' : tf === '1d' ? '3-10 days' : '1-4 weeks',
      expectedMove: (atr14/price > 0) ? `${(direction === 'LONG' ? '+' : '-')}${(atr14/price*200).toFixed(1)}%–${(direction === 'LONG' ? '+' : '-')}${(atr14/price*400).toFixed(1)}%` : '',
      qualityScore: Math.min(100, Math.round(
        (candles.length >= 50 ? 30 : candles.length / 50 * 30) +
        (rsi14 > 0 ? 15 : 0) + (adxData.adx > 0 ? 15 : 0) + (macd.hist !== 0 ? 10 : 0) +
        (direction !== 'WAIT' ? 10 : 0) + (confidence > 0 ? 20 : 0)
      )),
      missingFeatures: [rsi14, adxData.adx, macd.hist].filter(v => !v || v === 0).length,
      decisionWeights: {
        trend: +(W.trend.toFixed(2)), rsi: +(W.rsi.toFixed(2)), macd: +(W.macd.toFixed(2)),
        bb: +(W.bb.toFixed(2)), stoch: +(W.stoch.toFixed(2)), volume: +(W.volume.toFixed(2)),
        patterns: +(W.patterns.toFixed(2)), adx: +(W.adx.toFixed(2)),
        williamsR: +(W.williamsR.toFixed(2)), obv: +(W.obv.toFixed(2)),
        divergence: +(W.divergence.toFixed(2)), mtfAlignment: +(W.mtfAlignment.toFixed(2))
      },
      summary: this.buildSummary(signal,direction,symbol,rsi14,macdBull,macdCrossedUp,macdCrossedDown,patterns,bullReasons,bearReasons,price,ema200,stoch.k,volRatio,adxData,willR,bullDiv,bearDiv,ml),
      lastUpdated: new Date().toLocaleTimeString('en-IN',{hour12:false})
    };
  }

  // ── All indicator math ─────────────────────────────────────
  wilderRsi(closes:number[],period=14):number {
    const n=closes.length; if(n<=period+1)return 50;
    const ch=closes.slice(1).map((v,i)=>v-closes[i]);
    let ag=ch.slice(0,period).filter(c=>c>0).reduce((a,b)=>a+b,0)/period;
    let al=ch.slice(0,period).filter(c=>c<0).reduce((a,b)=>a+Math.abs(b),0)/period;
    for(let i=period;i<ch.length;i++){const d=ch[i];ag=(ag*(period-1)+(d>0?d:0))/period;al=(al*(period-1)+(d<0?Math.abs(d):0))/period;}
    if(al===0)return 100; return 100-100/(1+ag/al);
  }

  rsiArray(closes:number[],period=14):number[] {
    const r:number[]=new Array(period).fill(50);
    const ch=closes.slice(1).map((v,i)=>v-closes[i]);
    if(ch.length<period)return r;
    let ag=ch.slice(0,period).filter(c=>c>0).reduce((a,b)=>a+b,0)/period;
    let al=ch.slice(0,period).filter(c=>c<0).reduce((a,b)=>a+Math.abs(b),0)/period;
    r.push(al===0?100:100-100/(1+ag/al));
    for(let i=period;i<ch.length;i++){const d=ch[i];ag=(ag*(period-1)+(d>0?d:0))/period;al=(al*(period-1)+(d<0?Math.abs(d):0))/period;r.push(al===0?100:100-100/(1+ag/al));}
    return r;
  }

  private bullishDivergence(closes:number[],rsiArr:number[]):boolean {
    const n=closes.length; if(n<20)return false;
    const curLow=Math.min(...closes.slice(-10)); const prevLow=Math.min(...closes.slice(-20,-10));
    const curRsiLow=Math.min(...rsiArr.slice(-10)); const prevRsiLow=Math.min(...rsiArr.slice(-20,-10));
    return curLow<prevLow*0.998 && curRsiLow>prevRsiLow+2 && curRsiLow<45;
  }

  private bearishDivergence(closes:number[],rsiArr:number[]):boolean {
    const n=closes.length; if(n<20)return false;
    const curHigh=Math.max(...closes.slice(-10)); const prevHigh=Math.max(...closes.slice(-20,-10));
    const curRsiHigh=Math.max(...rsiArr.slice(-10)); const prevRsiHigh=Math.max(...rsiArr.slice(-20,-10));
    return curHigh>prevHigh*1.002 && curRsiHigh<prevRsiHigh-2 && curRsiHigh>55;
  }

  ema(data:number[],period:number):number {
    if(data.length<period)return data[data.length-1]||0;
    const k=2/(period+1); let v=data.slice(0,period).reduce((a,b)=>a+b,0)/period;
    for(let i=period;i<data.length;i++)v=data[i]*k+v*(1-k); return v;
  }

  emaArray(data:number[],period:number):number[] {
    if(data.length<period)return data.map(()=>0);
    const k=2/(period+1); const r:number[]=new Array(period-1).fill(0);
    let v=data.slice(0,period).reduce((a,b)=>a+b,0)/period; r.push(v);
    for(let i=period;i<data.length;i++){v=data[i]*k+v*(1-k);r.push(v);} return r;
  }

  bollingerBands(closes:number[],period=20,mult=2){
    const sl=closes.slice(-period); const mid=sl.reduce((a,b)=>a+b,0)/period;
    const std=Math.sqrt(sl.reduce((a,b)=>a+Math.pow(b-mid,2),0)/period);
    return{upper:mid+mult*std,mid,lower:mid-mult*std};
  }

  bollingerArray(closes:number[],period=20,mult=2):{upper:number[];mid:number[];lower:number[]} {
    const upper:number[]=[],mid:number[]=[],lower:number[]=[];
    for(let i=0;i<closes.length;i++){
      if(i<period){upper.push(0);mid.push(0);lower.push(0);continue;}
      const sl=closes.slice(i-period,i);const m=sl.reduce((a,b)=>a+b,0)/period;
      const std=Math.sqrt(sl.reduce((a,b)=>a+Math.pow(b-m,2),0)/period);
      upper.push(m+mult*std);mid.push(m);lower.push(m-mult*std);
    }
    return{upper,mid,lower};
  }

  private macdFull(closes:number[]){
    const e12=this.emaArray(closes,12); const e26=this.emaArray(closes,26);
    const ma=e12.map((v,i)=>v-e26[i]).filter(v=>v!==0);
    const ml=e12[e12.length-1]-e26[e26.length-1];
    const sig=this.ema(ma.length>=9?ma:[ml],9);
    return{macd:ml,signal:sig,hist:ml-sig};
  }

  atr(highs:number[],lows:number[],closes:number[],period=14):number {
    const n=closes.length; if(n<2)return 0;
    const trs=highs.slice(-period).map((h,i)=>{const lo=lows[n-period+i];const pc=i===0?closes[n-period-1]:closes[n-period+i-1];return Math.max(h-lo,Math.abs(h-pc),Math.abs(lo-pc));});
    return trs.reduce((a,b)=>a+b,0)/period;
  }

  private stochastic(highs:number[],lows:number[],closes:number[],k=14,d=3){
    const n=closes.length; if(n<k)return{k:50,d:50};
    const kVals:number[]=[];
    for(let i=k-1;i<n;i++){const wH=Math.max(...highs.slice(i-k+1,i+1));const wL=Math.min(...lows.slice(i-k+1,i+1));kVals.push(wH===wL?50:((closes[i]-wL)/(wH-wL))*100);}
    const kLast=kVals[kVals.length-1];
    const dLast=kVals.slice(-d).reduce((a,b)=>a+b,0)/Math.min(d,kVals.length);
    return{k:kLast,d:dLast};
  }

  private adx(highs:number[],lows:number[],closes:number[],period=14):{adx:number;diPlus:number;diMinus:number} {
    const n=closes.length; if(n<period+1)return{adx:20,diPlus:20,diMinus:20};
    const trA:number[]=[],pmA:number[]=[],nmA:number[]=[];
    for(let i=1;i<n;i++){
      const tr=Math.max(highs[i]-lows[i],Math.abs(highs[i]-closes[i-1]),Math.abs(lows[i]-closes[i-1]));
      const pm=highs[i]-highs[i-1];const nm=lows[i-1]-lows[i];
      trA.push(tr);pmA.push(pm>nm&&pm>0?pm:0);nmA.push(nm>pm&&nm>0?nm:0);
    }
    const atrV=trA.slice(-period).reduce((a,b)=>a+b,0)/period;
    const diP=(pmA.slice(-period).reduce((a,b)=>a+b,0)/period/(atrV||1))*100;
    const diN=(nmA.slice(-period).reduce((a,b)=>a+b,0)/period/(atrV||1))*100;
    const dxArr:number[]=[];
    for(let i=0;i<trA.length;i++){const at=trA.slice(Math.max(0,i-period+1),i+1).reduce((a,b)=>a+b,0)/period;const dp=(pmA.slice(Math.max(0,i-period+1),i+1).reduce((a,b)=>a+b,0)/period/(at||1))*100;const dn=(nmA.slice(Math.max(0,i-period+1),i+1).reduce((a,b)=>a+b,0)/period/(at||1))*100;dxArr.push(Math.abs(dp-dn)/(dp+dn+0.001)*100);}
    const adxV=dxArr.slice(-period).reduce((a,b)=>a+b,0)/period;
    return{adx:adxV,diPlus:diP,diMinus:diN};
  }

  private williamsR(highs:number[],lows:number[],closes:number[],period=14):number {
    const n=closes.length; if(n<period)return -50;
    const hh=Math.max(...highs.slice(-period));const ll=Math.min(...lows.slice(-period));
    return hh===ll?-50:((hh-closes[n-1])/(hh-ll))*-100;
  }

  private obv(closes:number[],vols:number[]):number {
    let o=0; for(let i=1;i<closes.length;i++){if(closes[i]>closes[i-1])o+=vols[i];else if(closes[i]<closes[i-1])o-=vols[i];} return o;
  }

  private vwap(candles:OHLCV[]):number {
    let cv=0,v=0;
    candles.slice(-Math.min(candles.length,100)).forEach(c=>{const tp=(c.high+c.low+c.close)/3;cv+=tp*c.volume;v+=c.volume;});
    return v===0?candles[candles.length-1].close:cv/v;
  }

  private srLevels(highs:number[],lows:number[]){
    const lb=5;
    const res=highs.filter((v,i,a)=>i>=lb&&i<a.length-lb&&a.slice(i-lb,i+lb+1).every(x=>x<=v)).slice(-6).sort((a,b)=>a-b);
    const sup=lows.filter((v,i,a)=>i>=lb&&i<a.length-lb&&a.slice(i-lb,i+lb+1).every(x=>x>=v)).slice(-6).sort((a,b)=>a-b);
    return{resistances:res,supports:sup};
  }

  detectPatterns(candles:OHLCV[]):string[]{
    const p:string[]=[]; const n=candles.length; if(n<4)return['⚪ Insufficient Data'];
    const c=candles[n-1],prev=candles[n-2],pp=candles[n-3];
    const body=(x:OHLCV)=>Math.abs(x.close-x.open);
    const rng=(x:OHLCV)=>(x.high-x.low)||0.0001;
    const bull=(x:OHLCV)=>x.close>x.open;
    const bear=(x:OHLCV)=>x.close<x.open;
    const uw=(x:OHLCV)=>x.high-Math.max(x.open,x.close);
    const lw=(x:OHLCV)=>Math.min(x.open,x.close)-x.low;
    const mid=(x:OHLCV)=>(x.open+x.close)/2;
    if(body(c)<rng(c)*0.07)p.push('⚪ Doji (Indecision)');
    if(lw(c)>body(c)*2&&uw(c)<body(c)*0.5&&bull(c)&&lw(c)>rng(c)*0.55)p.push('🔨 Hammer (Bullish Reversal)');
    if(uw(c)>body(c)*2&&lw(c)<body(c)*0.5&&bear(c)&&uw(c)>rng(c)*0.55)p.push('⭐ Shooting Star (Bearish)');
    if(body(c)>rng(c)*0.85&&bull(c))p.push('💪 Bullish Marubozu (Strong Buy)');
    if(body(c)>rng(c)*0.85&&bear(c))p.push('💀 Bearish Marubozu (Strong Sell)');
    if(lw(c)>rng(c)*0.6&&uw(c)<rng(c)*0.1&&body(c)<rng(c)*0.2)p.push('🐉 Dragonfly Doji (Bullish)');
    if(uw(c)>rng(c)*0.6&&lw(c)<rng(c)*0.1&&body(c)<rng(c)*0.2)p.push('💎 Gravestone Doji (Bearish)');
    if(bear(prev)&&bull(c)&&c.open<prev.close&&c.close>prev.open&&body(c)>body(prev)*0.8)p.push('🟢 Bullish Engulfing (Reversal)');
    if(bull(prev)&&bear(c)&&c.open>prev.close&&c.close<prev.open&&body(c)>body(prev)*0.8)p.push('🔴 Bearish Engulfing (Reversal)');
    if(bear(prev)&&bull(c)&&c.open<prev.close&&c.close>mid(prev)&&c.close<prev.open)p.push('🔆 Piercing Line (Bullish)');
    if(bull(prev)&&bear(c)&&c.open>prev.close&&c.close<mid(prev)&&c.close>prev.open)p.push('☁️ Dark Cloud Cover (Bearish)');
    if(Math.abs(c.low-prev.low)<rng(c)*0.04&&bull(c)&&bear(prev))p.push('📌 Tweezer Bottom (Bullish)');
    if(Math.abs(c.high-prev.high)<rng(c)*0.04&&bear(c)&&bull(prev))p.push('📌 Tweezer Top (Bearish)');
    if(bear(pp)&&body(prev)<rng(prev)*0.3&&bull(c)&&c.close>mid(pp)&&c.open<pp.close)p.push('🌅 Morning Star (Strong Bullish)');
    if(bull(pp)&&body(prev)<rng(prev)*0.3&&bear(c)&&c.close<mid(pp)&&c.open>pp.close)p.push('🌆 Evening Star (Strong Bearish)');
    if(bull(pp)&&bull(prev)&&bull(c)&&prev.close>pp.close&&c.close>prev.close&&body(c)>rng(c)*0.5)p.push('🚀 Three White Soldiers (Strong Bull)');
    if(bear(pp)&&bear(prev)&&bear(c)&&prev.close<pp.close&&c.close<prev.close&&body(c)>rng(c)*0.5)p.push('🐦 Three Black Crows (Strong Bear)');
    return p.length?p:['⚪ No Clear Pattern'];
  }

  private buildSummary(signal:string,direction:string,sym:string,rsi:number,macdBull:boolean,macdUp:boolean,macdDown:boolean,pats:string[],bullR:string[],bearR:string[],price:number,ema200:number,stochK:number,volR:number,adx:{adx:number},willR:number,bullDiv:boolean,bearDiv:boolean,ml:MLMemory|null):string {
    const trend=price>ema200?'above EMA200 (bull bias)':'below EMA200 (bear bias)';
    const vol=volR>1.5?`high volume (${volR.toFixed(1)}x avg)`:'average volume';
    const adxStr=adx.adx>25?`ADX ${adx.adx.toFixed(0)} strong trend`:`ADX ${adx.adx.toFixed(0)} weak trend`;
    const divStr=bullDiv?'⚡ Bullish RSI divergence.':bearDiv?'⚡ Bearish RSI divergence.':'';
    const mlStr=ml&&ml.totalCalls>=5?` [ML: ${ml.winRate.toFixed(0)}% WR on ${ml.totalCalls} calls]`:'';
    const topPats=pats.filter(p=>!p.includes('No Clear')&&!p.includes('Doji')&&!p.includes('Insufficient')).slice(0,2).map(p=>p.replace(/[^\w\s\(\)]/g,'').trim()).join(', ');
    if(direction==='LONG'){const r=bullR.slice(0,4).join(' · ');return`📈 ${sym} ${signal} — Price ${trend}. RSI ${rsi.toFixed(0)}, ${adxStr}, ${vol}. Key signals: ${r||'multiple bullish confluences'}. ${divStr}${topPats?'Patterns: '+topPats+'.':''} Strategy: Enter LONG. SL at 1.5×ATR below support. Scale out 50% T1, 30% T2, trail 20% to T3.${mlStr}`;}
    if(direction==='SHORT'){const r=bearR.slice(0,4).join(' · ');return`📉 ${sym} ${signal} — Price ${trend}. RSI ${rsi.toFixed(0)}, ${adxStr}, ${vol}. Key signals: ${r||'multiple bearish confluences'}. ${divStr}${topPats?'Patterns: '+topPats+'.':''} Strategy: SHORT entry. SL above resistance. Cover 50% T1, trail rest.${mlStr}`;}
    return`↔️ ${sym} consolidating — ${adxStr}. RSI ${rsi.toFixed(0)} neutral. Wait for MACD cross + volume confirmation.${mlStr}`;
  }

  round(v:number):number {
    if(v>=100000)return +v.toFixed(0);
    if(v>=10000)return +v.toFixed(1);
    if(v>=1000)return +v.toFixed(2);
    if(v>=1)return +v.toFixed(4);
    return +v.toFixed(8);
  }

  private emptyCall(candles:OHLCV[],tf:string):TradeCall {
    const p=candles[candles.length-1]?.close||0;
    return{direction:'WAIT',signal:'NEUTRAL',confidence:40,entry:p,stopLoss:p,target1:p,target2:p,target3:p,rsi:50,rsiZone:'⚪ Neutral',macdLine:0,macdSignal:0,macdHist:0,macdBull:false,ema9:p,ema20:p,ema50:p,ema200:p,trendEMA:'↔️ Sideways',bbUpper:p,bbMid:p,bbLower:p,bbSignal:'⚪ Normal',vwap:p,vwapSignal:'⚪ Neutral',stochK:50,stochD:50,stochZone:'⚪ Neutral',adx:20,adxTrend:'😴 Weak',williamsR:-50,williamsZone:'⚪ Neutral',obv:0,obvSignal:'⚪ Neutral',atr:0,atrPct:0,volumeSignal:'⚪ Average',volumeRatio:1,patterns:['⚠️ Need 30+ candles'],supports:[],resistances:[],rrRatio:'1:2.8',riskPct:0,timeframe:tf,risk:'HIGH',bullReasons:[],bearReasons:[],summary:'Need more candles for accurate analysis.',lastUpdated:''};
  }
}
