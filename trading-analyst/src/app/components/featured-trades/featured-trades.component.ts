import { Component, Input, OnInit, OnDestroy, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FeaturedTradesService, FeaturedTrade } from '../../services/featured-trades.service';

@Component({
  selector: 'app-featured-trades',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './featured-trades.component.html',
  styleUrls: ['./featured-trades.component.scss']
})
export class FeaturedTradesComponent implements OnInit, OnDestroy {
  @Input() market: 'CRYPTO' | 'STOCK' = 'CRYPTO';

  private svc = inject(FeaturedTradesService);

  trades:      FeaturedTrade[] = [];
  selectedTf   = '15m';
  loadedCount  = 0;
  private refreshTimer?: any;

  readonly cryptoTfs = ['5m','15m','30m','1h'];
  readonly stockTfs  = ['15m','30m','1h'];

  get tfs() { return this.market === 'CRYPTO' ? this.cryptoTfs : this.stockTfs; }

  ngOnInit() {
    this.selectedTf = this.market === 'CRYPTO' ? '15m' : '15m';
    this.load();
    // Auto refresh every 5 min
    this.refreshTimer = setInterval(() => this.load(), 300_000);
  }

  ngOnDestroy() { clearInterval(this.refreshTimer); }

  setTf(tf: string) {
    this.selectedTf = tf;
    this.load();
  }

  load() {
    this.loadedCount = 0;
    this.trades = this.market === 'CRYPTO'
      ? this.svc.getFeaturedCrypto(12)
      : this.svc.getFeaturedStocks(12);

    // Load signals with 300ms stagger to avoid rate limits
    this.trades.forEach((t, i) => {
      setTimeout(() => {
        if (this.market === 'CRYPTO') {
          this.svc.loadCryptoSignal(t, this.selectedTf);
        } else {
          this.svc.loadStockSignal(t, this.selectedTf);
        }
        this.loadedCount++;
      }, i * 350);
    });
  }

  get longTrades()  { return this.trades.filter(t => !t.loading && t.call?.direction === 'LONG'); }
  get shortTrades() { return this.trades.filter(t => !t.loading && t.call?.direction === 'SHORT'); }
  get waitTrades()  { return this.trades.filter(t => !t.loading && t.call?.direction === 'WAIT'); }
  get loadingCount(){ return this.trades.filter(t => t.loading).length; }

  dirLabel(t: FeaturedTrade): string {
    if (!t.call) return '';
    if (t.call.direction === 'LONG')  return '▲ LONG';
    if (t.call.direction === 'SHORT') return '▼ SHORT';
    return '⏸ WAIT';
  }
  dirClass(t: FeaturedTrade): string {
    if (!t.call) return '';
    if (t.call.direction === 'LONG')  return 'long';
    if (t.call.direction === 'SHORT') return 'short';
    return 'wait';
  }
  confColor(c: number): string {
    if (c >= 75) return '#00FF88';
    if (c >= 60) return '#FFB800';
    return '#FF3B5C';
  }
  fmtPrice(p: number): string {
    if (!p) return '—';
    if (p > 1000)    return `$${Math.round(p).toLocaleString()}`;
    if (p > 1)       return `$${p.toFixed(3)}`;
    return `$${p.toFixed(5)}`;
  }
  fmtStockPrice(p: number): string {
    if (!p) return '—';
    return `₹${p.toFixed(2)}`;
  }
  priceStr(t: FeaturedTrade): string {
    return t.market === 'CRYPTO' ? this.fmtPrice(t.price) : this.fmtStockPrice(t.price);
  }
  riskPct(t: FeaturedTrade): string {
    if (!t.call?.riskPct) return '';
    return `Risk ${t.call.riskPct.toFixed(1)}%`;
  }
}
