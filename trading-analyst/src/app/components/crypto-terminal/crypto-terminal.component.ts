import { environment } from '../../../environments/environment';
import { Component, OnInit, OnDestroy, ViewChild, ElementRef, AfterViewInit, ChangeDetectorRef, inject, effect } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { Subscription, debounceTime, Subject, distinctUntilChanged, switchMap, of, catchError, forkJoin, interval } from 'rxjs';
import { createChart, IChartApi, ISeriesApi, CandlestickSeries, LineSeries, HistogramSeries, ColorType, Time, IPriceLine } from 'lightweight-charts';
import { FeaturedTradesComponent } from '../featured-trades/featured-trades.component';
import { LiveDataService } from '../../services/live-data.service';
import { SmcEngineService } from '../../services/smc-engine.service';
import { MarketRegimeService, RegimeState } from '../../services/market-regime.service';
import { RiskEngineService, TradeRisk } from '../../services/risk-engine.service';
import { OrderFlowService, OrderFlowAnalysis } from '../../services/order-flow.service';
import { VolumeProfileService, VolumeProfile } from '../../services/volume-profile.service';
import { TaEngineService, TradeCall, OHLCV } from '../../services/ta-engine.service';
import { AuthService } from '../../services/auth.service';
import { CurrencyService } from '../../services/currency.service';
import { NotificationService } from '../../services/notification.service';
import { AnalysisModalComponent } from '../analysis-modal/analysis-modal.component';
import { CallHistoryComponent } from '../call-history/call-history.component';
import { FearGreedComponent } from '../fear-greed/fear-greed.component';

interface CoinResult { id:string; symbol:string; name:string; thumb?:string; market_cap_rank?:number; }

@Component({
  selector: 'app-crypto-terminal',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, FeaturedTradesComponent, AnalysisModalComponent, CallHistoryComponent, FearGreedComponent],
  templateUrl: './crypto-terminal.component.html',
  styleUrls: ['./crypto-terminal.component.scss']
})
export class CryptoTerminalComponent implements OnInit, OnDestroy, AfterViewInit {
  @ViewChild('chartContainer') chartRef!: ElementRef;
  @ViewChild('rsiContainer')   rsiRef!: ElementRef;
  @ViewChild('macdContainer')  macdRef!: ElementRef;

  private liveData = inject(LiveDataService);
  private ta       = inject(TaEngineService);
  currency         = inject(CurrencyService);
  private notif    = inject(NotificationService);
  private cdr      = inject(ChangeDetectorRef);
  private smc      = inject(SmcEngineService);
  private regime   = inject(MarketRegimeService);
  risk     = inject(RiskEngineService);
  regimeState: RegimeState | null = null;
  tradeRisk:   TradeRisk   | null = null;
  private of       = inject(OrderFlowService);
  private vp       = inject(VolumeProfileService);
  // Phase 1 analysis results
  smcAnalysis: any = null;
  ofAnalysis:  OrderFlowAnalysis | null = null;
  vpAnalysis:  VolumeProfile | null = null;
  loadingPhase1 = false;
  private http     = inject(HttpClient);
  auth             = inject(AuthService);

  // Timeframe descriptions
  readonly TF_INFO: Record<string,{label:string;type:string;desc:string;best:string}> = {
    '3m':  { label:'3 Minute',  type:'Scalping',      desc:'3-minute candles. Less noise than 1m. Good for quick scalp entries.', best:'Scalpers' },
    '6m':  { label:'6 Hour',    type:'Swing Trading', desc:'6-hour candles. Medium-term trend following. Good for multi-day swings.', best:'Swing traders' },
    '1y':  { label:'1 Year',    type:'Position',      desc:'Daily candles for 1 year. Long-term trend and key support/resistance.', best:'Position traders, investors' },
    '3y':  { label:'3 Year',    type:'Position',      desc:'Weekly candles for 3 years. Major bull/bear cycles, macro trends.', best:'Long-term investors' },
    '1m':  { label:'1 Minute',  type:'Scalping',      desc:'Extreme short-term. Noise-heavy, very fast signals. Only for experienced scalpers watching screen continuously.', best:'Scalpers, Market Makers' },
    '5m':  { label:'5 Minute',  type:'Scalping',      desc:'Short-term scalping. Good for quick in/out trades during high volatility. Requires tight stop losses.', best:'Day traders, Scalpers' },
    '15m': { label:'15 Minute', type:'Intraday',      desc:'Intraday trading. Sweet spot for day traders. Filters most noise while giving timely signals within the day.', best:'Day traders' },
    '30m': { label:'30 Minute', type:'Intraday',      desc:'Intraday to short swing. Good for catching intraday trends. Signals are more reliable than 15m.', best:'Day traders, Swing traders' },
    '1h':  { label:'1 Hour',    type:'Swing Trading', desc:'Best for swing trading. Balances signal quality with timeliness. Most popular timeframe for crypto traders.', best:'Swing traders (recommended)' },
    '4h':  { label:'4 Hour',    type:'Swing Trading', desc:'Medium-term swing trades lasting 1-3 days. Very reliable signals. Used by professional traders worldwide.', best:'Position traders, Professionals' },
    '1d':  { label:'Daily',     type:'Position',      desc:'Long-term position trading. Highly reliable signals. Best for identifying major trends. Hold for weeks/months.', best:'Investors, Position traders' },
  };

  readonly COIN_BINANCE_MAP: Record<string,string> = {
    'BTC':'BTCUSDT','ETH':'ETHUSDT','SOL':'SOLUSDT','BNB':'BNBUSDT','XRP':'XRPUSDT',
    'ADA':'ADAUSDT','AVAX':'AVAXUSDT','DOGE':'DOGEUSDT','DOT':'DOTUSDT','LINK':'LINKUSDT',
    'ONDO':'ONDOUSDT','FET':'FETUSDT','NEAR':'NEARUSDT','UNI':'UNIUSDT','INJ':'INJUSDT',
    'SHIB':'SHIBUSDT','LTC':'LTCUSDT','MATIC':'MATICUSDT','TRX':'TRXUSDT','XLM':'XLMUSDT',
    'APT':'APTUSDT','SUI':'SUIUSDT','ARB':'ARBUSDT','OP':'OPUSDT','GRT':'GRTUSDT',
    'FIL':'FILUSDT','ATOM':'ATOMUSDT','ALGO':'ALGOUSDT','VET':'VETUSDT','ICP':'ICPUSDT',
    'HBAR':'HBARUSDT','RNDR':'RNDRUSDT','IMX':'IMXUSDT','WLD':'WLDUSDT','SEI':'SEIUSDT',
    'TIA':'TIAUSDT','STRK':'STRKUSDT','PEPE':'PEPEUSDT','W':'WUSDT','JUP':'JUPUSDT',
    'JTO':'JTOUSDT','PYTH':'PYTHUSDT','THETA':'THETAUSDT','AAVE':'AAVEUSDT','MKR':'MKRUSDT',
    'S':'SONICUSDT','OM':'OMUSDT','AXL':'AXLUSDT','BONK':'BONKUSDT','WIF':'WIFUSDT',
    'POPCAT':'POPCATUSDT','NEIRO':'NEIROUSDT','FLOKI':'FLOKIUSDT','MEW':'MEWUSDT',
  };

  readonly INTERVALS = ['1m','3m','5m','15m','30m','1h','4h','1d','1w','1y','3y'];

  indicators = [
    { id:'ema',   label:'EMA 9/20/50/200', color:'#00D4FF', active:true,  desc:'Trend direction' },
    { id:'bb',    label:'Bollinger Bands', color:'#7B61FF', active:true,  desc:'Volatility bands' },
    { id:'rsi',   label:'RSI (14)',        color:'#FF9900', active:true,  desc:'Momentum oscillator' },
    { id:'macd',  label:'MACD',            color:'#00FF88', active:false, desc:'Trend + momentum' },
    { id:'vwap',  label:'VWAP',            color:'#FF3B5C', active:false, desc:'Institutional ref price' },
  ];

  // Coin search
  coinSearch     = '';
  coinResults: CoinResult[] = [];
  showCoinDrop   = false;
  searching      = false;
  private searchSubj = new Subject<string>();

  selectedSymbol  = 'BTC';
  selectedName    = 'Bitcoin';
  selectedBinance = 'BTCUSDT';
  selectedCoinId  = 'bitcoin';
  selectedInterval = '1h';

  // Timeframe tooltip
  showTfTooltip   = false;
  tfTooltipData:  { label:string;type:string;desc:string;best:string } | null = null;

  candles:    OHLCV[] = [];
  private _tradeCall: TradeCall | null = null;
  private tradeLevelPriceLines: IPriceLine[] = [];
  get tradeCall(): TradeCall | null { return this._tradeCall; }
  set tradeCall(v: TradeCall | null) {
    this._tradeCall = v;
    this.drawTradeLevelsOnChart();
  }

  // Draws entry/SL/target lines from the current call directly on the
  // candle chart. Safe to call before the chart exists (e.g. tradeCall set
  // during initial load) — silently no-ops until candleSer is ready, and
  // ngAfterViewInit-adjacent chart init will trigger a redraw via the next
  // selectedInterval/symbol change that re-runs analysis anyway. Always
  // clears prior lines first so switching symbols never leaves stale levels
  // from a different coin overlaid on the new chart.
  private drawTradeLevelsOnChart() {
    if (!this.candleSer) return;
    for (const line of this.tradeLevelPriceLines) { try { this.candleSer.removePriceLine(line); } catch(e) {} }
    this.tradeLevelPriceLines = [];
    const call = this._tradeCall;
    if (!call || call.direction === 'WAIT') return;

    const add = (price: number | undefined, color: string, title: string) => {
      if (price == null || price <= 0) return;
      this.tradeLevelPriceLines.push(this.candleSer!.createPriceLine({
        price, color, lineWidth: 2, lineStyle: 2, axisLabelVisible: true, title,
      }));
    };
    add(call.entry,    '#7B61FF', 'Entry');
    add(call.stopLoss, '#FF3B5C', 'SL');
    add(call.target1,  '#00FF88', 'T1');
    add(call.target2,  '#00FF88', 'T2');
    add(call.target3,  '#00FF88', 'T3');
  }
  loading     = true;
  error       = '';
  lastUpdated = '';
  modalVisible = false;
  historyRefresh = 0;
  mtfEnabled    = true;
  isFullscreen  = false;
  countdown     = 0;       // seconds until next refresh
  countdownMax  = 60;
  private countdownSub?:   Subscription;
  private userHasZoomed    = false;   // don't reset view if user zoomed/panned
  private isLoadingHistory = false;   // prevent multiple simultaneous history loads
  private liveSub?: Subscription;

  private chart:      IChartApi | null = null;
  private candleSer:  ISeriesApi<'Candlestick',Time> | null = null;
  private ema9S:      ISeriesApi<'Line',Time>|null=null;
  private ema20S:     ISeriesApi<'Line',Time>|null=null;
  private ema50S:     ISeriesApi<'Line',Time>|null=null;
  private ema200S:    ISeriesApi<'Line',Time>|null=null;
  private bbUpperS:   ISeriesApi<'Line',Time>|null=null;
  private bbMidS:     ISeriesApi<'Line',Time>|null=null;
  private bbLowerS:   ISeriesApi<'Line',Time>|null=null;
  private vwapS:      ISeriesApi<'Line',Time>|null=null;
  private rsiChart:   IChartApi | null = null;
  private rsiSer:     ISeriesApi<'Line',Time>|null=null;
  private rsiUpper:   ISeriesApi<'Line',Time>|null=null;
  private rsiLower:   ISeriesApi<'Line',Time>|null=null;
  private macdChart:  IChartApi | null = null;
  private macdLineSer:ISeriesApi<'Line',Time>|null=null;
  private macdSigSer: ISeriesApi<'Line',Time>|null=null;
  private macdHistSer:ISeriesApi<'Histogram',Time>|null=null;

  private subs:     Subscription[] = [];
  private resizeObs: ResizeObserver[] = [];
  private destroyed = false;
  private klineSub?: Subscription;

  get showRSI()  { return this.indicators.find(i=>i.id==='rsi')?.active; }
  get showMACD() { return this.indicators.find(i=>i.id==='macd')?.active; }
  get showEMA()  { return this.indicators.find(i=>i.id==='ema')?.active; }
  get showBB()   { return this.indicators.find(i=>i.id==='bb')?.active; }
  get showVWAP() { return this.indicators.find(i=>i.id==='vwap')?.active; }
  get currentTickerUSD() { return this.candles[this.candles.length-1]?.close||0; }

  constructor() {
    effect(() => {
      const cur = this.currency.currency(); const rate = this.currency.usdToInr();
      if (!this.destroyed && this.candles.length>0 && this.chart) {
        try { this.rebuildChart(); } catch(e) { console.warn('Chart rebuild',e); }
      }
    });
  }

  ngOnInit() {
    // Live CoinGecko search
    this.subs.push(
      this.searchSubj.pipe(
        debounceTime(400),
        distinctUntilChanged(),
        switchMap(q => {
          if (!q || q.length < 2) {
            this.coinResults = this.liveData.ALL_CRYPTO.slice(0,20).map(c=>({id:c.id,symbol:c.symbol,name:c.name}));
            return of(null);
          }
          this.searching = true;
          return this.http.get<any>(`${environment.coingeckoUrl}/api/v3/search?query=${encodeURIComponent(q)}`).pipe(
            catchError(() => of(null))
          );
        })
      ).subscribe((r: any) => {
        this.searching = false;
        if (r?.coins) {
          this.coinResults = r.coins.slice(0,15).map((c:any) => ({
            id: c.id, symbol: c.symbol?.toUpperCase(), name: c.name, thumb: c.thumb, market_cap_rank: c.market_cap_rank
          }));
        }
      })
    );
    this.coinResults = this.liveData.ALL_CRYPTO.slice(0,20).map(c=>({id:c.id,symbol:c.symbol,name:c.name}));
  }

  ngAfterViewInit() {
    // Small delay to let the route container fully render before measuring width
    setTimeout(() => {
      this.initCharts();
      this.loadCandles();
    }, 50);
  }
  loadMoreHistory() {
    if (this.isLoadingHistory || !this.candles.length) return;
    this.isLoadingHistory = true;
    const oldest = this.candles[0];
    const extraLimit = 500;
    const apiTf = this.liveData.TF_MAP[this.selectedInterval] || this.selectedInterval;
    // Fetch older candles ending at oldest candle time
    const endTime = oldest.time * 1000;
    this.http.get<any[]>(`${environment.binanceUrl}/api/v3/klines?symbol=${this.selectedBinance}&interval=${apiTf}&limit=${extraLimit}&endTime=${endTime}`)
      .pipe(catchError(() => of([])))
      .subscribe(raw => {
        if (!raw?.length) { this.isLoadingHistory = false; return; }
        const older = raw.map((k:any) => ({
          time: Math.floor(k[0]/1000), open: parseFloat(k[1]),
          high: parseFloat(k[2]), low: parseFloat(k[3]),
          close: parseFloat(k[4]), volume: parseFloat(k[5]),
        })).filter((c:any) => c.time < oldest.time);
        if (older.length) {
          this.candles = [...older, ...this.candles];
          this.rebuildChart();
          this.updateRSIChart();
          this.updateMACDChart();
        }
        this.isLoadingHistory = false;
      });
  }

  startLiveStream() {
    this.liveSub?.unsubscribe();
    const refreshMs = this.liveData.tfRefresh(this.selectedInterval);
    this.liveSub = interval(refreshMs).pipe(
      switchMap(() => this.liveData.getCryptoKlinesOnce(this.selectedBinance, this.selectedInterval, 500))
    ).subscribe({
      next: candles => {
        if (this.destroyed || !candles.length) return;
        this.candles = candles;
        this.rebuildChart();
        this.updateRSIChart();
        this.updateMACDChart();
        // Rerun analysis with fresh candles
        const regimeForTA = this.regimeState ? { regime:this.regimeState.regime, label:this.regimeState.label, emoji:this.regimeState.emoji, color:this.regimeState.color, confidence:this.regimeState.confidence, riskMultiplier:this.regimeState.strategy.riskMultiplier, slMultiplier:this.regimeState.strategy.slMultiplier, weightAdjustments:this.regimeState.weightAdjustments, strategy:this.regimeState.strategy, warnings:this.regimeState.warnings } : undefined;
        const smcForTA  = this.smcAnalysis  ? { bias:this.smcAnalysis.bias, biasStrength:this.smcAnalysis.biasStrength, entrySetup:this.smcAnalysis.entrySetup, structureBreaks:this.smcAnalysis.structureBreaks, orderBlocks:this.smcAnalysis.orderBlocks } : undefined;
        const vpForTA   = this.vpAnalysis   ? { priceLocation:this.vpAnalysis.priceLocation, poc:this.vpAnalysis.poc, vah:this.vpAnalysis.vah, val:this.vpAnalysis.val, bias:this.vpAnalysis.bias, nearestHVN:this.vpAnalysis.nearestHVN } : undefined;
        this.tradeCall  = this.ta.analyzeWithMTF(candles, this.selectedSymbol, this.selectedInterval, undefined, undefined, 'CRYPTO', smcForTA, undefined, vpForTA, regimeForTA);
        this.lastUpdated = new Date().toLocaleTimeString('en-IN',{hour12:false,timeZone:'Asia/Kolkata'}) + ' IST';
        this.cdr.markForCheck();
      }
    });
  }

  toggleFullscreen() {
    const el = document.querySelector('.ct-wrap') as HTMLElement;
    if (!this.isFullscreen) {
      el?.requestFullscreen?.().catch(()=>{});
      this.isFullscreen = true;
    } else {
      document.exitFullscreen?.().catch(()=>{});
      this.isFullscreen = false;
    }
  }

  isFav()   { return this.auth.isFavorite(this.selectedSymbol, 'CRYPTO'); }
  toggleFav()    { const f = this.isFav(); this.auth.toggleFavorite(this.selectedSymbol, 'CRYPTO', !f).subscribe(); }

  ngOnDestroy() {
    this.destroyed = true;
    // Disconnect ResizeObservers FIRST to prevent callbacks on disposed charts
    this.resizeObs.forEach(ro => ro.disconnect());
    this.resizeObs = [];
    // Unsubscribe
    this.subs.forEach(s => s.unsubscribe());
    this.klineSub?.unsubscribe();
    this.liveSub?.unsubscribe();
    this.countdownSub?.unsubscribe();
    // Null references before removing (prevents any lingering callbacks)
    const c = this.chart; const r = this.rsiChart; const m = this.macdChart;
    this.chart = null; this.rsiChart = null; this.macdChart = null;
    try { c?.remove(); } catch(e) {}
    try { r?.remove(); } catch(e) {}
    try { m?.remove(); } catch(e) {}
  }

  onCoinSearch() { this.showCoinDrop=true; this.searchSubj.next(this.coinSearch); }

  selectCoin(coin: CoinResult) {
    this.selectedSymbol  = coin.symbol;
    this.selectedName    = coin.name;
    this.selectedCoinId  = coin.id;
    this.selectedBinance = this.COIN_BINANCE_MAP[coin.symbol] || (coin.symbol+'USDT');
    this.coinSearch      = coin.name;
    this.showCoinDrop    = false;
    this.loadCandles();
  }

  // Timeframe tooltip
  showTfInfo(tf: string) { this.tfTooltipData = this.TF_INFO[tf]; this.showTfTooltip = true; }
  hideTfInfo() { setTimeout(() => this.showTfTooltip = false, 200); }
  selectTf(tf: string) { this.selectedInterval = tf; this.showTfTooltip = false; this.loadCandles(); }

  toggleIndicator(ind: any) {
    ind.active = !ind.active;
    if (this.candles.length>0) {
      this.rebuildChart();
      if (ind.id==='rsi')  setTimeout(()=>this.initRSIChart(),50);
      if (ind.id==='macd') setTimeout(()=>this.initMACDChart(),50);
    }
  }

  initCharts() { this.initMainChart(); this.initRSIChart(); this.initMACDChart(); }

  initMainChart() {
    if (!this.chartRef?.nativeElement) return;
    if (this.chart) { try { this.chart.remove(); } catch(e) {} this.chart = null; }
    // Force layout reflow before measuring
    const containerWidth = this.chartRef.nativeElement.getBoundingClientRect().width || 
                           this.chartRef.nativeElement.offsetWidth || 
                           window.innerWidth - 40;
    this.chart = createChart(this.chartRef.nativeElement, {
      width: containerWidth, height: 420,
      layout: { background:{type:ColorType.Solid,color:'#0F1525'}, textColor:'#8895B3' },
      grid: { vertLines:{color:'#1A2340'}, horzLines:{color:'#1A2340'} },
      crosshair: { vertLine:{color:'#2A3F6A'}, horzLine:{color:'#2A3F6A'} },
      rightPriceScale: { borderColor:'#1E2D4A' },
      timeScale: {
        borderColor: '#1E2D4A', timeVisible: true, secondsVisible: false,
        rightOffset: 5,
        barSpacing: 6,
        fixLeftEdge: true,
        fixRightEdge: false,
        lockVisibleTimeRangeOnResize: true,
      },
      localization: {
        // Convert UTC timestamps to IST (UTC+5:30)
        timeFormatter: (timestamp: number) => {
          const date = new Date((timestamp + 19800) * 1000); // +5:30 = +19800s
          const h = String(Math.floor((date.getUTCHours()) % 24)).padStart(2,'0');
          const m = String(date.getUTCMinutes()).padStart(2,'0');
          const d = date.getUTCDate();
          const mo = ['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'][date.getUTCMonth()];
          return `${d} ${mo} ${h}:${m}`;
        }
      },
    });
    this.candleSer  = this.chart.addSeries(CandlestickSeries, { upColor:'#00FF88', downColor:'#FF3B5C', borderUpColor:'#00FF88', borderDownColor:'#FF3B5C', wickUpColor:'#00FF88', wickDownColor:'#FF3B5C' });
    this.ema9S   = this.chart.addSeries(LineSeries, { color:'#FFD700', lineWidth:1, title:'EMA9',   priceLineVisible:false, lastValueVisible:true });
    this.ema20S  = this.chart.addSeries(LineSeries, { color:'#00D4FF', lineWidth:1, title:'EMA20',  priceLineVisible:false, lastValueVisible:true });
    this.ema50S  = this.chart.addSeries(LineSeries, { color:'#FF9900', lineWidth:1, title:'EMA50',  priceLineVisible:false, lastValueVisible:true });
    this.ema200S = this.chart.addSeries(LineSeries, { color:'#7B61FF', lineWidth:2, title:'EMA200', priceLineVisible:false, lastValueVisible:true });
    this.bbUpperS= this.chart.addSeries(LineSeries, { color:'rgba(123,97,255,0.5)', lineWidth:1, lineStyle:2, priceLineVisible:false, lastValueVisible:false });
    this.bbMidS  = this.chart.addSeries(LineSeries, { color:'rgba(123,97,255,0.3)', lineWidth:1, lineStyle:2, priceLineVisible:false, lastValueVisible:false });
    this.bbLowerS= this.chart.addSeries(LineSeries, { color:'rgba(123,97,255,0.5)', lineWidth:1, lineStyle:2, priceLineVisible:false, lastValueVisible:false });
    this.vwapS   = this.chart.addSeries(LineSeries, { color:'#FF3B5C', lineWidth:1, title:'VWAP', lineStyle:1, priceLineVisible:false, lastValueVisible:true });
    const ro1=new ResizeObserver(()=>{if(this.chart)try{this.chart.applyOptions({width:this.chartRef.nativeElement.offsetWidth});}catch(e){}});ro1.observe(this.chartRef.nativeElement);this.resizeObs.push(ro1);

    // Track user zoom/pan — don't auto-scroll when user is exploring
    this.chart.timeScale().subscribeVisibleLogicalRangeChange((range) => {
      if (!range) return;
      this.userHasZoomed = true;
      // Load more history when user scrolls to left edge
      if (range.from <= 5 && !this.isLoadingHistory && this.candles.length >= 100) {
        this.loadMoreHistory();
      }
    });
  }

  initRSIChart() {
    if (!this.rsiRef?.nativeElement || !this.showRSI) return;
    this.rsiChart?.remove();
    this.rsiChart = createChart(this.rsiRef.nativeElement, {
      width:this.rsiRef.nativeElement.getBoundingClientRect().width || this.rsiRef.nativeElement.offsetWidth || 800, height:100,
      layout:{background:{type:ColorType.Solid,color:'#0A0E1A'},textColor:'#8895B3'},
      grid:{vertLines:{color:'#1A2340'},horzLines:{color:'#1A2340'}},
      rightPriceScale:{borderColor:'#1E2D4A',scaleMargins:{top:0.1,bottom:0.1}},
      timeScale:{borderColor:'#1E2D4A',timeVisible:false},
      crosshair:{vertLine:{color:'#2A3F6A'},horzLine:{color:'#2A3F6A'}},
    });
    this.rsiSer    = this.rsiChart.addSeries(LineSeries,{color:'#FF9900',lineWidth:2,priceLineVisible:false,lastValueVisible:true,title:'RSI'});
    this.rsiUpper  = this.rsiChart.addSeries(LineSeries,{color:'rgba(255,59,92,0.4)',lineWidth:1,lineStyle:2,priceLineVisible:false,lastValueVisible:false});
    this.rsiLower  = this.rsiChart.addSeries(LineSeries,{color:'rgba(0,255,136,0.4)',lineWidth:1,lineStyle:2,priceLineVisible:false,lastValueVisible:false});
    const ro2=new ResizeObserver(()=>{if(this.rsiChart)try{this.rsiChart.applyOptions({width:this.rsiRef.nativeElement.offsetWidth});}catch(e){}});ro2.observe(this.rsiRef.nativeElement);this.resizeObs.push(ro2);
    if (this.candles.length) this.updateRSIChart();
  }

  initMACDChart() {
    if (!this.macdRef?.nativeElement || !this.showMACD) return;
    this.macdChart?.remove();
    this.macdChart = createChart(this.macdRef.nativeElement, {
      width:this.macdRef.nativeElement.getBoundingClientRect().width || this.macdRef.nativeElement.offsetWidth || 800, height:100,
      layout:{background:{type:ColorType.Solid,color:'#0A0E1A'},textColor:'#8895B3'},
      grid:{vertLines:{color:'#1A2340'},horzLines:{color:'#1A2340'}},
      rightPriceScale:{borderColor:'#1E2D4A',scaleMargins:{top:0.1,bottom:0.1}},
      timeScale:{borderColor:'#1E2D4A',timeVisible:false},
      crosshair:{vertLine:{color:'#2A3F6A'},horzLine:{color:'#2A3F6A'}},
    });
    this.macdLineSer = this.macdChart.addSeries(LineSeries,{color:'#00D4FF',lineWidth:1,title:'MACD',priceLineVisible:false,lastValueVisible:true});
    this.macdSigSer  = this.macdChart.addSeries(LineSeries,{color:'#FF9900',lineWidth:1,title:'Signal',priceLineVisible:false,lastValueVisible:true});
    this.macdHistSer = this.macdChart.addSeries(HistogramSeries,{color:'#00FF88',priceLineVisible:false,lastValueVisible:false});
    const ro3=new ResizeObserver(()=>{if(this.macdChart)try{this.macdChart.applyOptions({width:this.macdRef.nativeElement.offsetWidth});}catch(e){}});ro3.observe(this.macdRef.nativeElement);this.resizeObs.push(ro3);
    if (this.candles.length) this.updateMACDChart();
  }

  resetZoom() {
    this.userHasZoomed = false;
    this.chart?.timeScale().fitContent();
  }

  /**
   * P3-4 fix ("trading-analyst risk-engine.service.ts -- client 'risk engine' (localStorage,
   * $10k default) unrelated to server limits" -- external review): confirmed real by direct
   * inspection -- risk.updateParams() had ZERO callers anywhere in this app before this fix, so
   * the "Account: $10,000" this panel has always shown was a permanently-stuck, unchangeable
   * fiction nobody could correct, not a real reflection of anything. This component also has no
   * concept of "the active broker credential" at all (it's a manual analysis/signal terminal, not
   * scoped to one auto-trading credential the way the Settings → Broker & Auto-Trade screens
   * are), so genuinely binding this panel's numbers to a specific credential's server-side
   * RiskProfile isn't a natural fit here architecturally -- there's no single "the" profile to
   * bind to. The honest fix that IS in scope: let the user actually set the real capital this
   * calculator should size against (closing the "silently wrong forever" half of the finding),
   * and stop this panel implying it's anything more than a manual what-if calculator (see this
   * component's own updated risk-panel header/tooltip in the template) -- it never has been, and
   * never silently drove any actual order execution, which only ever happens server-side via
   * AutoTradeService/RiskEngineService.java's own real, enforced limits, completely independent
   * of this file.
   */
  onAccountSizeChange(value: number) {
    if (!Number.isFinite(value) || value <= 0) return;
    this.risk.updateParams({ accountSize: value });
    this.recomputeTradeRisk();
    this.cdr.markForCheck();
  }

  private recomputeTradeRisk() {
    if (this.tradeCall && this.tradeCall.direction !== 'WAIT') {
      const ml = this.ta.getMLMemory(this.selectedSymbol, 'CRYPTO');
      this.tradeRisk = this.risk.calculateRisk(
        this.tradeCall.entry, this.tradeCall.stopLoss,
        this.tradeCall.target1, this.tradeCall.target2, this.tradeCall.target3,
        this.tradeCall.atr, this.tradeCall.confidence,
        ml?.winRate || 50,
        this.regimeState?.strategy.riskMultiplier || 1.0
      );
    } else { this.tradeRisk = null; }
  }

  loadCandles() {
    this.userHasZoomed = false;  // reset zoom on new symbol/timeframe
    this.klineSub?.unsubscribe();
    this.liveSub?.unsubscribe();
    this.countdownSub?.unsubscribe();
    this.loading=true; this.error='';

    const higherTF1 = this.ta.nextHigherTF(this.selectedInterval);
    const higherTF2 = this.ta.nextHigherTF(higherTF1);
    const refreshMs = this.liveData.tfRefresh(this.selectedInterval);

    // Start countdown timer
    this.countdownMax = Math.floor(refreshMs / 1000);
    this.countdown    = this.countdownMax;
    this.countdownSub = interval(1000).subscribe(() => {
      this.countdown = Math.max(0, this.countdown - 1);
      if (this.countdown === 0) this.countdown = this.countdownMax;
    });

    const base$ = this.liveData.getCryptoKlinesOnce(this.selectedBinance, this.selectedInterval, 500);
    const htf1$ = this.liveData.getCryptoKlinesOnce(this.selectedBinance, higherTF1, 150);
    const htf2$ = this.liveData.getCryptoKlinesOnce(this.selectedBinance, higherTF2, 100);

    this.klineSub = forkJoin({ base: base$, htf1: htf1$, htf2: htf2$ }).subscribe({
      next: ({ base, htf1, htf2 }) => {
        this.candles = base;
        this.rebuildChart();
        this.updateRSIChart();
        this.updateMACDChart();
        // MTF analysis
        // Run SMC + Volume Profile + Regime on candles (synchronous)
        this.regimeState = this.regime.detect(base, this.selectedSymbol);
        this.smcAnalysis = this.smc.analyze(base, this.selectedSymbol);
        this.vpAnalysis  = this.vp.analyze(base);

        // Quick SMC/VP data for TA engine
        const regimeForTA = this.regimeState ? {
          regime: this.regimeState.regime, label: this.regimeState.label,
          emoji: this.regimeState.emoji, color: this.regimeState.color,
          confidence: this.regimeState.confidence,
          riskMultiplier: this.regimeState.strategy.riskMultiplier,
          slMultiplier: this.regimeState.strategy.slMultiplier,
          weightAdjustments: this.regimeState.weightAdjustments,
          strategy: this.regimeState.strategy,
          warnings: this.regimeState.warnings
        } : undefined;

        const smcForTA = this.smcAnalysis ? { bias: this.smcAnalysis.bias, biasStrength: this.smcAnalysis.biasStrength, entrySetup: this.smcAnalysis.entrySetup, structureBreaks: this.smcAnalysis.structureBreaks, orderBlocks: this.smcAnalysis.orderBlocks } : undefined;
        const vpForTA  = this.vpAnalysis ? { priceLocation: this.vpAnalysis.priceLocation, poc: this.vpAnalysis.poc, vah: this.vpAnalysis.vah, val: this.vpAnalysis.val, bias: this.vpAnalysis.bias, nearestHVN: this.vpAnalysis.nearestHVN } : undefined;

        this.tradeCall = this.ta.analyzeWithMTF(
          base, this.selectedSymbol, this.selectedInterval,
          htf1.length >= 30 ? htf1 : undefined,
          htf2.length >= 30 ? htf2 : undefined,
          'CRYPTO', smcForTA, undefined, vpForTA, regimeForTA
        );

        // Risk Engine
        this.recomputeTradeRisk();
        this.lastUpdated=new Date().toLocaleTimeString('en-IN',{hour12:false,timeZone:'Asia/Kolkata'})+' IST';
        this.loading=false; this.cdr.markForCheck();
        setTimeout(() => {
          if (this.chart && this.chartRef?.nativeElement) {
            const w = this.chartRef.nativeElement.getBoundingClientRect().width;
            if (w > 100) this.chart.applyOptions({ width: w });
          }
        }, 100);
        // Start live streaming for current candle updates
        this.startLiveStream();
        // Trigger resize to ensure chart fills container after content renders
        setTimeout(() => {
          if (this.chart && this.chartRef?.nativeElement) {
            const w = this.chartRef.nativeElement.getBoundingClientRect().width;
            if (w > 100) this.chart.applyOptions({ width: w });
          }
        }, 100);

        // Fetch Order Flow async (doesn't block chart)
        this.loadingPhase1 = true;
        this.of.analyze(this.selectedSymbol).subscribe(ofResult => {
          this.ofAnalysis = ofResult;
          this.loadingPhase1 = false;
          // Re-run analysis with OF data if significant
          if (ofResult.isLoaded && Math.abs(ofResult.score) >= 15) {
            const ofForTA = { overallBias: ofResult.overallBias, score: ofResult.score, reasons: ofResult.reasons };
            this.tradeCall = this.ta.analyzeWithMTF(base, this.selectedSymbol, this.selectedInterval, htf1.length>=30?htf1:undefined, htf2.length>=30?htf2:undefined, 'CRYPTO', smcForTA, ofForTA, vpForTA, regimeForTA);
          }
          this.cdr.markForCheck();
        });
      },
      error: ()=>{ this.error='Failed to fetch from Binance'; this.loading=false; this.cdr.markForCheck(); }
    });
  }

  private rebuildChart() {
    if (!this.chart||!this.candleSer||!this.candles.length) return;
    const rate=this.currency.currency()==='INR'?this.currency.usdToInr():1;
    const closes=this.candles.map(c=>c.close);
    this.candleSer.setData(this.candles.map(c=>({time:c.time as Time,open:c.open*rate,high:c.high*rate,low:c.low*rate,close:c.close*rate})));
    const setEMA=(s:ISeriesApi<'Line',Time>|null,p:number,vis:boolean)=>{
      if(!s||closes.length<p)return; const k=2/(p+1);
      let v=closes.slice(0,p).reduce((a,b)=>a+b,0)/p; const d:any[]=[];
      for(let i=p;i<closes.length;i++){v=closes[i]*k+v*(1-k);d.push({time:this.candles[i].time as Time,value:v*rate});}
      s.setData(d); s.applyOptions({visible:vis});
    };
    const emaOn=!!this.showEMA;
    setEMA(this.ema9S,9,emaOn);setEMA(this.ema20S,20,emaOn);setEMA(this.ema50S,50,emaOn);setEMA(this.ema200S,200,emaOn);
    const bbOn=!!this.showBB;
    if(this.bbUpperS&&this.bbLowerS&&this.bbMidS){
      const bb=this.ta.bollingerArray(closes); const up:any[]=[],mi:any[]=[],lo:any[]=[];
      bb.upper.forEach((v,i)=>{if(v>0){up.push({time:this.candles[i].time as Time,value:v*rate});mi.push({time:this.candles[i].time as Time,value:bb.mid[i]*rate});lo.push({time:this.candles[i].time as Time,value:bb.lower[i]*rate});}});
      this.bbUpperS.setData(up);this.bbMidS.setData(mi);this.bbLowerS.setData(lo);
      [this.bbUpperS,this.bbMidS,this.bbLowerS].forEach(s=>s?.applyOptions({visible:bbOn}));
    }
    if(this.vwapS){
      let cv=0,v=0; const vd:any[]=this.candles.map(c=>{const tp=(c.high+c.low+c.close)/3;cv+=tp*c.volume;v+=c.volume;return{time:c.time as Time,value:(v>0?cv/v:c.close)*rate};});
      this.vwapS.setData(vd);this.vwapS.applyOptions({visible:!!this.showVWAP});
    }
    this.updatePriceFormatter(rate);
    // Only auto-fit on initial load, not on live refresh if user has zoomed
    if (!this.userHasZoomed) {
      this.chart.timeScale().fitContent();
    }
  }

  private updateRSIChart() {
    if(!this.rsiChart||!this.rsiSer||!this.candles.length||!this.showRSI)return;
    const closes=this.candles.map(c=>c.close);
    const period=14; const rd:any[]=[],ud:any[]=[],ld:any[]=[];
    for(let i=period;i<closes.length;i++){
      let ag=0,al=0;
      for(let j=i-period+1;j<=i;j++){const d=closes[j]-closes[j-1];if(d>0)ag+=d;else al-=d;}
      const rs=(ag/period)/((al/period)||0.0001);
      const rsi=100-100/(1+rs); const t=this.candles[i].time as Time;
      rd.push({time:t,value:rsi});ud.push({time:t,value:70});ld.push({time:t,value:30});
    }
    this.rsiSer.setData(rd);this.rsiUpper?.setData(ud);this.rsiLower?.setData(ld);
    if (!this.userHasZoomed) this.rsiChart.timeScale().fitContent();
  }

  private updateMACDChart() {
    if(!this.macdChart||!this.macdLineSer||!this.candles.length||!this.showMACD)return;
    const closes=this.candles.map(c=>c.close);
    const emaA=(d:number[],p:number)=>{const k=2/(p+1);const out:number[]=[];let v=d.slice(0,p).reduce((a,b)=>a+b,0)/p;out.push(...new Array(p).fill(null));for(let i=p;i<d.length;i++){v=d[i]*k+v*(1-k);out.push(v);}return out;};
    const e12=emaA(closes,12);const e26=emaA(closes,26);
    const ml=closes.map((_,i)=>(e12[i]!=null&&e26[i]!=null)?e12[i]-e26[i]:null);
    const vml=ml.filter(v=>v!=null) as number[];
    const sig=emaA(vml,9); let si=0;
    const md:any[]=[],sd:any[]=[],hd:any[]=[];
    ml.forEach((v,i)=>{if(v==null)return;const sg=sig[si]||0;si++;const t=this.candles[i].time as Time;md.push({time:t,value:v});sd.push({time:t,value:sg});hd.push({time:t,value:v-sg,color:(v-sg)>=0?'rgba(0,255,136,0.6)':'rgba(255,59,92,0.6)'});});
    this.macdLineSer.setData(md);this.macdSigSer?.setData(sd);this.macdHistSer?.setData(hd);
    if (!this.userHasZoomed) this.macdChart.timeScale().fitContent();
  }

  private updatePriceFormatter(rate:number) {
    this.chart?.applyOptions({localization:{priceFormatter:(p:number)=>{
      if(this.currency.currency()==='INR'){
        if(p>=10000000)return`₹${(p/10000000).toFixed(2)}Cr`;
        if(p>=100000)return`₹${(p/100000).toFixed(2)}L`;
        if(p>=1000)return`₹${p.toLocaleString('en-IN',{maximumFractionDigits:2})}`;
        if(p>=1)return`₹${p.toFixed(4)}`;
        return`₹${p.toFixed(6)}`;
      }
      if(p>=1000)return`$${p.toLocaleString('en-US',{maximumFractionDigits:2})}`;
      if(p>=1)return`$${p.toFixed(4)}`;
      return`$${p.toFixed(6)}`;
    }}});
  }

  formatPrice(usdPrice:number):string{
    const rate=this.currency.currency()==='INR'?this.currency.usdToInr():1;
    const sym=this.currency.currency()==='INR'?'₹':'$';
    const p=usdPrice*rate;
    if(p>=10000000)return`${sym}${(p/10000000).toFixed(2)}Cr`;
    if(p>=100000)return`${sym}${(p/100000).toFixed(2)}L`;
    if(p>=1000)return`${sym}${p.toLocaleString('en',{maximumFractionDigits:2})}`;
    if(p>=1)return`${sym}${p.toFixed(4)}`;
    return`${sym}${p.toFixed(6)}`;
  }

  getPriceFormatter(){return(v:number)=>this.formatPrice(v);}

  // Template helpers (Angular doesn't support regex in templates)
  formatSignal(s: string|undefined|null): string { return s ? s.replace(/_/g, ' ') : ''; }
  range(n: number): number[] { return Array.from({length: Math.min(n||0, 5)}, (_,i) => i); }
}
