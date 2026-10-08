import { environment } from '../../../environments/environment';
import { Component, OnInit, OnDestroy, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { RouterModule } from '@angular/router';
import { interval, Subscription, timer } from 'rxjs';
import { switchMap, startWith, retry, catchError } from 'rxjs/operators';
import { of } from 'rxjs';


// ── Admin Dashboard Types ─────────────────────────────────
interface UserStats {
  total:        number;
  activeToday:  number;
  activeWeek:   number;
  newThisMonth: number;
  admins:       number;
}

interface MarketAccuracy {
  total:   number;
  wins:    number;
  winRate: number;
}

interface SignalStats {
  total:           number;
  today:           number;
  thisWeek:        number;
  wins:            number;
  losses:          number;
  overallWinRate:  number;
  accuracyByMarket: Record<string, MarketAccuracy>;
}

interface ApiStats {
  requests24h:  number;
  errors24h:    number;
  errorRatePct: number;
  avgLatencyMs: number;
  p95LatencyMs: number;
  topEndpoints: { endpoint: string; calls: number }[];
}

interface AdminDashboard {
  users:        UserStats;
  signals:      SignalStats;
  api:          ApiStats;
  topSymbols:   { symbol: string; calls: number }[];
  byMarket:     Record<string, number>;
  hourlyVolume: Record<number, number>;
  generatedAt:  string;
}

@Component({
    selector: 'app-admin',
    imports: [CommonModule, RouterModule],
    templateUrl: './admin.component.html',
    styleUrls: ['./admin.component.scss']
})
export class AdminComponent implements OnInit, OnDestroy {
  private http   = inject(HttpClient);
  private readonly API = `${environment.apiUrl}/admin`;

  dashboard: AdminDashboard | null = null;
  loading         = true;
  error           = '';
  lastRefresh     = '';
  private sub?: Subscription;

  ngOnInit() {
    // Auto-refresh every 60 seconds
    this.sub = interval(60000).pipe(
      startWith(0),
      switchMap(() =>
        this.http.get<any>(`${this.API}/dashboard`).pipe(
          // Retry up to 3 times with exponential backoff: 2s → 4s → 8s
          retry({
            count: 3,
            delay: (_, attempt) => timer(Math.pow(2, attempt) * 1000)
          }),
          catchError(e => of({ _error: e }))
        )
      )
    ).subscribe({
      next:  r  => {
        if ((r as any)?._error) {
          const e = (r as any)._error;
          this.error = e?.status === 403 ? 'Admin access required.' : 'API unavailable — retrying next cycle.';
          this.loading = false;
          return;
        }
        this.dashboard   = (r as { data: AdminDashboard })?.data;
        this.loading     = false;
        this.error       = '';
        this.lastRefresh = new Date().toLocaleTimeString('en-IN', { hour12:false });
      },
      error: () => {}   // catchError above handles all errors
    });
  }

  ngOnDestroy() { this.sub?.unsubscribe(); }

  // ── Computed getters ──────────────────────────────────────
  get users():     UserStats    { return this.dashboard?.users   || { total:0, activeToday:0, activeWeek:0, newThisMonth:0, admins:0 }; }
  get signals():   SignalStats  { return this.dashboard?.signals  || { total:0, today:0, thisWeek:0, wins:0, losses:0, overallWinRate:0, accuracyByMarket:{} }; }
  get apiMetrics(): ApiStats   { return this.dashboard?.api      || { requests24h:0, errors24h:0, errorRatePct:0, avgLatencyMs:0, p95LatencyMs:0, topEndpoints:[] }; }
  get topSymbols(): { symbol: string; calls: number }[] { return this.dashboard?.topSymbols || []; }
  get byMarket(): Record<string,number>  { return this.dashboard?.byMarket  || {}; }
  get topEndpoints(): { endpoint: string; calls: number }[] { return this.dashboard?.api?.topEndpoints || []; }
  get hourly(): Record<number,number>    { return this.dashboard?.hourlyVolume || {}; }

  // ── Derived stats ─────────────────────────────────────────
  get errorRateColor(): string {
    const r = this.apiMetrics.errorRatePct || 0;
    return r < 1 ? '#00FF88' : r < 5 ? '#FFB800' : '#FF3B5C';
  }
  get latencyColor(): string {
    const l = this.apiMetrics.avgLatencyMs || 0;
    return l < 100 ? '#00FF88' : l < 300 ? '#FFB800' : '#FF3B5C';
  }
  get wrColor(): string {
    const w = this.signals.overallWinRate || 0;
    return w >= 65 ? '#00FF88' : w >= 50 ? '#FFB800' : '#FF3B5C';
  }
  accuracyOf(market: string): MarketAccuracy {
    return this.signals.accuracyByMarket?.[market] ?? { total:0, wins:0, winRate:0 };
  }

  // ── Hourly chart bars ─────────────────────────────────────
  // Feedback
  feedbackItems: any[]  = [];
  feedbackStats: any    = {};
  feedbackFilter        = '';
  feedbackTypeFilter    = '';
  selectedFeedback: any = null;
  adminNote             = '';
  loadingFeedback       = false;

  loadFeedback(status='', type='') {
    this.loadingFeedback = true;
    this.feedbackFilter = status; this.feedbackTypeFilter = type;
    // Uses environment.apiUrl so requests resolve correctly across environments,
    // consistent with the other endpoints in this component.
    let url = `${environment.apiUrl}/feedback/admin?size=50`;
    if (status) url += `&status=${status}`;
    if (type)   url += `&type=${type}`;
    this.http.get<any>(url).subscribe({
      next: r => { this.feedbackItems = r?.data?.items || []; this.feedbackStats = r?.data?.stats || {}; this.loadingFeedback = false; },
      error: () => this.loadingFeedback = false
    });
  }

  updateFeedback(id: string, patch: any) {
    this.http.patch<any>(`${environment.apiUrl}/feedback/admin/${id}`, patch).subscribe({
      next: () => this.loadFeedback(this.feedbackFilter, this.feedbackTypeFilter)
    });
  }

  deleteFeedback(id: string) {
    if (!confirm('Delete this feedback?')) return;
    this.http.delete(`${environment.apiUrl}/feedback/admin/${id}`).subscribe({
      next: () => this.loadFeedback(this.feedbackFilter, this.feedbackTypeFilter)
    });
  }

  get hourlyBars(): { hour: number; count: number; pct: number }[] {
    const entries = Object.entries(this.hourly) as [string,number][];
    if (!entries.length) return [];
    const max = Math.max(...entries.map(([,v]) => v), 1);
    return Array.from({ length:24 }, (_, h) => {
      const count = (this.hourly[h] as number) || 0;
      return { hour: h, count, pct: Math.round(count / max * 100) };
    });
  }

  // Format endpoint for display
  fmtEndpoint(e: string): string { return e.length > 35 ? e.slice(0,35) + '…' : e; }
}
