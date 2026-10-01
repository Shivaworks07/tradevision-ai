import { Component, Input, Output, EventEmitter, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { TradeCall } from '../../services/ta-engine.service';
import { CurrencyService } from '../../services/currency.service';
import { TradeHistoryService } from '../../services/trade-history.service';
import { AuthService } from '../../services/auth.service';

@Component({
  selector: 'app-analysis-modal',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './analysis-modal.component.html',
  styleUrls: ['./analysis-modal.component.scss']
})
export class AnalysisModalComponent {
  @Input() result: TradeCall | null = null;
  @Input() visible   = false;
  @Input() symbol    = '';
  @Input() name      = '';
  @Input() market    = 'STOCK';
  @Input() currentPrice = 0;
  @Input() priceFormatter: (v: number) => string = (v) => `₹${v}`;
  @Output() close    = new EventEmitter<void>();
  @Output() callSaved = new EventEmitter<void>();

  private tradeHistory = inject(TradeHistoryService);
  auth                 = inject(AuthService);
  currency             = inject(CurrencyService);

  saved = false;
  saveError: string | null = null;
  saving = false;

  getSignalClass(): string {
    if (!this.result) return '';
    return this.result.direction === 'LONG' ? 'bull' : this.result.direction === 'SHORT' ? 'bear' : 'neutral';
  }

  getConfColor(conf: number): string {
    if (conf >= 80) return '#00FF88';
    if (conf >= 65) return '#00D4FF';
    if (conf >= 50) return '#FFB800';
    if (conf >= 35) return '#FF9900';
    return '#FF3B5C';
  }

  getStars(conf: number): string {
    const stars = Math.round(conf / 20);
    return '★'.repeat(Math.max(1, stars)) + '☆'.repeat(Math.max(0, 5 - stars));
  }

  getScoreRows(sb: any): { label: string; value: number }[] {
    if (!sb) return [];
    return [
      { label: 'Trend',              value: sb.trend },
      { label: 'Momentum',           value: sb.momentum },
      { label: 'Volume',             value: sb.volume },
      { label: 'Candlestick',        value: sb.candlestick },
      { label: 'Support/Resistance', value: sb.supportResistance },
    ];
  }

  getChecks(r: TradeCall): { label: string; pass: boolean }[] {
    return [
      { label: 'RSI Signal',     pass: r.rsi < 40 || r.rsi > 60 },
      { label: 'MACD Direction', pass: (r.direction === 'LONG' && r.macdBull) || (r.direction === 'SHORT' && !r.macdBull) },
      { label: 'EMA Alignment',  pass: !!(r.trendEMA?.includes('TREND')) },
      { label: 'Volume Confirm', pass: r.volumeRatio > 1.2 },
      { label: 'VWAP Direction', pass: (r.direction === 'LONG' && r.vwapSignal === 'ABOVE') || (r.direction === 'SHORT' && r.vwapSignal === 'BELOW') },
      { label: 'ADX Strength',   pass: r.adx > 20 },
    ];
  }

  getPassCount(r: TradeCall): number {
    return this.getChecks(r).filter(c => c.pass).length;
  }

  saveCall() {
  if (!this.result || !this.symbol) return;
  this.saving = true;
  this.saveError = null;
  this.tradeHistory.saveCall(this.result as any, this.symbol, this.market).subscribe({
    next: () => { this.saving = false; this.saved = true; this.callSaved.emit(); },
    error: (err: Error) => { this.saving = false; this.saveError = err.message || 'Could not save. Please try again.'; }
  });
}
}
