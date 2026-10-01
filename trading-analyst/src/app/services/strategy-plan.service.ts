import { environment } from '../../environments/environment';
import { Injectable, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, tap } from 'rxjs';

export type TradeDirection = 'LONG' | 'SHORT' | 'BOTH';
export type SessionMode = 'ALWAYS_ON' | 'DAILY' | 'CUSTOM_DAYS';
export type EndOfSessionAction = 'CLOSE_POSITIONS' | 'KEEP_OPEN';
export type DayOfWeek = 'MONDAY' | 'TUESDAY' | 'WEDNESDAY' | 'THURSDAY' | 'FRIDAY' | 'SATURDAY' | 'SUNDAY';

export interface StrategyPlan {
  id: string;
  userId: string;
  credentialId: string;
  name: string;
  enabled: boolean;
  /** Server-managed, bumped on every meaningful edit -- not user-editable. See the backend's own StrategyPlan.version field javadoc for why this exists (the atomic disable/edit-vs-execution race fix). */
  version: number;
  defaultPlan: boolean;
  timeframe: string;
  direction: TradeDirection;
  enabledSymbols: string[];
  dynamicUniverseEnabled: boolean;
  dynamicUniverseMaxSymbols: number;
  riskPerTradePercent: number;
  maxConcurrentTrades: number;
  maxCapital: number | null;
  maxHoldMinutes: number | null;
  exitOnSignalReversal: boolean;
  exitOnRiskEmergency: boolean;
  minConfidence: number;
  cooldownMinutes: number;
  sessionMode: SessionMode;
  sessionStart: string | null;   // "HH:mm:ss"
  sessionEnd: string | null;
  sessionTimezone: string;
  sessionDays: DayOfWeek[];
  endOfSessionAction: EndOfSessionAction;
  createdAt: string;
  updatedAt: string;
}

/** Everything StrategyPlan has except the server-assigned/read-only fields -- the shape the create/update form actually edits. */
export type StrategyPlanRequest = Omit<StrategyPlan, 'id' | 'userId' | 'enabled' | 'version' | 'defaultPlan' | 'createdAt' | 'updatedAt'> & { enabled: boolean };

/**
 * Thin wrapper around /api/strategy-plans, matching BrokerService's own established shape
 * exactly (environment.apiUrl, signal<T[]>([]) for list state, .pipe(tap(...)) to update it on
 * success). Every call requires the same JWT the rest of the app already uses.
 */
@Injectable({ providedIn: 'root' })
export class StrategyPlanService {
  private readonly API = `${environment.apiUrl}/strategy-plans`;

  plans = signal<StrategyPlan[]>([]);

  constructor(private http: HttpClient) {}

  list(credentialId: string): Observable<any> {
    return this.http.get(`${this.API}?credentialId=${encodeURIComponent(credentialId)}`).pipe(
      tap((r: any) => { if (r.success) this.plans.set(r.data || []); })
    );
  }

  create(req: StrategyPlanRequest): Observable<any> {
    return this.http.post(this.API, req);
  }

  update(id: string, req: StrategyPlanRequest): Observable<any> {
    return this.http.put(`${this.API}/${id}`, req);
  }

  setEnabled(id: string, enabled: boolean): Observable<any> {
    return this.http.patch(`${this.API}/${id}/enabled?enabled=${enabled}`, {});
  }

  delete(id: string): Observable<any> {
    return this.http.delete(`${this.API}/${id}`);
  }
}
