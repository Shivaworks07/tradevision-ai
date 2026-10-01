import { Component, OnInit, OnDestroy, ViewChild, ElementRef, AfterViewInit, inject, HostListener } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { createChart, IChartApi, ISeriesApi, CandlestickSeries, LineSeries, ColorType, Time } from 'lightweight-charts';
import { LiveDataService } from '../../services/live-data.service';
import { OHLCV, TaEngineService, TradeCall } from '../../services/ta-engine.service';
import { SmcEngineService } from '../../services/smc-engine.service';
import { MarketRegimeService } from '../../services/market-regime.service';
import { CurrencyService } from '../../services/currency.service';
import { catchError, of } from 'rxjs';

type Market = 'CRYPTO' | 'STOCK' | 'FOREX';
type ResultType = 'HIT_T1' | 'HIT_T2' | 'HIT_T3' | 'HIT_SL' | 'EXPIRED';

interface ReplayCall {
  idx:    number;
  call:   TradeCall;
  result: ResultType;
  pnlPct: number;
}

@Component({
  selector: 'app-replay',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './replay.component.html',
  styleUrls: ['./replay.component.scss']
})
export class ReplayComponent implements OnInit, OnDestroy, AfterViewInit {
  @ViewChild('replayChart') chartRef!: ElementRef;

  private liveData = inject(LiveDataService);
  private ta       = inject(TaEngineService);
  private smc      = inject(SmcEngineService);
  private regime   = inject(MarketRegimeService);
  currency         = inject(CurrencyService);

  // ── Market selection ──────────────────────────────────────
  activeMarket: Market = 'CRYPTO';

  // Crypto
  cryptoInput  = 'BTC';
  cryptoSymbol = 'BTC';
  showCryptSug = false;
  filteredSugg: string[] = [];
  readonly CRYPTO_LIST = [
    'BTC','ETH','SOL','BNB','XRP','ADA','AVAX','DOGE','MATIC','NEAR',
    'INJ','ARB','OP','LINK','DOT','ATOM','UNI','LTC','PEPE','WIF',
    'ONDO','FET','SUI','APT','TAO','TIA','SEI','BONK','FLOKI','SHIB',
    'TRX','XLM','VET','ALGO','HBAR','RNDR','IMX','GRT','AAVE','SNX',
  ];

  // Indian Stock
  stockInput    = 'RELIANCE';
  readonly STOCK_LIST = [
    'RELIANCE','TCS','HDFCBANK','INFY','ICICIBANK','HINDUNILVR','SBIN',
    'BHARTIARTL','ITC','KOTAKBANK','LT','AXISBANK','BAJFINANCE','WIPRO',
    'ASIANPAINT','MARUTI','TITAN','ULTRACEMCO','ONGC','NTPC','POWERGRID',
    'TATASTEEL','HINDALCO','COALINDIA','SUNPHARMA','DRREDDY','CIPLA',
    'ADANIENT','ADANIPORTS','JSWSTEEL','TECHM','HCLTECH','BAJAJFINSV',
  ];
  showStockSug  = false;
  filteredStock: string[] = [];

  // Forex
  forexBase  = 'EUR';
  forexQuote = 'USD';
  readonly FOREX_PAIRS = [
    { base:'EUR', quote:'USD', label:'EUR/USD' },
    { base:'GBP', quote:'USD', label:'GBP/USD' },
    { base:'USD', quote:'JPY', label:'USD/JPY' },
    { base:'USD', quote:'CHF', label:'USD/CHF' },
    { base:'AUD', quote:'USD', label:'AUD/USD' },
    { base:'USD', quote:'INR', label:'USD/INR' },
    { base:'EUR', quote:'INR', label:'EUR/INR' },
    { base:'GBP', quote:'INR', label:'GBP/INR' },
  ];

  // Timeframe
  timeframe = '4h';
  readonly CRYPTO_TFS  = ['15m','30m','1h','4h','1d'];
  readonly STOCK_TFS   = ['1d','1wk'];
  readonly FOREX_TFS   = ['1d'];

  get activeTfs(): string[] {
    if (this.activeMarket === 'STOCK') return this.STOCK_TFS;
    if (this.activeMarket === 'FOREX') return this.FOREX_TFS;
    return this.CRYPTO_TFS;
  }

  // ── Speed presets ─────────────────────────────────────────
  readonly SPEED_PRESETS = [
    { label:'1×',  ms:500  },
    { label:'2×',  ms:250  },
    { label:'5×',  ms:100  },
    { label:'10×', ms:50   },
  ];
  activeSpeedIdx = 0;

  // ── Date range ────────────────────────────────────────────
  dateRange = '6m';
  readonly DATE_RANGES = [
    { label:'1M',  value:'1mo',  limit:30  },
    { label:'3M',  value:'3mo',  limit:90  },
    { label:'6M',  value:'6mo',  limit:180 },
    { label:'1Y',  value:'1y',   limit:365 },
    { label:'2Y',  value:'2y',   limit:500 },
  ];

  // ── Buy & Hold comparison ─────────────────────────────────
  buyHoldReturn = 0;

  // ── State ─────────────────────────────────────────────────
  allCandles:   OHLCV[] = [];
  currentIdx    = 50;
  playing       = false;
  speed         = 500;
  loading       = false;
  loaded        = false;

  history:      ReplayCall[] = [];
  currentCall:  TradeCall | null = null;
  lastResult:   ResultType | null = null;

  wins    = 0;
  losses  = 0;
  totalPnl = 0;

  private chart:     IChartApi | null = null;
  private candleSer: ISeriesApi<'Candlestick', Time> | null = null;
  private ema20S:    ISeriesApi<'Line', Time> | null = null;
  private ema50S:    ISeriesApi<'Line', Time> | null = null;
  private ema200S:   ISeriesApi<'Line', Time> | null = null;
  private timer:     any = null;
  private pendingCall: { call: TradeCall; entryIdx: number } | null = null;
  private scanCooldown = 0; // candles since last signal — prevent overtrading

  ngOnInit()        {}
  ngAfterViewInit() {}
  ngOnDestroy()     { this.stop(); this.chart?.remove(); }

  @HostListener('document:keydown', ['$event'])
  onKey(e: KeyboardEvent) {
    if (!this.loaded) return;
    if (e.code === 'Space')       { e.preventDefault(); this.playing ? this.stop() : this.play(); }
    if (e.code === 'ArrowRight')  { this.stop(); this.step(); }
    if (e.code === 'ArrowLeft')   { this.stop(); this.stepBack(); }
    if (e.code === 'KeyR')        { this.reset(); }
    if (e.code === 'Digit1')      { this.setSpeed(0); }
    if (e.code === 'Digit2')      { this.setSpeed(1); }
    if (e.code === 'Digit3')      { this.setSpeed(2); }
    if (e.code === 'Digit4')      { this.setSpeed(3); }
  }

  // ── Market switching ──────────────────────────────────────
  setMarket(m: Market) {
    this.activeMarket = m;
    this.loaded       = false;
    this.stop();
    if (m === 'STOCK')  { this.timeframe = '1d'; }
    if (m === 'FOREX')  { this.timeframe = '1d'; }
    if (m === 'CRYPTO') { this.timeframe = '4h'; }
  }

  // ── Symbol search helpers ─────────────────────────────────
  onCryptoInput() {
    const q = this.cryptoInput.toUpperCase();
    this.cryptoSymbol  = q;
    this.filteredSugg  = this.CRYPTO_LIST.filter(s => s.startsWith(q) || s.includes(q)).slice(0, 8);
    this.showCryptSug  = !!q;
  }
  selectCrypto(s: string) { this.cryptoInput = s; this.cryptoSymbol = s; this.showCryptSug = false; }

  onStockInput() {
    const q = this.stockInput.toUpperCase();
    this.filteredStock = this.STOCK_LIST.filter(s => s.startsWith(q) || s.includes(q)).slice(0, 8);
    this.showStockSug  = !!q;
  }
  selectStock(s: string)  { this.stockInput = s; this.showStockSug = false; }

  // ── Load data ─────────────────────────────────────────────
  load() {
    this.loading = true;
    this.reset(false);

    if (this.activeMarket === 'CRYPTO') {
      this.liveData.getCryptoKlinesOnce(this.cryptoSymbol + 'USDT', this.timeframe, this.selectedRange.limit)
        .subscribe(c => this.onCandlesLoaded(c));

    } else if (this.activeMarket === 'STOCK') {
      const nse = this.stockInput.trim().toUpperCase() + '.NS';
      const range = this.selectedRange.value; const interval = this.timeframe === '1wk' ? '1wk' : '1d';
      this.liveData.getIndianKlinesOnce(nse, range, interval)
        .subscribe(c => this.onCandlesLoaded(c));

    } else { // FOREX
      this.liveData.getForexKlines(this.forexBase, this.forexQuote)
        .subscribe(c => this.onCandlesLoaded(c));
    }
  }

  private onCandlesLoaded(candles: OHLCV[]) {
    if (!candles?.length) { this.loading = false; return; }
    this.allCandles = candles.filter(c => c.close > 0);
    this.currentIdx = Math.min(80, Math.floor(this.allCandles.length * 0.15));
    this.loading    = false;
    this.loaded     = true;
    this.calcBuyHold();
    setTimeout(() => this.initChart(), 50);
  }

  // ── Chart ─────────────────────────────────────────────────
  initChart() {
    if (!this.chartRef?.nativeElement) return;
    this.chart?.remove();
    const el = this.chartRef.nativeElement;
    this.chart = createChart(el, {
      width:  el.getBoundingClientRect().width || 900,
      height: 400,
      layout: { background:{ type:ColorType.Solid, color:'#0A0E1A' }, textColor:'#8895B3' },
      grid:   { vertLines:{ color:'#1A2340' }, horzLines:{ color:'#1A2340' } },
      rightPriceScale: { borderColor:'#1E2D4A' },
      timeScale: { borderColor:'#1E2D4A', timeVisible:true, secondsVisible:false },
    });
    this.candleSer = this.chart.addSeries(CandlestickSeries, {
      upColor:'#00FF88', downColor:'#FF3B5C',
      borderUpColor:'#00FF88', borderDownColor:'#FF3B5C',
      wickUpColor:'#00FF88', wickDownColor:'#FF3B5C',
    });
    this.ema20S  = this.chart.addSeries(LineSeries, { color:'#00D4FF', lineWidth:1 as any, priceLineVisible:false, lastValueVisible:false });
    this.ema50S  = this.chart.addSeries(LineSeries, { color:'#FF9900', lineWidth:1 as any, priceLineVisible:false, lastValueVisible:false });
    this.ema200S = this.chart.addSeries(LineSeries, { color:'#7B61FF', lineWidth:1 as any, priceLineVisible:false, lastValueVisible:false });
    new ResizeObserver(() => {
      if (this.chart && this.chartRef?.nativeElement)
        try { this.chart.applyOptions({ width: this.chartRef.nativeElement.getBoundingClientRect().width }); } catch {}
    }).observe(el);
    this.renderChart();
    // Scroll so first candle is at left — user watches left-to-right
    setTimeout(() => {
      if (this.chart) {
        this.chart.timeScale().fitContent();
        this.chart.timeScale().scrollToPosition(this.currentIdx + 10, false);
      }
    }, 80);
    this.play();
  }

  renderChart() {
    if (!this.chart || !this.candleSer) return;
    const visible = this.allCandles.slice(0, this.currentIdx);
    this.candleSer.setData(visible.map(c => ({ time: c.time as Time, open:c.open, high:c.high, low:c.low, close:c.close })));
    const ema = (d: number[], p: number) => {
      if (d.length < p) return [];
      const k = 2/(p+1); let v = d.slice(0,p).reduce((a,b)=>a+b,0)/p;
      return d.slice(p).map((val, i) => { v = val*k + v*(1-k); return { time: visible[p+i].time as Time, value: v }; });
    };
    const cls = visible.map(c => c.close);
    this.ema20S?.setData(ema(cls, 20));
    this.ema50S?.setData(ema(cls, 50));
    this.ema200S?.setData(ema(cls, Math.min(200, cls.length - 1)));
    // Scroll so newest candle is visible at right edge (standard chart behaviour)
    this.chart.timeScale().scrollToPosition(-2, false);
  }

  // ── Core analysis — HIGH CONVICTION ONLY ─────────────────
  analyzeCurrentBar() {
    const candles = this.allCandles.slice(0, this.currentIdx);
    if (candles.length < 60) return;

    // Cooldown: don't signal on consecutive candles
    if (this.scanCooldown > 0) { this.scanCooldown--; return; }

    // Check if pending call has resolved
    if (this.pendingCall) {
      const { call, entryIdx } = this.pendingCall;
      const result = this.checkResolved(call, entryIdx, this.currentIdx);
      if (result) {
        const pnlPct = this.calcPnl(call, result);
        this.history.unshift({ idx: entryIdx, call, result, pnlPct });
        if (this.history.length > 30) this.history.pop();
        if (result.startsWith('HIT_T')) { this.wins++; this.totalPnl += pnlPct; }
        else if (result === 'HIT_SL')  { this.losses++; this.totalPnl += pnlPct; }
        this.lastResult   = result;
        this.pendingCall  = null;
        this.scanCooldown = 20; // 20-candle cooldown after a resolved call
      }
      return; // while pending, don't generate new signals
    }

    try {
      const call = this.ta.analyze(candles, this.displaySymbol, this.timeframe, this.activeMarket);

      // ── HIGH CONVICTION FILTER ─────────────────────────────
      // Professional criteria: confluence of 4 independent factors
      const isDirectional = call.direction !== 'WAIT';

      // 1. Confidence threshold (65%+ = quality signal)
      const hasConf = call.confidence >= 65;

      // 2. RSI must confirm direction (not exhausted)
      const rsiOk = call.direction === 'LONG'
        ? call.rsi >= 30 && call.rsi <= 65   // buy zone: not oversold or overbought
        : call.rsi >= 35 && call.rsi <= 72;  // sell zone: not in extreme territories

      // 3. Trend must have structure (ADX shows directional movement)
      const trendOk = call.adx > 18;

      // 4. MACD confirms direction
      const macdOk = call.direction === 'LONG' ? call.macdBull : !call.macdBull;

      // 5. Volume confirms (avoids fakeouts on thin volume)
      const volumeOk = call.volumeRatio > 0.8;

      const isHighConviction = isDirectional && hasConf && rsiOk && trendOk && macdOk && volumeOk;

      if (isHighConviction) {
        this.currentCall  = call;
        this.lastResult   = null;
        this.pendingCall  = { call, entryIdx: this.currentIdx };
        this.scanCooldown = 3;
      } else if (isDirectional && call.confidence >= 55) {
        this.currentCall = call;
      }
    } catch {}
  }

  // Check if candles from entryIdx to currentIdx have resolved the call
  // Targets checked BEFORE SL — if both in same candle, trade was profitable
  private checkResolved(call: TradeCall, entryIdx: number, currentIdx: number): ResultType | null {
    const isLong = call.direction === 'LONG';
    for (let j = entryIdx; j < Math.min(currentIdx, this.allCandles.length); j++) {
      const bar = this.allCandles[j];
      if (isLong) {
        if (bar.high >= call.target3)  return 'HIT_T3';  // best outcome first
        if (bar.high >= call.target2)  return 'HIT_T2';
        if (bar.high >= call.target1)  return 'HIT_T1';
        if (bar.low  <= call.stopLoss) return 'HIT_SL';  // loss only if no target hit
      } else {
        if (bar.low  <= call.target3)  return 'HIT_T3';
        if (bar.low  <= call.target2)  return 'HIT_T2';
        if (bar.low  <= call.target1)  return 'HIT_T1';
        if (bar.high >= call.stopLoss) return 'HIT_SL';
      }
    }
    // Expire after 50 candles with no resolution
    if (currentIdx - entryIdx >= 50) return 'EXPIRED';
    return null;
  }

  private calcPnl(call: TradeCall, result: ResultType): number {
    const isLong = call.direction === 'LONG';
    const entry  = call.entry;
    let exit = entry;
    if (result === 'HIT_T1') exit = call.target1;
    if (result === 'HIT_T2') exit = call.target2;
    if (result === 'HIT_T3') exit = call.target3;
    if (result === 'HIT_SL') exit = call.stopLoss;
    return isLong ? +((exit - entry) / entry * 100).toFixed(2) : +((entry - exit) / entry * 100).toFixed(2);
  }

  // ── Playback ──────────────────────────────────────────────
  step() {
    if (this.currentIdx >= this.allCandles.length - 1) { this.stop(); return; }
    this.currentIdx++;
    this.renderChart();
    this.analyzeCurrentBar();
  }

  play()  { if (this.playing) return; this.playing = true; this.timer = setInterval(() => this.step(), this.speed); }
  stop()  { this.playing = false; clearInterval(this.timer); }

  reset(rerender = true) {
    this.stop();
    this.currentIdx   = Math.min(80, this.allCandles.length > 0 ? Math.floor(this.allCandles.length * 0.15) : 80);
    this.history      = [];
    this.currentCall  = null;
    this.lastResult   = null;
    this.pendingCall  = null;
    this.scanCooldown = 0;
    this.wins = this.losses = this.totalPnl = 0;
    if (rerender) this.renderChart();
  }

  // ── Helpers ───────────────────────────────────────────────
  setSpeed(idx: number) {
    this.activeSpeedIdx = idx;
    this.speed = this.SPEED_PRESETS[idx].ms;
    if (this.playing) { this.stop(); this.play(); }
  }

  stepBack() {
    if (this.currentIdx <= 60) return;
    this.currentIdx--;
    this.renderChart();
  }

  private calcBuyHold() {
    if (!this.allCandles.length) return;
    const first = this.allCandles[Math.floor(this.allCandles.length * 0.15)]?.close || 1;
    const last  = this.allCandles[this.allCandles.length - 1]?.close || 1;
    this.buyHoldReturn = +((last - first) / first * 100).toFixed(1);
  }

  exportCSV() {
    if (!this.history.length) return;
    const rows = ['Bar,Symbol,Direction,Signal,Confidence,Entry,SL,T1,T2,Result,PnL%'];
    this.history.forEach(h => {
      rows.push([
        h.idx, this.displaySymbol, h.call.direction, h.call.signal, h.call.confidence,
        h.call.entry, h.call.stopLoss, h.call.target1, h.call.target2,
        h.result, h.pnlPct
      ].join(','));
    });
    const blob = new Blob([rows.join('\n')], { type:'text/csv' });
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = `replay_${this.displaySymbol}_${this.timeframe}.csv`;
    a.click();
  }

  get displaySymbol(): string {
    if (this.activeMarket === 'FOREX')  return `${this.forexBase}/${this.forexQuote}`;
    if (this.activeMarket === 'STOCK')  return this.stockInput.toUpperCase();
    return this.cryptoSymbol;
  }

  get selectedRange() { return this.DATE_RANGES.find(r => r.value === this.dateRange) || this.DATE_RANGES[2]; }
  get pendingCallActive(): boolean { return !!this.pendingCall; }
  get progress(): number { return this.allCandles.length ? (this.currentIdx / this.allCandles.length) * 100 : 0; }
  get winRate():  number { const t = this.wins + this.losses; return t > 0 ? Math.round(this.wins / t * 100) : 0; }
  get avgPnl():   number { const t = this.wins + this.losses; return t > 0 ? +(this.totalPnl / t).toFixed(2) : 0; }

  onSpeedChange() { if (this.playing) { this.stop(); this.play(); } }

  resultLabel(r: ResultType | string): string {
    if (r === 'HIT_T1') return '✓ T1 Hit';
    if (r === 'HIT_T2') return '✓✓ T2 Hit';
    if (r === 'HIT_T3') return '✓✓✓ T3 Hit';
    if (r === 'HIT_SL') return '✗ Stop Loss';
    return '⏰ Expired';
  }

  resultColor(r: string): string {
    if (r?.startsWith('HIT_T')) return '#00FF88';
    if (r === 'HIT_SL')         return '#FF3B5C';
    return '#4A5568';
  }

  fmtPrice(p: number): string {
    if (this.activeMarket === 'STOCK') {
      return p >= 1000 ? `₹${p.toLocaleString('en-IN', { maximumFractionDigits: 2 })}` : `₹${p.toFixed(2)}`;
    }
    if (this.activeMarket === 'FOREX') return p.toFixed(5);
    return p >= 1000 ? `$${p.toLocaleString('en', { maximumFractionDigits: 2 })}` : `$${p.toFixed(4)}`;
  }
}
