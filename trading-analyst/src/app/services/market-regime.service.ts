import { Injectable } from '@angular/core';
import { OHLCV } from './ta-engine.service';

export type RegimeType =
  | 'STRONG_BULL_TREND'
  | 'BULL_TREND'
  | 'BEAR_TREND'
  | 'STRONG_BEAR_TREND'
  | 'HIGH_VOLATILITY'
  | 'LOW_VOLATILITY_RANGE'
  | 'RANGING'
  | 'BREAKOUT_IMMINENT'
  | 'POST_BREAKOUT_BULL'
  | 'POST_BREAKOUT_BEAR';

export interface RegimeState {
  regime:         RegimeType;
  confidence:     number;           // 0-100 how certain we are of this regime
  label:          string;           // Human-readable label
  emoji:          string;
  color:          string;           // CSS color
  duration:       number;           // how many candles in this regime (approx)
  // Regime metrics
  adx:            number;
  bbWidth:        number;           // Bollinger band width %
  atrPct:         number;           // ATR as % of price
  trendScore:     number;           // -100 to +100
  volumeRatio:    number;
  // Strategy recommendations for this regime
  strategy: {
    type:           string;         // e.g. "Trend Following", "Mean Reversion"
    bestIndicators: string[];
    avoid:          string[];
    riskMultiplier: number;         // multiply normal position size by this
    slMultiplier:   number;         // ATR multiplier for SL
    tpMultiplier:   number;         // ATR multiplier for TP
    entryStyle:     string;
    description:    string;
  };
  // Indicator weight adjustments for this regime
  weightAdjustments: {
    trend:      number;
    rsi:        number;
    macd:       number;
    bb:         number;
    volume:     number;
    patterns:   number;
    adx:        number;
    mtf:        number;
  };
  summary:        string;
  warnings:       string[];
}

@Injectable({ providedIn: 'root' })
export class MarketRegimeService {

  detect(candles: OHLCV[], symbol: string): RegimeState {
    if (candles.length < 50) return this.defaultRegime();

    const closes  = candles.map(c => c.close);
    const highs   = candles.map(c => c.high);
    const lows    = candles.map(c => c.low);
    const vols    = candles.map(c => c.volume);
    const n       = closes.length;
    const price   = closes[n-1];

    // ── Core Metrics ─────────────────────────────────────────
    const adx       = this.calcADX(highs, lows, closes, 14);
    const bbWidth   = this.calcBBWidth(closes, 20);
    const atrPct    = this.calcATRPct(highs, lows, closes, 14, price);
    const trendScore= this.calcTrendScore(closes, highs, lows);
    const volRatio  = this.calcVolumeRatio(vols);
    const bbSqueeze = bbWidth < 0.03;  // very tight bands
    const bbExpansion = bbWidth > 0.08;

    // Historical volatility: std dev of returns
    const returns   = closes.slice(-20).map((c, i, a) => i === 0 ? 0 : (c - a[i-1]) / a[i-1]);
    const meanRet   = returns.reduce((a, b) => a + b, 0) / returns.length;
    const hvPct     = Math.sqrt(returns.reduce((a, b) => a + Math.pow(b - meanRet, 2), 0) / returns.length) * 100;

    // Price position vs EMAs
    const ema20  = this.ema(closes, 20);
    const ema50  = this.ema(closes, 50);
    const ema200 = this.ema(closes, Math.min(200, n-1));
    const aboveEma20  = price > ema20;
    const aboveEma50  = price > ema50;
    const aboveEma200 = price > ema200;
    const ema20AboveEma50  = ema20 > ema50;
    const ema50AboveEma200 = ema50 > ema200;

    // Recent swing analysis (last 20 candles)
    const recent20H = Math.max(...highs.slice(-20));
    const recent20L = Math.min(...lows.slice(-20));
    const recent20Range = (recent20H - recent20L) / price * 100;

    // Classify regime
    const regime = this.classifyRegime(
      adx, bbWidth, atrPct, trendScore, volRatio, hvPct,
      aboveEma20, aboveEma50, aboveEma200, ema20AboveEma50, ema50AboveEma200,
      bbSqueeze, bbExpansion, recent20Range
    );

    const strategy   = this.getStrategy(regime, adx, bbWidth);
    const weights    = this.getWeightAdjustments(regime);
    const confidence = this.calcConfidence(regime, adx, bbWidth, atrPct, trendScore);
    const warnings   = this.buildWarnings(regime, adx, bbWidth, atrPct, hvPct, volRatio);
    const summary    = this.buildSummary(symbol, regime, adx, bbWidth, atrPct, trendScore, strategy);

    return {
      regime, confidence,
      label:   this.regimeLabel(regime),
      emoji:   this.regimeEmoji(regime),
      color:   this.regimeColor(regime),
      duration: this.estimateDuration(closes, regime),
      adx: +adx.toFixed(1),
      bbWidth: +bbWidth.toFixed(4),
      atrPct: +atrPct.toFixed(2),
      trendScore: Math.round(trendScore),
      volumeRatio: +volRatio.toFixed(2),
      strategy, weightAdjustments: weights,
      summary, warnings
    };
  }

  private classifyRegime(
    adx: number, bbWidth: number, atrPct: number, trendScore: number,
    volRatio: number, hvPct: number,
    aboveEma20: boolean, aboveEma50: boolean, aboveEma200: boolean,
    ema20AboveEma50: boolean, ema50AboveEma200: boolean,
    bbSqueeze: boolean, bbExpansion: boolean, recent20Range: number
  ): RegimeType {

    // HIGH VOLATILITY: extreme ATR, wide BB, erratic
    if (hvPct > 3.5 && atrPct > 4) return 'HIGH_VOLATILITY';

    // BREAKOUT IMMINENT: BB squeeze + ADX still low
    if (bbSqueeze && adx < 20 && volRatio < 0.9) return 'BREAKOUT_IMMINENT';

    // POST-BREAKOUT: BB expansion after squeeze + strong volume
    if (bbExpansion && volRatio > 1.8 && adx > 20) {
      return trendScore > 0 ? 'POST_BREAKOUT_BULL' : 'POST_BREAKOUT_BEAR';
    }

    // STRONG TREND: ADX > 30, EMA perfectly aligned
    if (adx > 30) {
      const strongBull = trendScore > 50 && aboveEma20 && aboveEma50 && aboveEma200 && ema20AboveEma50 && ema50AboveEma200;
      const strongBear = trendScore < -50 && !aboveEma20 && !aboveEma50 && !aboveEma200 && !ema20AboveEma50 && !ema50AboveEma200;
      if (strongBull) return 'STRONG_BULL_TREND';
      if (strongBear) return 'STRONG_BEAR_TREND';
    }

    // MODERATE TREND: ADX 20-30
    if (adx > 20) {
      if (trendScore > 25 && aboveEma50) return 'BULL_TREND';
      if (trendScore < -25 && !aboveEma50) return 'BEAR_TREND';
    }

    // LOW VOL RANGE: very tight price action, low volume
    if (bbWidth < 0.04 && atrPct < 1.5 && recent20Range < 5) return 'LOW_VOLATILITY_RANGE';

    // Default: ranging
    return 'RANGING';
  }

  private getStrategy(regime: RegimeType, adx: number, bbWidth: number): RegimeState['strategy'] {
    const strategies: Record<RegimeType, RegimeState['strategy']> = {
      STRONG_BULL_TREND: {
        type: 'Aggressive Trend Following',
        bestIndicators: ['EMA Stack', 'MACD Cross', 'ADX+DI+', 'OBV Rising'],
        avoid: ['RSI Oversold (stay long)', 'BB Lower (don\'t fade trend)', 'Counter-trend shorts'],
        riskMultiplier: 1.3,
        slMultiplier: 1.2,
        tpMultiplier: 5.0,
        entryStyle: 'Buy pullbacks to EMA20. Add on breakouts.',
        description: 'ADX strong uptrend. All EMAs aligned. Buy every dip, ride the trend. Do NOT short.'
      },
      BULL_TREND: {
        type: 'Trend Following',
        bestIndicators: ['EMA20 Bounce', 'MACD', 'RSI Pullback 40-50', 'VWAP'],
        avoid: ['Overbought RSI shorts', 'BB upper fades'],
        riskMultiplier: 1.1,
        slMultiplier: 1.5,
        tpMultiplier: 3.5,
        entryStyle: 'Buy EMA20 bounces. Wait for RSI pullback to 40-50.',
        description: 'Moderate bull trend. Look for dip entries. Trail stop below EMA20.'
      },
      BEAR_TREND: {
        type: 'Trend Following (Short Bias)',
        bestIndicators: ['EMA Stack Bear', 'MACD Bear Cross', 'RSI Rally 50-60', 'OBV Falling'],
        avoid: ['Counter-trend longs', 'RSI oversold longs (might keep falling)'],
        riskMultiplier: 1.0,
        slMultiplier: 1.5,
        tpMultiplier: 3.0,
        entryStyle: 'Short rallies to EMA20. RSI 50-60 in downtrend = short entry.',
        description: 'Moderate bear trend. Short dead cat bounces. Keep stops tight.'
      },
      STRONG_BEAR_TREND: {
        type: 'Aggressive Short / Capital Protection',
        bestIndicators: ['EMA Stack (all bearish)', 'MACD Hist Falling', 'ADX DI-', 'Volume on down days'],
        avoid: ['Catching falling knives', 'RSI oversold longs', 'Any counter-trend longs'],
        riskMultiplier: 0.7,
        slMultiplier: 1.2,
        tpMultiplier: 4.5,
        entryStyle: 'Short only. Wait for relief rallies to EMA9. Reduce position size.',
        description: 'Strong downtrend. Capital protection mode. Reduce position size by 30%.'
      },
      HIGH_VOLATILITY: {
        type: 'Scalping / Very Tight Risk',
        bestIndicators: ['ATR SL', 'BB Extremes', 'Candlestick Patterns', 'Volume Spikes'],
        avoid: ['Wide stops', 'Large positions', 'Swing trading', 'Holding overnight'],
        riskMultiplier: 0.5,
        slMultiplier: 2.5,
        tpMultiplier: 1.5,
        entryStyle: 'Small positions only. Quick scalps. Do not hold. BB extremes only.',
        description: 'DANGER: Extreme volatility. Reduce size by 50%. Use BB extremes for quick bounces only.'
      },
      RANGING: {
        type: 'Mean Reversion',
        bestIndicators: ['RSI Extremes', 'BB Bands', 'Stochastic', 'Support/Resistance'],
        avoid: ['Trend indicators (EMA, MACD)', 'Breakout trades', 'Wide targets'],
        riskMultiplier: 0.8,
        slMultiplier: 1.0,
        tpMultiplier: 1.5,
        entryStyle: 'Buy oversold at support (RSI<30, BB lower). Sell overbought at resistance (RSI>70, BB upper).',
        description: 'Range-bound market. Use mean reversion. Target 50-70% of the range. Tight stops.'
      },
      LOW_VOLATILITY_RANGE: {
        type: 'Avoid / Wait for Breakout',
        bestIndicators: ['BB Width', 'Volume', 'ATR increasing'],
        avoid: ['Trading at all — very low volatility means small moves and whipsaws'],
        riskMultiplier: 0.4,
        slMultiplier: 0.8,
        tpMultiplier: 1.2,
        entryStyle: 'WAIT. Do not trade. Place breakout orders above/below range. Watch for volume surge.',
        description: 'Dead market. Very tight range. Wait for BB squeeze to resolve. Do not trade yet.'
      },
      BREAKOUT_IMMINENT: {
        type: 'Breakout Preparation',
        bestIndicators: ['BB Width (narrowing)', 'Volume Contraction', 'OB/FVG above/below'],
        avoid: ['Mean reversion trades inside range'],
        riskMultiplier: 0.9,
        slMultiplier: 1.0,
        tpMultiplier: 4.0,
        entryStyle: 'Place buy stop above resistance + sell stop below support. One will trigger. Big move incoming.',
        description: 'BB squeeze — breakout imminent. Set bracket orders. Volume will confirm direction.'
      },
      POST_BREAKOUT_BULL: {
        type: 'Momentum / Breakout Continuation',
        bestIndicators: ['Volume surge', 'Price > prev resistance', 'MACD Cross', 'EMA stack'],
        avoid: ['Fading the breakout', 'RSI overbought shorts (breakouts can stay overbought)'],
        riskMultiplier: 1.2,
        slMultiplier: 1.5,
        tpMultiplier: 4.5,
        entryStyle: 'Enter on first pullback after breakout. Previous resistance = new support.',
        description: 'Fresh bull breakout with volume. High momentum phase. Ride it. Trail stop below breakout level.'
      },
      POST_BREAKOUT_BEAR: {
        type: 'Breakdown Continuation / Short',
        bestIndicators: ['Volume on break', 'Price < prev support', 'MACD Cross bearish'],
        avoid: ['Buying into breakdown', 'Holding longs'],
        riskMultiplier: 1.0,
        slMultiplier: 1.5,
        tpMultiplier: 4.0,
        entryStyle: 'Short on first dead-cat bounce. Previous support = new resistance.',
        description: 'Fresh bear breakdown with volume. Short the bounce. Trail stop above breakdown level.'
      },
    };
    return strategies[regime];
  }

  private getWeightAdjustments(regime: RegimeType): RegimeState['weightAdjustments'] {
    // Weight multipliers for each regime
    // Values > 1.0 = boost that indicator, < 1.0 = reduce its impact
    const weights: Record<RegimeType, RegimeState['weightAdjustments']> = {
      STRONG_BULL_TREND:    { trend:2.0, rsi:0.5, macd:1.5, bb:0.3, volume:1.5, patterns:0.8, adx:2.0, mtf:1.8 },
      BULL_TREND:           { trend:1.7, rsi:0.8, macd:1.3, bb:0.5, volume:1.2, patterns:1.0, adx:1.5, mtf:1.5 },
      BEAR_TREND:           { trend:1.7, rsi:0.8, macd:1.3, bb:0.5, volume:1.2, patterns:1.0, adx:1.5, mtf:1.5 },
      STRONG_BEAR_TREND:    { trend:2.0, rsi:0.5, macd:1.5, bb:0.3, volume:1.5, patterns:0.8, adx:2.0, mtf:1.8 },
      HIGH_VOLATILITY:      { trend:0.4, rsi:1.5, macd:0.5, bb:1.8, volume:2.0, patterns:1.5, adx:0.5, mtf:0.5 },
      RANGING:              { trend:0.3, rsi:1.8, macd:0.4, bb:2.0, volume:0.8, patterns:1.5, adx:0.3, mtf:0.5 },
      LOW_VOLATILITY_RANGE: { trend:0.2, rsi:1.5, macd:0.3, bb:1.8, volume:2.0, patterns:1.0, adx:0.3, mtf:0.4 },
      BREAKOUT_IMMINENT:    { trend:0.5, rsi:1.0, macd:0.7, bb:2.0, volume:2.0, patterns:1.2, adx:0.5, mtf:1.0 },
      POST_BREAKOUT_BULL:   { trend:1.5, rsi:0.4, macd:1.5, bb:0.5, volume:2.0, patterns:1.2, adx:1.5, mtf:1.3 },
      POST_BREAKOUT_BEAR:   { trend:1.5, rsi:0.4, macd:1.5, bb:0.5, volume:2.0, patterns:1.2, adx:1.5, mtf:1.3 },
    };
    return weights[regime];
  }

  private calcConfidence(regime: RegimeType, adx: number, bbWidth: number, atrPct: number, trendScore: number): number {
    let conf = 60;
    if (regime === 'STRONG_BULL_TREND' || regime === 'STRONG_BEAR_TREND') {
      conf = Math.min(95, 60 + adx * 0.8);
    } else if (regime === 'BULL_TREND' || regime === 'BEAR_TREND') {
      conf = Math.min(85, 55 + adx * 0.7);
    } else if (regime === 'BREAKOUT_IMMINENT') {
      conf = Math.min(88, 70 + (0.08 - bbWidth) * 500);
    } else if (regime === 'POST_BREAKOUT_BULL' || regime === 'POST_BREAKOUT_BEAR') {
      conf = Math.min(90, 65 + atrPct * 3);
    } else if (regime === 'HIGH_VOLATILITY') {
      conf = Math.min(82, 50 + atrPct * 4);
    } else if (regime === 'RANGING') {
      conf = Math.min(75, 50 + (30 - adx) * 1.5);
    } else {
      conf = 60;
    }
    return Math.round(Math.max(50, Math.min(95, conf)));
  }

  private buildWarnings(regime: RegimeType, adx: number, bbWidth: number, atrPct: number, hvPct: number, volRatio: number): string[] {
    const w: string[] = [];
    if (regime === 'HIGH_VOLATILITY')      w.push('⚠️ Extreme volatility — reduce position size by 50%');
    if (regime === 'LOW_VOLATILITY_RANGE') w.push('⚠️ Very low volatility — do not trade, wait for breakout');
    if (regime === 'STRONG_BEAR_TREND')    w.push('⚠️ Strong downtrend — longs are high risk, consider hedging');
    if (regime === 'BREAKOUT_IMMINENT')    w.push('💥 BB squeeze detected — large move expected soon in either direction');
    if (atrPct > 5)                        w.push(`⚠️ ATR ${atrPct.toFixed(1)}% is very high — widen stops`);
    if (volRatio < 0.5)                    w.push('⚠️ Very low volume — signals may be unreliable');
    if (volRatio > 3)                      w.push('🔥 Volume spike — potential institutional move or manipulation');
    if (adx < 15)                          w.push('💤 Very weak trend (ADX<15) — avoid trend strategies entirely');
    return w;
  }

  private buildSummary(symbol: string, regime: RegimeType, adx: number, bbWidth: number, atrPct: number, trendScore: number, strategy: RegimeState['strategy']): string {
    const label = this.regimeLabel(regime);
    return `${this.regimeEmoji(regime)} ${symbol} is in ${label} (ADX ${adx.toFixed(0)}, BB Width ${(bbWidth*100).toFixed(1)}%, ATR ${atrPct.toFixed(1)}%). Strategy: ${strategy.type}. ${strategy.description}`;
  }

  // ── Math helpers ─────────────────────────────────────────────
  private calcADX(highs: number[], lows: number[], closes: number[], period: number): number {
    const n = closes.length; if (n < period+1) return 20;
    const trA:number[]=[], pmA:number[]=[], nmA:number[]=[];
    for (let i=1;i<n;i++) {
      const tr=Math.max(highs[i]-lows[i],Math.abs(highs[i]-closes[i-1]),Math.abs(lows[i]-closes[i-1]));
      const pm=highs[i]-highs[i-1]; const nm=lows[i-1]-lows[i];
      trA.push(tr); pmA.push(pm>nm&&pm>0?pm:0); nmA.push(nm>pm&&nm>0?nm:0);
    }
    const atrV=trA.slice(-period).reduce((a,b)=>a+b,0)/period;
    const diP=(pmA.slice(-period).reduce((a,b)=>a+b,0)/period/(atrV||1))*100;
    const diN=(nmA.slice(-period).reduce((a,b)=>a+b,0)/period/(atrV||1))*100;
    const dxArr:number[]=[];
    for (let i=0;i<trA.length;i++) {
      const at=trA.slice(Math.max(0,i-period+1),i+1).reduce((a,b)=>a+b,0)/period;
      const dp=(pmA.slice(Math.max(0,i-period+1),i+1).reduce((a,b)=>a+b,0)/period/(at||1))*100;
      const dn=(nmA.slice(Math.max(0,i-period+1),i+1).reduce((a,b)=>a+b,0)/period/(at||1))*100;
      dxArr.push(Math.abs(dp-dn)/(dp+dn+0.001)*100);
    }
    return dxArr.slice(-period).reduce((a,b)=>a+b,0)/period;
  }

  private calcBBWidth(closes: number[], period: number): number {
    const sl=closes.slice(-period); const mid=sl.reduce((a,b)=>a+b,0)/period;
    const std=Math.sqrt(sl.reduce((a,b)=>a+Math.pow(b-mid,2),0)/period);
    return mid > 0 ? (std*4)/mid : 0;  // (upper-lower)/mid
  }

  private calcATRPct(highs: number[], lows: number[], closes: number[], period: number, price: number): number {
    const n=closes.length; if (n<2) return 0;
    const trs=highs.slice(-period).map((h,i)=>{const lo=lows[n-period+i];const pc=i===0?closes[n-period-1]:closes[n-period+i-1];return Math.max(h-lo,Math.abs(h-pc),Math.abs(lo-pc));});
    return (trs.reduce((a,b)=>a+b,0)/period/price)*100;
  }

  private calcTrendScore(closes: number[], highs: number[], lows: number[]): number {
    const n = closes.length; const price = closes[n-1];
    const ema9  = this.ema(closes, 9);
    const ema20 = this.ema(closes, 20);
    const ema50 = this.ema(closes, 50);
    const ema200= this.ema(closes, Math.min(200,n-1));
    let score = 0;
    if (price > ema9)   score += 15; else score -= 15;
    if (price > ema20)  score += 20; else score -= 20;
    if (price > ema50)  score += 25; else score -= 25;
    if (price > ema200) score += 20; else score -= 20;
    if (ema9  > ema20)  score += 10; else score -= 10;
    if (ema20 > ema50)  score += 10; else score -= 10;
    return Math.max(-100, Math.min(100, score));
  }

  private calcVolumeRatio(vols: number[]): number {
    const avg = vols.slice(-20).reduce((a,b)=>a+b,0)/20;
    return avg > 0 ? vols[vols.length-1]/avg : 1;
  }

  private ema(data: number[], period: number): number {
    if (data.length < period) return data[data.length-1]||0;
    const k=2/(period+1); let v=data.slice(0,period).reduce((a,b)=>a+b,0)/period;
    for (let i=period; i<data.length; i++) v=data[i]*k+v*(1-k); return v;
  }

  private estimateDuration(closes: number[], regime: RegimeType): number {
    // Simple estimate: count recent candles matching this regime type
    return regime.includes('TREND') ? Math.floor(closes.length * 0.3) :
           regime.includes('RANGE') ? Math.floor(closes.length * 0.2) : 10;
  }

  regimeLabel(r: RegimeType): string {
    const labels: Record<RegimeType,string> = {
      STRONG_BULL_TREND:    'Strong Bull Trend',
      BULL_TREND:           'Bull Trend',
      BEAR_TREND:           'Bear Trend',
      STRONG_BEAR_TREND:    'Strong Bear Trend',
      HIGH_VOLATILITY:      'High Volatility',
      LOW_VOLATILITY_RANGE: 'Low Volatility Range',
      RANGING:              'Ranging / Consolidation',
      BREAKOUT_IMMINENT:    'Breakout Imminent',
      POST_BREAKOUT_BULL:   'Post-Breakout Bull',
      POST_BREAKOUT_BEAR:   'Post-Breakout Bear',
    };
    return labels[r];
  }

  regimeEmoji(r: RegimeType): string {
    const emojis: Record<RegimeType,string> = {
      STRONG_BULL_TREND:'🚀',BULL_TREND:'📈',BEAR_TREND:'📉',STRONG_BEAR_TREND:'🔻',
      HIGH_VOLATILITY:'⚡',LOW_VOLATILITY_RANGE:'😴',RANGING:'↔️',
      BREAKOUT_IMMINENT:'💥',POST_BREAKOUT_BULL:'🎯',POST_BREAKOUT_BEAR:'🎯',
    };
    return emojis[r];
  }

  regimeColor(r: RegimeType): string {
    const colors: Record<RegimeType,string> = {
      STRONG_BULL_TREND:'#00FF88',BULL_TREND:'rgba(0,255,136,0.7)',
      BEAR_TREND:'rgba(255,59,92,0.7)',STRONG_BEAR_TREND:'#FF3B5C',
      HIGH_VOLATILITY:'#FFB800',LOW_VOLATILITY_RANGE:'#4A5568',RANGING:'#8895B3',
      BREAKOUT_IMMINENT:'#7B61FF',POST_BREAKOUT_BULL:'#00D4FF',POST_BREAKOUT_BEAR:'#FF6B35',
    };
    return colors[r];
  }

  private defaultRegime(): RegimeState {
    return this.detect([{time:0,open:100,high:105,low:95,close:100,volume:1000}], 'N/A');
  }
}
