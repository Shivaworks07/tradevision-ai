import { environment } from '../../environments/environment';
import { Injectable, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, tap } from 'rxjs';

export interface BrokerCredentialResponse {
  id: string;
  broker: 'BINANCE' | 'MUDREX';
  // PAPER is a real backend mode (see BrokerMode's own javadoc on the backend): a PAPER
  // credential never sends an authenticated request to Binance — everything is simulated
  // in-process against real public prices.
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
  // Enforced server-side by RiskEngineService; read back here so the settings form can
  // display a user's current values for these rather than only writing new ones.
  maxDrawdownPercent: number;
  maxOrdersPerHour: number;
  maxConsecutiveAutoTradeLosses: number;
  // RiskProfileService.doUpsert on the backend unconditionally $sets these fields from
  // whatever the request DTO contains, so a save must always echo back the most recently
  // loaded value for each of these — not just the ones with a dedicated form control —
  // or an unrelated save (e.g. flipping autoTradeEnabled) would silently reset them.
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
  // Connects a TESTNET or PAPER credential (TESTNET is the default if mode is omitted).
  // A LIVE credential is a genuinely separate two-step flow below, not a flag flipped on
  // this same connection afterward — Binance testnet and mainnet keys are different
  // credentials entirely.
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

  // takeProfitPrice/stopLossTriggerPrice are optional and only meaningful for a BUY; without
  // them, a manually placed order has no protection once the backend's reconciliation pass
  // discovers its fill, and gets emergency-flattened unprotected. Sent as undefined (dropped
  // from the JSON body entirely, not sent as null) when not provided.
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
   * Saves a credential's risk profile. maxSymbolExposureQuote/maxPriceDeviationPercent/
   * circuitBreakerThreshold/correlationGroups/correlationGroupCaps are optional so a caller
   * with no value yet (a brand-new, unconfigured profile) doesn't have to fabricate one — but
   * the settings component always passes through whatever it most recently loaded for every
   * one of these, even fields with no dedicated form control, so a save can never silently
   * reset a previously-configured value back to the backend's bare defaults. Every numeric
   * field on RiskProfileRequest is a primitive with its own validation default, so there is
   * no null-vs-zero distinction on the backend to lean on instead — the request must always
   * carry the full profile.
   */
  saveRiskProfile(req: {
    credentialId: string; autoTradeEnabled: boolean; enabledSymbols: string[];
    minConfidence: number; maxPositionQuoteAmount: number; maxConcurrentTrades: number;
    dailyLossLimitQuote: number; riskPerTradePercent: number;
    // Optional so a caller that doesn't pass them keeps the backend DTO's existing
    // "disabled" default; the settings form passes them so a user can configure
    // the LIVE-required limits.
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
  // There is no "mode toggle" or "revert" between TESTNET and LIVE, since a LIVE credential
  // is never the same row as a TESTNET one. Connecting LIVE means providing an actual Binance
  // mainnet API key/secret (not the testnet ones), validated against Binance's real LIVE endpoint.
  requestLiveConnect(broker: string, apiKey: string, apiSecret: string): Observable<any> {
    return this.http.post(`${this.API}/connect/live/request`, { broker, apiKey, apiSecret, mode: 'LIVE' });
  }

  confirmLiveConnect(confirmToken: string): Observable<any> {
    return this.http.post(`${this.API}/connect/live/confirm`, { confirmToken });
  }
}
