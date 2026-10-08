import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, Subject, interval } from 'rxjs';
import { map, switchMap, startWith } from 'rxjs/operators';

export interface OHLCV {
  time: number;      // unix seconds
  open: number;
  high: number;
  low: number;
  close: number;
  volume: number;
}

export interface TradingSignal {
  signal: 'STRONG BUY' | 'BUY' | 'NEUTRAL' | 'SELL' | 'STRONG SELL';
  confidence: number;
  entry: number;
  stopLoss: number;
  target1: number;
  target2: number;
  target3: number;
  rsi: number;
  rsiZone: string;
  macd: number;
  macdSignal: number;
  macdHist: number;
  macdBull: boolean;
  ema20: number;
  ema50: number;
  ema200: number;
  trendEMA: string;
  bbUpper: number;
  bbMid: number;
  bbLower: number;
  bbSignal: string;
  atr: number;
  volumeSignal: string;
  patterns: string[];
  supportLevels: number[];
  resistanceLevels: number[];
  summary: string;
  risk: 'LOW' | 'MEDIUM' | 'HIGH';
  rrRatio: string;
}

export interface CryptoTicker {
  symbol: string;
  price: number;
  change24h: number;
  changePct24h: number;
  volume24h: number;
  high24h: number;
  low24h: number;
}

@Injectable({ providedIn: 'root' })
export class BinanceService {
  // Calls api.binance.com directly for public market data only (candles, tickers), which
  // Binance serves without authentication. No API key, secret or credential ever passes
  // through this class or this URL — those stay server-side (BrokerCredentialService) and
  // are never sent to the browser. Order placement, balances and anything account-specific
  // go through the backend's credential-scoped endpoints instead; this class exists purely
  // to drive the chart/ticker display.
  private readonly BASE = 'https://api.binance.com/api/v3';
  // Market data here is REST polling only. The backend's own user-data WebSocket stream
  // handles the real, account-specific execution feed; this class never touches
  // account-specific data, so it has no live ticker stream of its own.
  private tickerSubject = new Subject<CryptoTicker>();

  readonly SYMBOLS = [
    { symbol: 'BTCUSDT',  display: 'BTC/USDT',  name: 'Bitcoin' },
    { symbol: 'ETHUSDT',  display: 'ETH/USDT',  name: 'Ethereum' },
    { symbol: 'SOLUSDT',  display: 'SOL/USDT',  name: 'Solana' },
    { symbol: 'ONDOUSDT', display: 'ONDO/USDT', name: 'Ondo Finance' },
    { symbol: 'FETUSDT',  display: 'FET/USDT',  name: 'Fetch.ai' },
    { symbol: 'BNBUSDT',  display: 'BNB/USDT',  name: 'BNB' },
    { symbol: 'XRPUSDT',  display: 'XRP/USDT',  name: 'XRP' },
    { symbol: 'ADAUSDT',  display: 'ADA/USDT',  name: 'Cardano' },
    { symbol: 'AVAXUSDT', display: 'AVAX/USDT', name: 'Avalanche' },
    { symbol: 'LINKUSDT', display: 'LINK/USDT', name: 'Chainlink' },
    { symbol: 'DOGEUSDT', display: 'DOGE/USDT', name: 'Dogecoin' },
    { symbol: 'DOTUSDT',  display: 'DOT/USDT',  name: 'Polkadot' },
    { symbol: 'MATICUSDT',display: 'MATIC/USDT',name: 'Polygon' },
    { symbol: 'UNIUSDT',  display: 'UNI/USDT',  name: 'Uniswap' },
    { symbol: 'LTCUSDT',  display: 'LTC/USDT',  name: 'Litecoin' },
  ];

  readonly INTERVALS = ['1m','5m','15m','1h','4h','1d'];

  constructor(private http: HttpClient) {}

  // ── Live candles from Binance REST ─────────────────────────
  getKlines(symbol: string, tf: string, limit = 200): Observable<OHLCV[]> {
    return this.http.get<any[]>(
      `${this.BASE}/klines?symbol=${symbol}&interval=${tf}&limit=${limit}`
    ).pipe(
      map(raw => raw.map(k => ({
        time: Math.floor(k[0] / 1000),
        open: parseFloat(k[1]),
        high: parseFloat(k[2]),
        low: parseFloat(k[3]),
        close: parseFloat(k[4]),
        volume: parseFloat(k[5])
      })))
    );
  }

  // ── Live price via REST polling ────────────────────────────
  getLivePrices(): Observable<CryptoTicker[]> {
    const syms = this.SYMBOLS.map(s => `"${s.symbol}"`).join(',');
    return interval(5000).pipe(
      startWith(0),
      switchMap(() =>
        this.http.get<any[]>(`${this.BASE}/ticker/24hr?symbols=[${syms}]`)
      ),
      map(data => data.map(d => ({
        symbol: d.symbol,
        price: parseFloat(d.lastPrice),
        change24h: parseFloat(d.priceChange),
        changePct24h: parseFloat(d.priceChangePercent),
        volume24h: parseFloat(d.quoteVolume),
        high24h: parseFloat(d.highPrice),
        low24h: parseFloat(d.lowPrice),
      })))
    );
  }

  // Auto-refresh candles every 10s
  getLiveKlines(symbol: string, tf: string, limit = 200): Observable<OHLCV[]> {
    return interval(10000).pipe(
      startWith(0),
      switchMap(() => this.getKlines(symbol, tf, limit))
    );
  }

  // ── Full Technical Analysis Engine ─────────────────────────
  analyze(candles: OHLCV[], symbol: string): TradingSignal {
    const closes = candles.map(c => c.close);
    const highs   = candles.map(c => c.high);
    const lows    = candles.map(c => c.low);
    const vols    = candles.map(c => c.volume);
    const price   = closes[closes.length - 1];

    // ── Indicators ──────────────────────────────────────────
    const rsi     = this.rsi(closes, 14);
    const ema20   = this.ema(closes, 20);
    const ema50   = this.ema(closes, 50);
    const ema200  = this.ema(closes, 200);
    const { macd, signal: macdSig, hist } = this.macd(closes);
    const { upper, mid, lower } = this.bollingerBands(closes, 20, 2);
    const atr     = this.atr(highs, lows, closes, 14);
    const { supports, resistances } = this.srLevels(highs, lows, 20);

    // ── Volume ──────────────────────────────────────────────
    const volAvg = vols.slice(-20).reduce((a, b) => a + b, 0) / 20;
    const curVol = vols[vols.length - 1];
    const volSignal = curVol > volAvg * 2 ? 'Very High (Strong Confirmation)' :
                      curVol > volAvg * 1.5 ? 'High (Confirmed)' :
                      curVol > volAvg ? 'Above Average' : 'Low (Weak Signal)';

    // ── Pattern Detection ───────────────────────────────────
    const patterns = this.detectPatterns(candles);

    // ── Scoring System ──────────────────────────────────────
    let score = 50;
    // RSI
    if (rsi < 25) score += 25;
    else if (rsi < 35) score += 15;
    else if (rsi < 45) score += 8;
    else if (rsi > 75) score -= 25;
    else if (rsi > 65) score -= 15;
    else if (rsi > 55) score -= 8;
    // MACD
    if (hist > 0 && hist > Math.abs(hist) * 0.1) score += 18;
    else if (hist > 0) score += 10;
    else if (hist < 0 && Math.abs(hist) > Math.abs(hist) * 0.1) score -= 18;
    else if (hist < 0) score -= 10;
    // EMA Trend
    if (price > ema20 && ema20 > ema50) score += 12;
    else if (price < ema20 && ema20 < ema50) score -= 12;
    if (price > ema200) score += 8; else score -= 8;
    // Bollinger
    if (price < lower) score += 15;
    else if (price > upper) score -= 15;
    else if (price < mid) score += 3;
    else score -= 3;
    // Volume confirmation
    if (curVol > volAvg * 1.5) score += 8;
    // Pattern bonus
    const bullishPatterns = ['Bullish Engulfing','Hammer','Morning Star','Piercing Line','3 White Soldiers','Dragonfly Doji','Bullish Marubozu','Tweezer Bottom'];
    const bearishPatterns = ['Bearish Engulfing','Shooting Star','Evening Star','Dark Cloud Cover','3 Black Crows','Gravestone Doji','Bearish Marubozu','Tweezer Top'];
    patterns.forEach(p => {
      if (bullishPatterns.some(bp => p.includes(bp.split(' ')[0]))) score += 6;
      if (bearishPatterns.some(bp => p.includes(bp.split(' ')[0]))) score -= 6;
    });

    score = Math.max(0, Math.min(100, score));

    const tradingSignal = score >= 78 ? 'STRONG BUY' : score >= 62 ? 'BUY' :
                          score <= 22 ? 'STRONG SELL' : score <= 38 ? 'SELL' : 'NEUTRAL';

    // ── Trade Levels ─────────────────────────────────────────
    const isBull = tradingSignal.includes('BUY');
    const slDist = atr * (score >= 78 || score <= 22 ? 2.0 : 2.5);
    const entry  = price;
    const sl     = isBull ? entry - slDist : entry + slDist;
    const rMult  = Math.abs(entry - sl);
    const t1     = isBull ? entry + rMult * 1.5 : entry - rMult * 1.5;
    const t2     = isBull ? entry + rMult * 2.5 : entry - rMult * 2.5;
    const t3     = isBull ? entry + rMult * 4.0 : entry - rMult * 4.0;
    const rrRatio = `1 : ${(rMult * 2.5 / rMult).toFixed(1)}`;

    const risk: 'LOW' | 'MEDIUM' | 'HIGH' =
      (score >= 70 || score <= 30) ? 'LOW' :
      (score >= 58 || score <= 42) ? 'MEDIUM' : 'HIGH';

    return {
      signal: tradingSignal, confidence: Math.round(score),
      entry: this.fmt(entry), stopLoss: this.fmt(sl),
      target1: this.fmt(t1), target2: this.fmt(t2), target3: this.fmt(t3),
      rsi: parseFloat(rsi.toFixed(1)),
      rsiZone: rsi < 30 ? 'Oversold 🟢' : rsi > 70 ? 'Overbought 🔴' : 'Neutral ⚪',
      macd: parseFloat(macd.toFixed(6)), macdSignal: parseFloat(macdSig.toFixed(6)),
      macdHist: parseFloat(hist.toFixed(6)), macdBull: hist > 0,
      ema20: this.fmt(ema20), ema50: this.fmt(ema50), ema200: this.fmt(ema200),
      trendEMA: price > ema20 && ema20 > ema50 ? '📈 Uptrend' : price < ema20 && ema20 < ema50 ? '📉 Downtrend' : '↔️ Sideways',
      bbUpper: this.fmt(upper), bbMid: this.fmt(mid), bbLower: this.fmt(lower),
      bbSignal: price < lower ? '🟢 Below Lower (Oversold)' : price > upper ? '🔴 Above Upper (Overbought)' : '⚪ Within Bands',
      atr: this.fmt(atr), volumeSignal: volSignal,
      patterns, supportLevels: supports.slice(0,3).map(s => this.fmt(s)),
      resistanceLevels: resistances.slice(0,3).map(r => this.fmt(r)),
      summary: this.buildSummary(tradingSignal, symbol, rsi, hist > 0, patterns, price, ema200),
      risk, rrRatio
    };
  }

  // ── Indicators ─────────────────────────────────────────────
  private rsi(closes: number[], period = 14): number {
    let gains = 0, losses = 0;
    for (let i = closes.length - period; i < closes.length; i++) {
      const d = closes[i] - closes[i-1];
      if (d > 0) gains += d; else losses -= d;
    }
    const rs = (gains / period) / ((losses / period) || 0.0001);
    return 100 - 100 / (1 + rs);
  }

  private ema(data: number[], period: number): number {
    const k = 2 / (period + 1);
    let val = data.slice(0, period).reduce((a, b) => a + b, 0) / period;
    for (let i = period; i < data.length; i++) val = data[i] * k + val * (1 - k);
    return val;
  }

  private macd(closes: number[]): { macd: number; signal: number; hist: number } {
    const ema12 = this.ema(closes, 12);
    const ema26 = this.ema(closes, 26);
    const macdLine = ema12 - ema26;
    // Approximate signal line
    const macdHistory = closes.slice(-35).map((_, i, arr) => {
      const sl = arr.slice(0, i+1);
      if (sl.length < 26) return 0;
      return this.ema(sl, 12) - this.ema(sl, 26);
    }).filter(v => v !== 0);
    const signalLine = this.ema(macdHistory.length >= 9 ? macdHistory : [macdLine], 9);
    return { macd: macdLine, signal: signalLine, hist: macdLine - signalLine };
  }

  private bollingerBands(closes: number[], period = 20, mult = 2): { upper: number; mid: number; lower: number } {
    const slice = closes.slice(-period);
    const mid   = slice.reduce((a, b) => a + b, 0) / period;
    const std   = Math.sqrt(slice.reduce((a, b) => a + Math.pow(b - mid, 2), 0) / period);
    return { upper: mid + mult * std, mid, lower: mid - mult * std };
  }

  private atr(highs: number[], lows: number[], closes: number[], period = 14): number {
    const trs = highs.slice(-period).map((h, i, arr) => {
      const lo = lows[lows.length - period + i];
      const pc = i === 0 ? closes[closes.length - period - 1] : closes[closes.length - period + i - 1];
      return Math.max(h - lo, Math.abs(h - pc), Math.abs(lo - pc));
    });
    return trs.reduce((a, b) => a + b, 0) / period;
  }

  private srLevels(highs: number[], lows: number[], lookback: number): { supports: number[]; resistances: number[] } {
    const recentHighs = highs.slice(-lookback * 3);
    const recentLows  = lows.slice(-lookback * 3);
    const resistances = recentHighs
      .filter((h, i, arr) => i > 0 && i < arr.length - 1 && h > arr[i-1] && h > arr[i+1])
      .sort((a, b) => b - a);
    const supports = recentLows
      .filter((l, i, arr) => i > 0 && i < arr.length - 1 && l < arr[i-1] && l < arr[i+1])
      .sort((a, b) => a - b);
    return { supports, resistances };
  }

  private detectPatterns(candles: OHLCV[]): string[] {
    const patterns: string[] = [];
    const n = candles.length;
    if (n < 3) return patterns;
    const c  = candles[n-1], p = candles[n-2], pp = candles[n-3];
    const body  = (x: OHLCV) => Math.abs(x.close - x.open);
    const range = (x: OHLCV) => x.high - x.low;
    const isBull= (x: OHLCV) => x.close > x.open;
    const isBear= (x: OHLCV) => x.close < x.open;
    const upperWick = (x: OHLCV) => x.high - Math.max(x.open, x.close);
    const lowerWick = (x: OHLCV) => Math.min(x.open, x.close) - x.low;

    // Doji
    if (body(c) < range(c) * 0.08) patterns.push('Doji (Indecision)');
    // Hammer
    if (lowerWick(c) > body(c) * 2 && upperWick(c) < body(c) * 0.4 && isBull(c)) patterns.push('Hammer 🔨 (Bullish Reversal)');
    // Shooting Star
    if (upperWick(c) > body(c) * 2 && lowerWick(c) < body(c) * 0.4 && isBear(c)) patterns.push('Shooting Star ⭐ (Bearish Reversal)');
    // Bullish Engulfing
    if (isBear(p) && isBull(c) && c.open < p.close && c.close > p.open) patterns.push('Bullish Engulfing 🟢');
    // Bearish Engulfing
    if (isBull(p) && isBear(c) && c.open > p.close && c.close < p.open) patterns.push('Bearish Engulfing 🔴');
    // Morning Star
    if (isBear(pp) && body(p) < range(p) * 0.3 && isBull(c) && c.close > (pp.open + pp.close) / 2) patterns.push('Morning Star 🌅 (Strong Bullish)');
    // Evening Star
    if (isBull(pp) && body(p) < range(p) * 0.3 && isBear(c) && c.close < (pp.open + pp.close) / 2) patterns.push('Evening Star 🌆 (Strong Bearish)');
    // 3 White Soldiers
    if (isBull(pp) && isBull(p) && isBull(c) && p.close > pp.close && c.close > p.close && body(c) > range(c) * 0.6) patterns.push('3 White Soldiers 🚀 (Strong Bull)');
    // 3 Black Crows
    if (isBear(pp) && isBear(p) && isBear(c) && p.close < pp.close && c.close < p.close && body(c) > range(c) * 0.6) patterns.push('3 Black Crows 🐦 (Strong Bear)');
    // Marubozu
    if (body(c) > range(c) * 0.9 && isBull(c)) patterns.push('Bullish Marubozu (Strong Buy)');
    if (body(c) > range(c) * 0.9 && isBear(c)) patterns.push('Bearish Marubozu (Strong Sell)');
    // Piercing Line
    if (isBear(p) && isBull(c) && c.open < p.low && c.close > (p.open + p.close) / 2) patterns.push('Piercing Line (Bullish)');
    // Dark Cloud Cover
    if (isBull(p) && isBear(c) && c.open > p.high && c.close < (p.open + p.close) / 2) patterns.push('Dark Cloud Cover (Bearish)');

    if (patterns.length === 0) patterns.push('No Significant Pattern');
    return patterns;
  }

  private buildSummary(signal: string, sym: string, rsi: number, macdBull: boolean, patterns: string[], price: number, ema200: number): string {
    const trend = price > ema200 ? 'above the 200 EMA (long-term uptrend)' : 'below the 200 EMA (long-term downtrend)';
    const patStr = patterns.filter(p => !p.includes('No Significant')).join(', ') || 'no clear pattern';
    if (signal === 'STRONG BUY') return `${sym} shows a high-conviction BUY setup. RSI at ${rsi.toFixed(0)} is in oversold territory with ${macdBull ? 'bullish MACD momentum' : 'potential MACD reversal'}. Price is ${trend}. Detected: ${patStr}. Risk/Reward is favorable — consider scaling in with tight stop loss.`;
    if (signal === 'BUY') return `${sym} has a bullish bias. RSI at ${rsi.toFixed(0)} with ${macdBull ? 'positive MACD' : 'improving MACD'}. Price ${trend}. Pattern: ${patStr}. Entry on confirmed breakout recommended.`;
    if (signal === 'SELL') return `${sym} is showing bearish pressure. RSI at ${rsi.toFixed(0)} with ${!macdBull ? 'negative MACD' : 'weakening MACD'}. Price ${trend}. Pattern: ${patStr}. Consider reducing longs or short entry on retest.`;
    if (signal === 'STRONG SELL') return `${sym} has a high-conviction SELL setup. RSI at ${rsi.toFixed(0)} is overbought. ${!macdBull ? 'MACD bearish.' : ''} Price ${trend}. Pattern: ${patStr}. Strong downward momentum expected.`;
    return `${sym} is in consolidation. RSI at ${rsi.toFixed(0)} is neutral. Price ${trend}. ${patStr}. Wait for a clear breakout above resistance or breakdown below support before entering.`;
  }

  private fmt(v: number): number {
    if (v >= 1000) return parseFloat(v.toFixed(2));
    if (v >= 1)    return parseFloat(v.toFixed(4));
    return parseFloat(v.toFixed(6));
  }
}
