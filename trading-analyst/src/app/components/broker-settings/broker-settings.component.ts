import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { BrokerService, BrokerCredentialResponse, AssetBalance, RiskProfile, ExecutedOrder } from '../../services/broker.service';

/**
 * Stage 1+2+3 UI in one place: connect a broker key (testnet-validated), see balances,
 * fire a manual test order, configure auto-trade risk caps, and the kill switch / live-mode
 * controls. Nothing here bypasses the backend's safety checks — every button just calls the
 * matching /api/broker/** endpoint and shows whatever it says back, including rejections.
 */
@Component({
    selector: 'app-broker-settings',
    imports: [CommonModule, FormsModule, RouterLink],
    templateUrl: './broker-settings.component.html',
    styleUrls: ['./broker-settings.component.scss']
})
export class BrokerSettingsComponent implements OnInit {
  broker = inject(BrokerService);

  credentials = signal<BrokerCredentialResponse[]>([]);
  selected = signal<BrokerCredentialResponse | null>(null);
  balances = signal<AssetBalance[]>([]);
  riskProfile = signal<RiskProfile | null>(null);
  orders = signal<ExecutedOrder[]>([]);

  loading = signal(false);
  message = signal<{ type: 'ok' | 'error'; text: string } | null>(null);

  // Connect form
  connectApiKey = '';
  connectApiSecret = '';
  // P3-10 fix ("PAPER mode requires 'live authorization' and isn't selectable in UI" -- external
  // review, confirmed real by direct inspection: PAPER was a fully working backend mode with
  // literally no way to select it here -- connect() always hardcoded 'TESTNET'). PAPER still uses
  // a real (testnet) API key for the same one-time permission check TESTNET does (see
  // BrokerCredentialService.doConnect's own PAPER-routing comment on the backend) -- it just never
  // sends an authenticated order afterward -- so it reuses this same form and fields, only the
  // mode selector is new.
  connectMode: 'TESTNET' | 'PAPER' = 'TESTNET';

  // LIVE connect form — deliberately separate fields from the TESTNET connect form above:
  // review finding ("P0 #1") — a LIVE credential needs your actual Binance mainnet API
  // key/secret, never the testnet ones, since Binance treats them as genuinely different keys.
  liveConnectApiKey = '';
  liveConnectApiSecret = '';

  // Manual test order form
  testSymbol = 'BTCUSDT';
  testSide: 'BUY' | 'SELL' = 'BUY';
  testQuantity: number | null = null;
  // Follow-up fix (full context in BrokerService.placeTestOrder's own updated comment, PR #33):
  // optional, BUY-only -- left null on a SELL or when not wanted, unchanged existing behavior.
  testTakeProfitPrice: number | null = null;
  testStopLossTriggerPrice: number | null = null;

  // Risk profile form
  autoTradeEnabled = false;
  enabledSymbolsCsv = 'BTCUSDT,ETHUSDT';
  minConfidence = 75;
  maxPositionQuoteAmount = 50;
  maxConcurrentTrades = 1;
  dailyLossLimitQuote = 25;
  riskPerTradePercent = 1.0;
  // P0-6 fix ("LIVE risk-limit enforcement" -- full context in the backend's own
  // authorizeLiveAutoTrade javadoc): these are now REQUIRED (non-zero) before this credential
  // can be authorized for autonomous LIVE trading -- exposed here so a user can actually set
  // them, rather than being silently stuck at the backend model's own disabled defaults with no
  // way to change them through this form.
  maxTotalExposureQuote = 100;
  maxDrawdownPercent = 10;
  maxOrdersPerHour = 20;
  maxConsecutiveAutoTradeLosses = 3;
  // P1-20 fix ("Frontend risk-profile save silently wipes fields" -- full context in
  // BrokerService.saveRiskProfile's own updated javadoc): these three previously had no form
  // control AND were never even read back from the backend by loadRiskProfile below, so every
  // save from this component silently reset them to the backend's own bare defaults
  // (maxSymbolExposureQuote=disabled, maxPriceDeviationPercent=1.5, circuitBreakerThreshold=3)
  // regardless of whether the user had ever deliberately configured something else. Now genuinely
  // round-tripped: loaded from the profile below, editable here, and sent back on every save.
  maxSymbolExposureQuote = 0; // 0 = disabled, matching the backend model's own default
  maxPriceDeviationPercent = 1.5;
  circuitBreakerThreshold = 3;
  // P1-20 fix: correlationGroups/correlationGroupCaps still have no dedicated editor UI in this
  // pass (a real group-membership editor is a separate, larger UI feature, not a bug fix) -- but
  // they ARE now round-tripped through this component (loaded here, sent back unmodified on
  // save), which is the actual fix this item needs: a save from this form must never again wipe
  // out correlation groups/caps a user configured through some OTHER path (a future dedicated
  // editor, or directly via the API), just because this form has no UI for them yet.
  correlationGroups: Record<string, string[]> = {};
  correlationGroupCaps: Record<string, number> = {};

  // Live mode
  liveConfirmToken = '';
  awaitingLiveConfirm = false;

  ngOnInit() { this.refreshList(); }

  private show(type: 'ok' | 'error', text: string) {
    this.message.set({ type, text });
    setTimeout(() => this.message.set(null), 6000);
  }

  refreshList() {
    this.broker.list().subscribe({
      next: (r: any) => {
        this.credentials.set(r.data || []);
        if (!this.selected() && r.data?.length) this.select(r.data[0]);
      },
      error: () => this.show('error', 'Could not load broker credentials.')
    });
  }

  connect() {
    if (!this.connectApiKey || !this.connectApiSecret) {
      this.show('error', 'Enter both API key and secret.');
      return;
    }
    this.loading.set(true);
    this.broker.connect('BINANCE', this.connectApiKey, this.connectApiSecret, this.connectMode).subscribe({
      next: (r: any) => {
        this.loading.set(false);
        this.connectApiKey = ''; this.connectApiSecret = '';
        this.show('ok', r.message || 'Connected.');
        this.refreshList();
      },
      error: (e) => { this.loading.set(false); this.show('error', e?.error?.message || 'Connect failed.'); }
    });
  }

  select(c: BrokerCredentialResponse) {
    this.selected.set(c);
    this.loadBalance(c.id);
    this.loadRiskProfile(c.id);
    this.loadHistory();
  }

  removeCredential(c: BrokerCredentialResponse) {
    this.broker.remove(c.id).subscribe({
      next: () => { this.show('ok', 'Removed.'); this.selected.set(null); this.refreshList(); },
      error: (e) => this.show('error', e?.error?.message || 'Could not remove.')
    });
  }

  loadBalance(credentialId: string) {
    this.broker.balance(credentialId).subscribe({
      next: (r: any) => this.balances.set(r.data || []),
      error: (e) => this.show('error', e?.error?.message || 'Could not load balance.')
    });
  }

  loadRiskProfile(credentialId: string) {
    this.broker.getRiskProfile(credentialId).subscribe({
      next: (r: any) => {
        const p: RiskProfile = r.data;
        this.riskProfile.set(p);
        this.autoTradeEnabled = p.autoTradeEnabled;
        this.enabledSymbolsCsv = (p.enabledSymbols || []).join(',');
        this.minConfidence = p.minConfidence;
        this.maxPositionQuoteAmount = p.maxPositionQuoteAmount;
        this.maxConcurrentTrades = p.maxConcurrentTrades;
        this.dailyLossLimitQuote = p.dailyLossLimitQuote;
        this.riskPerTradePercent = p.riskPerTradePercent;
        // P0-6 fix: only overwrite the form's own default when the backend actually has a real
        // value on file -- an existing profile saved before this fix legitimately has these at
        // 0/undefined, and falling back to this form's own sane defaults there is friendlier
        // than showing "0" (which would otherwise look like a deliberate, saved 0, not "never set").
        if (p.maxTotalExposureQuote) this.maxTotalExposureQuote = p.maxTotalExposureQuote;
        if (p.maxDrawdownPercent) this.maxDrawdownPercent = p.maxDrawdownPercent;
        if (p.maxOrdersPerHour) this.maxOrdersPerHour = p.maxOrdersPerHour;
        if (p.maxConsecutiveAutoTradeLosses) this.maxConsecutiveAutoTradeLosses = p.maxConsecutiveAutoTradeLosses;
        // P1-20 fix (full context in this component's own updated field comments above): these
        // must be loaded here too, or saveRiskProfile() below would echo back this form's own
        // stale/default in-memory values instead of the backend's actual current ones on every
        // save that doesn't itself change them.
        if (p.maxSymbolExposureQuote) this.maxSymbolExposureQuote = p.maxSymbolExposureQuote;
        if (p.maxPriceDeviationPercent) this.maxPriceDeviationPercent = p.maxPriceDeviationPercent;
        if (p.circuitBreakerThreshold) this.circuitBreakerThreshold = p.circuitBreakerThreshold;
        this.correlationGroups = p.correlationGroups || {};
        this.correlationGroupCaps = p.correlationGroupCaps || {};
      },
      error: () => this.riskProfile.set(null) // no profile configured yet — defaults in the form stand
    });
  }

  loadHistory() {
    this.broker.history().subscribe({ next: (r: any) => this.orders.set(r.data || []) });
  }

  placeTestOrder() {
    const c = this.selected();
    if (!c || !this.testQuantity) { this.show('error', 'Pick a credential and enter a quantity.'); return; }
    // Follow-up fix (full context in BrokerService.placeTestOrder's own updated comment, PR #33):
    // same both-or-neither / BUY-only / SL-below-TP rules the backend enforces -- checked here
    // too so a bad combination is caught before the request even goes out, not just reported
    // back as a 400 after the fact. The backend remains the real source of truth for this.
    if ((this.testTakeProfitPrice == null) !== (this.testStopLossTriggerPrice == null)) {
      this.show('error', 'Take-profit and stop-loss must both be set, or both left blank.'); return;
    }
    if (this.testTakeProfitPrice != null && this.testSide !== 'BUY') {
      this.show('error', 'Take-profit/stop-loss only apply to a BUY.'); return;
    }
    if (this.testTakeProfitPrice != null && this.testStopLossTriggerPrice! >= this.testTakeProfitPrice) {
      this.show('error', 'Stop-loss must be below take-profit.'); return;
    }
    this.loading.set(true);
    this.broker.placeTestOrder(c.id, this.testSymbol, this.testSide, this.testQuantity,
      this.testTakeProfitPrice, this.testStopLossTriggerPrice).subscribe({
      next: (r: any) => {
        this.loading.set(false);
        this.show('ok', r.message || 'Order placed.');
        this.loadHistory(); this.loadBalance(c.id);
      },
      error: (e) => { this.loading.set(false); this.show('error', e?.error?.message || 'Order failed.'); }
    });
  }

  saveRiskProfile() {
    const c = this.selected();
    if (!c) return;
    const symbols = this.enabledSymbolsCsv.split(',').map(s => s.trim().toUpperCase()).filter(Boolean);
    this.broker.saveRiskProfile({
      credentialId: c.id,
      autoTradeEnabled: this.autoTradeEnabled,
      enabledSymbols: symbols,
      minConfidence: this.minConfidence,
      maxPositionQuoteAmount: this.maxPositionQuoteAmount,
      maxConcurrentTrades: this.maxConcurrentTrades,
      dailyLossLimitQuote: this.dailyLossLimitQuote,
      riskPerTradePercent: this.riskPerTradePercent,
      maxTotalExposureQuote: this.maxTotalExposureQuote,
      maxDrawdownPercent: this.maxDrawdownPercent,
      maxOrdersPerHour: this.maxOrdersPerHour,
      maxConsecutiveAutoTradeLosses: this.maxConsecutiveAutoTradeLosses,
      // P1-20 fix (full context in this component's own updated field comments and
      // BrokerService.saveRiskProfile's own updated javadoc): always echoed back now, so this
      // save can never silently wipe them -- maxSymbolExposureQuote/maxPriceDeviationPercent/
      // circuitBreakerThreshold from this form's own (now genuinely round-tripped) fields, and
      // correlationGroups/correlationGroupCaps unmodified from whatever was most recently loaded,
      // since this pass still has no dedicated editor UI for them.
      maxSymbolExposureQuote: this.maxSymbolExposureQuote,
      maxPriceDeviationPercent: this.maxPriceDeviationPercent,
      circuitBreakerThreshold: this.circuitBreakerThreshold,
      correlationGroups: this.correlationGroups,
      correlationGroupCaps: this.correlationGroupCaps
    }).subscribe({
      next: (r: any) => { this.show('ok', 'Risk profile saved.'); this.riskProfile.set(r.data); },
      error: (e) => this.show('error', e?.error?.message || 'Could not save risk profile.')
    });
  }

  haltThis() {
    const c = this.selected();
    if (!c) return;
    this.broker.halt(c.id, 'Manually halted from settings UI').subscribe({
      next: (r: any) => { this.show('ok', 'Halted.'); this.riskProfile.set(r.data); },
      error: (e) => this.show('error', e?.error?.message || 'Could not halt.')
    });
  }

  resumeThis() {
    const c = this.selected();
    if (!c) return;
    this.broker.resume(c.id).subscribe({
      next: (r: any) => { this.show('ok', 'Resumed.'); this.riskProfile.set(r.data); },
      error: (e) => this.show('error', e?.error?.message || 'Could not resume.')
    });
  }

  killSwitchEverything() {
    if (!confirm('This halts auto-trading on every connected broker credential. Continue?')) return;
    this.broker.killSwitchAll('Global kill switch from settings UI').subscribe({
      next: () => { this.show('ok', 'Kill switch engaged everywhere.'); const c = this.selected(); if (c) this.loadRiskProfile(c.id); },
      error: (e) => this.show('error', e?.error?.message || 'Kill switch failed.')
    });
  }

  // Review finding ("P0 #1" — "LIVE Binance credential architecture is wrong"): this used to
  // operate on this.selected() — an existing TESTNET-validated credential — and just flip its
  // mode flag. That could never actually work against real Binance (a testnet key is rejected
  // outright at the LIVE endpoint). Now a genuinely separate connection, with its own key/secret
  // fields, validated against Binance's real LIVE endpoint from the start.
  requestLive() {
    if (!this.liveConnectApiKey || !this.liveConnectApiSecret) {
      this.show('error', 'Enter your Binance LIVE (mainnet) API key and secret — not your testnet ones.');
      return;
    }
    if (!confirm('This validates a NEW LIVE credential against Binance mainnet. Real funds could be at risk once confirmed. Continue?')) return;
    this.broker.requestLiveConnect('BINANCE', this.liveConnectApiKey, this.liveConnectApiSecret).subscribe({
      next: (r: any) => { this.awaitingLiveConfirm = true; this.show('ok', r.message); },
      error: (e) => this.show('error', e?.error?.message || 'LIVE connect request failed.')
    });
  }

  confirmLive() {
    if (!this.liveConfirmToken) return;
    if (!confirm('Final confirmation: this saves a LIVE credential that trades with real money. Proceed?')) return;
    this.broker.confirmLiveConnect(this.liveConfirmToken).subscribe({
      next: (r: any) => {
        this.awaitingLiveConfirm = false; this.liveConfirmToken = '';
        this.liveConnectApiKey = ''; this.liveConnectApiSecret = '';
        this.show('ok', r.message); this.refreshList();
      },
      error: (e) => this.show('error', e?.error?.message || 'LIVE connect confirmation failed.')
    });
  }

  authorizeLiveAutoTrade() {
    const c = this.selected();
    if (!c) return;
    if (!confirm('This is the SECOND, separate unlock required before auto-trade will place LIVE orders. Continue?')) return;
    this.broker.authorizeLiveAutoTrade(c.id).subscribe({
      next: (r: any) => { this.show('ok', r.message); this.riskProfile.set(r.data); },
      error: (e) => this.show('error', e?.error?.message || 'Authorization failed.')
    });
  }

  revokeLiveAutoTrade() {
    const c = this.selected();
    if (!c) return;
    this.broker.revokeLiveAutoTrade(c.id).subscribe({
      next: (r: any) => { this.show('ok', 'Live auto-trade authorization revoked.'); this.riskProfile.set(r.data); },
      error: (e) => this.show('error', e?.error?.message || 'Could not revoke.')
    });
  }
}
