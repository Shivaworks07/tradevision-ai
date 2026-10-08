import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
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
  private router = inject(Router);

  goBack(): void {
    this.router.navigate(['/app/crypto']);
  }

  credentials = signal<BrokerCredentialResponse[]>([]);
  selected = signal<BrokerCredentialResponse | null>(null);
  balances = signal<AssetBalance[]>([]);
  riskProfile = signal<RiskProfile | null>(null);
  orders = signal<ExecutedOrder[]>([]);

  loading = signal(false);
  message = signal<{ type: 'ok' | 'error'; text: string } | null>(null);

  // Paged client-side (both endpoints return the full list in one call) so the credential
  // list and order history stay a fixed, short height no matter how many accumulate, keeping
  // the manual-order/auto-trade sections below reachable without a long scroll.
  readonly credPageSize = 5;
  readonly ordPageSize = 8;
  credPage = signal(0);
  ordPage = signal(0);

  credTotalPages = computed(() => Math.max(1, Math.ceil(this.credentials().length / this.credPageSize)));
  pagedCredentials = computed(() => {
    const start = this.credPage() * this.credPageSize;
    return this.credentials().slice(start, start + this.credPageSize);
  });

  ordTotalPages = computed(() => Math.max(1, Math.ceil(this.orders().length / this.ordPageSize)));
  pagedOrders = computed(() => {
    const start = this.ordPage() * this.ordPageSize;
    return this.orders().slice(start, start + this.ordPageSize);
  });

  credPrevPage() { this.credPage.set(Math.max(0, this.credPage() - 1)); }
  credNextPage() { this.credPage.set(Math.min(this.credTotalPages() - 1, this.credPage() + 1)); }
  ordPrevPage() { this.ordPage.set(Math.max(0, this.ordPage() - 1)); }
  ordNextPage() { this.ordPage.set(Math.min(this.ordTotalPages() - 1, this.ordPage() + 1)); }

  // Connect form
  connectApiKey = '';
  connectApiSecret = '';
  // PAPER mode uses a real (testnet) API key for the same one-time permission check TESTNET
  // does (see BrokerCredentialService.doConnect's PAPER-routing on the backend) but never
  // sends an authenticated order afterward, so it reuses this same form and fields.
  connectMode: 'TESTNET' | 'PAPER' = 'TESTNET';

  // LIVE connect form — deliberately separate fields from the TESTNET connect form above:
  // a LIVE credential needs the actual Binance mainnet API key/secret, never the testnet
  // ones, since Binance treats them as genuinely different keys.
  liveConnectApiKey = '';
  liveConnectApiSecret = '';

  // Manual test order form
  testSymbol = 'BTCUSDT';
  testSide: 'BUY' | 'SELL' = 'BUY';
  testQuantity: number | null = null;
  // Optional, BUY-only — left null on a SELL or when not wanted.
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
  // Required (non-zero) before a credential can be authorized for autonomous LIVE trading
  // (see the backend's authorizeLiveAutoTrade), so they're editable here with sane defaults
  // rather than left at the backend model's disabled defaults with no way to change them.
  maxTotalExposureQuote = 100;
  maxDrawdownPercent = 10;
  maxOrdersPerHour = 20;
  maxConsecutiveAutoTradeLosses = 3;
  // Round-tripped with the backend: loaded from the profile in loadRiskProfile below,
  // editable here, and sent back on every save, so a save never resets them to the
  // backend's bare defaults.
  maxSymbolExposureQuote = 0; // 0 = disabled, matching the backend model's own default
  maxPriceDeviationPercent = 1.5;
  circuitBreakerThreshold = 3;
  // No dedicated editor UI for group membership yet, but these are loaded here and sent
  // back unmodified on save, so a save from this form never wipes out correlation
  // groups/caps configured through some other path (a future editor, or the API directly).
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
        this.credPage.set(0);
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
        // Only overwrite the form's default when the backend has a real value on file —
        // a profile with these at 0/undefined falls back to this form's own sane defaults,
        // which reads better than showing "0" as if it were a deliberately saved value.
        if (p.maxTotalExposureQuote) this.maxTotalExposureQuote = p.maxTotalExposureQuote;
        if (p.maxDrawdownPercent) this.maxDrawdownPercent = p.maxDrawdownPercent;
        if (p.maxOrdersPerHour) this.maxOrdersPerHour = p.maxOrdersPerHour;
        if (p.maxConsecutiveAutoTradeLosses) this.maxConsecutiveAutoTradeLosses = p.maxConsecutiveAutoTradeLosses;
        // Must be loaded here too, or saveRiskProfile() below would echo back this form's
        // stale/default in-memory values instead of the backend's actual current ones on
        // every save that doesn't itself change them.
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
    this.broker.history().subscribe({ next: (r: any) => { this.orders.set(r.data || []); this.ordPage.set(0); } });
  }

  placeTestOrder() {
    const c = this.selected();
    if (!c || !this.testQuantity) { this.show('error', 'Pick a credential and enter a quantity.'); return; }
    // Mirrors the same both-or-neither / BUY-only / SL-below-TP rules the backend enforces,
    // so a bad combination is caught before the request even goes out rather than only
    // reported back as a 400. The backend remains the real source of truth for this.
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
      // Always echoed back so a save never wipes these: maxSymbolExposureQuote/
      // maxPriceDeviationPercent/circuitBreakerThreshold from this form's round-tripped
      // fields, and correlationGroups/correlationGroupCaps unmodified from whatever was
      // most recently loaded, since there's no dedicated editor UI for them yet.
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

  // Establishes a genuinely separate LIVE connection with its own key/secret fields,
  // validated against Binance's real LIVE endpoint — a testnet key would be rejected
  // outright there, so this can't simply reuse an existing TESTNET-validated credential.
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
