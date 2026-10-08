import { environment } from '../../environments/environment';
import { Injectable, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, tap } from 'rxjs';

// Mirrors the backend's PositionSummaryDto exactly, so there is one contract
// for the position shape rather than a separately-maintained frontend copy.
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
  // Quantity actually covered by a stop/OCO order, so the UI can distinguish
  // "fully protected" from "partially protected" instead of a single binary badge.
  protectedQuantity: number | null;
  // Server-computed classification using the real exchange minQty, so a genuine
  // residual gap can be told apart from harmless exchange-rounding dust.
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
 * Thin wrapper around /api/positions/**, following the same auth pattern as every
 * other service in this app.
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

  // Backs the dashboard's incident indicator.
  unresolvedIncidents(credentialId: string): Observable<any> {
    return this.http.get(`${this.API}/credential/${credentialId}/incidents`);
  }
}
