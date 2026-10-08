import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, of, map, catchError, shareReplay, timer, switchMap } from 'rxjs';

export interface FearGreedData {
  value:          number;       // 0-100
  classification: string;       // Extreme Fear / Fear / Neutral / Greed / Extreme Greed
  timestamp:      number;
  // Historical
  yesterday:      number;
  lastWeek:       number;
  lastMonth:      number;
  // Analysis
  signal:         'STRONG_BUY' | 'BUY' | 'NEUTRAL' | 'SELL' | 'STRONG_SELL';
  signalReason:   string;
  color:          string;
  emoji:          string;
  // Chart data
  history:        { value: number; classification: string; timestamp: number }[];
  /**
   * false means the real data source failed or returned nothing — every other field here is a
   * placeholder, NOT a real reading. A trading-facing UI must not render this as if it were a
   * genuine "Neutral 50" sentiment reading; consumers must check this before displaying or
   * using any of the other fields.
   */
  available:      boolean;
}

@Injectable({ providedIn: 'root' })
export class FearGreedService {
  private http = inject(HttpClient);

  private cache$: Observable<FearGreedData> | null = null;

  get(): Observable<FearGreedData> {
    if (!this.cache$) {
      this.cache$ = this.http.get<any>('/fng-api/fng/?limit=30&format=json').pipe(
        map(r => this.process(r)),
        catchError(() => of(this.unavailableData())),
        shareReplay(1)
      );
      // Refresh every 30 minutes
      timer(30 * 60 * 1000).subscribe(() => this.cache$ = null);
    }
    return this.cache$;
  }

  private process(raw: any): FearGreedData {
    const data = raw?.data || [];
    if (!data.length) return this.unavailableData();

    const current   = data[0];
    const yesterday = data[1] || current;
    const lastWeek  = data[7] || data[data.length-1] || current;
    const lastMonth = data[29] || data[data.length-1] || current;

    const val = parseInt(current.value);

    const { signal, reason } = this.getSignal(val, parseInt(yesterday.value));

    return {
      value:          val,
      classification: current.value_classification,
      timestamp:      parseInt(current.timestamp) * 1000,
      yesterday:      parseInt(yesterday.value),
      lastWeek:       parseInt(lastWeek.value),
      lastMonth:      parseInt(lastMonth.value),
      signal,
      signalReason: reason,
      color:          this.getColor(val),
      emoji:          this.getEmoji(val),
      history: data.slice(0, 30).map((d: any) => ({
        value:          parseInt(d.value),
        classification: d.value_classification,
        timestamp:      parseInt(d.timestamp) * 1000
      })),
      available: true
    };
  }

  private getSignal(val: number, prev: number): { signal: FearGreedData['signal']; reason: string } {
    const trend = val - prev;

    // Contrarian signal logic:
    // Extreme Fear (0-25) = best time to BUY (everyone is scared = bottom)
    // Extreme Greed (75-100) = best time to SELL (everyone is greedy = top)
    if (val <= 20) return {
      signal: 'STRONG_BUY',
      reason: `Extreme Fear ${val} — historically the best time to accumulate. Market panic creates opportunity. ${trend > 0 ? 'Sentiment recovering (+' + trend + ').' : ''}`
    };
    if (val <= 35) return {
      signal: 'BUY',
      reason: `Fear ${val} — market is fearful. Contrarian signal: consider buying quality assets. ${trend > 5 ? 'Fear is decreasing — sentiment improving.' : ''}`
    };
    if (val >= 80) return {
      signal: 'STRONG_SELL',
      reason: `Extreme Greed ${val} — market is euphoric. Historically precedes corrections. ${trend < 0 ? 'Greed decreasing — watch for reversal.' : 'Consider taking profits.'}`
    };
    if (val >= 65) return {
      signal: 'SELL',
      reason: `Greed ${val} — market is overconfident. Reduce risk, take partial profits. ${trend > 0 ? 'Greed still rising — be cautious.' : ''}`
    };
    return {
      signal: 'NEUTRAL',
      reason: `Neutral ${val} — market sentiment balanced. Follow technical signals. ${trend > 5 ? 'Shifting towards greed.' : trend < -5 ? 'Shifting towards fear.' : 'Stable sentiment.'}`
    };
  }

  getColor(val: number): string {
    if (val <= 20)  return '#FF3B5C';
    if (val <= 40)  return '#FF6B35';
    if (val <= 60)  return '#FFB800';
    if (val <= 80)  return '#00D4FF';
    return '#00FF88';
  }

  getEmoji(val: number): string {
    if (val <= 20)  return 'Extreme Fear';
    if (val <= 40)  return 'Fear';
    if (val <= 60)  return 'Neutral';
    if (val <= 80)  return 'Greed';
    return 'Extreme Greed';
  }

  /**
   * Returned whenever the real API fails, explicitly marked unavailable rather than a
   * fabricated "Neutral 50" reading that would be indistinguishable from genuine neutral
   * sentiment to a consumer that didn't check signalReason's text. Fails closed instead of
   * inventing market data.
   */
  private unavailableData(): FearGreedData {
    return { value:0, classification:'Unavailable', timestamp:Date.now(), yesterday:0, lastWeek:0, lastMonth:0,
      signal:'NEUTRAL', signalReason:'Data unavailable — source did not respond.', color:'#4A5568', emoji:'—',
      history:[], available:false };
  }
}
