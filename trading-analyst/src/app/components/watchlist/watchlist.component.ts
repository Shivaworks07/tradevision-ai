import { Component, OnInit, OnDestroy, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';
import { AuthService } from '../../services/auth.service';
import { LiveDataService } from '../../services/live-data.service';
import { TaEngineService, TradeCall } from '../../services/ta-engine.service';
import { forkJoin, Subscription, interval } from 'rxjs';
import { switchMap, startWith } from 'rxjs/operators';

interface WatchItem {
  symbol:     string;
  type:       'STOCK' | 'CRYPTO' | 'FOREX';
  price?:     number;
  changePct?: number;
  call?:      TradeCall;
  prevSignal?:string;
  changed:    boolean;
  loading:    boolean;
}

@Component({
  selector: 'app-watchlist',
  standalone: true,
  imports: [CommonModule, RouterModule],
  templateUrl: './watchlist.component.html',
  styleUrls: ['./watchlist.component.scss']
})
export class WatchlistComponent implements OnInit, OnDestroy {
  auth     = inject(AuthService);
  liveData = inject(LiveDataService);
  ta       = inject(TaEngineService);

  items:   WatchItem[] = [];
  loading  = true;
  private sub?: Subscription;
  private refreshSub?: Subscription;

  ngOnInit() {
    if (!this.auth.isLoggedIn) { this.loading = false; return; }
    this.buildWatchlist();
    // Refresh signals every 2 minutes
    this.refreshSub = interval(120000).subscribe(() => this.refreshSignals());
  }

  ngOnDestroy() { this.sub?.unsubscribe(); this.refreshSub?.unsubscribe(); }

  private buildWatchlist() {
    const u = this.auth.currentUser();
    if (!u) { this.loading = false; return; }

    const stocks  = Array.from(u.favoriteStocks  || []);
    const cryptos = Array.from(u.favoriteCryptos || []);
    const forex   = Array.from(u.favoriteForex   || []);

    this.items = [
      ...stocks.map(s  => ({ symbol:s,  type:'STOCK'  as const, changed:false, loading:true })),
      ...cryptos.map(s => ({ symbol:s,  type:'CRYPTO' as const, changed:false, loading:true })),
      ...forex.map(s   => ({ symbol:s,  type:'FOREX'  as const, changed:false, loading:true })),
    ];
    this.loading = false;
    if (this.items.length) this.refreshSignals();
  }

  refreshSignals() {
    this.items.forEach((item, idx) => {
      item.loading = true;
      const prevSignal = item.call?.signal;

      if (item.type === 'CRYPTO') {
        const sym = item.symbol.includes('USDT') ? item.symbol : item.symbol + 'USDT';
        this.liveData.getCryptoKlinesOnce(sym, '1h', 150).subscribe(candles => {
          if (!candles.length) { item.loading = false; return; }
          const lastClose = candles[candles.length-1].close;
          item.price     = lastClose;
          const prev1    = candles[candles.length-2]?.close || lastClose;
          item.changePct = +((lastClose - prev1)/prev1*100).toFixed(2);
          item.call      = this.ta.analyze(candles, item.symbol, '1h', 'CRYPTO');
          item.changed   = !!(prevSignal && prevSignal !== item.call.signal);
          if (item.changed) item.prevSignal = prevSignal;
          item.loading   = false;
        });
      } else if (item.type === 'STOCK') {
        this.liveData.fetchStockQuote({ sym: item.symbol, name: item.symbol, sector: '' }).subscribe(q => {
          if (q) {
            item.price     = q.price;
            item.changePct = q.changePct;
          }
          item.loading = false;
        });
      } else {
        item.loading = false;
      }
    });
  }

  remove(item: WatchItem) {
    this.auth.toggleFavorite(item.symbol, item.type, false).subscribe(() => {
      this.items = this.items.filter(i => i.symbol !== item.symbol || i.type !== item.type);
    });
  }

  sigClass(call?: TradeCall): string {
    if (!call || call.direction === 'WAIT') return 'neutral';
    return call.direction === 'LONG' ? 'bull' : 'bear';
  }

  confColor(c: number): string { return c>=75?'#00FF88':c>=60?'#00D4FF':c>=45?'#FFB800':'#FF3B5C'; }
  priceStr(p?: number): string {
    if (p === undefined || p === null || p === 0) return '—';
    if (p >= 100000) return `₹${(p/100000).toFixed(2)}L`;
    if (p >= 1000)   return `₹${p.toLocaleString('en-IN',{maximumFractionDigits:2})}`;
    return `$${p.toLocaleString('en-US',{maximumFractionDigits:4})}`;
  }
}
