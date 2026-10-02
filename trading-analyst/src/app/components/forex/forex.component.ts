import { Component, OnInit, OnDestroy, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Subscription } from 'rxjs';
import { LiveDataService, LiveQuote } from '../../services/live-data.service';
import { TaEngineService, TradeCall } from '../../services/ta-engine.service';
import { CurrencyService } from '../../services/currency.service';
import { AnalysisModalComponent } from '../analysis-modal/analysis-modal.component';
import { CallHistoryComponent } from '../call-history/call-history.component';
import { TradeHistoryService } from '../../services/trade-history.service';
import { AuthService } from '../../services/auth.service';

@Component({
    selector: 'app-forex',
    imports: [CommonModule, FormsModule, AnalysisModalComponent, CallHistoryComponent],
    templateUrl: './forex.component.html',
    styleUrls: ['./forex.component.scss']
})
export class ForexComponent implements OnInit, OnDestroy {
  private liveData     = inject(LiveDataService);
  private ta           = inject(TaEngineService);
  currency             = inject(CurrencyService);
  private tradeHistory = inject(TradeHistoryService);
  auth                 = inject(AuthService);
  private sub?: Subscription;

  allRates:   Record<string,number> = {};  // raw ECB rates (USD base)
  allPairs:   LiveQuote[] = [];
  filtered:   LiveQuote[] = [];
  search       = '';
  loading      = true;
  analyzing    = '';
  tradeCall:   TradeCall | null = null;
  modalVisible = false;

  // Review finding (closing statement — "Never: 🟢 LIVE when the application is actually
  // showing fallback numbers"): checked against allPairs (the full fetched set), not filtered
  // (which only reflects the current search) — the header badge should reflect the whole
  // dataset's actual freshness, not just whatever the user happens to be searching for right now.
  anyStale(): boolean {
    return this.allPairs.some(p => p.stale);
  }
  selectedPair: LiveQuote | null = null;
  lastUpdated  = '';
  selectedGroup = 'All';
  groups = ['All','INR Pairs','Major Pairs','Cross Pairs'];
  historyRefresh = 0;

  ngOnInit() {
    this.sub = this.liveData.getForexLive().subscribe(pairs => {
      this.allPairs    = pairs;
      this.filterPairs();
      this.loading     = false;
      this.lastUpdated = new Date().toLocaleTimeString('en-IN',{hour12:false});
    });
  }
  ngOnDestroy() { this.sub?.unsubscribe(); }

  filterPairs() {
    let list = this.allPairs;
    if (this.selectedGroup === 'INR Pairs')   list = list.filter(p => p.symbol.includes('INR'));
    if (this.selectedGroup === 'Major Pairs') list = list.filter(p => !p.symbol.includes('INR') && (p.symbol.includes('USD') || p.symbol.includes('EUR')));
    if (this.selectedGroup === 'Cross Pairs') list = list.filter(p => !p.symbol.includes('USD') && !p.symbol.includes('INR'));
    if (this.search) {
      const q = this.search.toLowerCase();
      list = list.filter(p => p.symbol.toLowerCase().includes(q) || p.name.toLowerCase().includes(q));
    }
    this.filtered = list;
  }

  setGroup(g: string) { this.selectedGroup = g; this.filterPairs(); }

  // ── Proper forex price display with USD/INR conversion ────
  formatRate(pair: LiveQuote): string {
    const cur = this.currency.currency();
    const sym = pair.symbol;  // e.g. USD/INR, EUR/USD, GBP/INR

    if (cur === 'INR') {
      // Show price in INR terms
      if (sym.endsWith('/INR')) {
        // Already in INR (USD/INR, EUR/INR etc) — show directly
        return '₹' + pair.price.toFixed(4);
      } else if (sym.startsWith('USD/')) {
        // e.g. USD/JPY: price is JPY per USD. Convert: INR per unit = INR/JPY
        // To get INR value: how much INR for 1 unit of base?
        // USD/JPY price = JPY per USD. We want INR per 1 USD = usdToInr
        // For display: show ₹ equivalent of 1 USD = usdToInr
        return '₹' + this.currency.usdToInr().toFixed(4) + ' (per USD)';
      } else {
        // Cross pair like EUR/GBP: convert base to INR
        // pair.price = GBP per EUR. INR per EUR = EUR/USD * usdToInr
        return '₹' + (pair.price * this.currency.usdToInr()).toFixed(4);
      }
    } else {
      // USD mode — show actual pair price
      if (sym.endsWith('/INR')) {
        // USD/INR etc: convert to show USD equivalent
        // USD/INR price = INR per USD. In USD mode show: $1 / price
        if (sym === 'USD/INR') return '$1.0000';
        // EUR/INR: EUR/USD = EUR/INR / usdToInr
        return '$' + (pair.price / this.currency.usdToInr()).toFixed(5);
      } else {
        // Show normal pair price in USD
        return pair.price.toFixed(5);
      }
    }
  }

  // Get the value to use in analysis modal
  getDisplayPrice(pair: LiveQuote): number {
    if (this.currency.currency() === 'INR') {
      if (pair.symbol.endsWith('/INR')) return pair.price;
      return pair.price * this.currency.usdToInr();
    }
    return pair.price;
  }

  analyze(pair: LiveQuote) {
    this.analyzing = pair.symbol;
    this.selectedPair = pair;
    const [base, quote] = pair.symbol.split('/');
    this.liveData.getForexKlines(base, quote).subscribe(candles => {
      // Review finding (P1 #9 — "Synthetic candles are still used to generate trading
      // analysis" — "I'd actually make it P0 for anything connected to live trading"):
      // confirmed real — this fell back to Math.random()-generated candles and fed them
      // straight into the real signal pipeline with no indication to the user.
      if (candles.length < 20) {
        this.analyzing = '';
        this.tradeCall = null as any;
        this.analysisUnavailable = pair.symbol;
        return;
      }
      this.analysisUnavailable = '';
      this.tradeCall = this.ta.analyze(candles, pair.symbol, '1D');
      this.modalVisible = true;
      this.analyzing = '';
    });
  }
  analysisUnavailable = '';

  // Review finding (P1 #9 — full context above at analyze()): genCandles() was removed
  // entirely, not just unused — Math.random()-generated fake market history has no
  // legitimate caller left in this file.

  fmtPrice = (v: number) => {
    if (this.currency.currency() === 'INR') return '₹' + v.toFixed(4);
    return '$' + v.toFixed(5);
  };
}
