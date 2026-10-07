import { environment } from '../../environments/environment';
import { Injectable, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, tap } from 'rxjs';

export interface BrokerCredentialResponse {
  id: string;
  broker: 'BINANCE' | 'MUDREX';
  // P3-10 fix ("PAPER mode requires 'live authorization' and isn't selectable in UI" -- external
  // review, confirmed real by direct inspection): PAPER has been a fully real, working backend
  // mode all along (see BrokerMode's own javadoc on the backend -- a PAPER credential never sends
  // an authenticated request to Binance, everything is simulated in-process against real public
  // prices) but this type never even admitted it existed, so the UI below had no way to show or
  // select it.
  mode: 'TESTNET' | 'LIVE' | 'PAPER';
  keyHint: string;
  connectedAt: string;
  lastValidatedAt: string;
}

export interface AssetBalance { asset: string; free: string; locked: string; }

export interface ExecutedOrder {
  id: string; symbol: string; side: string; type: string;
  quantity: number; fillPrice: number; brokerOrderId: string;
  status: string; errorMessage: string; triggerSource: string;
  stopLossPrice: number; takeProfitPrice: number; placedAt: string;
}

export interface RiskProfile {
  id: string; credentialId: string; autoTradeEnabled: boolean;
  enabledSymbols: string[]; minConfidence: number;
  maxPositionQuoteAmount: number; maxConcurrentTrades: number;
  dailyLossLimitQuote: number; dailyRealizedLossQuote: number;
  riskPerTradePercent: number;
  maxTotalExposureQuote: number;
  maxPriceDeviationPercent: number;
  consecutiveOrderFailures: number;
  circuitBreakerThreshold: number;
  // P0-6 fix ("LIVE risk-limit enforcement" -- full context in the backend's own
  // RiskProfileRequest javadoc): these already existed on the backend model and were already
  // enforced by RiskEngineService, but had no way to be read back here until now -- the settings
  // form below couldn't show a user their own current values for these, only save new ones.
  maxDrawdownPercent: number;
  maxOrdersPerHour: number;
  maxConsecutiveAutoTradeLosses: number;
  // P1-20 fix ("Frontend risk-profile save silently wipes fields" -- external review, confirmed
  // real by direct inspection of RiskProfileService.doUpsert on the backend: it unconditionally
  // $sets maxSymbolExposureQuote/correlationGroups/correlationGroupCaps from whatever this
  // request DTO contains, so any save from a form that never round-tripped these silently reset
  // them to disabled/empty on every single save -- even a save the user made for an entirely
  // unrelated field, like flipping autoTradeEnabled). These three were never even present on
  // this interface before this fix, so this service had no way to read them back OR send them
  // back unchanged -- see saveRiskProfile's own updated javadoc for the actual fix (the settings
  // component now always echoes back whatever it most recently loaded for these, not just the
  // fields it has a form control for).
  maxSymbolExposureQuote: number;
  correlationGroups: Record<string, string[]>;
  correlationGroupCaps: Record<string, number>;
  tradingHalted: boolean; haltReason: string;
  liveAutoTradeAuthorized: boolean;
}

/**
 * Thin wrapper around /api/broker/**. Every call here relies on the same HttpOnly session
 * cookie the rest of the app already uses — nothing broker-specific about auth.
 */
@Injectable({ providedIn: 'root' })
export class BrokerService {
  private readonly API = `${environment.apiUrl}/broker`;

  credentials = signal<BrokerCredentialResponse[]>([]);
  orderHistory = signal<ExecutedOrder[]>([]);

  constructor(private http: HttpClient) {}

  // ── Stage 1 ──────────────────────────────────────────────
  // Review finding ("P0 #1" — "LIVE Binance credential architecture is wrong"): mode is now
  // explicit — this connects a TESTNET or PAPER credential (the common cases, TESTNET still the
  // default if omitted). A LIVE credential is a genuinely separate two-step flow below, not a
  // flag flipped on this same connection afterward — Binance testnet and mainnet keys are
  // different credentials, confirmed against current Binance documentation.
  //
  // P3-10 fix ("PAPER mode requires 'live authorization' and isn't selectable in UI" -- external
  // review): `mode` is now a real parameter instead of a hardcoded 'TESTNET' literal, so the
  // component below can actually offer PAPER. The backend's own /connect endpoint (see
  // ConnectBrokerRequest/BrokerCredentialService.connect) already accepted PAPER here all along —
  // this was purely a frontend gap, never a backend one.
  connect(broker: string, apiKey: string, apiSecret: string, mode: 'TESTNET' | 'PAPER' = 'TESTNET'): Observable<any> {
    return this.http.post(`${this.API}/connect`, { broker, apiKey, apiSecret, mode });
  }

  list(): Observable<any> {
    return this.http.get(`${this.API}/list`).pipe(
      tap((r: any) => { if (r.success) this.credentials.set(r.data); })
    );
  }

  remove(credentialId: string): Observable<any> {
    return this.http.delete(`${this.API}/${credentialId}`);
  }

  balance(credentialId: string): Observable<any> {
    return this.http.get(`${this.API}/${credentialId}/balance`);
  }

  openOrders(credentialId: string): Observable<any> {
    return this.http.get(`${this.API}/${credentialId}/open-orders`);
  }

  // Follow-up fix (full context in the backend's own PlaceTestOrderRequest/OrderExecutionService
  // comments, PR #33): takeProfitPrice/stopLossTriggerPrice are optional and only meaningful for
  // a BUY -- without them, a manually placed order has no way to be protected once the backend's
  // own reconciliation pass discovers its fill, and gets emergency-flattened unprotected. Sent
  // as undefined (dropped from the JSON body entirely, not sent as null) when not provided, so
  // an unchanged caller behaves exactly as before this fix.
  placeTestOrder(credentialId: string, symbol: string, side: string, quantity: number,
                 takeProfitPrice?: number | null, stopLossTriggerPrice?: number | null): Observable<any> {
    return this.http.post(`${this.API}/test-order`, {
      credentialId, symbol, side, quantity,
      takeProfitPrice: takeProfitPrice ?? undefined,
      stopLossTriggerPrice: stopLossTriggerPrice ?? undefined,
    });
  }

  history(): Observable<any> {
    return this.http.get(`${this.API}/orders/history`).pipe(
      tap((r: any) => { if (r.success) this.orderHistory.set(r.data); })
    );
  }

  // ── Stage 2: risk profile / auto-trade ──────────────────
  /**
   * P1-20 fix ("Frontend risk-profile save silently wipes fields" -- full context in
   * RiskProfile's own updated field comments above): maxSymbolExposureQuote/
   * maxPriceDeviationPercent/circuitBreakerThreshold/correlationGroups/correlationGroupCaps
   * added to this request shape, all optional so a caller that genuinely has no value for one
   * yet (a brand-new profile that's never configured it) doesn't have to fabricate one -- but
   * broker-settings.component's own saveRiskProfile() now always passes through whatever it most
   * recently loaded for every one of these, even the ones with no dedicated form control, so a
   * save from the UI can never again silently reset a previously-configured value to the
   * backend's own bare defaults just because this specific save request didn't happen to
   * mention it. This is the audit's own "send the full profile from the UI" option, chosen over
   * restructuring the backend into PATCH semantics: every numeric field on RiskProfileRequest is
   * a primitive `double`/`int` with its own Jakarta-Bean-Validation default value, so a null vs.
   * "genuinely zero" distinction doesn't exist there today -- making the backend a true PATCH
   * would mean boxing every one of those fields and auditing every downstream read for a new
   * null case, a materially larger and riskier change than fixing the one place actually
   * constructing an incomplete request.
   */
  saveRiskProfile(req: {
    credentialId: string; autoTradeEnabled: boolean; enabledSymbols: string[];
    minConfidence: number; maxPositionQuoteAmount: number; maxConcurrentTrades: number;
    dailyLossLimitQuote: number; riskPerTradePercent: number;
    // P0-6 fix: optional so an existing caller of this method that hasn't been updated to pass
    // them keeps behaving exactly as before (the backend DTO defaults every one of these to its
    // own existing "disabled" value, unchanged) -- but the settings form now does pass them, so
    // a user can actually configure the LIVE-required limits this fix added enforcement for.
    maxTotalExposureQuote?: number;
    maxDrawdownPercent?: number; maxOrdersPerHour?: number; maxConsecutiveAutoTradeLosses?: number;
    maxSymbolExposureQuote?: number; maxPriceDeviationPercent?: number; circuitBreakerThreshold?: number;
    correlationGroups?: Record<string, string[]>; correlationGroupCaps?: Record<string, number>;
  }): Observable<any> {
    return this.http.post(`${this.API}/risk-profile`, req);
  }

  getRiskProfile(credentialId: string): Observable<any> {
    return this.http.get(`${this.API}/risk-profile/${credentialId}`);
  }

  // ── Stage 3 (fix): explicit second unlock required before LIVE auto-trading can fire ──
  authorizeLiveAutoTrade(credentialId: string): Observable<any> {
    return this.http.post(`${this.API}/risk-profile/${credentialId}/authorize-live-autotrade`,
      { confirm: 'I UNDERSTAND THIS ENABLES AUTONOMOUS LIVE TRADING' });
  }

  revokeLiveAutoTrade(credentialId: string): Observable<any> {
    return this.http.post(`${this.API}/risk-profile/${credentialId}/revoke-live-autotrade`, {});
  }

  // ── Stage 3: kill switch ────────────────────────────────
  halt(credentialId: string, reason?: string): Observable<any> {
    return this.http.post(`${this.API}/risk-profile/${credentialId}/halt`, { reason });
  }

  resume(credentialId: string): Observable<any> {
    return this.http.post(`${this.API}/risk-profile/${credentialId}/resume`, {});
  }

  killSwitchAll(reason?: string): Observable<any> {
    return this.http.post(`${this.API}/kill-switch`, { reason });
  }

  // ── Stage 3: LIVE connect (two-step, a genuinely separate credential) ──
  // Review finding ("P0 #1"): replaces requestLiveMode/confirmLiveMode/revertToTestnet — there
  // is no "mode toggle" or "revert" anymore, because a LIVE credential was never the same row
  // as a TESTNET one to begin with. Connecting LIVE means providing your actual Binance mainnet
  // API key/secret (not the testnet ones), validated against Binance's real LIVE endpoint.
  requestLiveConnect(broker: string, apiKey: string, apiSecret: string): Observable<any> {
    return this.http.post(`${this.API}/connect/live/request`, { broker, apiKey, apiSecret, mode: 'LIVE' });
  }

  confirmLiveConnect(confirmToken: string): Observable<any> {
    return this.http.post(`${this.API}/connect/live/confirm`, { confirmToken });
  }
}
