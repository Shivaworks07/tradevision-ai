import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, of, map, catchError, forkJoin } from 'rxjs';

export interface OptionStrike {
  strikePrice:  number;
  expiryDate:   string;
  CE: {
    oi: number; oiChange: number; volume: number;
    iv: number; ltp: number; delta?: number;
    oiChangeDir: 'UP' | 'DOWN';
  };
  PE: {
    oi: number; oiChange: number; volume: number;
    iv: number; ltp: number; delta?: number;
    oiChangeDir: 'UP' | 'DOWN';
  };
  isATM: boolean;   // At The Money
  ceOiHighlight: boolean;  // unusually high CE OI (resistance)
  peOiHighlight: boolean;  // unusually high PE OI (support)
}

export interface OptionChainData {
  symbol:         string;
  spotPrice:      number;
  expiryDates:    string[];
  selectedExpiry: string;
  strikes:        OptionStrike[];
  // PCR Analysis
  totalCEOI:      number;
  totalPEOI:      number;
  pcr:            number;       // Put-Call Ratio = Total PE OI / Total CE OI
  pcrSignal:      'BULLISH' | 'BEARISH' | 'NEUTRAL';
  pcrInterpretation: string;
  // Max Pain
  maxPain:        number;       // strike where max options expire worthless
  maxPainDiff:    number;       // % diff from current price
  // Key levels from OI
  strongSupports: number[];     // High PE OI = strong support
  strongResistances: number[];  // High CE OI = strong resistance
  // IV Analysis
  ivPercentile:   number;       // how high/low IV is vs history
  ivSignal:       string;
  // Summary
  bias:           'BULLISH' | 'BEARISH' | 'NEUTRAL';
  summary:        string;
  lastUpdated:    string;
}

@Injectable({ providedIn: 'root' })
export class OptionChainService {
  private http = inject(HttpClient);

  getOptionChain(symbol = 'NIFTY', expiry = ''): Observable<OptionChainData> {
    const url = symbol === 'NIFTY'
      ? '/nse-api/api/option-chain-indices?symbol=NIFTY'
      : symbol === 'BANKNIFTY'
      ? '/nse-api/api/option-chain-indices?symbol=BANKNIFTY'
      : `/nse-api/api/option-chain-equities?symbol=${symbol}`;

    return this.http.get<any>(url).pipe(
      map(r => this.processChain(r, symbol, expiry)),
      catchError(err => {
        console.warn('[OptionChain] NSE API failed:', err.message);
        return of(this.emptyChain(symbol));
      })
    );
  }

  private processChain(raw: any, symbol: string, selectedExpiry: string): OptionChainData {
    const records    = raw?.records;
    const filtered   = raw?.filtered;
    if (!records) return this.emptyChain(symbol);

    const spotPrice   = records.underlyingValue || 0;
    const expiryDates = records.expiryDates || [];
    const expiry      = selectedExpiry || expiryDates[0] || '';
    const data        = (records.data || []).filter((d: any) => d.expiryDate === expiry);

    if (!data.length) return this.emptyChain(symbol);

    // Build strike rows
    const strikes: OptionStrike[] = data
      .filter((d: any) => d.CE || d.PE)
      .map((d: any) => {
        const sp = d.strikePrice;
        const ce = d.CE || {};
        const pe = d.PE || {};
        return {
          strikePrice: sp,
          expiryDate:  expiry,
          CE: {
            oi:         ce.openInterest || 0,
            oiChange:   ce.changeinOpenInterest || 0,
            volume:     ce.totalTradedVolume || 0,
            iv:         ce.impliedVolatility || 0,
            ltp:        ce.lastPrice || 0,
            oiChangeDir: (ce.changeinOpenInterest || 0) >= 0 ? 'UP' : 'DOWN'
          },
          PE: {
            oi:         pe.openInterest || 0,
            oiChange:   pe.changeinOpenInterest || 0,
            volume:     pe.totalTradedVolume || 0,
            iv:         pe.impliedVolatility || 0,
            ltp:        pe.lastPrice || 0,
            oiChangeDir: (pe.changeinOpenInterest || 0) >= 0 ? 'UP' : 'DOWN'
          },
          isATM:         Math.abs(sp - spotPrice) < (spotPrice * 0.005),
          ceOiHighlight: false,
          peOiHighlight: false
        };
      })
      .sort((a: OptionStrike, b: OptionStrike) => a.strikePrice - b.strikePrice);

    // Mark high OI strikes
    const allCEOI = strikes.map(s => s.CE.oi);
    const allPEOI = strikes.map(s => s.PE.oi);
    const avgCEOI = allCEOI.reduce((a,b)=>a+b,0) / (allCEOI.length||1);
    const avgPEOI = allPEOI.reduce((a,b)=>a+b,0) / (allPEOI.length||1);
    strikes.forEach(s => {
      s.ceOiHighlight = s.CE.oi > avgCEOI * 2.5;
      s.peOiHighlight = s.PE.oi > avgPEOI * 2.5;
    });

    // PCR
    const totalCEOI = allCEOI.reduce((a,b)=>a+b,0);
    const totalPEOI = allPEOI.reduce((a,b)=>a+b,0);
    const pcr = totalCEOI > 0 ? totalPEOI / totalCEOI : 1;

    const { pcrSignal, pcrInterpretation } = this.analyzePCR(pcr);

    // Max Pain — strike where total option sellers profit most
    const maxPain = this.calcMaxPain(strikes);
    const maxPainDiff = spotPrice > 0 ? ((maxPain - spotPrice) / spotPrice) * 100 : 0;

    // Key levels from OI
    const strikesNearATM = strikes.filter(s =>
      Math.abs(s.strikePrice - spotPrice) < spotPrice * 0.05
    );
    const strongResistances = strikesNearATM
      .filter(s => s.CE.oi > avgCEOI * 1.5 && s.strikePrice > spotPrice)
      .sort((a,b) => b.CE.oi - a.CE.oi)
      .slice(0,3).map(s => s.strikePrice);

    const strongSupports = strikesNearATM
      .filter(s => s.PE.oi > avgPEOI * 1.5 && s.strikePrice < spotPrice)
      .sort((a,b) => b.PE.oi - a.PE.oi)
      .slice(0,3).map(s => s.strikePrice);

    // IV Analysis
    const avgIV = strikes.filter(s => s.isATM).map(s => (s.CE.iv + s.PE.iv) / 2)[0] || 20;
    const ivSignal = avgIV > 25 ? 'High IV — options expensive, sell strategies preferred'
      : avgIV < 12 ? 'Low IV — cheap options, good time to buy'
      : 'Normal IV range';

    // Overall bias
    const bias: OptionChainData['bias'] =
      pcr > 1.3 ? 'BULLISH' : pcr < 0.7 ? 'BEARISH' : 'NEUTRAL';

    const summary = this.buildSummary(symbol, spotPrice, pcr, pcrSignal, maxPain, maxPainDiff, strongSupports, strongResistances, bias);

    return {
      symbol, spotPrice, expiryDates, selectedExpiry: expiry,
      strikes: this.getStrikesNearATM(strikes, spotPrice, 20),
      totalCEOI, totalPEOI, pcr: +pcr.toFixed(2),
      pcrSignal, pcrInterpretation,
      maxPain, maxPainDiff: +maxPainDiff.toFixed(2),
      strongSupports, strongResistances,
      ivPercentile: Math.round(avgIV),
      ivSignal, bias, summary,
      lastUpdated: new Date().toLocaleTimeString('en-IN', {hour12:false})
    };
  }

  private analyzePCR(pcr: number): { pcrSignal: OptionChainData['pcrSignal']; pcrInterpretation: string } {
    // PCR > 1 = more puts = bearish bets = contrarian bullish
    // PCR < 1 = more calls = bullish bets = contrarian bearish
    if (pcr >= 1.5) return {
      pcrSignal: 'BULLISH',
      pcrInterpretation: `PCR ${pcr.toFixed(2)} — very high put writing. Institutions are selling puts = expecting market to hold or rise. Strong bullish signal.`
    };
    if (pcr >= 1.1) return {
      pcrSignal: 'BULLISH',
      pcrInterpretation: `PCR ${pcr.toFixed(2)} — elevated puts. Moderate bullish bias. More hedging than speculative shorting.`
    };
    if (pcr <= 0.6) return {
      pcrSignal: 'BEARISH',
      pcrInterpretation: `PCR ${pcr.toFixed(2)} — very high call buying. Market over-optimistic. Contrarian bearish signal.`
    };
    if (pcr <= 0.8) return {
      pcrSignal: 'BEARISH',
      pcrInterpretation: `PCR ${pcr.toFixed(2)} — more calls than puts. Slight bearish bias. Watch for reversal.`
    };
    return {
      pcrSignal: 'NEUTRAL',
      pcrInterpretation: `PCR ${pcr.toFixed(2)} — balanced put/call ratio. Market in equilibrium. No strong directional bias.`
    };
  }

  private calcMaxPain(strikes: OptionStrike[]): number {
    if (!strikes.length) return 0;
    let minPain = Infinity;
    let maxPainStrike = strikes[0].strikePrice;

    for (const target of strikes) {
      let totalPain = 0;
      for (const s of strikes) {
        // CE pain: call holders lose if target > strike
        if (target.strikePrice > s.strikePrice) totalPain += s.CE.oi * (target.strikePrice - s.strikePrice);
        // PE pain: put holders lose if target < strike
        if (target.strikePrice < s.strikePrice) totalPain += s.PE.oi * (s.strikePrice - target.strikePrice);
      }
      if (totalPain < minPain) { minPain = totalPain; maxPainStrike = target.strikePrice; }
    }
    return maxPainStrike;
  }

  private getStrikesNearATM(strikes: OptionStrike[], spot: number, count: number): OptionStrike[] {
    const sorted = [...strikes].sort((a,b) => Math.abs(a.strikePrice-spot) - Math.abs(b.strikePrice-spot));
    const near = sorted.slice(0, count);
    return near.sort((a,b) => a.strikePrice - b.strikePrice);
  }

  private buildSummary(sym: string, spot: number, pcr: number, pcrSig: string, maxPain: number, mpDiff: number, supports: number[], resists: number[], bias: string): string {
    const parts = [`${sym} @ ${spot.toLocaleString('en-IN')}. PCR ${pcr.toFixed(2)} → ${pcrSig}.`];
    if (resists.length) parts.push(`CE OI resistance: ${resists.slice(0,2).join(', ')}.`);
    if (supports.length) parts.push(`PE OI support: ${supports.slice(0,2).join(', ')}.`);
    parts.push(`Max Pain: ${maxPain} (${mpDiff >= 0 ? '+' : ''}${mpDiff.toFixed(1)}% from spot).`);
    parts.push(`Overall bias: ${bias}.`);
    return parts.join(' ');
  }

  private emptyChain(symbol: string): OptionChainData {
    return { symbol, spotPrice:0, expiryDates:[], selectedExpiry:'', strikes:[], totalCEOI:0, totalPEOI:0, pcr:1, pcrSignal:'NEUTRAL', pcrInterpretation:'NSE data unavailable', maxPain:0, maxPainDiff:0, strongSupports:[], strongResistances:[], ivPercentile:20, ivSignal:'N/A', bias:'NEUTRAL', summary:'Option chain data unavailable', lastUpdated:'' };
  }
}
