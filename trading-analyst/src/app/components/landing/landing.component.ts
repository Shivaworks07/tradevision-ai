import { Component, OnInit, OnDestroy, inject } from '@angular/core';
import { CommonModule, DecimalPipe } from '@angular/common';
import { Router } from '@angular/router';
import { environment } from '../../../environments/environment';
import { HttpClient } from '@angular/common/http';
import { AuthService } from '../../services/auth.service';
import { AuthModalComponent } from '../auth-modal/auth-modal.component';
import { interval, Subscription, forkJoin } from 'rxjs';
import { startWith, switchMap, catchError } from 'rxjs/operators';
import { of } from 'rxjs';

interface TickerItem { label: string; price: string; change: number; }

@Component({
    selector: 'app-landing',
    imports: [CommonModule, DecimalPipe, AuthModalComponent],
    templateUrl: './landing.component.html',
    styleUrls: ['./landing.component.scss']
})
export class LandingComponent implements OnInit, OnDestroy {
  auth   = inject(AuthService);
  router = inject(Router);
  private http = inject(HttpClient);

  showAuth     = false;
  sessionValid = false;

  // ── Live ticker data ──────────────────────────────────────
  tickers: TickerItem[] = [];

  // ── BTC / Crypto ─────────────────────────────────────────
  btcPrice  = 0; btcChange = 0; btcHigh = 0; btcLow = 0; btcAtr = 0;
  usdInr    = 84.5;

  get btcInr()   { return this.btcPrice * this.usdInr; }
  get btcSL()    { return this.btcPrice - this.btcAtr * 1.5; }
  get btcT1()    { return this.btcPrice + this.btcAtr * 1.5 * 1.5; }
  get btcT2()    { return this.btcPrice + this.btcAtr * 1.5 * 3; }
  get btcT3()    { return this.btcPrice + this.btcAtr * 1.5 * 5; }
  get btcSLInr() { return this.btcSL  * this.usdInr; }
  get btcT1Inr() { return this.btcT1  * this.usdInr; }
  get btcT2Inr() { return this.btcT2  * this.usdInr; }
  get btcT3Inr() { return this.btcT3  * this.usdInr; }

  // ── Market sentiment ─────────────────────────────────────
  niftyChange  = 0;
  sensexChange = 0;
  btcSentiment = 0;   // BTC 24h change
  fearGreedValue = 50;
  fearGreedLabel = 'Neutral';

  get overallSentiment(): 'STRONGLY_BULLISH'|'BULLISH'|'NEUTRAL'|'BEARISH'|'STRONGLY_BEARISH' {
    // Weighted: Nifty 40%, Sensex 30%, BTC 20%, Fear&Greed 10%
    const score = (this.niftyChange * 0.4) + (this.sensexChange * 0.3) +
                  (this.btcSentiment * 0.2) + ((this.fearGreedValue - 50) / 10 * 0.1);
    if (score >  1.5) return 'STRONGLY_BULLISH';
    if (score >  0.4) return 'BULLISH';
    if (score < -1.5) return 'STRONGLY_BEARISH';
    if (score < -0.4) return 'BEARISH';
    return 'NEUTRAL';
  }
  get sentimentLabel(): string {
    const m: Record<string,string> = {
      'STRONGLY_BULLISH':'Strongly Bullish 🚀','BULLISH':'Bullish 📈',
      'NEUTRAL':'Neutral ⚖️','BEARISH':'Bearish 📉','STRONGLY_BEARISH':'Strongly Bearish 🔴'
    };
    return m[this.overallSentiment] || 'Loading…';
  }
  get sentimentColor(): string {
    const s = this.overallSentiment;
    if (s==='STRONGLY_BULLISH') return '#00FF88';
    if (s==='BULLISH')          return '#00D4FF';
    if (s==='BEARISH')          return '#FF3B5C';
    if (s==='STRONGLY_BEARISH') return '#FF0040';
    return '#FFB800';
  }

  // ── Gold prices (INR) ─────────────────────────────────────
  goldUsd    = 0;   // per troy oz
  goldChange = 0;
  // Indian gold: spot USD/oz → INR per 10g with 6% import duty + 3% GST + state levies ~15% total premium
  get gold24kInr10g(): number { return (this.goldUsd / 31.1035) * 10 * this.usdInr * 1.15; }
  get gold22kInr10g(): number { return this.gold24kInr10g * 22/24; }
  get gold18kInr10g(): number { return this.gold24kInr10g * 18/24; }

  // ── Nifty / Sensex ────────────────────────────────────────
  niftyPrice  = 0;
  sensexPrice = 0;

  private subs: Subscription[] = [];

  // ── Formatters ────────────────────────────────────────────
  fmtL(inr: number): string {
    if (!inr) return '—';
    return `₹${(inr/100000).toFixed(2)}L`;
  }
  fmtInr(inr: number): string {
    if (!inr) return '₹—';
    return `₹${Math.round(inr).toLocaleString('en-IN')}`;
  }
  fmtK(n: number): string {
    if (!n) return '—';
    return n >= 1000 ? `${(n/1000).toFixed(2)}K` : n.toFixed(2);
  }
  fmtGold(n: number): string {
    if (!n) return '—';
    return `₹${Math.round(n).toLocaleString('en-IN')}`;
  }
  pctClass(v: number): string { return v >= 0 ? 'up' : 'dn'; }
  pctStr(v: number):   string { return (v >= 0 ? '+' : '') + v.toFixed(2) + '%'; }

  ngOnInit() {
    // Review finding ("Frontend still caches non-secret user profile in localStorage; residual
    // XSS surface for session continuity" -- external review, nineteenth pass, P1, full context
    // in AuthService's own sessionReady field comment): this route isn't behind authGuard (it's
    // the public landing page), so it must await sessionReady itself before checking isLoggedIn
    // -- otherwise a page load could see isLoggedIn: false for a genuinely logged-in user during
    // the brief window before the new, async session-restoration check resolves.
    this.auth.sessionReady.then(() => {
      if (this.auth.isLoggedIn) {
        this.auth.refreshProfile().subscribe({
          next:  r => { this.sessionValid = !!r?.success; },
          error: () => { this.sessionValid = false; }
        });
      }
    });
    this.startAllFeeds();
  }

  ngOnDestroy() { this.subs.forEach(s => s.unsubscribe()); }

  private startAllFeeds() {
    // ── BTC every 15s ────────────────────────────────────────
    this.subs.push(
      interval(15000).pipe(
        startWith(0),
        switchMap(() => this.http.get<any>(
          `${environment.binanceUrl}/api/v3/ticker/24hr?symbol=BTCUSDT`
        ).pipe(catchError(() => of(null))))
      ).subscribe(d => {
        if (!d) return;
        this.btcPrice    = parseFloat(d.lastPrice);
        this.btcChange   = parseFloat(d.priceChangePercent);
        this.btcHigh     = parseFloat(d.highPrice);
        this.btcLow      = parseFloat(d.lowPrice);
        this.btcAtr      = (this.btcHigh - this.btcLow) / 4;
        this.btcSentiment = this.btcChange;
        this.updateTickers();
      })
    );

    // ── Nifty + Sensex every 60s via Yahoo Finance proxy ─────
    this.subs.push(
      interval(60000).pipe(
        startWith(0),
        switchMap(() => forkJoin({
          nifty:  this.http.get<any>(`${environment.yahooUrl}/v8/finance/chart/%5ENSEI?range=1d&interval=1d`).pipe(catchError(() => of(null))),
          sensex: this.http.get<any>(`${environment.yahooUrl}/v8/finance/chart/%5EBSESN?range=1d&interval=1d`).pipe(catchError(() => of(null))),
        }))
      ).subscribe(({ nifty, sensex }) => {
        if (nifty?.chart?.result?.[0]) {
          const r = nifty.chart.result[0].meta;
          this.niftyPrice  = r.regularMarketPrice || 0;
          this.niftyChange = r.regularMarketChangePercent || 0;
        }
        if (sensex?.chart?.result?.[0]) {
          const r = sensex.chart.result[0].meta;
          this.sensexPrice  = r.regularMarketPrice || 0;
          this.sensexChange = r.regularMarketChangePercent || 0;
        }
        this.updateTickers();
      })
    );

    // ── Gold via Metals-API alternative (Yahoo Finance) every 60s ──
    this.subs.push(
      interval(60000).pipe(
        startWith(0),
        switchMap(() => this.http.get<any>(
          `${environment.yahooUrl}/v8/finance/chart/GC%3DF?range=1d&interval=1d`
        ).pipe(catchError(() => of(null))))
      ).subscribe(d => {
        if (d?.chart?.result?.[0]) {
          const meta = d.chart.result[0].meta;
          this.goldUsd    = meta.regularMarketPrice || 0;
          this.goldChange = meta.regularMarketChangePercent || 0;
          this.updateTickers();
        }
      })
    );

    // ── USD/INR + key forex every 60s ────────────────────────
    this.subs.push(
      interval(60000).pipe(
        startWith(0),
        switchMap(() => this.http.get<any>(
          `${environment.forexUrl}/latest?from=USD&to=INR`
        ).pipe(catchError(() => of(null))))
      ).subscribe(r => {
        if (r?.rates?.INR) { this.usdInr = r.rates.INR; this.updateTickers(); }
      })
    );

    // ── Fear & Greed index every 5 min ───────────────────────
    this.subs.push(
      interval(300000).pipe(
        startWith(0),
        switchMap(() => this.http.get<any>('/fng-api/fng/1').pipe(catchError(() => of(null))))
      ).subscribe(d => {
        const val = parseInt(d?.data?.[0]?.value || '50');
        this.fearGreedValue = val;
        this.fearGreedLabel = d?.data?.[0]?.value_classification || 'Neutral';
      })
    );
  }

  private updateTickers() {
    const items: TickerItem[] = [];
    if (this.niftyPrice)  items.push({ label:'NIFTY50',  price: this.fmtK(this.niftyPrice),  change: this.niftyChange });
    if (this.sensexPrice) items.push({ label:'SENSEX',   price: this.fmtK(this.sensexPrice), change: this.sensexChange });
    if (this.btcPrice)    items.push({ label:'BTC/USDT', price: `$${Math.round(this.btcPrice).toLocaleString()}`, change: this.btcChange });
    if (this.usdInr)      items.push({ label:'USD/INR',  price: this.usdInr.toFixed(2), change: 0 });
    if (this.goldUsd)     items.push({ label:'GOLD/oz',  price: `$${Math.round(this.goldUsd).toLocaleString()}`, change: this.goldChange });
    // Duplicate for seamless scroll
    this.tickers = [...items, ...items];
  }

  openAuth() {
    if (this.sessionValid) { this.router.navigate(['/app/stocks']); return; }
    this.showAuth = true;
  }
  goToDashboard() { this.router.navigate(['/app/stocks']); }
  onLoggedIn()    { this.showAuth = false; this.router.navigate(['/app/stocks']); }
}
