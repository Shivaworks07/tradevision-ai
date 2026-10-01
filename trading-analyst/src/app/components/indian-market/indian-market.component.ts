import { FeaturedTradesComponent } from '../featured-trades/featured-trades.component';
import { Component, OnInit, OnDestroy, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { SkeletonCardComponent } from '../../shared/skeleton.component';
import { EmptyStateComponent, ErrorStateComponent } from '../../shared/empty-state.component';
import { FormsModule } from '@angular/forms';
import { Subscription, of, forkJoin } from 'rxjs';
import { debounceTime, distinctUntilChanged, switchMap } from 'rxjs/operators';
import { Subject } from 'rxjs';
import { LiveDataService, LiveQuote } from '../../services/live-data.service';
import { TaEngineService, TradeCall } from '../../services/ta-engine.service';
import { CurrencyService } from '../../services/currency.service';
import { AuthService } from '../../services/auth.service';
import { SmcEngineService } from '../../services/smc-engine.service';
import { VolumeProfileService } from '../../services/volume-profile.service';
import { MarketRegimeService } from '../../services/market-regime.service';
import { AnalysisModalComponent } from '../analysis-modal/analysis-modal.component';
import { CallHistoryComponent } from '../call-history/call-history.component';
import { StockChartComponent } from '../stock-chart/stock-chart.component';
import { OptionChainComponent } from '../option-chain/option-chain.component';
import { TradeHistoryService } from '../../services/trade-history.service';

@Component({
  selector: 'app-indian-market',
  standalone: true,
  imports: [CommonModule, FeaturedTradesComponent, SkeletonCardComponent, EmptyStateComponent, ErrorStateComponent, FormsModule, AnalysisModalComponent, CallHistoryComponent, StockChartComponent, OptionChainComponent],
  templateUrl: './indian-market.component.html',
  styleUrls: ['./indian-market.component.scss']
})
export class IndianMarketComponent implements OnInit, OnDestroy {
  private liveData = inject(LiveDataService);
  private ta       = inject(TaEngineService);
  currency         = inject(CurrencyService);
  auth             = inject(AuthService);
  private smcSvc   = inject(SmcEngineService);
  private vpSvc    = inject(VolumeProfileService);
  private regimeSvc = inject(MarketRegimeService);
  // P3-4 fix ("client 'risk engine' ... unrelated to server limits" -- external review): this
  // RiskEngineService injection was genuinely dead code -- never referenced anywhere else in this
  // component, confirmed by grepping this file before removing it, not assumed.
  private tradeHistory = inject(TradeHistoryService);
  selectedForHistory = '';
  historyRefresh = 0;
  chartSymbol = '';
  showChart = false;

  stocks:        LiveQuote[] = [];
  search         = '';
  searchResults: { sym: string; name: string; sector: string }[] = [];
  showDropdown   = false;
  loading        = true;
  searching      = false;
  selectedSector = 'All';
  sectors        = ['All','IT','Banking','Energy','Auto','Pharma','FMCG','Finance','Power','Telecom','Infrastructure','Metals','Cement','Defence','Consumer','Fintech','Insurance','Healthcare','Conglomerate','Renewable'];

  analyzing      = '';
  tradeCall:     TradeCall | null = null;
  modalVisible   = false;
  selectedStock: LiveQuote | null = null;
  lastUpdated    = '';
  dataSource     = 'Loading...';
  // Review finding (P1 #10 — "Index fallback is also fake market data"): the INITIAL state
  // shown before the first live fetch resolves was ALSO these exact static numbers with no
  // stale marking — the same gap, just at component construction time rather than in the
  // fallback function itself.
  nifty          = { value: '24,013', change: '-154.90', pct: '-0.64', up: false, stale: true };
  sensex         = { value: '79,212', change: '-512.30', pct: '-0.64', up: false, stale: true };

  private subs:        Subscription[] = [];
  private searchSubj = new Subject<string>();

  get filtered(): LiveQuote[] {
    if (this.selectedSector === 'All') return this.stocks;
    return this.stocks.filter(s => s.sector === this.selectedSector);
  }

  ngOnInit() {
    this.subs.push(
      this.liveData.getIndianStocks().subscribe(stocks => {
        this.stocks  = stocks;
        this.loading = false;
        this.lastUpdated = new Date().toLocaleTimeString('en-IN', { hour12: false });
        const hasLive = stocks.some(s => Math.abs(s.changePct) > 0.001);
        this.dataSource = hasLive ? '✅ Yahoo Finance (Live)' : '⚠️ Fallback Data';
      })
    );
    this.subs.push(
      this.liveData.getIndexQuotes().subscribe(idx => {
        this.nifty = idx.nifty; this.sensex = idx.sensex;
      })
    );

    // Debounced live search — searches the full ~2000-symbol NSE universe
    // via the backend, not just the curated large-cap list.
    this.subs.push(
      this.searchSubj.pipe(
        debounceTime(300),
        distinctUntilChanged(),
        switchMap(q => q.length > 0 ? this.liveData.searchNSEStocksLive(q) : of([]))
      ).subscribe(results => {
        this.searchResults = results;
        this.showDropdown  = this.search.length > 0;
      })
    );
  }

  ngOnDestroy() { this.subs.forEach(s => s.unsubscribe()); }

  onSearchInput() {
    this.searchSubj.next(this.search);
    if (!this.search) { this.showDropdown = false; }
  }

  selectFromDropdown(item: { sym: string; name: string; sector: string }) {
    this.showDropdown = false;
    this.search = item.name;
    this.searching = true;
    this.liveData.fetchStockQuote(item).subscribe(q => {
      if (q) {
        // Add or replace in list
        const idx = this.stocks.findIndex(s => s.symbol === q.symbol);
        if (idx >= 0) this.stocks[idx] = q;
        else this.stocks = [q, ...this.stocks];
        // Open its chart immediately — picking a stock from the full NSE
        // search (not just the curated grid) should lead straight to
        // viewing it, not just silently add a row further down the page.
        this.openChart(q.symbol);
      }
      this.searching = false;
      this.search = '';
    });
  }

  setSector(sec: string) { this.selectedSector = sec; }

  analyze(stock: LiveQuote) {
    this.analyzing     = stock.symbol;
    this.selectedStock = stock;
    this.callMode       = 'SWING';
    // MTF: Daily + Weekly — for positional/swing calls
    forkJoin({
      daily:  this.liveData.getIndianKlines(stock.symbol + '.NS'),
      weekly: this.liveData.getIndianKlinesOnce(stock.symbol + '.NS', '1y', '1wk')
    }).subscribe(({ daily, weekly }) => {
      // Review finding (P1 #9 — "Synthetic candles are still used to generate trading
      // analysis" — "I'd actually make it P0 for anything connected to live trading"):
      // confirmed real — this fell back to Math.random()-generated candles and fed them
      // straight into the real signal pipeline with no indication to the user. Matches the
      // pattern analyzeIntraday() below already correctly uses: refuse rather than fabricate.
      if (daily.length < 20) {
        this.analyzing = '';
        this.tradeCall = null as any;
        this.analysisUnavailable = stock.symbol;
        return;
      }
      this.analysisUnavailable = '';
      const weeklyData = weekly.length >= 20 ? weekly : undefined;
      this.runAnalysisPipeline(stock, daily, weeklyData, '1D');
    });
  }
  analysisUnavailable = '';

  // Intraday call: 15m entry timeframe confirmed against 1h higher-timeframe
  // trend — the standard intraday MTF pairing, as distinct from analyze()
  // above which is Daily+Weekly for swing/positional calls. Uses the same
  // SMC/Volume-Profile/Regime pipeline, just on intraday candles, so the
  // resulting entry/SL/targets are genuinely intraday-appropriate rather
  // than daily levels mislabeled as intraday.
  callMode: 'SWING' | 'INTRADAY' = 'SWING';
  analyzeIntraday(stock: LiveQuote) {
    this.analyzing     = stock.symbol;
    this.selectedStock = stock;
    this.callMode       = 'INTRADAY';
    forkJoin({
      m15: this.liveData.getIndianKlinesOnce(stock.symbol + '.NS', '5d', '15m'),
      h1:  this.liveData.getIndianKlinesOnce(stock.symbol + '.NS', '1mo', '60m'),
    }).subscribe(({ m15, h1 }) => {
      if (m15.length < 20) {
        // Intraday candles genuinely may not exist yet (pre-market, illiquid
        // stock) — don't fabricate a call on synthetic data for something
        // labeled "intraday", unlike the swing path's synthetic fallback.
        this.analyzing = '';
        this.tradeCall = null as any;
        this.intradayUnavailable = stock.symbol;
        return;
      }
      this.intradayUnavailable = '';
      const h1Data = h1.length >= 20 ? h1 : undefined;
      this.runAnalysisPipeline(stock, m15, h1Data, '15m');
    });
  }
  intradayUnavailable = '';

  private runAnalysisPipeline(stock: LiveQuote, data: any[], higherTf: any[] | undefined, tf: string) {
    const smc = this.smcSvc.analyze(data, stock.symbol);
    const vp  = this.vpSvc.analyze(data);
    const smcForTA = { bias: smc.bias, biasStrength: smc.biasStrength, entrySetup: smc.entrySetup, structureBreaks: smc.structureBreaks, orderBlocks: smc.orderBlocks };
    const vpForTA  = { priceLocation: vp.priceLocation, poc: vp.poc, vah: vp.vah, val: vp.val, bias: vp.bias, nearestHVN: vp.nearestHVN };
    const reg = this.regimeSvc.detect(data, stock.symbol);
    const regForTA = { regime: reg.regime, label: reg.label, emoji: reg.emoji, color: reg.color, confidence: reg.confidence, riskMultiplier: reg.strategy.riskMultiplier, slMultiplier: reg.strategy.slMultiplier, weightAdjustments: reg.weightAdjustments, strategy: reg.strategy, warnings: reg.warnings };

    this.tradeCall = this.ta.analyzeWithMTF(data, stock.symbol, tf, higherTf, undefined, 'STOCK', smcForTA, undefined, vpForTA, regForTA);
    this.modalVisible = true;
    this.analyzing    = '';
    this.selectedForHistory = stock.symbol;

    // If the chart for this symbol is already open, draw the new levels on
    // it immediately rather than requiring the user to reopen the chart.
    if (this.chartSymbol === stock.symbol) this.showChart = true;
  }

  // Bound into <app-stock-chart [tradeLevels]="chartTradeLevels()"> — only
  // supplies levels when they belong to the symbol currently on screen, so
  // switching charts never shows a stale call from a different stock.
  chartTradeLevels() {
    if (!this.tradeCall || this.selectedStock?.symbol !== this.chartSymbol) return null;
    return {
      entry: this.tradeCall.entry, stopLoss: this.tradeCall.stopLoss,
      target1: this.tradeCall.target1, target2: this.tradeCall.target2, target3: this.tradeCall.target3,
      direction: this.tradeCall.direction,
    };
  }

  // Review finding (P1 #9 — full context above at analyze()): syntheticCandles() was removed
  // entirely, not just unused — Math.random()-generated fake market history has no legitimate
  // caller left in this file and shouldn't be a temptation for a future call site to reach for.

  openChart(symbol: string, event?: Event) {
    event?.stopPropagation();
    if (this.chartSymbol === symbol && this.showChart) { this.showChart = false; this.chartSymbol = ''; return; }
    this.chartSymbol = symbol;
    this.showChart = true;
  }

  toggleFavorite(symbol: string, event: Event) {
    event.stopPropagation();
    const isFav = this.auth.isFavorite(symbol, 'STOCK');
    this.auth.toggleFavorite(symbol, 'STOCK', !isFav).subscribe();
  }

  isFav(symbol: string) { return this.auth.isFavorite(symbol, 'STOCK'); }

  formatPrice(inrPrice: number): string {
    // Indian stocks always INR — no USD conversion
    if (inrPrice >= 10000000) return `₹${(inrPrice/10000000).toFixed(2)}Cr`;
    if (inrPrice >= 100000)   return `₹${(inrPrice/100000).toFixed(2)}L`;
    if (inrPrice >= 1000)     return `₹${(inrPrice/1000).toFixed(2)}K`;
    return `₹${inrPrice.toLocaleString('en-IN', { maximumFractionDigits: 2 })}`;
  }

  priceFormatter = (v: number) => this.formatPrice(v);

  // Signal filters (feature 8)
  activeSignalFilter = 'ALL';
  activeHoldFilter   = 'ALL';

  readonly signalFilters = [
    { label: 'All',          value: 'ALL' },
    { label: '🔥 Strong Buy', value: 'STRONG BUY' },
    { label: '▲ Buy',        value: 'BUY' },
    { label: '— Hold',       value: 'NEUTRAL' },
    { label: '▼ Sell',       value: 'SELL' },
    { label: '🔻 Strong Sell',value: 'STRONG SELL' },
  ];
  readonly holdFilters = [
    { label: 'All',       value: 'ALL' },
    { label: 'Intraday',  value: 'INTRADAY' },
    { label: 'Swing',     value: 'SWING' },
    { label: 'Long Term', value: 'LONGTERM' },
  ];

  setSignalFilter(v: string) { this.activeSignalFilter = v; }
  setHoldFilter(v: string)   { this.activeHoldFilter = v; }

  load() {
    // Re-trigger the subscription to reload data
    this.liveData.getIndianStocks().subscribe(stocks => {
      if (stocks?.length) { this.stocks = stocks; this.loading = false; }
    });
  }
}