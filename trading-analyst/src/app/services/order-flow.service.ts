import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, forkJoin, of, map, catchError } from 'rxjs';

export interface FundingData {
  symbol:       string;
  fundingRate:  number;       // Current funding rate
  nextFunding:  number;       // Next funding rate
  bias:         'LONG_BIAS' | 'SHORT_BIAS' | 'NEUTRAL';
  interpretation: string;
}

export interface OpenInterestData {
  symbol:       string;
  oi:           number;       // Current OI in USD
  oiChange1h:   number;       // % change last hour
  oiChange24h:  number;       // % change 24h
  priceChange:  number;       // price % change same period
  signal:       'LONGS_BUILDING' | 'SHORTS_BUILDING' | 'LONG_SQUEEZE' | 'SHORT_SQUEEZE' | 'NEUTRAL';
  interpretation: string;
}

export interface LiquidationData {
  symbol:        string;
  longLiqs24h:   number;      // USD value of long liquidations
  shortLiqs24h:  number;
  dominance:     'LONG_LIQD' | 'SHORT_LIQD' | 'BALANCED';
  interpretation: string;
}

export interface CVDData {
  cvd:          number;       // Cumulative Volume Delta
  cvdTrend:     'RISING' | 'FALLING' | 'FLAT';
  buyVol:       number;
  sellVol:      number;
  interpretation: string;
}

export interface OrderFlowAnalysis {
  funding:      FundingData | null;
  openInterest: OpenInterestData | null;
  liquidations: LiquidationData | null;
  cvd:          CVDData | null;
  overallBias:  'STRONG_BULL' | 'BULL' | 'NEUTRAL' | 'BEAR' | 'STRONG_BEAR';
  score:        number;       // -100 to +100
  reasons:      string[];
  summary:      string;
  isLoaded:     boolean;
}

@Injectable({ providedIn: 'root' })
export class OrderFlowService {
  private http = inject(HttpClient);

  analyze(symbol: string): Observable<OrderFlowAnalysis> {
    // symbol = "BTC", convert to BTCUSDT for futures
    const futuresSymbol = symbol.toUpperCase().replace('/USDT', '') + 'USDT';

    return forkJoin({
      funding:  this.fetchFunding(futuresSymbol),
      oi:       this.fetchOpenInterest(futuresSymbol),
      oiHist:   this.fetchOIHistory(futuresSymbol),
      trades:   this.fetchRecentTrades(futuresSymbol),
      ticker:   this.fetchTicker(futuresSymbol),
    }).pipe(
      map(({ funding, oi, oiHist, trades, ticker }) => {
        const fundingData  = this.processFunding(funding, futuresSymbol);
        const oiData       = this.processOI(oi, oiHist, ticker, futuresSymbol);
        const liqData      = null; // Liquidation endpoint requires auth on Binance
        const cvdData      = this.processCVD(trades, futuresSymbol);
        const { bias, score, reasons } = this.calculateBias(fundingData, oiData, cvdData);

        return {
          funding:      fundingData,
          openInterest: oiData,
          liquidations: liqData,
          cvd:          cvdData,
          overallBias:  bias,
          score,
          reasons,
          summary:      this.buildSummary(symbol, bias, score, fundingData, oiData, cvdData),
          isLoaded:     true
        };
      }),
      catchError(err => {
        console.warn('[OrderFlow] Error:', err);
        return of(this.emptyAnalysis());
      })
    );
  }

  private fetchFunding(symbol: string): Observable<any> {
    return this.http.get<any>(`/binance-futures/fapi/v1/premiumIndex?symbol=${symbol}`)
      .pipe(catchError(() => of(null)));
  }

  private fetchOpenInterest(symbol: string): Observable<any> {
    return this.http.get<any>(`/binance-futures/fapi/v1/openInterest?symbol=${symbol}`)
      .pipe(catchError(() => of(null)));
  }

  private fetchOIHistory(symbol: string): Observable<any[]> {
    return this.http.get<any[]>(`/binance-futures/futures/data/openInterestHist?symbol=${symbol}&period=1h&limit=24`)
      .pipe(catchError(() => of([])));
  }

  private fetchRecentTrades(symbol: string): Observable<any[]> {
    // Aggregate trades to compute CVD
    return this.http.get<any[]>(`/binance-futures/fapi/v1/aggTrades?symbol=${symbol}&limit=200`)
      .pipe(catchError(() => of([])));
  }

  private fetchTicker(symbol: string): Observable<any> {
    return this.http.get<any>(`/binance-futures/fapi/v1/ticker/24hr?symbol=${symbol}`)
      .pipe(catchError(() => of(null)));
  }

  private processFunding(data: any, symbol: string): FundingData | null {
    if (!data) return null;
    const rate = parseFloat(data.lastFundingRate || 0) * 100; // as %

    let bias: FundingData['bias'];
    let interpretation: string;

    if (rate > 0.05) {
      bias = 'LONG_BIAS';
      interpretation = `Funding ${rate.toFixed(4)}% — longs paying shorts. Market overleveraged long. Potential long squeeze if price dips.`;
    } else if (rate < -0.05) {
      bias = 'SHORT_BIAS';
      interpretation = `Funding ${rate.toFixed(4)}% — shorts paying longs. Market overleveraged short. Potential short squeeze.`;
    } else if (rate > 0.01) {
      bias = 'LONG_BIAS';
      interpretation = `Funding ${rate.toFixed(4)}% — slightly positive. Mild bullish sentiment in futures.`;
    } else if (rate < -0.01) {
      bias = 'SHORT_BIAS';
      interpretation = `Funding ${rate.toFixed(4)}% — slightly negative. Mild bearish sentiment in futures.`;
    } else {
      bias = 'NEUTRAL';
      interpretation = `Funding ${rate.toFixed(4)}% — neutral. Balanced leverage, no extreme positioning.`;
    }

    return { symbol, fundingRate: +rate.toFixed(4), nextFunding: +rate.toFixed(4), bias, interpretation };
  }

  private processOI(oi: any, oiHist: any[], ticker: any, symbol: string): OpenInterestData | null {
    if (!oi) return null;
    const currentOI = parseFloat(oi.openInterest || 0);
    const price     = ticker ? parseFloat(ticker.lastPrice || 0) : 0;
    const oiUSD     = currentOI * price;

    let oiChange1h = 0, oiChange24h = 0, priceChange = 0;

    if (oiHist.length >= 2) {
      const oldest = parseFloat(oiHist[0]?.sumOpenInterest || currentOI);
      const hour1  = parseFloat(oiHist[Math.max(0, oiHist.length-2)]?.sumOpenInterest || currentOI);
      oiChange1h  = oldest > 0 ? ((currentOI - hour1) / hour1) * 100 : 0;
      oiChange24h = oldest > 0 ? ((currentOI - oldest) / oldest) * 100 : 0;
    }

    if (ticker) priceChange = parseFloat(ticker.priceChangePercent || 0);

    // OI + Price interpretation
    let signal: OpenInterestData['signal'];
    let interpretation: string;

    if (oiChange1h > 2 && priceChange > 0) {
      signal = 'LONGS_BUILDING';
      interpretation = `OI +${oiChange1h.toFixed(1)}% with price up — longs building. Bullish momentum.`;
    } else if (oiChange1h > 2 && priceChange < 0) {
      signal = 'SHORTS_BUILDING';
      interpretation = `OI +${oiChange1h.toFixed(1)}% with price down — shorts building. Bearish pressure.`;
    } else if (oiChange1h < -3 && priceChange > 0) {
      signal = 'SHORT_SQUEEZE';
      interpretation = `OI dropping ${oiChange1h.toFixed(1)}% with price rising — SHORT SQUEEZE in progress!`;
    } else if (oiChange1h < -3 && priceChange < 0) {
      signal = 'LONG_SQUEEZE';
      interpretation = `OI dropping ${oiChange1h.toFixed(1)}% with price falling — LONG SQUEEZE / capitulation.`;
    } else {
      signal = 'NEUTRAL';
      interpretation = `OI change ${oiChange1h.toFixed(1)}% — no strong positioning bias.`;
    }

    return { symbol, oi: +oiUSD.toFixed(0), oiChange1h: +oiChange1h.toFixed(2), oiChange24h: +oiChange24h.toFixed(2), priceChange: +priceChange.toFixed(2), signal, interpretation };
  }

  private processCVD(trades: any[], symbol: string): CVDData | null {
    if (!trades || !trades.length) return null;

    let buyVol = 0, sellVol = 0;
    trades.forEach(t => {
      const qty = parseFloat(t.q || t.quantity || 0);
      const price = parseFloat(t.p || t.price || 0);
      const val = qty * price;
      // isBuyerMaker: true means seller initiated (maker was buyer, aggressor was seller)
      if (t.m === false || t.isBuyerMaker === false) buyVol += val;  // buyer aggressor
      else sellVol += val;
    });

    const cvd = buyVol - sellVol;
    const total = buyVol + sellVol || 1;
    const cvdTrend: CVDData['cvdTrend'] = cvd > total * 0.05 ? 'RISING' : cvd < -total * 0.05 ? 'FALLING' : 'FLAT';

    const interpretation = cvdTrend === 'RISING'
      ? `CVD +${(cvd/1000000).toFixed(2)}M — more aggressive buying. Bulls in control.`
      : cvdTrend === 'FALLING'
      ? `CVD ${(cvd/1000000).toFixed(2)}M — more aggressive selling. Bears in control.`
      : `CVD balanced — no dominant aggressor.`;

    return { cvd: +cvd.toFixed(0), cvdTrend, buyVol: +buyVol.toFixed(0), sellVol: +sellVol.toFixed(0), interpretation };
  }

  private calculateBias(f: FundingData|null, oi: OpenInterestData|null, cvd: CVDData|null): {
    bias: OrderFlowAnalysis['overallBias'], score: number, reasons: string[]
  } {
    let score = 0;
    const reasons: string[] = [];

    // Funding rate
    if (f) {
      if (f.fundingRate < -0.03) { score += 25; reasons.push(`🟢 Funding ${f.fundingRate.toFixed(3)}% — shorts overleveraged (squeeze fuel)`); }
      else if (f.fundingRate < 0) { score += 10; reasons.push(`Funding slightly negative (bearish positioning)`); }
      else if (f.fundingRate > 0.05) { score -= 20; reasons.push(`🔴 Funding ${f.fundingRate.toFixed(3)}% — longs overleveraged (squeeze risk)`); }
      else if (f.fundingRate > 0) { score -= 5; }
    }

    // OI
    if (oi) {
      if (oi.signal === 'LONGS_BUILDING')  { score += 20; reasons.push(`🟢 OI rising with price — longs building (+${oi.oiChange1h}%)`); }
      if (oi.signal === 'SHORT_SQUEEZE')   { score += 30; reasons.push(`🚀 SHORT SQUEEZE — OI dropping as price rises`); }
      if (oi.signal === 'SHORTS_BUILDING') { score -= 20; reasons.push(`🔴 OI rising with price down — shorts building`); }
      if (oi.signal === 'LONG_SQUEEZE')    { score -= 30; reasons.push(`💥 LONG SQUEEZE — forced long liquidations`); }
    }

    // CVD
    if (cvd) {
      if (cvd.cvdTrend === 'RISING')  { score += 15; reasons.push(`🟢 CVD rising — aggressive buying pressure`); }
      if (cvd.cvdTrend === 'FALLING') { score -= 15; reasons.push(`🔴 CVD falling — aggressive selling pressure`); }
    }

    score = Math.max(-100, Math.min(100, score));

    const bias: OrderFlowAnalysis['overallBias'] =
      score >= 40  ? 'STRONG_BULL' :
      score >= 15  ? 'BULL' :
      score <= -40 ? 'STRONG_BEAR' :
      score <= -15 ? 'BEAR' : 'NEUTRAL';

    return { bias, score, reasons };
  }

  private buildSummary(symbol: string, bias: string, score: number, f: FundingData|null, oi: OpenInterestData|null, cvd: CVDData|null): string {
    const parts: string[] = [`Order Flow: ${bias} (score: ${score > 0 ? '+' : ''}${score})`];
    if (f)   parts.push(f.interpretation);
    if (oi)  parts.push(oi.interpretation);
    if (cvd) parts.push(cvd.interpretation);
    return parts.join(' | ');
  }

  private emptyAnalysis(): OrderFlowAnalysis {
    return { funding:null, openInterest:null, liquidations:null, cvd:null, overallBias:'NEUTRAL', score:0, reasons:[], summary:'Order flow data unavailable.', isLoaded:false };
  }
}
