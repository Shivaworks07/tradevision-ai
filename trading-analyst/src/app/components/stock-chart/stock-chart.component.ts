import { Component, Input, OnChanges, SimpleChanges, ViewChild, ElementRef, AfterViewInit, OnDestroy, inject, effect } from '@angular/core';
import { CommonModule } from '@angular/common';
import { createChart, IChartApi, ISeriesApi, CandlestickSeries, LineSeries, ColorType, Time, IPriceLine } from 'lightweight-charts';
import { LiveDataService } from '../../services/live-data.service';
import { TaEngineService } from '../../services/ta-engine.service';
import { CurrencyService } from '../../services/currency.service';

/**
 * Trade levels to draw on the chart as horizontal price lines — entry, stop
 * loss, and targets. Optional: the chart works standalone (used across the
 * app for plain price viewing) and also as the visual home for an intraday
 * call (used from Featured Trades / intraday call panels), in which case
 * this input is supplied and the levels render directly on the candles.
 */
export interface ChartTradeLevels {
  entry?: number;
  stopLoss?: number;
  target1?: number;
  target2?: number;
  target3?: number;
  direction?: 'LONG' | 'SHORT' | 'WAIT';
}

@Component({
  selector: 'app-stock-chart',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="sc-wrap">
      <div class="sc-header">
        <span class="sc-sym">{{symbol}} · {{ isIntraday() ? intervalLabel() + ' Intraday' : rangeLabel() + ' Daily' }} Chart</span>
        <div class="sc-tfs">
          <span class="sc-tf-group-label">Intraday</span>
          <button *ngFor="let t of intradayTfs" [class.active]="activeTf===t.key" (click)="setTimeframe(t.key)">{{t.label}}</button>
          <span class="sc-tf-divider"></span>
          <span class="sc-tf-group-label">Daily</span>
          <button *ngFor="let r of dailyRanges" [class.active]="activeTf===r.key" (click)="setTimeframe(r.key)">{{r.label}}</button>
        </div>
        <button class="sc-close" (click)="close()">✕</button>
      </div>
      <div class="sc-loading" *ngIf="loading"><div class="sc-spin"></div> Loading chart…</div>
      <div class="sc-error" *ngIf="error">{{error}}</div>
      <div #chartEl class="sc-canvas" [style.display]="loading||error?'none':'block'"></div>
      <div class="sc-legend" *ngIf="!loading && !error">
        <span class="le ema9">EMA9</span>
        <span class="le ema20">EMA20</span>
        <span class="le ema50">EMA50</span>
        <span class="le entry" *ngIf="tradeLevels?.entry">— Entry</span>
        <span class="le sl" *ngIf="tradeLevels?.stopLoss">— Stop Loss</span>
        <span class="le tgt" *ngIf="tradeLevels?.target1">— Targets</span>
      </div>
    </div>
  `,
  styles: [`
    .sc-wrap { background:#0A0E1A; border:1px solid #1E2D4A; border-radius:12px; overflow:hidden; margin-top:10px; }
    .sc-header { display:flex; align-items:center; gap:10px; padding:10px 14px; background:#0F1525; border-bottom:1px solid #1E2D4A; flex-wrap:wrap; }
    .sc-sym { font-size:11px; font-weight:700; color:#E8EDF5; flex:1; min-width:160px; }
    .sc-tfs { display:flex; align-items:center; gap:3px; flex-wrap:wrap;
      button { padding:3px 8px; border-radius:5px; border:1px solid #1E2D4A; background:transparent; color:#8895B3; font-family:'Space Grotesk',sans-serif; font-size:10px; cursor:pointer; transition:all 0.15s; &:hover,&.active { background:rgba(255,153,0,0.1); color:#FF9900; border-color:#FF9900; } }
    }
    .sc-tf-group-label { font-size:8px; color:#4A5568; text-transform:uppercase; letter-spacing:0.5px; margin-right:2px; }
    .sc-tf-divider { width:1px; height:14px; background:#1E2D4A; margin:0 4px; }
    .sc-close { background:none; border:none; color:#4A5568; cursor:pointer; font-size:14px; &:hover { color:#FF3B5C; } }
    .sc-loading { display:flex; align-items:center; gap:8px; padding:20px; color:#8895B3; font-size:12px; }
    .sc-spin { width:14px; height:14px; border:2px solid #1E2D4A; border-top-color:#FF9900; border-radius:50%; animation:spin 0.8s linear infinite; }
    .sc-error { padding:14px; color:#FF3B5C; font-size:12px; }
    .sc-canvas { width:100%; }
    .sc-legend { display:flex; gap:10px; padding:6px 14px; background:#0A0E1A; font-size:10px; font-family:'JetBrains Mono',monospace; flex-wrap:wrap; }
    .le { &.ema9 { color:#FFD700; } &.ema20 { color:#00D4FF; } &.ema50 { color:#FF9900; } &.entry { color:#7B61FF; } &.sl { color:#FF3B5C; } &.tgt { color:#00FF88; } }
    @keyframes spin { to { transform:rotate(360deg); } }
  `]
})
export class StockChartComponent implements OnChanges, AfterViewInit, OnDestroy {
  @Input() symbol = '';
  @Input() visible = false;
  @Input() tradeLevels: ChartTradeLevels | null = null;
  @ViewChild('chartEl') chartEl!: ElementRef;

  private liveData = inject(LiveDataService);
  private ta       = inject(TaEngineService);
  currency         = inject(CurrencyService);

  readonly intradayTfs = [
    { key: '5m',  label: '5m',  range: '5d',  interval: '5m'  },
    { key: '15m', label: '15m', range: '5d',  interval: '15m' },
    { key: '1h',  label: '1H',  range: '1mo', interval: '60m' },
  ];
  readonly dailyRanges = [
    { key: '1mo', label: '1M', range: '1mo', interval: '1d' },
    { key: '3mo', label: '3M', range: '3mo', interval: '1d' },
    { key: '6mo', label: '6M', range: '6mo', interval: '1d' },
    { key: '1y',  label: '1Y', range: '1y',  interval: '1d' },
  ];

  activeTf = '15m';
  loading = false;
  error   = '';

  private chart: IChartApi | null = null;
  private candleSer: ISeriesApi<'Candlestick',Time>|null=null;
  private ema9S: ISeriesApi<'Line',Time>|null=null;
  private ema20S:ISeriesApi<'Line',Time>|null=null;
  private ema50S:ISeriesApi<'Line',Time>|null=null;
  private priceLines: IPriceLine[] = [];

  constructor() {
    effect(() => {
      this.currency.currency(); this.currency.usdToInr();
    });
  }

  ngAfterViewInit() { if (this.visible && this.symbol) this.initAndLoad(); }

  ngOnChanges(c: SimpleChanges) {
    if (c['visible']?.currentValue && this.symbol && this.chartEl) {
      setTimeout(() => this.initAndLoad(), 50);
    }
    if (c['tradeLevels'] && !c['tradeLevels'].firstChange && this.candleSer) {
      this.drawTradeLevels();
    }
  }

  ngOnDestroy() { try { this.chart?.remove(); } catch(e) {} this.chart = null; }

  isIntraday(): boolean { return this.intradayTfs.some(t => t.key === this.activeTf); }
  intervalLabel(): string { return this.intradayTfs.find(t => t.key === this.activeTf)?.label ?? ''; }
  rangeLabel(): string { return this.dailyRanges.find(r => r.key === this.activeTf)?.label ?? '3M'; }

  setTimeframe(key: string) { this.activeTf = key; this.loadData(); }
  close() { this.chart?.remove(); this.chart=null; }

  private initAndLoad() {
    if (!this.chartEl?.nativeElement) return;
    this.chart?.remove();
    this.chart = createChart(this.chartEl.nativeElement, {
      width: this.chartEl.nativeElement.offsetWidth, height: 340,
      layout: { background:{type:ColorType.Solid,color:'#0A0E1A'}, textColor:'#8895B3' },
      grid: { vertLines:{color:'#1A2340'}, horzLines:{color:'#1A2340'} },
      rightPriceScale: { borderColor:'#1E2D4A' },
      timeScale: { borderColor:'#1E2D4A', timeVisible:true, secondsVisible:false },
    });
    this.candleSer = this.chart.addSeries(CandlestickSeries, { upColor:'#00FF88', downColor:'#FF3B5C', borderUpColor:'#00FF88', borderDownColor:'#FF3B5C', wickUpColor:'#00FF88', wickDownColor:'#FF3B5C' });
    this.ema9S  = this.chart.addSeries(LineSeries, { color:'#FFD700', lineWidth:1, priceLineVisible:false, lastValueVisible:false });
    this.ema20S = this.chart.addSeries(LineSeries, { color:'#00D4FF', lineWidth:1, priceLineVisible:false, lastValueVisible:false });
    this.ema50S = this.chart.addSeries(LineSeries, { color:'#FF9900', lineWidth:1, priceLineVisible:false, lastValueVisible:false });
    new ResizeObserver(()=>{if(this.chart)try{this.chart.applyOptions({width:this.chartEl.nativeElement.offsetWidth});}catch(e){}}).observe(this.chartEl.nativeElement);
    this.loadData();
  }

  private loadData() {
    if (!this.chart || !this.candleSer) return;
    this.loading = true; this.error = '';

    const tf = this.intradayTfs.find(t => t.key === this.activeTf) ?? this.dailyRanges.find(r => r.key === this.activeTf)!;

    this.liveData.getIndianKlinesRange(this.symbol + '.NS', tf.range, tf.interval).subscribe({
      next: candles => {
        if (!candles.length) {
          this.error = this.isIntraday()
            ? `No ${this.intervalLabel()} intraday data available for ${this.symbol} right now.`
            : 'No chart data available';
          this.loading = false;
          return;
        }
        this.candleSer!.setData(candles.map(c=>({time:c.time as Time,open:c.open,high:c.high,low:c.low,close:c.close})));
        const closes = candles.map(c=>c.close);
        const setE = (s:ISeriesApi<'Line',Time>|null,p:number)=>{
          if(!s||closes.length<p)return; const k=2/(p+1);
          let v=closes.slice(0,p).reduce((a,b)=>a+b,0)/p; const d:any[]=[];
          for(let i=p;i<closes.length;i++){v=closes[i]*k+v*(1-k);d.push({time:candles[i].time as Time,value:v});}
          s.setData(d);
        };
        setE(this.ema9S,9); setE(this.ema20S,20); setE(this.ema50S,50);
        this.chart!.timeScale().fitContent();
        this.drawTradeLevels();
        this.loading = false;
      },
      error: () => { this.error = 'Chart data unavailable'; this.loading = false; }
    });
  }

  private drawTradeLevels() {
    if (!this.candleSer) return;
    for (const line of this.priceLines) { try { this.candleSer.removePriceLine(line); } catch(e) {} }
    this.priceLines = [];
    if (!this.tradeLevels) return;

    const add = (price: number | undefined, color: string, title: string) => {
      if (price == null || price <= 0) return;
      this.priceLines.push(this.candleSer!.createPriceLine({
        price, color, lineWidth: 2, lineStyle: 2, axisLabelVisible: true, title,
      }));
    };

    add(this.tradeLevels.entry,    '#7B61FF', 'Entry');
    add(this.tradeLevels.stopLoss, '#FF3B5C', 'SL');
    add(this.tradeLevels.target1,  '#00FF88', 'T1');
    add(this.tradeLevels.target2,  '#00FF88', 'T2');
    add(this.tradeLevels.target3,  '#00FF88', 'T3');
  }
}
