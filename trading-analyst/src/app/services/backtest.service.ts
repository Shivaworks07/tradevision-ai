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
 * Simulates a trading strategy against historical candles to estimate how it would have
 * performed. It models execution costs (fees and slippage) and restricts a simulated trade's
 * profit exit to T1 only, since that is all a single live OCO order can ever actually capture
 * (see PositionSafetyService's own OCO placement, which never re-arms a second leg past T1) —
 * T2/T3 are still recorded on each BacktestTrade as context, but never credited to the equity
 * curve or win/loss scoring. Equity updates use real fixed-fractional sizing, scaling by the
 * trade's own R-multiple (risk-defined by stop distance) rather than its raw price-move
 * percentage, since that is what risking a fixed % of equity per trade actually computes.
 *
 * This engine does not share any code with the live `ServerSignalEngine` (a separate Java
 * class) — TaEngineService/SmcEngineService/MarketRegimeService here are an independent
 * TypeScript implementation. True engine parity would mean either porting the full Java
 * decision logic to TypeScript and keeping both in lockstep, or replaying historical candles
 * through the real engine server-side; neither is attempted here. Because of that gap,
 * BacktestResult.summary always carries an explicit disclaimer (see calcMetrics/emptyResult
 * below) so a result is never read as a guarantee of live behavior.
 */
@Injectable({ providedIn: 'root' })
export class BacktestService {
  private http   = inject(HttpClient);
  private ta     = inject(TaEngineService);
  private smc    = inject(SmcEngineService);
  private vp     = inject(VolumeProfileService);
  private regime = inject(MarketRegimeService);

  // Matches PaperBrokerAdapter.java's own simulated-fill constants (SIMULATED_TAKER_FEE_RATE /
  // SIMULATED_SLIPPAGE_RATE) so a paper run and a backtest run of the same strategy at least
  // model execution costs consistently, even though their signal-generation engines differ.
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
          // Fixed-fractional position sizing risks riskPerTrade% of equity per trade, sized
          // by the stop distance — so a completed trade's equity impact is riskPerTrade% times
          // its R-multiple (pnlR), not its raw price-move percentage.
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

    // Entry pays slippage in the same direction a real market order would: a LONG entry
    // fills slightly worse (higher), a SHORT entry fills slightly lower — matching
    // PaperBrokerAdapter's own SIMULATED_SLIPPAGE_RATE direction convention.
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

      // Matches what a single live OCO order can actually achieve: if a candle touches both
      // the stop and T1 (the only target a live OCO can capture), the stop is credited — never
      // a target, regardless of which level the candle's high/low also reached. T1 is the only
      // reachable profit exit; T2/T3 are not treated as reachable results here since the live
      // OCO this backtest approximates never re-arms past T1.
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

    // Exit pays slippage with the same direction convention as entry: a LONG exit (a sell)
    // fills slightly worse (lower); a SHORT exit (a buy-to-cover) fills slightly higher. An
    // EXPIRED exit (closed at the maxHold candle's close) still pays this cost, since it is
    // still a real market order in live trading.
    const exitPrice = isLong
      ? rawExitPrice * (1 - BacktestService.SLIPPAGE_RATE)
      : rawExitPrice * (1 + BacktestService.SLIPPAGE_RATE);

    const grossPnlPct = isLong
      ? (exitPrice - entry) / entry * 100
      : (entry - exitPrice) / entry * 100;
    // Taker fee charged on both legs (entry + exit), in percentage-of-notional terms --
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

  // This backtest runs a different signal-generation engine than live trading (this app's own
  // TypeScript indicator/SMC/regime engines, vs. the Java ServerSignalEngine live trading
  // actually uses), so it can approximate but never guarantee live behavior — appended to
  // every result's summary so that context is visible wherever a grade is read.
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
