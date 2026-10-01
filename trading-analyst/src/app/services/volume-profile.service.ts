import { Injectable } from '@angular/core';
import { OHLCV } from './ta-engine.service';

export interface VolumeBucket {
  priceLevel:  number;
  volume:      number;
  buyVolume:   number;
  sellVolume:  number;
  percent:     number;        // % of total volume
  isHVN:       boolean;       // High Volume Node
  isLVN:       boolean;       // Low Volume Node
  isPOC:       boolean;       // Point of Control
  isVAH:       boolean;       // Value Area High
  isVAL:       boolean;       // Value Area Low
}

export interface VolumeProfile {
  poc:          number;       // Point of Control (highest volume price)
  vah:          number;       // Value Area High (70% of volume above this — top of value area)
  val:          number;       // Value Area Low (70% of volume below this — bottom of value area)
  hvns:         number[];     // High Volume Nodes — strong support/resistance
  lvns:         number[];     // Low Volume Nodes — thin areas price moves through fast
  buckets:      VolumeBucket[];
  currentPrice: number;
  priceLocation: 'ABOVE_VAH' | 'INSIDE_VA' | 'BELOW_VAL';
  bias:         'BULLISH' | 'BEARISH' | 'NEUTRAL';
  nearestHVN:   { price: number; distance: number; direction: 'ABOVE' | 'BELOW' } | null;
  nearestLVN:   { price: number; distance: number; direction: 'ABOVE' | 'BELOW' } | null;
  interpretation: string;
  tradingLevels: { price: number; label: string; strength: 'STRONG' | 'MEDIUM' | 'WEAK' }[];
}

@Injectable({ providedIn: 'root' })
export class VolumeProfileService {

  analyze(candles: OHLCV[], numBuckets = 50): VolumeProfile {
    if (candles.length < 20) return this.emptyProfile(candles);

    const price  = candles[candles.length-1].close;
    const high   = Math.max(...candles.map(c => c.high));
    const low    = Math.min(...candles.map(c => c.low));
    const range  = high - low;
    if (range <= 0) return this.emptyProfile(candles);

    const bucketSize = range / numBuckets;
    const buckets:VolumeBucket[] = Array.from({ length: numBuckets }, (_, i) => ({
      priceLevel: low + i * bucketSize + bucketSize / 2,
      volume: 0, buyVolume: 0, sellVolume: 0, percent: 0,
      isHVN: false, isLVN: false, isPOC: false, isVAH: false, isVAL: false
    }));

    // Distribute volume into buckets
    let totalVolume = 0;
    for (const c of candles) {
      const cRange = c.high - c.low || bucketSize;
      const isBull = c.close >= c.open;

      // Distribute candle volume across price range it covered
      for (let i = 0; i < numBuckets; i++) {
        const bucketLow  = low + i * bucketSize;
        const bucketHigh = bucketLow + bucketSize;
        const overlap    = Math.min(c.high, bucketHigh) - Math.max(c.low, bucketLow);

        if (overlap > 0) {
          const volShare = (overlap / cRange) * c.volume;
          buckets[i].volume += volShare;
          if (isBull) buckets[i].buyVolume  += volShare;
          else        buckets[i].sellVolume += volShare;
          totalVolume += volShare;
        }
      }
    }

    if (totalVolume === 0) return this.emptyProfile(candles);

    // Calculate percentages
    buckets.forEach(b => { b.percent = +((b.volume / totalVolume) * 100).toFixed(2); });

    // Find POC (highest volume bucket)
    const pocBucket = buckets.reduce((a, b) => a.volume > b.volume ? a : b);
    pocBucket.isPOC = true;

    // Value Area: 70% of total volume centered around POC
    const { vah, val } = this.calculateValueArea(buckets, pocBucket, totalVolume);

    // Mark VAH / VAL
    buckets.forEach(b => {
      if (Math.abs(b.priceLevel - vah) < bucketSize) b.isVAH = true;
      if (Math.abs(b.priceLevel - val) < bucketSize) b.isVAL = true;
    });

    // HVN / LVN detection
    const avgVol = totalVolume / numBuckets;
    const hvns: number[] = [], lvns: number[] = [];

    buckets.forEach((b, i) => {
      const isLocalMax = i > 0 && i < numBuckets-1 &&
        b.volume > buckets[i-1].volume && b.volume > buckets[i+1].volume && b.volume > avgVol * 1.5;
      const isLocalMin = i > 0 && i < numBuckets-1 &&
        b.volume < buckets[i-1].volume && b.volume < buckets[i+1].volume && b.volume < avgVol * 0.4;

      if (isLocalMax) { b.isHVN = true; hvns.push(b.priceLevel); }
      if (isLocalMin) { b.isLVN = true; lvns.push(b.priceLevel); }
    });

    // Price location
    const priceLocation: VolumeProfile['priceLocation'] =
      price > vah ? 'ABOVE_VAH' : price < val ? 'BELOW_VAL' : 'INSIDE_VA';

    // Nearest HVN and LVN
    const nearestHVN = hvns.length ? hvns
      .map(h => ({ price: h, distance: Math.abs(h - price) / price * 100, direction: (h >= price ? 'ABOVE' : 'BELOW') as 'ABOVE'|'BELOW' }))
      .sort((a,b) => a.distance - b.distance)[0] : null;

    const nearestLVN = lvns.length ? lvns
      .map(l => ({ price: l, distance: Math.abs(l - price) / price * 100, direction: (l >= price ? 'ABOVE' : 'BELOW') as 'ABOVE'|'BELOW' }))
      .sort((a,b) => a.distance - b.distance)[0] : null;

    // Bias
    const bias: VolumeProfile['bias'] =
      price > pocBucket.priceLevel && priceLocation === 'ABOVE_VAH' ? 'BULLISH' :
      price < pocBucket.priceLevel && priceLocation === 'BELOW_VAL' ? 'BEARISH' : 'NEUTRAL';

    const tradingLevels = this.buildTradingLevels(pocBucket.priceLevel, vah, val, hvns, price);
    const interpretation = this.buildInterpretation(price, pocBucket.priceLevel, vah, val, priceLocation, nearestHVN, nearestLVN, hvns, lvns);

    return {
      poc: +pocBucket.priceLevel.toFixed(4),
      vah: +vah.toFixed(4),
      val: +val.toFixed(4),
      hvns: hvns.slice(0, 5).map(h => +h.toFixed(4)),
      lvns: lvns.slice(0, 5).map(l => +l.toFixed(4)),
      buckets: buckets.slice(0, 50),   // limit for display
      currentPrice: +price.toFixed(4),
      priceLocation,
      bias,
      nearestHVN,
      nearestLVN,
      interpretation,
      tradingLevels
    };
  }

  private calculateValueArea(buckets: VolumeBucket[], poc: VolumeBucket, total: number) {
    const target = total * 0.70;
    let included = poc.volume;
    const pocIdx = buckets.indexOf(poc);
    let upper = pocIdx, lower = pocIdx;

    while (included < target && (upper < buckets.length-1 || lower > 0)) {
      const addUp   = upper < buckets.length-1 ? buckets[upper+1].volume : 0;
      const addDown = lower > 0 ? buckets[lower-1].volume : 0;

      if (addUp >= addDown && upper < buckets.length-1) { upper++; included += buckets[upper].volume; }
      else if (lower > 0) { lower--; included += buckets[lower].volume; }
      else break;
    }

    return { vah: buckets[upper].priceLevel, val: buckets[lower].priceLevel };
  }

  private buildTradingLevels(poc: number, vah: number, val: number, hvns: number[], price: number) {
    const levels: { price: number; label: string; strength: 'STRONG'|'MEDIUM'|'WEAK' }[] = [
      { price: poc, label: '🎯 POC (Point of Control)', strength: 'STRONG' },
      { price: vah, label: '📊 VAH (Value Area High)', strength: 'STRONG' },
      { price: val, label: '📊 VAL (Value Area Low)',  strength: 'STRONG' },
    ];
    hvns.forEach(h => {
      const dist = Math.abs(h-price)/price*100;
      if (dist < 5) levels.push({ price: h, label: `💎 HVN (High Vol Node)`, strength: 'MEDIUM' });
    });
    return levels.sort((a,b) => Math.abs(a.price-price) - Math.abs(b.price-price));
  }

  private buildInterpretation(price: number, poc: number, vah: number, val: number, location: string, hvn: any, lvn: any, hvns: number[], lvns: number[]): string {
    const parts: string[] = [];

    if (location === 'ABOVE_VAH')
      parts.push(`Price ABOVE value area (${vah.toFixed(2)}). Strong bullish — institutions accepted higher prices. VAH becomes support.`);
    else if (location === 'BELOW_VAL')
      parts.push(`Price BELOW value area (${val.toFixed(2)}). Bearish — institutions rejected lower prices. VAL becomes resistance.`);
    else
      parts.push(`Price INSIDE value area (${val.toFixed(2)}–${vah.toFixed(2)}). Balanced market — range-bound between VAL and VAH.`);

    parts.push(`POC at ${poc.toFixed(2)} — highest traded price, acts as magnet.`);

    if (hvn) parts.push(`Nearest HVN ${hvn.direction === 'ABOVE' ? 'resistance' : 'support'} at ${hvn.price.toFixed(2)} (${hvn.distance.toFixed(1)}% away).`);
    if (lvn) parts.push(`Nearest LVN at ${lvn.price.toFixed(2)} — thin area, price moves quickly through here.`);

    return parts.join(' ');
  }

  private emptyProfile(candles: OHLCV[]): VolumeProfile {
    const p = candles[candles.length-1]?.close || 0;
    return { poc:p, vah:p, val:p, hvns:[], lvns:[], buckets:[], currentPrice:p, priceLocation:'INSIDE_VA', bias:'NEUTRAL', nearestHVN:null, nearestLVN:null, interpretation:'Need 20+ candles for volume profile.', tradingLevels:[] };
  }
}
