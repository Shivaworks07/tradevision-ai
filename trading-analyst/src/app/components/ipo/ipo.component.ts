import { Component, OnInit, OnDestroy, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Subscription } from 'rxjs';
import { LiveDataService, IPOItem } from '../../services/live-data.service';
import { CurrencyService } from '../../services/currency.service';
import { IpoCountPipe } from '../../pipes/ipo-count.pipe';
import { SkeletonCardComponent } from '../../shared/skeleton.component';
import { EmptyStateComponent } from '../../shared/empty-state.component';

interface IpoAnalysis {
  bullPoints:  string[];
  bearPoints:  string[];
  score:       number;
  verdict:     string;
  verdictText: string;
}

@Component({
    selector: 'app-ipo',
    imports: [CommonModule, FormsModule, IpoCountPipe, SkeletonCardComponent, EmptyStateComponent],
    templateUrl: './ipo.component.html',
    styleUrls: ['./ipo.component.scss']
})
export class IpoComponent implements OnInit, OnDestroy {
  private liveData = inject(LiveDataService);
  currency         = inject(CurrencyService);
  private sub?: Subscription;

  allIPOs:    IPOItem[] = [];
  filtered:   IPOItem[] = [];
  selectedIpo: IPOItem | null = null;
  ipoAnalysis: IpoAnalysis | null = null;

  openDetail(ipo: IPOItem) {
    this.selectedIpo  = ipo;
    this.ipoAnalysis  = this.analyzeIPO(ipo);
  }
  closeDetail() { this.selectedIpo = null; this.ipoAnalysis = null; }

  analyzeIPO(ipo: IPOItem): IpoAnalysis {
    const bullPoints: string[] = [];
    const bearPoints: string[] = [];
    let score = 50;

    // GMP analysis — GMP is unofficial and not always available from live sources;
    // absence of data is scored neutrally, never fabricated.
    if (ipo.gmpPct != null) {
      if (ipo.gmpPct > 30) { bullPoints.push(`Strong GMP +${ipo.gmpPct}% — grey market betting big on listing gains`); score += 15; }
      else if (ipo.gmpPct > 15) { bullPoints.push(`Positive GMP +${ipo.gmpPct}% — moderate listing gain expected`); score += 8; }
      else if (ipo.gmpPct > 0)  { bullPoints.push(`Slight GMP +${ipo.gmpPct}% — small listing premium`); score += 3; }
      else if (ipo.gmpPct < -5) { bearPoints.push(`Negative GMP ${ipo.gmpPct}% — grey market expects listing at discount`); score -= 12; }
    }

    // Subscription analysis
    const subX = 0; // live subscription-times data not available from current sources — scored neutrally, not fabricated
    if (subX > 50)  { bullPoints.push(`Massively oversubscribed ${subX}x — extremely high demand`); score += 15; }
    else if (subX > 20) { bullPoints.push(`Heavily oversubscribed ${subX}x — strong investor demand`); score += 10; }
    else if (subX > 5)  { bullPoints.push(`Well subscribed ${subX}x — good demand`); score += 5; }
    else if (subX > 0 && subX < 1) { bearPoints.push(`Undersubscribed ${subX}x — weak demand, risk of listing below issue`); score -= 15; }

    // Sector analysis
    const growthSectors = ['Technology','EV','FinTech','Healthcare','Defence','AI','SaaS','EV/Auto','Clean Energy'];
    const avoidSectors  = ['Real Estate','Textile','Commodity'];
    if (growthSectors.some(s => ipo.category?.includes(s))) {
      bullPoints.push(`${ipo.category} — high growth sector with premium valuations`); score += 8;
    }
    if (avoidSectors.some(s => ipo.category?.includes(s))) {
      bearPoints.push(`${ipo.category} — sector typically trades at lower multiples`); score -= 5;
    }

    // Size analysis
    const sizeNum = parseFloat((ipo.issueSize||'').replace(/[₹,Cr]/g, ''));
    if (sizeNum > 5000)  { bullPoints.push(`Large issue size ₹${ipo.issueSize} — institutional participation likely`); score += 5; }
    if (sizeNum < 100)   { bearPoints.push(`Very small issue ₹${ipo.issueSize} — limited liquidity post-listing`); score -= 5; }

    // Exchange analysis
    if (ipo.exchange?.includes('NSE') && ipo.exchange?.includes('BSE')) {
      bullPoints.push('Listed on both NSE & BSE — maximum liquidity'); score += 3;
    }
    if (ipo.exchange?.includes('SME')) {
      bearPoints.push('SME IPO — lower liquidity, higher risk, lock-in periods apply'); score -= 8;
    }

    // Status analysis
    if (ipo.status === 'OPEN') {
      bullPoints.push(`IPO closes ${ipo.closeDate} — you can still apply now`);
    }

    // Listing timing
    if (ipo.listingDate) {
      bullPoints.push(`Lists on ${ipo.listingDate} — plan your exit around listing date`);
    }

    score = Math.max(10, Math.min(95, score));
    const verdict: 'STRONG_SUBSCRIBE'|'SUBSCRIBE'|'NEUTRAL'|'AVOID'|'STRONG_AVOID' =
      score >= 80 ? 'STRONG_SUBSCRIBE' : score >= 65 ? 'SUBSCRIBE' :
      score >= 45 ? 'NEUTRAL' : score >= 30 ? 'AVOID' : 'STRONG_AVOID';

    const verdictText: Record<string, string> = {
      STRONG_SUBSCRIBE: 'High conviction. Apply for maximum allotment.',
      SUBSCRIBE:        'Good IPO. Apply with normal allocation.',
      NEUTRAL:          'Average IPO. Apply only if you like the sector.',
      AVOID:            'Risk outweighs reward. Consider skipping.',
      STRONG_AVOID:     'Multiple red flags. Avoid this IPO.'
    };

    return { bullPoints, bearPoints, score, verdict, verdictText: verdictText[verdict] };
  }
  search    = '';
  loading   = true;
  filter    = 'All';
  filters   = ['All','OPEN','UPCOMING','CLOSED','LISTED'];
  lastUpdated = '';
  autoRefreshCountdown = 300;
  private timer?: any;

  get totalSubscribe() { return this.filtered.filter(i => this.verdictFor(i).startsWith('SUBSCRIBE') || this.verdictFor(i)==='STRONG_SUBSCRIBE').length; }
  get totalAvoid()     { return this.filtered.filter(i => this.verdictFor(i)==='AVOID' || this.verdictFor(i)==='STRONG_AVOID').length; }
  private verdictCache = new Map<string, string>();
  verdictFor(ipo: IPOItem): string {
    const key = ipo.symbol + ipo.company;
    if (!this.verdictCache.has(key)) this.verdictCache.set(key, this.analyzeIPO(ipo).verdict);
    return this.verdictCache.get(key)!;
  }
  get totalOpen()      { return this.allIPOs.filter(i=>i.status==='OPEN').length; }

  isStale     = false;
  fetchWarning: string | null = null;
  gmpDisclaimer = '';

  ngOnInit() {
    this.sub = this.liveData.getIPOData().subscribe(res => {
      this.allIPOs      = res.ipos;
      this.isStale       = res.stale;
      this.fetchWarning  = res.warning ?? null;
      this.gmpDisclaimer = res.gmpDisclaimer;
      this.verdictCache.clear();
      this.applyFilter();
      this.loading     = false;
      this.lastUpdated = res.lastUpdated
        ? new Date(res.lastUpdated).toLocaleTimeString('en-IN',{hour12:false})
        : '';
    });
    this.timer = setInterval(() => {
      this.autoRefreshCountdown = Math.max(0, this.autoRefreshCountdown-1);
      if (this.autoRefreshCountdown===0) this.autoRefreshCountdown=300;
    },1000);
  }

  ngOnDestroy() { this.sub?.unsubscribe(); clearInterval(this.timer); }

  applyFilter() {
    let list = this.allIPOs;
    if (this.filter !== 'All') list = list.filter(i => i.status === this.filter);
    if (this.search) {
      const q = this.search.toLowerCase();
      list = list.filter(i =>
        i.company.toLowerCase().includes(q) ||
        i.symbol.toLowerCase().includes(q)  ||
        i.category.toLowerCase().includes(q)
      );
    }
    this.filtered = list;
  }

  setFilter(f: string) { this.filter = f; this.applyFilter(); }

  formatPrice(inrPrice: number): string {
    if (this.currency.currency()==='USD') {
      return `$${(inrPrice/this.currency.usdToInr()).toFixed(2)}`;
    }
    if (inrPrice>=10000000) return `₹${(inrPrice/10000000).toFixed(2)}Cr`;
    if (inrPrice>=100000)   return `₹${(inrPrice/100000).toFixed(2)}L`;
    return `₹${inrPrice.toLocaleString('en-IN')}`;
  }

  getRecoClass(r: string)  { return r.toLowerCase(); }
  getStatusClass(s: string){ return s.toLowerCase().replace(' ','-'); }
  fmt(s: string): string { return s ? s.replace(/_/g,' ') : ''; }
  verdictClass(v: string): string { return v ? v.toLowerCase().split('_').join('-') : ''; }
  gmpColor(pct: number|null) { if (pct == null) return '#4A5568'; return pct>20?'#00FF88':pct>0?'#FFB800':'#FF3B5C'; }
}
