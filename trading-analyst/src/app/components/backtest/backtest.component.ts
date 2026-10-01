import { Component, inject, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { BacktestService, BacktestConfig, BacktestResult, BacktestTrade } from '../../services/backtest.service';

@Component({
  selector: 'app-backtest',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './backtest.component.html',
  styleUrls: ['./backtest.component.scss']
})
export class BacktestComponent implements OnDestroy {
  bt = inject(BacktestService);

  config: BacktestConfig = {
    symbol:        'BTC',
    market:        'CRYPTO',
    timeframe:     '4h',
    startYear:     2021,
    endYear:       2024,
    riskPerTrade:  1.0,
    minConfidence: 65,
    useSMC:        true,
    useVP:         true,
    useRegime:     true,
    regimeFilter:  [],
  };

  // Presets for quick testing
  readonly PRESETS = [
    { label:'BTC 4h Bull',   symbol:'BTC',      tf:'4h', conf:65, regimes:['STRONG_BULL_TREND','BULL_TREND','POST_BREAKOUT_BULL'] },
    { label:'BTC 1h All',    symbol:'BTC',      tf:'1h', conf:70, regimes:[] },
    { label:'ETH 4h',        symbol:'ETH',      tf:'4h', conf:65, regimes:[] },
    { label:'SOL 1h',        symbol:'SOL',      tf:'1h', conf:68, regimes:[] },
    { label:'RELIANCE Daily',symbol:'RELIANCE', tf:'1d', conf:62, regimes:[] },
  ];

  applyPreset(p: any) {
    this.symbolInput          = p.symbol;
    this.config.symbol        = p.symbol;
    this.config.timeframe     = p.tf;
    this.config.minConfidence = p.conf;
    this.config.regimeFilter  = [...p.regimes];
    this.config.market        = p.symbol === 'RELIANCE' ? 'STOCK' : 'CRYPTO';
  }

  result:   BacktestResult | null = null;
  running   = false;
  error     = '';
  activeTab: 'overview' | 'trades' | 'monthly' | 'regime' | 'equity' = 'overview';

  // Suggestions only — user can type anything
  readonly CRYPTO_SUGGEST = [
    'BTC','ETH','SOL','BNB','XRP','ADA','AVAX','DOGE','DOT','LINK',
    'MATIC','NEAR','INJ','ARB','OP','ATOM','UNI','LTC','PEPE','WIF',
    'ONDO','FET','SUI','APT','TAO','TIA','SEI','BONK','FLOKI','TON',
    'SHIB','TRX','XLM','VET','ALGO','HBAR','RNDR','IMX','GRT','AAVE',
  ];
  readonly STOCK_SUGGEST  = [
    // NIFTY 50
    'RELIANCE','TCS','HDFCBANK','INFY','ICICIBANK','HINDUNILVR','SBIN',
    'BHARTIARTL','ITC','KOTAKBANK','LT','AXISBANK','BAJFINANCE','WIPRO',
    'ASIANPAINT','MARUTI','SUNPHARMA','TATAMOTORS','ULTRACEMCO','NTPC',
    'POWERGRID','ONGC','BAJAJFINSV','HCLTECH','TITAN','TECHM','NESTLEIND',
    'TATASTEEL','JSWSTEEL','ADANIPORTS','ADANIENT','COALINDIA','BPCL',
    // Mid cap
    'ZOMATO','NAUKRI','PAYTM','POLICYBAZAAR','DELHIVERY','NYKAA',
    'IRCTC','HDFCLIFE','ICICIPRULI','SBILIFE','PIDILITIND','BERGEPAINT',
    'MUTHOOTFIN','CHOLAFIN','BAJAJ-AUTO','HEROMOTOCO','EICHERMOT',
    // Small cap popular
    'TATAPOWER','SUZLON','YESBANK','BANDHANBNK','RBLBANK','FEDERALBNK',
    'MOTHERSON','BALKRISIND','WHIRLPOOL','RAJESHEXPO','KPRMILL','DIXON',
  ];
  readonly TIMEFRAMES     = ['15m','30m','1h','4h','1d'];
  readonly YEARS          = [2019,2020,2021,2022,2023,2024];
  readonly REGIMES        = ['STRONG_BULL_TREND','BULL_TREND','BEAR_TREND','STRONG_BEAR_TREND','RANGING','BREAKOUT_IMMINENT','POST_BREAKOUT_BULL','HIGH_VOLATILITY'];

  symbolInput   = 'BTC';
  showSuggest   = false;
  filteredSugg: string[] = [];

  get suggestList() {
    return this.config.market === 'CRYPTO' ? this.CRYPTO_SUGGEST : this.STOCK_SUGGEST;
  }

  onSymbolInput() {
    const q = this.symbolInput.toUpperCase();
    this.config.symbol = q;
    if (q.length >= 1) {
      this.filteredSugg = this.suggestList
        .filter(s => s.startsWith(q) || s.includes(q))
        .slice(0, 8);
      this.showSuggest = this.filteredSugg.length > 0;
    } else {
      this.filteredSugg = this.suggestList.slice(0, 8);
      this.showSuggest  = true;
    }
  }

  selectSymbol(s: string) {
    this.symbolInput = s;
    this.config.symbol = s;
    this.showSuggest = false;
  }

  onSymbolBlur() { setTimeout(() => this.showSuggest = false, 150); }
  onSymbolFocus() {
    this.filteredSugg = this.suggestList.slice(0, 8);
    this.showSuggest  = true;
  }

  private worker?: any;

  onMarketChange() {
    // Reset to first suggestion for the new market
    this.symbolInput = this.config.market === 'CRYPTO' ? 'BTC' : 'RELIANCE';
    this.config.symbol = this.symbolInput;
    if (this.config.market === 'STOCK') this.config.timeframe = '1d';
  }

  toggleRegime(r: string) {
    const idx = this.config.regimeFilter.indexOf(r);
    if (idx >= 0) this.config.regimeFilter.splice(idx, 1);
    else          this.config.regimeFilter.push(r);
  }

  runBacktest() {
    this.running = true;
    this.error   = '';
    this.result  = null;
    this.bt.progress = 0;
    this.bt.progressMessage = 'Starting...';

    this.bt.run({ ...this.config }).subscribe({
      next: r => {
        this.result  = r;
        this.running = false;
        this.activeTab = 'overview';
      },
      error: e => {
        this.error   = e.message || 'Backtest failed. Check symbol and try again.';
        this.running = false;
      }
    });
  }

  ngOnDestroy() {}

  resultColor(r: string) {
    if (r === 'HIT_T3') return '#00FF88';
    if (r === 'HIT_T2') return '#00D4FF';
    if (r === 'HIT_T1') return 'rgba(0,212,255,0.6)';
    if (r === 'HIT_SL') return '#FF3B5C';
    return '#4A5568';
  }

  pnlColor(v: number) { return v >= 0 ? '#00FF88' : '#FF3B5C'; }

  gradeDesc(g: string): string {
    const d: Record<string,string> = {
      'A+':'🏆 Exceptional','A':'⭐ Excellent','B+':'✅ Very Good',
      'B':'👍 Good','C':'⚠️ Average','D':'❌ Below Avg','F':'🔴 Failing'
    };
    return d[g] || g;
  }

  fmt(s: string): string { return s ? s.replace(/_/g, ' ') : ''; }

  formatTime(ts: number): string {
    return new Date(ts * 1000).toLocaleDateString('en-IN', { day:'2-digit', month:'short', year:'2-digit' });
  }

  // SVG equity curve
  get svgPath(): string {
    if (!this.result?.equityCurve.length) return '';
    const pts = this.result.equityCurve;
    const minE = Math.min(...pts.map(p => p.equity));
    const maxE = Math.max(...pts.map(p => p.equity));
    const range = maxE - minE || 1;
    const w = 800, h = 200;
    return pts.map((p, i) => {
      const x = (i / (pts.length - 1)) * w;
      const y = h - ((p.equity - minE) / range) * (h - 20) - 10;
      return `${i === 0 ? 'M' : 'L'}${x.toFixed(1)},${y.toFixed(1)}`;
    }).join(' ');
  }

  get svgFill(): string {
    if (!this.result?.equityCurve.length) return '';
    const pts = this.result.equityCurve;
    const minE = Math.min(...pts.map(p => p.equity));
    const maxE = Math.max(...pts.map(p => p.equity));
    const range = maxE - minE || 1;
    const w = 800, h = 200;
    const first = `M0,${h}`;
    const line  = pts.map((p, i) => {
      const x = (i / (pts.length-1)) * w;
      const y = h - ((p.equity-minE)/range) * (h-20) - 10;
      return `L${x.toFixed(1)},${y.toFixed(1)}`;
    }).join(' ');
    return `${first} ${line} L${w},${h} Z`;
  }
}
