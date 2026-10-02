import { Component, Input, OnChanges, SimpleChanges, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { TradeHistoryService, SavedCall } from '../../services/trade-history.service';
import { AuthService } from '../../services/auth.service';
import { TaEngineService } from '../../services/ta-engine.service';

@Component({
    selector: 'app-call-history',
    imports: [CommonModule],
    templateUrl: './call-history.component.html',
    styleUrls: ['./call-history.component.scss']
})
export class CallHistoryComponent implements OnChanges {
  @Input() symbol  = '';
  @Input() market  = '';
  @Input() refresh = 0; // bump to load fresh after saving a call

  private history = inject(TradeHistoryService);
  auth            = inject(AuthService);
  private ta      = inject(TaEngineService);

  calls:   SavedCall[] = [];
  loading  = false;
  expanded = false; // collapsed by default - only load when user expands

  ngOnChanges(c: SimpleChanges) {
    // Only auto-load when refresh is bumped (i.e. after a new call is saved)
    if (c['refresh'] && !c['refresh'].firstChange && c['refresh'].currentValue > 0) {
      if (this.expanded) this.load();
      else { this.expanded = true; this.load(); }
    }
  }

  toggle() {
    this.expanded = !this.expanded;
    if (this.expanded && this.calls.length === 0) this.load();
  }

  load() {
    if (!this.symbol || !this.auth.isLoggedIn) return;
    this.loading = true;
    this.history.getCallsForSymbol(this.symbol).subscribe((r: any) => {
      const raw = r?.data || [];
      // Map nested outcome.result back to flat result field for backward compat
      this.calls = raw.map((call: any) => ({
        ...call,
        result:     call.result || call.outcome?.result || 'PENDING',
        exitPrice:  call.exitPrice  ?? call.outcome?.exitPrice,
        resolvedAt: call.resolvedAt ?? call.outcome?.resolvedAt,
      }));
      this.loading = false;
      this.syncMLFromHistory();
    });
  }

  markResult(call: SavedCall, result: string) {
    const exit = result === 'HIT_T1' ? call.target1
               : result === 'HIT_T2' ? call.target2
               : result === 'HIT_T3' ? call.target3
               : call.stopLoss;
    this.history.updateResult(call.id, result, exit).subscribe(() => {
      // Feed outcome to ML engine so it learns what works for this symbol
      this.ta.updateMLFromOutcome(call.symbol, call.market, result, {
        direction:   call.direction as any,
        rsi:         call.rsi,
        macdBull:    call.macdBull,
        patterns:    [],
        volumeRatio: 1,
      });
      this.load();
    });
  }

  // Sync ML from loaded call history
  private syncMLFromHistory() {
    if (this.calls.length >= 3 && this.symbol && this.market) {
      this.ta.syncMLFromHistory(this.symbol, this.market, this.calls);
    }
  }

  resultLabel(r: string): string {
    if (!r) return '⏳ Pending';
    return r === 'PENDING' ? '⏳ Pending'
         : r === 'HIT_T1'  ? '✅ T1 Hit'
         : r === 'HIT_T2'  ? '✅✅ T2 Hit'
         : r === 'HIT_T3'  ? '🎯 T3 Hit'
         : r === 'HIT_SL'  ? '❌ SL Hit'
         : '⏰ Expired';
  }

  resultClass(r: string): string {
    if (!r) return 'pending';
    return r.startsWith('HIT_T') ? 'win'
         : r === 'HIT_SL'        ? 'loss'
         : r === 'PENDING'       ? 'pending'
         : 'expired';
  }

  formatTime(iso: string): string {
    if (!iso) return '';
    return new Date(iso).toLocaleTimeString('en-IN', {hour:'2-digit', minute:'2-digit', hour12:true, timeZone:'Asia/Kolkata'});
  }

  timeAgo(iso: string): string {
    const diff = Date.now() - new Date(iso).getTime();
    const m = Math.floor(diff / 60000);
    if (m < 60) return m + 'm ago';
    const h = Math.floor(m / 60);
    if (h < 24) return h + 'h ago';
    return Math.floor(h / 24) + 'd ago';
  }
}
