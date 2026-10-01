import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, forkJoin, of, map, catchError, switchMap } from 'rxjs';
import { TaEngineService, OHLCV, TradeCall } from './ta-engine.service';
import { SmcEngineService } from './smc-engine.service';
import { VolumeProfileService } from './volume-profile.service';
import { MarketRegimeService } from './market-regime.service';

export interface BacktestTrade {
  entryTime:    number;
  exitTime:     number;
  entryPrice:   number;
  exitPrice:    number;
  direction:    'LONG' | 'SHORT';
  signal:       string;
  confidence:   number;
  stopLoss:     number;
  target1:      number;
  target2:      number;
  target3:      number;
  result:       'HIT_T1' | 'HIT_T2' | 'HIT_T3' | 'HIT_SL' | 'EXPIRED';
  pnlPct:       number;       // % gain or loss
  pnlR:         number;       // gain/loss in R multiples
  holdBars:     number;       // how many candles held
  regime?:      string;
  smcBias?:     string;
  vpLocation?:  string;
}

export interface BacktestResult {
  symbol:           string;
  timeframe:        string;
  startDate:        string;
  endDate:          string;
  totalCandles:     number;
  totalTrades:      number;
  winningTrades:    number;
  losingTrades:     number;
  winRate:          number;
  // P&L
  totalPnlPct:      number;
  totalPnlR:        number;
  avgWinPct:        number;
  avgLossPct:       number;
  avgWinR:          number;
  avgLossR:         number;
  // Risk metrics
  sharpeRatio:      number;
  sortinoRatio:     number;
  profitFactor:     number;
  maxDrawdownPct:   number;
  maxDrawdownR:     number;
  expectancy:       number;   // avg R per trade
  calmarRatio:      number;   // annualized return / max drawdown
  // Streaks
  maxWinStreak:     number;
  maxLossStreak:    number;
  avgHoldBars:      number;
  // Equity curve
  equityCurve:      { time: number; equity: number; drawdown: number }[];
  // Trades list
  trades:           BacktestTrade[];
  // Per-regime stats
  regimeStats:      { regime: string; trades: number; winRate: number; avgR: number }[];
  // Monthly stats
  monthlyStats:     { month: string; trades: number; winRate: number; pnlPct: number }[];
  // Grade
  grade:            'A+' | 'A' | 'B+' | 'B' | 'C' | 'D' | 'F';
  gradeColor:       string;
  gradeReason:      string;
  summary:          string;
}

export interface BacktestConfig {
  symbol:         string;       // e.g. BTC, ETH, RELIANCE
  market:         'CRYPTO' | 'STOCK' | 'FOREX';
  timeframe:      string;       // 1h, 4h, 1d
  startYear:      number;       // e.g. 2020
  endYear:        number;       // e.g. 2024
  riskPerTrade:   number;       // % per trade (1.0 = 1%)
  minConfidence:  number;       // only take signals above this (e.g. 60)
  useSMC:         boolean;
  useVP:          boolean;
  useRegime:      boolean;
  regimeFilter:   string[];     // only trade these regimes, empty = all
}

/**
 * P1-19 fix ("Backtest (frontend) is systematically optimistic and uses a different engine than
 * live" -- external review, confirmed real by direct inspection of simulateTrade/runStrategyAsync
 * below before any fix was attempted: no fees or slippage were modeled at all; when SL and a
 * target both touched within the same candle the code credited the FARTHEST target reached
 * (T3 checked before T2 before T1, before SL) instead of the conservative SL-first assumption any
 * honest backtest must make on an ambiguous bar; trades held all the way to T2/T3 even though this
 * application's real, live exit mechanism is a single OCO order that exits at T1 (or SL) only --
 * see PositionSafetyService's own OCO placement, which never re-arms a second leg past T1; and the
 * equity-curve update `equity *= (1 + trade.pnlPct * (riskPerTrade/100) / 100)` multiplied a raw
 * PRICE-move percentage by a risk-percentage, which is not what real fixed-fractional position
 * sizing does at all (real sizing risks `riskPerTrade%` of equity on a trade whose STOP distance
 * defines quantity, so the equity impact of a completed trade is `riskPerTrade% * pnlR`, not
 * `riskPerTrade% * pnlPct`) -- together these four bugs each independently inflate backtest
 * results, and compound.
 *
 * The fix actually shipped in this pass, scoped honestly:
 *  1. Fee + slippage modeling on both entry and exit, using the SAME disclosed constants this
 *     codebase's own PaperBrokerAdapter already uses for its simulated fills (taker fee 0.10% per
 *     side, slippage 0.05% against the trade direction) -- see FEE_RATE/SLIPPAGE_RATE below.
 *  2. SL-first on ambiguous bars: if a candle touches BOTH the stop and any target, the stop is
 *     now always credited -- never a target, regardless of which price level the candle's high/low
 *     technically also reached.
 *  3. T1-only exit: simulateTrade now exits at the FIRST of {SL, T1} touched, exactly matching
 *     what this application's live OCO exit mechanism can actually achieve (see
 *     PositionSafetyService's own OCO-placement javadoc) -- T2/T3 are still recorded on the
 *     returned BacktestTrade (useful context for the user), but a trade can no longer be scored,
 *     nor equity impacted, as though it captured a T2/T3 exit that live trading structurally
 *     cannot reach.
 *  4. Equity math corrected to real fixed-fractional sizing: `equity *= (1 + (riskPerTrade/100) *
 *     pnlR)`, using the trade's own R-multiple (already computed correctly elsewhere in this file)
 *     rather than its raw price-percentage move.
 *
 * Deliberately NOT attempted in this pass, disclosed rather than silently skipped (same honesty
 * precedent as this codebase's own backend fixes, e.g. PositionMonitorService's checkDrawdown
 * javadoc): genuine engine parity with the live `ServerSignalEngine` (a large, separate Java
 * class this frontend's TaEngineService/SmcEngineService/MarketRegimeService do not share a single
 * line of code with) is NOT achieved here. Truly closing that gap means either porting
 * ServerSignalEngine's full decision logic to TypeScript and keeping the two in permanent lockstep,
 * or standing up a genuinely new server-side backtest endpoint that replays historical candles
 * through the real Java engine -- either is a materially larger undertaking than a bug-fix pass
 * (new service design, new API surface, and its own test suite), not a fix to the four concrete
 * numerical distortions above. Because this gap remains open, `BacktestResult.summary` now always
 * carries an explicit disclaimer (see calcMetrics/emptyResult below) so a user reading a backtest
 * grade is told, in the result itself, that it approximates but does not guarantee live behavior --
 * directly addressing the audit's own stated danger ("Users will authorize LIVE based on inflated
 * results") even though the underlying engine-parity gap itself is not closed by this pass.
 */
@Injectable({ providedIn: 'root' })
export class BacktestService {
  private http   = inject(HttpClient);
  private ta     = inject(TaEngineService);
  private smc    = inject(SmcEngineService);
  private vp     = inject(VolumeProfileService);
  private regime = inject(MarketRegimeService);

  // P1-19 fix: the same disclosed simulated-fill constants PaperBrokerAdapter.java already uses
  // (SIMULATED_TAKER_FEE_RATE / SIMULATED_SLIPPAGE_RATE) -- kept numerically identical so a paper
  // run and a backtest run of the same strategy are at least modeling execution costs consistently
  // with each other, even though the signal-generation engines themselves still differ (see this
  // class's own javadoc above).
  private static readonly FEE_RATE      = 0.001;   // 0.10% taker fee, per side (entry + exit)
  private static readonly SLIPPAGE_RATE = 0.0005;  // 0.05% adverse slippage, per side

  // Progress callback
  progress = 0;
  progressMessage = '';

  run(config: BacktestConfig): Observable<BacktestResult> {
    this.progress = 0;
    this.progressMessage = `Fetching historical data for ${config.symbol}...`;
    return this.fetchAllCandles(config).pipe(
      switchMap(candles => {
        if (candles.length < 100) throw new Error('Not enough historical data — check symbol name');
        this.progressMessage = `Analyzing ${candles.length} candles...`;
        // Run in async chunks to keep browser responsive
        return this.runStrategyAsync(candles, config);
      })
    );
  }

  private runStrategyAsync(candles: OHLCV[], config: BacktestConfig): Observable<BacktestResult> {
    return new Observable<BacktestResult>(observer => {
      const trades: BacktestTrade[] = [];
      const warmup    = 50;
      let equity      = 100;
      const equityCurve: { time:number; equity:number; drawdown:number }[] = [];
      let peakEquity  = 100;
      let i = warmup;
      const total = candles.length - 10;

      const processChunk = () => {
        const chunkSize = 50; // process 50 candles per frame
        const end = Math.min(i + chunkSize, total);

        for (; i < end; i++) {
          this.progress = Math.round((i / total) * 80);

          const historicCandles = candles.slice(0, i + 1);
          const call = this.generateSignal(historicCandles, config);

          if (!call || call.direction === 'WAIT') continue;
          if (call.confidence < config.minConfidence) continue;
          if (config.market === 'CRYPTO' && call.direction === 'SHORT') {
            const regime = call.regime || '';
            if (!regime.includes('BEAR') && !regime.includes('POST_BREAKOUT_BEAR')) continue;
          }
          if (config.regimeFilter.length > 0 && call.regime) {
            if (!config.regimeFilter.includes(call.regime)) continue;
          }

          const trade = this.simulateTrade(candles, i, call, config);
          if (!trade) continue;

          trades.push(trade);
          // P1-19 fix (full context in this class's own javadoc above): real fixed-fractional
          // position sizing risks riskPerTrade% of equity per trade, sized by the STOP distance --
          // so a completed trade's equity impact is riskPerTrade% * pnlR (the trade's own
          // R-multiple), never riskPerTrade% * pnlPct (a raw price-move percentage, which was the
          // pre-fix formula here and is not what position sizing by risk actually computes).
          equity *= (1 + (config.riskPerTrade / 100) * trade.pnlR);
          peakEquity = Math.max(peakEquity, equity);
          const drawdown = ((peakEquity - equity) / peakEquity) * 100;
          equityCurve.push({ time: trade.entryTime, equity: +equity.toFixed(3), drawdown: +drawdown.toFixed(2) });
          i += Math.max(1, Math.floor(trade.holdBars * 0.5));
        }

        this.progressMessage = `Analyzed ${Math.round(i/total*100)}% of candles... (${trades.length} signals found)`;

        if (i < total) {
          // Schedule next chunk — yields to browser between chunks
          setTimeout(processChunk, 0);
        } else {
          // Done — calculate metrics
          this.progress = 90;
          this.progressMessage = 'Calculating performance metrics...';
          setTimeout(() => {
            try {
              const result = this.calcMetrics(trades, equityCurve, candles, config);
              observer.next(result);
              observer.complete();
            } catch(e: any) {
              observer.error(e);
            }
          }, 50);
        }
      };

      setTimeout(processChunk, 10);
    });
  }

  private fetchAllCandles(config: BacktestConfig): Observable<OHLCV[]> {
    if (config.market === 'CRYPTO') {
      return this.fetchCryptoHistory(config.symbol, config.timeframe, config.startYear, config.endYear);
    } else if (config.market === 'STOCK') {
      return this.fetchStockHistory(config.symbol, config.timeframe);
    }
    return of([]);
  }

  // Fetch multiple batches from Binance to get full history
  private fetchCryptoHistory(symbol: string, tf: string, startYear: number, endYear: number): Observable<OHLCV[]> {
    const binanceSymbol = symbol.toUpperCase() + 'USDT';
    const startTs = new Date(`${startYear}-01-01`).getTime();
    const endTs   = new Date(`${endYear}-12-31`).getTime();

    // Batch fetches: Binance allows 1000 candles per request
    const tfMs: Record<string,number> = {
      '1m':60000,'5m':300000,'15m':900000,'30m':1800000,
      '1h':3600000,'4h':14400000,'1d':86400000,'1w':604800000
    };
    const candleMs    = tfMs[tf] || 3600000;
    const batchSize   = 1000;
    const batchMs     = batchSize * candleMs;
    const batches: number[] = [];

    let t = startTs;
    while (t < endTs) {
      batches.push(t);
      t += batchMs;
    }

    // Fetch up to 5 batches max (performance)
    const maxBatches = Math.min(batches.length, 5);
    const requests = batches.slice(0, maxBatches).map(startTime =>
      this.http.get<any[]>(
        `/binance-spot/api/v3/klines?symbol=${binanceSymbol}&interval=${tf}&startTime=${startTime}&limit=1000`
      ).pipe(
        catchError(() => of([])),
        map((raw: any[]) => raw.map(k => ({
          time:   Math.floor(k[0] / 1000),
          open:   parseFloat(k[1]),
          high:   parseFloat(k[2]),
          low:    parseFloat(k[3]),
          close:  parseFloat(k[4]),
          volume: parseFloat(k[5]),
        })))
      )
    );

    return forkJoin(requests).pipe(
      map(batches => {
        const all = batches.flat();
        // Deduplicate by time
        const seen = new Set<number>();
        return all.filter(c => { if (seen.has(c.time)) return false; seen.add(c.time); return true; })
                  .sort((a, b) => a.time - b.time);
      })
    );
  }

  private fetchStockHistory(symbol: string, tf: string): Observable<OHLCV[]> {
    const range = tf === '1d' ? '5y' : '2y';
    const interval = tf === '4h' ? '1h' : tf; // Yahoo doesn't have 4h
    return this.http.get<any>(
      `/yf-api/v8/finance/chart/${symbol}.NS?range=${range}&interval=${interval}&events=div&lang=en-US`
    ).pipe(
      catchError(() => of(null)),
      map(data => {
        if (!data?.chart?.result?.[0]) return [];
        const r = data.chart.result[0];
        const ts = r.timestamp || [];
        const q  = r.indicators?.quote?.[0] || {};
        return ts.map((t: number, i: number) => ({
          time: t, open: q.open?.[i]||0, high: q.high?.[i]||0,
          low: q.low?.[i]||0, close: q.close?.[i]||0, volume: q.volume?.[i]||0,
        })).filter((c: any) => c.close > 0);
      })
    );
  }

  // ── Core strategy runner ──────────────────────────────────


  private generateSignal(candles: OHLCV[], config: BacktestConfig): TradeCall | null {
    if (candles.length < 50) return null;
    try {
      let smcData: any = undefined;
      let vpData:  any = undefined;
      let regimeData: any = undefined;

      if (config.useSMC) {
        const smcResult = this.smc.analyze(candles, config.symbol);
        smcData = { bias: smcResult.bias, biasStrength: smcResult.biasStrength,
          entrySetup: smcResult.entrySetup, structureBreaks: smcResult.structureBreaks, orderBlocks: smcResult.orderBlocks };
      }
      if (config.useVP) {
        const vpResult = this.vp.analyze(candles);
        vpData = { priceLocation: vpResult.priceLocation, poc: vpResult.poc, vah: vpResult.vah, val: vpResult.val, bias: vpResult.bias, nearestHVN: vpResult.nearestHVN };
      }
      if (config.useRegime) {
        const reg = this.regime.detect(candles, config.symbol);
        regimeData = { regime: reg.regime, label: reg.label, emoji: reg.emoji, color: reg.color, confidence: reg.confidence, riskMultiplier: reg.strategy.riskMultiplier, slMultiplier: reg.strategy.slMultiplier, weightAdjustments: reg.weightAdjustments, strategy: reg.strategy, warnings: reg.warnings };
      }

      const result = this.ta.analyzeWithMTF(candles, config.symbol, config.timeframe, undefined, undefined, config.market, smcData, undefined, vpData, regimeData);
      
      // Quality filter: require at least 2 bull/bear reasons for a valid signal
      if (result.direction !== 'WAIT') {
        const reasons = result.direction === 'LONG' ? result.bullReasons : result.bearReasons;
        if (!reasons || reasons.length < 2) return null;
      }
      
      return result;
    } catch { return null; }
  }

  private simulateTrade(candles: OHLCV[], entryIdx: number, call: TradeCall, config: BacktestConfig): BacktestTrade | null {
    const entryCandle = candles[entryIdx];
    const rawEntry = call.entry;
    const sl       = call.stopLoss;
    const t1       = call.target1;
    const t2       = call.target2;
    const t3       = call.target3;
    const isLong   = call.direction === 'LONG';
    const maxHold  = 50; // max candles to hold

    // P1-19 fix (full context in this class's own javadoc above): the entry itself now pays
    // slippage too, same direction a real market order's slippage moves (a LONG entry fills
    // slightly WORSE, i.e. higher; a SHORT entry fills slightly lower) -- matching
    // PaperBrokerAdapter's own SIMULATED_SLIPPAGE_RATE direction convention exactly.
    const entry = isLong
      ? rawEntry * (1 + BacktestService.SLIPPAGE_RATE)
      : rawEntry * (1 - BacktestService.SLIPPAGE_RATE);

    let result: BacktestTrade['result'] = 'EXPIRED';
    let rawExitPrice = candles[Math.min(entryIdx + maxHold - 1, candles.length-1)].close; // exit at close if expired
    let exitTime   = entryCandle.time;
    let holdBars   = 0;

    for (let j = entryIdx + 1; j < Math.min(entryIdx + maxHold, candles.length); j++) {
      const c = candles[j];
      holdBars = j - entryIdx;

      // P1-19 fix (full context in this class's own javadoc above): two changes from the
      // pre-fix version here, both making this loop match what this application's real, live
      // exit mechanism (a single OCO order) can actually achieve --
      //  (a) SL-first on an ambiguous bar: if the candle touches BOTH the stop and T1 (the only
      //      target a live OCO can ever capture), the stop is credited, full stop. No more
      //      checking T3/T2 first regardless of whether SL was also touched.
      //  (b) T1 is now the only reachable profit exit -- once T1 is touched (and SL was not, or
      //      was touched on a later, separate candle), the trade exits at T1; T2/T3 are no longer
      //      treated as reachable results, since the live OCO this backtest is supposed to
      //      approximate never re-arms past T1.
      if (isLong) {
        const hitSL = c.low <= sl;
        const hitT1 = c.high >= t1;
        if (hitSL) { result = 'HIT_SL'; rawExitPrice = sl; exitTime = c.time; break; }
        if (hitT1) { result = 'HIT_T1'; rawExitPrice = t1; exitTime = c.time; break; }
      } else {
        const hitSL = c.high >= sl;
        const hitT1 = c.low <= t1;
        if (hitSL) { result = 'HIT_SL'; rawExitPrice = sl; exitTime = c.time; break; }
        if (hitT1) { result = 'HIT_T1'; rawExitPrice = t1; exitTime = c.time; break; }
      }
    }

    // P1-19 fix: exit slippage too, same direction convention as entry above -- a LONG exit
    // (a sell) fills slightly WORSE (lower); a SHORT exit (a buy-to-cover) fills slightly higher.
    // An EXPIRED exit (closed at the maxHold candle's own close, not a stop/target level) still
    // pays this same cost, since it is still a real market order in live trading.
    const exitPrice = isLong
      ? rawExitPrice * (1 - BacktestService.SLIPPAGE_RATE)
      : rawExitPrice * (1 + BacktestService.SLIPPAGE_RATE);

    const grossPnlPct = isLong
      ? (exitPrice - entry) / entry * 100
      : (entry - exitPrice) / entry * 100;
    // P1-19 fix: taker fee charged on both legs (entry + exit), in percentage-of-notional terms --
    // matching PaperBrokerAdapter's own per-side FEE_RATE.
    const feePct = BacktestService.FEE_RATE * 100 * 2;
    const pnlPct = grossPnlPct - feePct;

    const slDist = Math.abs(entry - sl);
    const grossPnlR = slDist > 0 ? (isLong ? exitPrice - entry : entry - exitPrice) / slDist : 0;
    const pnlR = slDist > 0 ? grossPnlR - (feePct / 100) * (entry / slDist) : 0;

    return {
      entryTime: entryCandle.time, exitTime,
      entryPrice: entry, exitPrice,
      direction: call.direction as 'LONG'|'SHORT',
      signal: call.signal, confidence: call.confidence,
      stopLoss: sl, target1: t1, target2: t2, target3: t3,
      result, pnlPct: +pnlPct.toFixed(3), pnlR: +pnlR.toFixed(3),
      holdBars,
      regime: call.regime,
      smcBias: call.smcBias,
      vpLocation: call.vpLocation
    };
  }

  private calcMetrics(trades: BacktestTrade[], equityCurve: { time:number; equity:number; drawdown:number }[], candles: OHLCV[], config: BacktestConfig): BacktestResult {
    if (!trades.length) return this.emptyResult(config, candles);

    const wins   = trades.filter(t => t.pnlR > 0);
    const losses = trades.filter(t => t.pnlR <= 0);
    const winRate = trades.length > 0 ? (wins.length / trades.length) * 100 : 0;

    const winPnls  = wins.map(t => t.pnlPct);
    const lossPnls = losses.map(t => t.pnlPct);
    const winRs    = wins.map(t => t.pnlR);
    const lossRs   = losses.map(t => t.pnlR);

    const avgWinPct  = winPnls.length ? winPnls.reduce((a,b)=>a+b,0)/winPnls.length : 0;
    const avgLossPct = lossPnls.length ? lossPnls.reduce((a,b)=>a+b,0)/lossPnls.length : 0;
    const avgWinR    = winRs.length ? winRs.reduce((a,b)=>a+b,0)/winRs.length : 0;
    const avgLossR   = lossRs.length ? Math.abs(lossRs.reduce((a,b)=>a+b,0)/lossRs.length) : 1;

    const totalGross  = winPnls.reduce((a,b)=>a+b,0);
    const totalGrossL = Math.abs(lossPnls.reduce((a,b)=>a+b,0));
    const profitFactor = totalGrossL > 0 ? totalGross / totalGrossL : totalGross > 0 ? 99 : 0;
    const expectancy   = (winRate/100 * avgWinR) - ((1-winRate/100) * avgLossR);
    const totalPnlPct  = equityCurve.length ? equityCurve[equityCurve.length-1].equity - 100 : 0;
    const totalPnlR    = trades.reduce((a,b)=>a+b.pnlR,0);
    const maxDrawdownPct = equityCurve.length ? Math.max(...equityCurve.map(e=>e.drawdown)) : 0;
    const maxDrawdownR   = Math.abs(Math.min(...trades.map(t=>t.pnlR)));
    const avgHoldBars    = trades.reduce((a,b)=>a+b.holdBars,0)/trades.length;

    // Sharpe & Sortino (approximate using trade R values)
    const rValues = trades.map(t => t.pnlR);
    const meanR   = rValues.reduce((a,b)=>a+b,0)/rValues.length;
    const stdR    = Math.sqrt(rValues.reduce((a,b)=>a+Math.pow(b-meanR,2),0)/rValues.length);
    const downR   = rValues.filter(r=>r<0);
    const downStd = downR.length ? Math.sqrt(downR.reduce((a,b)=>a+b*b,0)/downR.length) : 0.001;
    const sharpeRatio  = stdR > 0 ? meanR / stdR * Math.sqrt(252) : 0;
    const sortinoRatio = downStd > 0 ? meanR / downStd * Math.sqrt(252) : meanR * 10;
    const calmarRatio  = maxDrawdownPct > 0 ? (totalPnlPct / maxDrawdownPct) : 0;

    // Win/loss streaks
    let maxWin=0, maxLoss=0, curWin=0, curLoss=0;
    trades.forEach(t => {
      if (t.pnlR > 0) { curWin++; curLoss=0; maxWin=Math.max(maxWin,curWin); }
      else             { curLoss++; curWin=0; maxLoss=Math.max(maxLoss,curLoss); }
    });

    // Per-regime stats
    const regimeMap = new Map<string, {trades:number;wins:number;totalR:number}>();
    trades.forEach(t => {
      const r = t.regime || 'UNKNOWN';
      const s = regimeMap.get(r) || {trades:0,wins:0,totalR:0};
      s.trades++; if(t.pnlR>0)s.wins++; s.totalR+=t.pnlR; regimeMap.set(r,s);
    });
    const regimeStats = Array.from(regimeMap.entries()).map(([regime,s]) => ({
      regime, trades:s.trades, winRate:+(s.wins/s.trades*100).toFixed(1), avgR:+(s.totalR/s.trades).toFixed(2)
    })).sort((a,b)=>b.avgR-a.avgR);

    // Monthly stats
    const monthMap = new Map<string,{trades:number;wins:number;pnl:number}>();
    trades.forEach(t => {
      const d = new Date(t.entryTime*1000);
      const key = `${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}`;
      const s = monthMap.get(key)||{trades:0,wins:0,pnl:0};
      s.trades++; if(t.pnlR>0)s.wins++; s.pnl+=t.pnlPct; monthMap.set(key,s);
    });
    const monthlyStats = Array.from(monthMap.entries())
      .sort(([a],[b])=>a.localeCompare(b))
      .map(([month,s])=>({ month, trades:s.trades, winRate:+(s.wins/s.trades*100).toFixed(1), pnlPct:+s.pnl.toFixed(2) }));

    const { grade, color: gradeColor, reason: gradeReason } = this.gradeStrategy(winRate, profitFactor, sharpeRatio, maxDrawdownPct, expectancy, trades.length);

    const startDate = candles.length ? new Date(candles[0].time*1000).toLocaleDateString('en-IN') : '';
    const endDate   = candles.length ? new Date(candles[candles.length-1].time*1000).toLocaleDateString('en-IN') : '';

    this.progress = 100;
    this.progressMessage = 'Backtest complete!';

    return {
      symbol: config.symbol, timeframe: config.timeframe,
      startDate, endDate, totalCandles: candles.length, totalTrades: trades.length,
      winningTrades: wins.length, losingTrades: losses.length,
      winRate: +winRate.toFixed(1),
      totalPnlPct: +totalPnlPct.toFixed(2), totalPnlR: +totalPnlR.toFixed(2),
      avgWinPct: +avgWinPct.toFixed(2), avgLossPct: +avgLossPct.toFixed(2),
      avgWinR: +avgWinR.toFixed(2), avgLossR: +avgLossR.toFixed(2),
      sharpeRatio: +sharpeRatio.toFixed(2), sortinoRatio: +sortinoRatio.toFixed(2),
      profitFactor: +profitFactor.toFixed(2), maxDrawdownPct: +maxDrawdownPct.toFixed(2),
      maxDrawdownR: +maxDrawdownR.toFixed(2), expectancy: +expectancy.toFixed(3),
      calmarRatio: +calmarRatio.toFixed(2),
      maxWinStreak: maxWin, maxLossStreak: maxLoss, avgHoldBars: +avgHoldBars.toFixed(1),
      equityCurve, trades, regimeStats, monthlyStats,
      grade, gradeColor, gradeReason,
      summary: this.buildSummary(config.symbol, config.timeframe, trades.length, winRate, totalPnlPct, profitFactor, maxDrawdownPct, sharpeRatio, grade)
    };
  }

  private gradeStrategy(wr: number, pf: number, sharpe: number, dd: number, exp: number, n: number): { grade: BacktestResult['grade']; color: string; reason: string } {
    let score = 0;
    if (wr >= 65) score += 3; else if (wr >= 55) score += 2; else if (wr >= 45) score += 1; else score -= 1;
    if (pf >= 2.0) score += 3; else if (pf >= 1.5) score += 2; else if (pf >= 1.2) score += 1; else score -= 2;
    if (sharpe >= 2) score += 3; else if (sharpe >= 1) score += 2; else if (sharpe >= 0.5) score += 1; else score -= 1;
    if (dd <= 5) score += 2; else if (dd <= 15) score += 1; else if (dd <= 25) score -= 0; else score -= 2;
    if (exp > 0.5) score += 2; else if (exp > 0.2) score += 1; else if (exp < 0) score -= 2;
    if (n >= 30) score += 1; else if (n < 10) score -= 1;

    const grade: BacktestResult['grade'] =
      score >= 12 ? 'A+' : score >= 9 ? 'A' : score >= 7 ? 'B+' :
      score >= 5  ? 'B'  : score >= 3 ? 'C' : score >= 1 ? 'D' : 'F';
    const colors = { 'A+':'#00FF88', 'A':'#00D4FF', 'B+':'#7B61FF', 'B':'#FFB800', 'C':'#FF9900', 'D':'#FF6B35', 'F':'#FF3B5C' };
    const reasons: Record<string,string> = {
      'A+': 'Exceptional — institutional grade. All metrics excellent.',
      'A':  'Excellent — strong win rate, good risk-adjusted returns.',
      'B+': 'Very Good — above average, minor improvements possible.',
      'B':  'Good — profitable with acceptable drawdown.',
      'C':  'Average — profitable but needs improvement.',
      'D':  'Below Average — barely profitable, high risk.',
      'F':  'Failing — avoid. Strategy loses money or has unacceptable drawdown.'
    };
    return { grade, color: colors[grade], reason: reasons[grade] };
  }

  private buildSummary(sym: string, tf: string, n: number, wr: number, pnl: number, pf: number, dd: number, sharpe: number, grade: string): string {
    return `${sym} ${tf} backtest: ${n} trades, ${wr.toFixed(0)}% win rate, ${pnl >= 0 ? '+' : ''}${pnl.toFixed(1)}% P&L, ${pf.toFixed(2)} profit factor, ${dd.toFixed(1)}% max drawdown, Sharpe ${sharpe.toFixed(2)}. Grade: ${grade}. `
      + BacktestService.PARITY_DISCLAIMER;
  }

  // P1-19 fix (full context in this class's own javadoc above): this backtest runs a different
  // signal-generation engine than live trading (this app's own TypeScript indicator/SMC/regime
  // engines here, vs. the Java ServerSignalEngine live trading actually uses), so it can approximate
  // but never guarantee live behavior -- appended to every result's summary so a user reading a
  // grade sees this in the result itself, directly addressing the audit's own named danger
  // ("Users will authorize LIVE based on inflated results").
  private static readonly PARITY_DISCLAIMER =
    'Note: this backtest approximates live trading (fees, slippage, and a single T1-only exit are modeled) '
    + 'but runs a different signal engine than live trading and is not a guarantee of live results -- '
    + 'treat it as directional guidance, not a basis alone for authorizing LIVE trading.';

  private emptyResult(config: BacktestConfig, candles: OHLCV[]): BacktestResult {
    return {
      symbol: config.symbol, timeframe: config.timeframe,
      startDate: '', endDate: '', totalCandles: candles.length, totalTrades: 0,
      winningTrades:0, losingTrades:0, winRate:0, totalPnlPct:0, totalPnlR:0,
      avgWinPct:0, avgLossPct:0, avgWinR:0, avgLossR:0,
      sharpeRatio:0, sortinoRatio:0, profitFactor:0, maxDrawdownPct:0, maxDrawdownR:0,
      expectancy:0, calmarRatio:0, maxWinStreak:0, maxLossStreak:0, avgHoldBars:0,
      equityCurve:[], trades:[], regimeStats:[], monthlyStats:[],
      grade:'F', gradeColor:'#FF3B5C', gradeReason:'No trades generated.',
      summary: 'No trades generated — try lowering minimum confidence or adjusting config. ' + BacktestService.PARITY_DISCLAIMER
    };
  }
}
