import { environment } from '../../environments/environment';
import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, of, catchError } from 'rxjs';

export interface AnalyticsRow {
  label:        string;
  trades:       number;
  wins:         number;
  winRate:      number;
  profitFactor: number;
  avgR:         number;
}

export interface CalibrationRow {
  bucket:         string;
  trades:         number;
  winRate:        number;
  avgR:           number;
  calibrationGap: number; // actual - expected win rate
}

export interface StrategyAnalytics {
  overall:       { total:number; wins:number; winRate:number; avgR:number; profitFactor:number };
  byRegime:      AnalyticsRow[];
  byConfidence:  AnalyticsRow[];
  bySMC:         AnalyticsRow[];
  byTimeframe:   AnalyticsRow[];
  bySymbol:      AnalyticsRow[];
  byVPLocation:  AnalyticsRow[];
  byBOS:         AnalyticsRow[];
  byFearGreed:   AnalyticsRow[];
  byOrderBlock:  AnalyticsRow[];
}

export interface WalkForwardResult {
  trainPeriod: { from:string; to:string; stats: any };
  testPeriod:  { from:string; to:string; stats: any };
  overfitScore: string;
}

@Injectable({ providedIn: 'root' })
export class AnalyticsService {
  private http = inject(HttpClient);
  private API  = `${environment.apiUrl}/calls`;

  getAnalytics(): Observable<any> {
    return this.http.get<any>(`${this.API}/analytics`)
      .pipe(catchError(() => of({ data: null })));
  }

  getCalibration(): Observable<any> {
    return this.http.get<any>(`${this.API}/calibration`)
      .pipe(catchError(() => of({ data: null })));
  }

  getCorrelation(): Observable<any> {
    return this.http.get<any>(`${this.API}/correlation`)
      .pipe(catchError(() => of({ data: null })));
  }

  getStrategyComparison(): Observable<any> {
    return this.http.get<any>(`${this.API}/comparison`)
      .pipe(catchError(() => of({ data: null })));
  }

  getWalkForward(): Observable<any> {
    return this.http.get<any>(`${this.API}/walkforward`)
      .pipe(catchError(() => of({ data: null })));
  }

  downloadDataset(format: 'csv' | 'json' = 'csv'): void {
    const url = `${this.API}/ml/export/${format}`;
    this.http.get(url, {
      responseType: 'blob'
    }).subscribe({
      next: (blob: Blob) => {
        const objectUrl = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = objectUrl;
        a.download = `tradevision_dataset.${format}`;
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        URL.revokeObjectURL(objectUrl);
      },
      error: () => console.error('Export failed')
    });
  }

  // Feature importance — calculated from rule weights in analytics data
  calcFeatureImportance(analytics: any): { feature: string; importance: number; color: string }[] {
    if (!analytics) return [];
    const features = [
      { feature: 'EMA Trend',      score: this.featureScore(analytics.byRegime) },
      { feature: 'Market Regime',  score: this.featureScore(analytics.byRegime) * 0.9 },
      { feature: 'SMC Bias',       score: this.featureScore(analytics.bySMC) },
      { feature: 'BOS Confirmed',  score: this.featureScore(analytics.byBOS) },
      { feature: 'Volume Profile', score: this.featureScore(analytics.byVPLocation) },
      { feature: 'Confidence',     score: this.featureScore(analytics.byConfidence) },
      { feature: 'Fear & Greed',   score: this.featureScore(analytics.byFearGreed) },
      { feature: 'Order Block',    score: this.featureScore(analytics.byOrderBlock) },
      { feature: 'MTF Alignment',  score: this.featureScore(analytics.byMTF) * 0.8 },
    ];
    const total = features.reduce((a, b) => a + b.score, 0) || 1;
    const colors = ['#00FF88','#00D4FF','#7B61FF','#FFB800','#FF9900','#FF6B35','#FF3B5C','#4A5568','#8895B3'];
    return features
      .map((f, i) => ({ feature: f.feature, importance: Math.round(f.score / total * 100), color: colors[i] }))
      .sort((a, b) => b.importance - a.importance);
  }

  private featureScore(rows: any[]): number {
    if (!rows || !rows.length) return 10; // default
    const top = rows[0];
    const bot = rows[rows.length - 1];
    if (!top || !bot) return 10;
    // Score = spread between best and worst profit factor
    return Math.abs((top.profitFactor || 1) - (bot.profitFactor || 1)) * 20 + (top.trades || 0) * 0.1;
  }
}
