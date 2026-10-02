import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { TradeHistoryService } from '../../services/trade-history.service';
import { AuthService } from '../../services/auth.service';

interface PeriodStats {
  label:        string;
  total:        number;
  wins:         number;
  losses:       number;
  pending:      number;
  winRate:      number;
  avgGain:      number;
  avgLoss:      number;
  profitFactor: number;
}

@Component({
    selector: 'app-accuracy',
    imports: [CommonModule],
    templateUrl: './accuracy.component.html',
    styleUrls: ['./accuracy.component.scss']
})
export class AccuracyComponent implements OnInit {
  private history = inject(TradeHistoryService);
  auth            = inject(AuthService);

  loading = true;
  allCalls: any[] = [];
  periods: PeriodStats[] = [];
  bySignal: { signal: string; total: number; wins: number; winRate: number; avgR: number }[] = [];
  recentCalls: any[] = [];
  filteredCalls: any[] = [];
  allTimeStats: PeriodStats | null = null;

  // Filters
  filterResult = 'ALL';   // ALL | WIN | LOSS | PENDING
  filterMarket = 'ALL';   // ALL | STOCK | CRYPTO | FOREX

  readonly resultFilters = [
    { label:'All',     value:'ALL'     },
    { label:'✅ Wins',  value:'WIN'     },
    { label:'❌ Losses',value:'LOSS'    },
    { label:'⏳ Pending',value:'PENDING'},
  ];
  readonly marketFilters = [
    { label:'All Markets', value:'ALL'    },
    { label:'🇮🇳 Stocks',  value:'STOCK'  },
    { label:'₿ Crypto',   value:'CRYPTO' },
    { label:'💱 Forex',   value:'FOREX'  },
  ];

  applyFilters() {
    this.filteredCalls = this.recentCalls.filter(c => {
      const mktOk = this.filterMarket === 'ALL' || c.market === this.filterMarket;
      let resOk = true;
      if      (this.filterResult === 'WIN')     resOk = c.result?.startsWith('HIT_T');
      else if (this.filterResult === 'LOSS')    resOk = c.result === 'HIT_SL';
      else if (this.filterResult === 'PENDING') resOk = !c.result || c.result === 'PENDING';
      return mktOk && resOk;
    });
  }

  ngOnInit() {
    if (!this.auth.isLoggedIn) { this.loading = false; return; }
    this.history.getRecentCalls(200).subscribe({
      next: (r: any) => {
        this.allCalls  = (r?.data || []).map((c: any) => ({
          ...c,
          result: c.result || c.outcome?.result || 'PENDING',
          pnlPct: c.pnlPct ?? c.outcome?.pnlPct,
          pnlR:   c.pnlR   ?? c.outcome?.pnlR,
        }));
        this.compute();
        this.loading = false;
      },
      error: () => this.loading = false
    });
  }

  private compute() {
    const now   = Date.now();
    const day   = 86400000;
    const resolved = this.allCalls.filter(c => c.result && c.result !== 'PENDING' && c.result !== 'EXPIRED');

    const periodDefs = [
      { label:'Today',    ms: day },
      { label:'7 Days',   ms: 7*day },
      { label:'30 Days',  ms: 30*day },
      { label:'90 Days',  ms: 90*day },
      { label:'All Time', ms: Infinity },
    ];

    this.periods = periodDefs.map(p => {
      const calls = p.ms === Infinity
        ? resolved
        : resolved.filter(c => now - new Date(c.calledAt).getTime() < p.ms);
      return this.calcStats(p.label, calls);
    });

    // By signal type
    const signalMap = new Map<string, any[]>();
    resolved.forEach(c => {
      const k = c.signal || 'NEUTRAL';
      if (!signalMap.has(k)) signalMap.set(k, []);
      signalMap.get(k)!.push(c);
    });
    this.bySignal = Array.from(signalMap.entries()).map(([signal, calls]) => {
      const wins = calls.filter(c => c.result?.startsWith('HIT_T'));
      const avgR = calls.filter(c => c.pnlR != null).reduce((a,c)=>a+c.pnlR,0) / (calls.length || 1);
      return { signal, total: calls.length, wins: wins.length, winRate: Math.round(wins.length/calls.length*100), avgR: +avgR.toFixed(2) };
    }).sort((a,b) => b.total - a.total);

    // Recent calls for the track record table
    this.recentCalls = this.allCalls.slice(0, 100);
    this.allTimeStats = this.periods.length > 4 ? this.periods[4] : null;
    this.applyFilters();
  }

  private calcStats(label: string, calls: any[]): PeriodStats {
    const wins   = calls.filter(c => c.result?.startsWith('HIT_T'));
    const losses = calls.filter(c => c.result === 'HIT_SL');
    const winPnls  = wins.filter(c => c.pnlPct != null).map(c => c.pnlPct);
    const lossPnls = losses.filter(c => c.pnlPct != null).map(c => Math.abs(c.pnlPct));
    const avgGain  = winPnls.length  ? +(winPnls.reduce((a,b)=>a+b,0)/winPnls.length).toFixed(2) : 0;
    const avgLoss  = lossPnls.length ? +(lossPnls.reduce((a,b)=>a+b,0)/lossPnls.length).toFixed(2) : 0;
    const grossW   = winPnls.reduce((a,b)=>a+b,0);
    const grossL   = lossPnls.reduce((a,b)=>a+b,0);
    const pending  = calls.filter(c => c.result === 'PENDING' || !c.result).length;
    const winRate  = calls.length > 0 ? Math.round(wins.length / calls.length * 100) : 0;
    const pf       = grossL > 0 ? +(grossW/grossL).toFixed(2) : grossW > 0 ? 99 : 0;
    return { label, total:calls.length, wins:wins.length, losses:losses.length, pending, winRate, avgGain, avgLoss, profitFactor:pf };
  }

  resultLabel(r: string): string {
    if (!r || r === 'PENDING') return '⏳ Pending';
    if (r.startsWith('HIT_T'))  return '✅ Target Hit';
    if (r === 'HIT_SL')         return '❌ SL Hit';
    return '⏰ Expired';
  }

  resultClass(r: string): string {
    if (!r || r === 'PENDING') return 'pending';
    if (r.startsWith('HIT_T')) return 'win';
    if (r === 'HIT_SL')        return 'loss';
    return 'expired';
  }

  wrColor(wr: number): string { return wr>=60?'#00FF88':wr>=50?'#FFB800':'#FF3B5C'; }
  pfColor(pf: number): string { return pf>=1.5?'#00FF88':pf>=1?'#FFB800':'#FF3B5C'; }
  timeAgo(iso: string): string {
    const m = Math.floor((Date.now() - new Date(iso).getTime()) / 60000);
    if (m < 60) return `${m}m ago`;
    const h = Math.floor(m/60); if (h < 24) return `${h}h ago`;
    return `${Math.floor(h/24)}d ago`;
  }
}
