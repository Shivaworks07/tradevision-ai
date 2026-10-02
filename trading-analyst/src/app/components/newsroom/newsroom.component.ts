import { Component, OnInit, OnDestroy, inject } from '@angular/core';

import { HttpClient } from '@angular/common/http';
import { Subscription } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { of } from 'rxjs';
import { environment } from '../../../environments/environment';

interface NewsItem {
  id: string; title: string; summary: string; url: string;
  source: string; category: string; publishedAt: string;
  imageUrl: string; publishedMs: number;
}

@Component({
    selector: 'app-newsroom',
    imports: [],
    templateUrl: './newsroom.component.html',
    styleUrls: ['./newsroom.component.scss']
})
export class NewsroomComponent implements OnInit, OnDestroy {
  private http = inject(HttpClient);

  articles:    NewsItem[] = [];
  loading      = true;
  error        = '';
  activeTab    = 'ALL';
  lastUpdated  = '';
  private sub?: Subscription;
  private refreshTimer?: any;

  readonly tabs = [
    { label:'All',           value:'ALL',    icon:'🌐' },
    { label:'Indian Stocks', value:'STOCKS', icon:'🇮🇳' },
    { label:'Crypto',        value:'CRYPTO', icon:'₿'  },
    { label:'Forex',         value:'FOREX',  icon:'💱' },
    { label:'IPO',           value:'IPO',    icon:'🚀' },
    { label:'Gold',          value:'GOLD',   icon:'🥇' },
    { label:'Global',        value:'GLOBAL', icon:'📰' },
  ];

  readonly catColors: Record<string,string> = {
    CRYPTO:'#F7931A', STOCKS:'#00FF88', FOREX:'#00D4FF',
    IPO:'#7B61FF', GOLD:'#FFB800', GLOBAL:'#8895B3'
  };

  ngOnInit() { this.fetch(); }

  ngOnDestroy() {
    this.sub?.unsubscribe();
    clearTimeout(this.refreshTimer);
  }

  setTab(tab: string) {
    this.activeTab = tab;
    this.loading   = true;
    this.articles  = [];
    this.fetch();
  }

  private fetch() {
    this.sub?.unsubscribe();
    this.sub = this.http.get<any>(
      `${environment.apiUrl}/news?category=${this.activeTab}&limit=50`
    ).pipe(catchError(() => of(null))).subscribe(r => {
      if (!r) { this.error = 'Could not load news.'; this.loading = false; return; }
      this.articles    = r?.data?.articles || [];
      this.loading     = false;
      this.error       = '';
      const ts = r?.data?.lastUpdated;
      this.lastUpdated = ts ? new Date(ts).toLocaleTimeString('en-IN',{hour:'2-digit',minute:'2-digit'}) : '';

      // Re-fetch every 5 min ONLY while on this page
      clearTimeout(this.refreshTimer);
      this.refreshTimer = setTimeout(() => {
        if (this.articles.length > 0) this.fetch();
      }, 300_000);
    });
  }

  timeAgo(ms: number): string {
    if (!ms) return '';
    const diff = Date.now() - ms;
    const m = Math.floor(diff/60000);
    if (m < 1)  return 'Just now';
    if (m < 60) return `${m}m ago`;
    const h = Math.floor(m/60);
    if (h < 24) return `${h}h ago`;
    return `${Math.floor(h/24)}d ago`;
  }

  catColor(cat: string) { return this.catColors[cat] || '#4A5568'; }
  trackById(_: number, item: NewsItem) { return item.id; }
}
