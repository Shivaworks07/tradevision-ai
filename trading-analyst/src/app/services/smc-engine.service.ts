import { Injectable } from '@angular/core';
import { OHLCV } from './ta-engine.service';

export interface SwingPoint {
  index: number;
  price: number;
  type: 'HIGH' | 'LOW';
  time: number;
}

export interface OrderBlock {
  top: number;
  bottom: number;
  direction: 'BULL' | 'BEAR';   // Bull OB = last bearish candle before impulsive up
  time: number;
  strength: number;              // 1-3
  mitigated: boolean;
  description: string;
}

export interface FairValueGap {
  top: number;
  bottom: number;
  direction: 'BULL' | 'BEAR';
  time: number;
  size: number;       // gap size as % of price
  filled: boolean;
  description: string;
}

export interface LiquidityLevel {
  price: number;
  type: 'EQL_HIGHS' | 'EQL_LOWS' | 'BSL' | 'SSL';  // Buy/Sell Side Liquidity
  time: number;
  swept: boolean;
  description: string;
}

export interface StructureBreak {
  type: 'BOS' | 'CHOCH';        // Break of Structure | Change of Character
  direction: 'BULL' | 'BEAR';
  price: number;
  time: number;
  significance: 'MINOR' | 'MAJOR';
  description: string;
}

export interface PremiumDiscount {
  equilibrium: number;
  premium: number;    // above 0.5 fib = premium
  discount: number;   // below 0.5 fib = discount
  currentZone: 'PREMIUM' | 'DISCOUNT' | 'EQUILIBRIUM';
  fibLevel: number;   // 0-1 position
}

export interface SMCAnalysis {
  swingHighs: SwingPoint[];
  swingLows: SwingPoint[];
  orderBlocks: OrderBlock[];
  fairValueGaps: FairValueGap[];
  liquidityLevels: LiquidityLevel[];
  structureBreaks: StructureBreak[];
  premiumDiscount: PremiumDiscount | null;
  trend: 'BULL_TREND' | 'BEAR_TREND' | 'RANGING';
  bias: 'BULLISH' | 'BEARISH' | 'NEUTRAL';
  biasStrength: number;           // 0-100
  keyLevels: { price: number; label: string; type: string }[];
  summary: string;
  entrySetup: string | null;      // Specific trade setup if found
}

@Injectable({ providedIn: 'root' })
export class SmcEngineService {

  analyze(candles: OHLCV[], symbol: string): SMCAnalysis {
    if (candles.length < 50) {
      return this.emptyAnalysis();
    }

    const swingHighs = this.findSwingPoints(candles, 'HIGH', 5);
    const swingLows  = this.findSwingPoints(candles, 'LOW', 5);
    const structureBreaks = this.detectStructureBreaks(candles, swingHighs, swingLows);
    const orderBlocks = this.detectOrderBlocks(candles);
    const fvgs = this.detectFairValueGaps(candles);
    const liquidityLevels = this.detectLiquidityLevels(candles, swingHighs, swingLows);
    const premiumDiscount = this.calculatePremiumDiscount(candles, swingHighs, swingLows);

    const { trend, bias, biasStrength } = this.determineBias(structureBreaks, orderBlocks);
    const keyLevels = this.buildKeyLevels(orderBlocks, fvgs, liquidityLevels, candles[candles.length-1].close);
    const entrySetup = this.findEntrySetup(candles, orderBlocks, fvgs, bias, premiumDiscount);
    const summary = this.buildSummary(symbol, bias, biasStrength, structureBreaks, orderBlocks, fvgs, liquidityLevels, premiumDiscount, entrySetup);

    return {
      swingHighs: swingHighs.slice(-5),
      swingLows:  swingLows.slice(-5),
      orderBlocks:      orderBlocks.filter(o => !o.mitigated).slice(-4),
      fairValueGaps:    fvgs.filter(f => !f.filled).slice(-4),
      liquidityLevels:  liquidityLevels.slice(-5),
      structureBreaks:  structureBreaks.slice(-4),
      premiumDiscount,
      trend, bias, biasStrength,
      keyLevels: keyLevels.slice(0, 8),
      summary,
      entrySetup
    };
  }

  // ── Swing Point Detection ─────────────────────────────────
  private findSwingPoints(candles: OHLCV[], type: 'HIGH' | 'LOW', lookback = 5): SwingPoint[] {
    const points: SwingPoint[] = [];
    const n = candles.length;
    for (let i = lookback; i < n - lookback; i++) {
      if (type === 'HIGH') {
        const isSwingHigh = candles.slice(i - lookback, i).every(c => c.high <= candles[i].high)
          && candles.slice(i + 1, i + lookback + 1).every(c => c.high <= candles[i].high);
        if (isSwingHigh) points.push({ index: i, price: candles[i].high, type: 'HIGH', time: candles[i].time });
      } else {
        const isSwingLow = candles.slice(i - lookback, i).every(c => c.low >= candles[i].low)
          && candles.slice(i + 1, i + lookback + 1).every(c => c.low >= candles[i].low);
        if (isSwingLow) points.push({ index: i, price: candles[i].low, type: 'LOW', time: candles[i].time });
      }
    }
    return points;
  }

  // ── Structure Break Detection (BOS & CHOCH) ───────────────
  private detectStructureBreaks(candles: OHLCV[], highs: SwingPoint[], lows: SwingPoint[]): StructureBreak[] {
    const breaks: StructureBreak[] = [];
    const n = candles.length;
    const price = candles[n-1].close;

    // BOS = price breaks previous swing high/low IN DIRECTION OF TREND (continuation)
    // CHOCH = price breaks previous swing in OPPOSITE direction (reversal)
    for (let i = 1; i < highs.length; i++) {
      const prevHigh = highs[i-1].price;
      const currHigh = highs[i].price;
      const isHigherHigh = currHigh > prevHigh;

      // Check if recent candles broke through this high
      const breakIdx = candles.findIndex((c, idx) => idx > highs[i].index && c.close > currHigh);
      if (breakIdx > 0) {
        // Was previous structure bullish or bearish?
        const prevLow = lows.filter(l => l.index < highs[i].index).slice(-1)[0];
        const wasUptrend = prevLow && highs[i].price > (prevLow?.price || 0);

        breaks.push({
          type:          wasUptrend ? 'BOS' : 'CHOCH',
          direction:     'BULL',
          price:         currHigh,
          time:          candles[breakIdx].time,
          significance:  isHigherHigh ? 'MAJOR' : 'MINOR',
          description:   wasUptrend
            ? `BOS: Bullish continuation — broke ${currHigh.toFixed(2)}`
            : `CHoCH: Bullish reversal — broke above ${currHigh.toFixed(2)}, structure changed`
        });
      }
    }

    for (let i = 1; i < lows.length; i++) {
      const prevLow = lows[i-1].price;
      const currLow = lows[i].price;
      const isLowerLow = currLow < prevLow;

      const breakIdx = candles.findIndex((c, idx) => idx > lows[i].index && c.close < currLow);
      if (breakIdx > 0) {
        const prevHigh = highs.filter(h => h.index < lows[i].index).slice(-1)[0];
        const wasDowntrend = prevHigh && lows[i].price < (prevHigh?.price || Infinity);

        breaks.push({
          type:          wasDowntrend ? 'BOS' : 'CHOCH',
          direction:     'BEAR',
          price:         currLow,
          time:          candles[breakIdx].time,
          significance:  isLowerLow ? 'MAJOR' : 'MINOR',
          description:   wasDowntrend
            ? `BOS: Bearish continuation — broke ${currLow.toFixed(2)}`
            : `CHoCH: Bearish reversal — broke below ${currLow.toFixed(2)}, structure changed`
        });
      }
    }

    return breaks.sort((a, b) => a.time - b.time);
  }

  // ── Order Block Detection ─────────────────────────────────
  // Bullish OB = last bearish candle before a strong impulsive move UP
  // Bearish OB = last bullish candle before a strong impulsive move DOWN
  private detectOrderBlocks(candles: OHLCV[]): OrderBlock[] {
    const obs: OrderBlock[] = [];
    const n = candles.length;
    const price = candles[n-1].close;

    for (let i = 2; i < n - 3; i++) {
      const c = candles[i];
      const body = Math.abs(c.close - c.open);
      const range = c.high - c.low || 0.0001;

      // Bullish OB: bearish candle followed by strong bullish impulse
      if (c.close < c.open) {  // bearish candle
        const nextThree = candles.slice(i+1, i+4);
        const impulseUp = nextThree.some(n => (n.close - n.open) / range > 0.8 && n.close > c.high);
        if (impulseUp) {
          const mitigated = candles.slice(i+4).some(cc => cc.low <= c.low);
          obs.push({
            top:        c.open,
            bottom:     c.close,
            direction:  'BULL',
            time:       c.time,
            strength:   impulseUp ? 3 : 2,
            mitigated,
            description: `Bull OB at ${c.close.toFixed(2)}–${c.open.toFixed(2)} (${mitigated ? 'mitigated' : 'unmitigated'})`
          });
        }
      }

      // Bearish OB: bullish candle followed by strong bearish impulse
      if (c.close > c.open) {  // bullish candle
        const nextThree = candles.slice(i+1, i+4);
        const impulseDown = nextThree.some(n => (n.open - n.close) / range > 0.8 && n.close < c.low);
        if (impulseDown) {
          const mitigated = candles.slice(i+4).some(cc => cc.high >= c.high);
          obs.push({
            top:        c.close,
            bottom:     c.open,
            direction:  'BEAR',
            time:       c.time,
            strength:   impulseDown ? 3 : 2,
            mitigated,
            description: `Bear OB at ${c.open.toFixed(2)}–${c.close.toFixed(2)} (${mitigated ? 'mitigated' : 'unmitigated'})`
          });
        }
      }
    }

    return obs.filter(ob => !ob.mitigated).slice(-6);
  }

  // ── Fair Value Gap Detection ──────────────────────────────
  // FVG = 3-candle pattern where candle 1 high/low and candle 3 low/high don't overlap
  private detectFairValueGaps(candles: OHLCV[]): FairValueGap[] {
    const fvgs: FairValueGap[] = [];
    const n = candles.length;
    const price = candles[n-1].close;

    for (let i = 1; i < n - 1; i++) {
      const c1 = candles[i-1];
      const c2 = candles[i];
      const c3 = candles[i+1];

      // Bullish FVG: c1.high < c3.low (gap up — inefficiency)
      if (c3.low > c1.high) {
        const gapTop    = c3.low;
        const gapBottom = c1.high;
        const gapSize   = (gapTop - gapBottom) / gapBottom * 100;
        if (gapSize > 0.1) {
          const filled = candles.slice(i+2).some(cc => cc.low <= gapBottom);
          fvgs.push({
            top: gapTop, bottom: gapBottom,
            direction: 'BULL', time: c2.time,
            size: +gapSize.toFixed(3), filled,
            description: `Bull FVG ${gapBottom.toFixed(2)}–${gapTop.toFixed(2)} (${gapSize.toFixed(2)}% gap, ${filled?'filled':'unfilled'})`
          });
        }
      }

      // Bearish FVG: c1.low > c3.high (gap down — inefficiency)
      if (c1.low > c3.high) {
        const gapTop    = c1.low;
        const gapBottom = c3.high;
        const gapSize   = (gapTop - gapBottom) / gapBottom * 100;
        if (gapSize > 0.1) {
          const filled = candles.slice(i+2).some(cc => cc.high >= gapTop);
          fvgs.push({
            top: gapTop, bottom: gapBottom,
            direction: 'BEAR', time: c2.time,
            size: +gapSize.toFixed(3), filled,
            description: `Bear FVG ${gapBottom.toFixed(2)}–${gapTop.toFixed(2)} (${gapSize.toFixed(2)}% gap, ${filled?'filled':'unfilled'})`
          });
        }
      }
    }

    return fvgs.filter(f => !f.filled).slice(-6);
  }

  // ── Liquidity Levels ──────────────────────────────────────
  // Equal highs/lows = trapped traders = liquidity pool = likely target for smart money
  private detectLiquidityLevels(candles: OHLCV[], highs: SwingPoint[], lows: SwingPoint[]): LiquidityLevel[] {
    const levels: LiquidityLevel[] = [];
    const tolerance = 0.003; // 0.3%

    // Equal highs (Buy Side Liquidity)
    for (let i = 0; i < highs.length - 1; i++) {
      for (let j = i + 1; j < highs.length; j++) {
        const diff = Math.abs(highs[i].price - highs[j].price) / highs[i].price;
        if (diff < tolerance) {
          const swept = candles.slice(highs[j].index).some(c => c.high > highs[j].price * 1.001);
          levels.push({
            price:       (highs[i].price + highs[j].price) / 2,
            type:        'EQL_HIGHS',
            time:        highs[j].time,
            swept,
            description: `Equal Highs ~${highs[j].price.toFixed(2)} (Buy Side Liquidity${swept?' — swept':''})`
          });
          break;
        }
      }
    }

    // Equal lows (Sell Side Liquidity)
    for (let i = 0; i < lows.length - 1; i++) {
      for (let j = i + 1; j < lows.length; j++) {
        const diff = Math.abs(lows[i].price - lows[j].price) / lows[i].price;
        if (diff < tolerance) {
          const swept = candles.slice(lows[j].index).some(c => c.low < lows[j].price * 0.999);
          levels.push({
            price:       (lows[i].price + lows[j].price) / 2,
            type:        'EQL_LOWS',
            time:        lows[j].time,
            swept,
            description: `Equal Lows ~${lows[j].price.toFixed(2)} (Sell Side Liquidity${swept?' — swept':''})`
          });
          break;
        }
      }
    }

    return levels.filter(l => !l.swept).slice(-6);
  }

  // ── Premium / Discount Zones ──────────────────────────────
  // Based on the swing range — discount = below 50% fib, premium = above 50%
  private calculatePremiumDiscount(candles: OHLCV[], highs: SwingPoint[], lows: SwingPoint[]): PremiumDiscount | null {
    if (!highs.length || !lows.length) return null;
    const recentHigh = Math.max(...highs.slice(-3).map(h => h.price));
    const recentLow  = Math.min(...lows.slice(-3).map(l => l.price));
    const range = recentHigh - recentLow;
    if (range <= 0) return null;

    const equilibrium = recentLow + range * 0.5;
    const price       = candles[candles.length-1].close;
    const fibLevel    = (price - recentLow) / range;

    return {
      equilibrium: +equilibrium.toFixed(4),
      premium:     +recentHigh.toFixed(4),
      discount:    +recentLow.toFixed(4),
      fibLevel:    +fibLevel.toFixed(3),
      currentZone: fibLevel > 0.55 ? 'PREMIUM' : fibLevel < 0.45 ? 'DISCOUNT' : 'EQUILIBRIUM'
    };
  }

  // ── Bias Determination ────────────────────────────────────
  private determineBias(breaks: StructureBreak[], obs: OrderBlock[]): {
    trend: SMCAnalysis['trend']; bias: SMCAnalysis['bias']; biasStrength: number
  } {
    const recentBreaks = breaks.slice(-4);
    const bullBreaks   = recentBreaks.filter(b => b.direction === 'BULL').length;
    const bearBreaks   = recentBreaks.filter(b => b.direction === 'BEAR').length;
    const bullOBs      = obs.filter(o => o.direction === 'BULL' && !o.mitigated).length;
    const bearOBs      = obs.filter(o => o.direction === 'BEAR' && !o.mitigated).length;

    let bullScore = bullBreaks * 25 + bullOBs * 15;
    let bearScore = bearBreaks * 25 + bearOBs * 15;

    // Recent CHoCH has more weight
    const recentChoch = recentBreaks.slice(-2).filter(b => b.type === 'CHOCH');
    recentChoch.forEach(c => { if(c.direction==='BULL') bullScore+=20; else bearScore+=20; });

    const total = bullScore + bearScore || 1;
    const bias: SMCAnalysis['bias'] =
      bullScore > bearScore * 1.3 ? 'BULLISH' :
      bearScore > bullScore * 1.3 ? 'BEARISH' : 'NEUTRAL';

    const biasStrength = Math.round(Math.max(bullScore, bearScore) / total * 100);
    const trend: SMCAnalysis['trend'] =
      bullScore > bearScore * 1.5 ? 'BULL_TREND' :
      bearScore > bullScore * 1.5 ? 'BEAR_TREND' : 'RANGING';

    return { trend, bias, biasStrength };
  }

  // ── Entry Setup Detection ─────────────────────────────────
  // Finds specific high-probability setups
  private findEntrySetup(
    candles: OHLCV[], obs: OrderBlock[], fvgs: FairValueGap[],
    bias: string, pd: PremiumDiscount | null
  ): string | null {
    const price = candles[candles.length-1].close;

    if (bias === 'BULLISH') {
      // Best bull setup: price in discount zone + at unmitigated bull OB + FVG above
      const nearBullOB = obs.find(ob =>
        ob.direction === 'BULL' && !ob.mitigated &&
        price >= ob.bottom * 0.995 && price <= ob.top * 1.01
      );
      const inDiscount = pd?.currentZone === 'DISCOUNT';
      const bullFVGAbove = fvgs.find(f => f.direction === 'BULL' && f.bottom > price);

      if (nearBullOB && inDiscount)
        return `🎯 HIGH PROBABILITY LONG: Price at Bull OB (${nearBullOB.bottom.toFixed(2)}–${nearBullOB.top.toFixed(2)}) in DISCOUNT zone${bullFVGAbove?'. FVG target: '+bullFVGAbove.top.toFixed(2):''}. Enter on bullish confirmation candle.`;
      if (nearBullOB)
        return `📍 Potential LONG: At Bull OB (${nearBullOB.bottom.toFixed(2)}–${nearBullOB.top.toFixed(2)}). Wait for mitigation + bullish reaction.`;
      if (inDiscount && bullFVGAbove)
        return `📍 Potential LONG: In discount zone. Bull FVG above at ${bullFVGAbove.bottom.toFixed(2)}–${bullFVGAbove.top.toFixed(2)} is a magnet target.`;
    }

    if (bias === 'BEARISH') {
      const nearBearOB = obs.find(ob =>
        ob.direction === 'BEAR' && !ob.mitigated &&
        price <= ob.top * 1.005 && price >= ob.bottom * 0.99
      );
      const inPremium = pd?.currentZone === 'PREMIUM';
      const bearFVGBelow = fvgs.find(f => f.direction === 'BEAR' && f.top < price);

      if (nearBearOB && inPremium)
        return `🎯 HIGH PROBABILITY SHORT: Price at Bear OB (${nearBearOB.bottom.toFixed(2)}–${nearBearOB.top.toFixed(2)}) in PREMIUM zone${bearFVGBelow?'. FVG target: '+bearFVGBelow.bottom.toFixed(2):''}. Enter on bearish confirmation candle.`;
      if (nearBearOB)
        return `📍 Potential SHORT: At Bear OB (${nearBearOB.bottom.toFixed(2)}–${nearBearOB.top.toFixed(2)}). Wait for rejection.`;
    }

    return null;
  }

  private buildKeyLevels(obs: OrderBlock[], fvgs: FairValueGap[], liq: LiquidityLevel[], price: number) {
    const levels: { price: number; label: string; type: string }[] = [];
    obs.filter(o=>!o.mitigated).forEach(o => {
      levels.push({ price: (o.top+o.bottom)/2, label: `${o.direction === 'BULL' ? '🟢' : '🔴'} ${o.direction} OB`, type: o.direction === 'BULL' ? 'support' : 'resistance' });
    });
    fvgs.filter(f=>!f.filled).forEach(f => {
      levels.push({ price: (f.top+f.bottom)/2, label: `${f.direction === 'BULL' ? '🟢' : '🔴'} FVG (${f.size.toFixed(2)}%)`, type: 'imbalance' });
    });
    liq.filter(l=>!l.swept).forEach(l => {
      levels.push({ price: l.price, label: `💧 ${l.type === 'EQL_HIGHS' ? 'BSL' : 'SSL'} ~${l.price.toFixed(2)}`, type: 'liquidity' });
    });
    return levels.sort((a, b) => Math.abs(a.price - price) - Math.abs(b.price - price));
  }

  private buildSummary(symbol: string, bias: string, strength: number, breaks: StructureBreak[], obs: OrderBlock[], fvgs: FairValueGap[], liq: LiquidityLevel[], pd: PremiumDiscount | null, setup: string | null): string {
    const lastBreak = breaks.slice(-1)[0];
    const unmitigatedOBs = obs.filter(o => !o.mitigated).length;
    const unfilledFVGs = fvgs.filter(f => !f.filled).length;
    const pdStr = pd ? ` Price in ${pd.currentZone} zone (${(pd.fibLevel*100).toFixed(0)}% of range).` : '';
    const setupStr = setup ? ` ${setup}` : ' Wait for price to reach key OB or FVG for entry.';
    const bosStr = lastBreak ? ` Last structure event: ${lastBreak.type} ${lastBreak.direction} at ${lastBreak.price.toFixed(2)}.` : '';
    return `SMC ${bias} bias (${strength}% strength). ${unmitigatedOBs} active Order Blocks, ${unfilledFVGs} unfilled FVGs.${pdStr}${bosStr}${setupStr}`;
  }

  private emptyAnalysis(): SMCAnalysis {
    return { swingHighs:[], swingLows:[], orderBlocks:[], fairValueGaps:[], liquidityLevels:[], structureBreaks:[], premiumDiscount:null, trend:'RANGING', bias:'NEUTRAL', biasStrength:0, keyLevels:[], summary:'Need 50+ candles for SMC analysis.', entrySetup:null };
  }
}
