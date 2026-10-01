import { Component, OnInit, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { PositionService, PositionSummary } from '../../services/position.service';
import { BrokerService, BrokerCredentialResponse } from '../../services/broker.service';
import { environment } from '../../../environments/environment';

/**
 * Review finding ("UI has no real Position/Execution dashboard" — "A user should never have to
 * open Binance manually to discover: does my bot currently own something?"): this is that
 * dashboard. Also carries the two safe manual actions this review asked for (Emergency Flatten,
 * Reconcile Now) — see PositionDashboardService's own javadoc for why "Cancel protection" was
 * deliberately left out rather than added alongside them.
 */
@Component({
  selector: 'app-positions',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './positions.component.html',
  styleUrl: './positions.component.scss'
})
export class PositionsComponent implements OnInit, OnDestroy {
  credentials: BrokerCredentialResponse[] = [];
  selectedCredentialId = '';
  statusFilter: 'OPEN' | 'CLOSED' | 'ALL' = 'OPEN';
  positions: PositionSummary[] = [];
  currentPrices: Record<string, number | undefined> = {};
  loading = false;
  criticalIncidentCount = 0;
  actionInFlight: Record<string, boolean> = {};
  toast: { text: string; err: boolean } | null = null;

  private priceRefreshHandle: any;

  constructor(
    private positionService: PositionService,
    private brokerService: BrokerService,
    private http: HttpClient
  ) {}

  ngOnInit() {
    this.brokerService.list().subscribe({
      next: (r: any) => {
        this.credentials = r?.data || [];
        if (this.credentials.length) {
          this.selectedCredentialId = this.credentials[0].id;
          this.refresh();
        }
      }
    });
    // Refresh open-position prices periodically — a static "Current: $x" would go stale within
    // seconds on a moving market, which defeats the point of a live dashboard.
    this.priceRefreshHandle = setInterval(() => this.refreshPrices(), 15000);
  }

  ngOnDestroy() {
    if (this.priceRefreshHandle) clearInterval(this.priceRefreshHandle);
  }

  onCredentialChange() {
    this.refresh();
  }

  onStatusFilterChange() {
    this.refresh();
  }

  refresh() {
    if (!this.selectedCredentialId) return;
    this.loading = true;
    this.positionService.list(this.selectedCredentialId, this.statusFilter).subscribe({
      next: (r: any) => {
        this.positions = r?.data || [];
        this.loading = false;
        this.refreshPrices();
      },
      error: () => { this.loading = false; this.showToast('Could not load positions.', true); }
    });
    // Review finding (P1 #20 — "Need a durable incident model"): the "🔴 N CRITICAL" indicator
    // the review's own mockup asked for.
    this.positionService.unresolvedIncidents(this.selectedCredentialId).subscribe({
      next: (r: any) => { this.criticalIncidentCount = (r?.data || []).length; },
      error: () => { /* non-critical to the page's core function — fails silently */ }
    });
  }

  private refreshPrices() {
    const openSymbols = [...new Set(this.positions.filter(p => p.status === 'OPEN').map(p => p.symbol))];
    for (const symbol of openSymbols) {
      this.http.get<{ price: string }>(`${environment.binanceUrl}/api/v3/ticker/price?symbol=${symbol}`)
        .subscribe({
          next: r => { this.currentPrices[symbol] = parseFloat(r.price); },
          error: () => { /* price unavailable this cycle — the card falls back to showing entry-only, not a stale guess */ }
        });
    }
  }

  unrealizedPnl(p: PositionSummary): number | null {
    if (p.status !== 'OPEN' || p.avgEntryPriceUnverified || p.avgEntryPrice == null) return null;
    const current = this.currentPrices[p.symbol];
    if (current == null) return null;
    return (current - p.avgEntryPrice) * p.quantity;
  }

  selectedCredential(): BrokerCredentialResponse | undefined {
    return this.credentials.find(c => c.id === this.selectedCredentialId);
  }

  confirmFlatten(p: PositionSummary) {
    const qty = p.quantity;
    if (!confirm(`Emergency flatten ${qty} ${p.symbol} right now? This closes the position at the current market price immediately.`)) return;
    this.actionInFlight[p.id] = true;
    this.positionService.emergencyFlatten(p.id).subscribe({
      next: (r: any) => { this.actionInFlight[p.id] = false; this.showToast(r?.message || 'Emergency flatten requested.', !r?.success); this.refresh(); },
      error: (e) => { this.actionInFlight[p.id] = false; this.showToast(e?.error?.message || 'Emergency flatten failed.', true); }
    });
  }

  reconcileNow() {
    if (!this.selectedCredentialId) return;
    this.actionInFlight['__reconcile'] = true;
    this.positionService.reconcileNow(this.selectedCredentialId).subscribe({
      next: (r: any) => { this.actionInFlight['__reconcile'] = false; this.showToast(r?.message || 'Reconciliation triggered.', !r?.success); setTimeout(() => this.refresh(), 2000); },
      error: (e) => { this.actionInFlight['__reconcile'] = false; this.showToast(e?.error?.message || 'Reconcile failed.', true); }
    });
  }

  // Review finding ("The UI needs explicit protection-state presentation" -- external review,
  // second pass): this component's own former client-side isPartiallyProtected() guess is
  // removed -- the template now reads the server's own real, minQty-aware protectionStatus
  // field directly (see position.service.ts's own updated interface), rather than a
  // client-side heuristic that had no real exchange minQty to judge a residual against at all.

  private showToast(text: string, err: boolean) {
    this.toast = { text, err };
    setTimeout(() => { this.toast = null; }, 5000);
  }
}
