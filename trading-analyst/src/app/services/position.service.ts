import { environment } from '../../environments/environment';
import { Injectable, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, tap } from 'rxjs';

// Review finding ("UI has no real Position/Execution dashboard"): mirrors PositionSummaryDto on
// the backend exactly — one contract, not a separately-invented frontend shape.
export interface PositionSummary {
  id: string;
  credentialId: string;
  symbol: string;
  mode: 'TESTNET' | 'LIVE';
  status: string; // OPEN, CLOSED, CLOSED_UNVERIFIED_PNL, NAKED_FLATTENED
  quantity: number;
  closedQuantity: number | null;
  avgEntryPrice: number | null;
  avgEntryPriceUnverified: boolean;
  stopLossPrice: number | null;
  takeProfitPrice: number | null;
  protectedByOco: boolean;
  // Review finding ("Position protection status is not yet a first-class invariant" -- external
  // review, full context in the backend's own PositionSummaryDto header javadoc): mirrors the
  // backend's own new field -- lets the UI distinguish "fully protected" from "partially
  // protected" instead of collapsing both into the same binary badge.
  protectedQuantity: number | null;
  // Review finding ("dust classification needs one more invariant" -- external review, second
  // pass, full context in the backend's own PositionSummaryDto header javadoc): 'FULL' |
  // 'DUST_RESIDUAL' | 'PARTIAL' | 'UNPROTECTED' | 'N/A' -- server-computed using the real
  // exchange minQty, replacing this file's own earlier isPartiallyProtected() client-side
  // guess (which had no minQty to judge a residual against at all).
  protectionStatus: 'FULL' | 'DUST_RESIDUAL' | 'PARTIAL' | 'UNPROTECTED' | 'N/A';
  exitPrice: number | null;
  realizedPnlQuote: number | null;
  entryFeeQuote: number | null;
  exitFeeQuote: number | null;
  closeReason: string | null;
  triggerSource: string;
  openedAt: string;
  closedAt: string | null;
}

/**
 * Review finding ("No user-facing emergency position action"): thin wrapper around
 * /api/positions/**, same auth pattern as every other service in this app.
 */
@Injectable({ providedIn: 'root' })
export class PositionService {
  private readonly API = `${environment.apiUrl}/positions`;

  positions = signal<PositionSummary[]>([]);

  constructor(private http: HttpClient) {}

  list(credentialId: string, status: string = 'ALL'): Observable<any> {
    return this.http.get(`${this.API}/${credentialId}?status=${status}`).pipe(
      tap((r: any) => { if (r.success) this.positions.set(r.data); })
    );
  }

  emergencyFlatten(positionId: string): Observable<any> {
    return this.http.post(`${this.API}/${positionId}/emergency-flatten`, {});
  }

  reconcileNow(credentialId: string): Observable<any> {
    return this.http.post(`${this.API}/credential/${credentialId}/reconcile-now`, {});
  }

  // Review finding (P1 #20): backs the dashboard's incident indicator.
  unresolvedIncidents(credentialId: string): Observable<any> {
    return this.http.get(`${this.API}/credential/${credentialId}/incidents`);
  }
}
