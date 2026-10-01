import { environment } from '../../environments/environment';
import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Observable, of, throwError } from 'rxjs';
import { catchError, switchMap } from 'rxjs/operators';
import { AuthService } from './auth.service';

export interface SavedCall {
  id:          string;
  symbol:      string;
  market:      string;
  direction:   string;
  signal:      string;
  confidence:  number;
  entryPrice:  number;
  stopLoss:    number;
  target1:     number;
  target2:     number;
  target3:     number;
  atr:         number;
  rrRatio:     number;
  risk:        string;
  rsi:         number;
  rsiZone:     string;
  macdBull:    boolean;
  trendEMA:    string;
  summary:     string;
  // Legacy flat fields (kept for backward compat)
  result:      string;   // PENDING | HIT_T1 | HIT_T2 | HIT_T3 | HIT_SL
  exitPrice?:  number;
  calledAt:    string;
  resolvedAt?: string;
  // New nested structure (v18+)
  outcome?: {
    result:          string;
    exitPrice?:      number;
    pnlPct?:         number;
    pnlR?:           number;
    holdBars?:       number;
    durationMinutes?:number;
    durationLabel?:  string;
    resolvedAt?:     string;
  };
  features?: {
    regime?:       string;
    smcBias?:      string;
    fearGreedValue?: number;
    adx?:          number;
    exchange?:     string;
    featureVersion?: number;
  };
  timeframe:   string;
}

@Injectable({ providedIn: 'root' })
export class TradeHistoryService {
  private readonly API = `${environment.apiUrl}/calls`;
  private authSvc = inject(AuthService);

  // Save a trade call after user clicks "Get Trade Call"
  saveCall(callData: any, symbol: string, market: string): Observable<any> {
  if (!this.authSvc.isLoggedIn) {
    return throwError(() => new Error('Not logged in — sign in to save calls to your history.'));
  }
  const body = {
    symbol,
    market,
    direction:  callData.direction,
    signal:     callData.signal,
    confidence: callData.confidence,
    entryPrice: callData.entry,
    stopLoss:   callData.stopLoss,
    target1:    callData.target1,
    target2:    callData.target2,
    target3:    callData.target3,
    atr:        callData.atr,
    rrRatio:    parseFloat(callData.rrRatio) || 0,
    risk:       callData.risk,
    rsi:        callData.rsi,
    rsiZone:    callData.rsiZone,
    macdBull:   callData.macdBull,
    trendEMA:   callData.trendEMA,
    summary:    callData.summary,
    timeframe:  callData.timeframe || '1D'
  };
  return this.http.post(`${this.API}/save`, body).pipe(
    switchMap(res => {
      if (!res) return throwError(() => new Error('Server did not confirm the save.'));
      return of(res);
    }),
    catchError((err: HttpErrorResponse) => {
      const msg = err.status === 401
        ? 'Session expired — please log in again to save calls.'
        : err.error?.message || 'Could not save to history. Please try again.';
      return throwError(() => new Error(msg));
    })
  );
}

  // Get last 10 calls for a specific symbol
  getCallsForSymbol(symbol: string): Observable<any> {
    if (!this.authSvc.isLoggedIn) return of({ data: [] });
    return this.http.get<any>(`${this.API}/symbol?symbol=${symbol}`).pipe(
      catchError(() => of({ data: [] }))
    );
  }

  // Get recent calls (for dashboard)
  getRecentCalls(limit = 10): Observable<any> {
    if (!this.authSvc.isLoggedIn) return of({ data: [] });
    return this.http.get<any>(`${this.API}/recent?limit=${limit}`)
      .pipe(catchError(() => of({ data: [] })));
  }

  // Mark a call result (HIT_T1, HIT_T2, HIT_T3, HIT_SL)
  updateResult(callId: string, result: string, exitPrice: number): Observable<any> {
    return this.http.post(`${this.API}/result`, { id: callId, callId, result, exitPrice })
      .pipe(catchError(() => of(null)));
  }

  // Get win/loss stats
  getStats(): Observable<any> {
    if (!this.authSvc.isLoggedIn) return of({ data: null });
    return this.http.get<any>(`${this.API}/stats`)
      .pipe(catchError(() => of({ data: null })));
  }

  constructor(private http: HttpClient) {}
}
