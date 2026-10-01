import { Component, Input, OnChanges } from '@angular/core';
import { CommonModule } from '@angular/common';
import { TradeCall } from '../services/ta-engine.service';

@Component({
  selector: 'tv-market-mood',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="mm-widget" *ngIf="call">
      <div class="mm-title">Market Mood</div>
      <div class="mm-mood" [style.color]="moodColor">{{mood}}</div>
      <div class="mm-details">
        <div class="mmd-item">
          <span class="mmd-l">Trend</span>
          <span class="mmd-v" [style.color]="trendColor">{{trend}}</span>
        </div>
        <div class="mmd-item" *ngIf="call.adx">
          <span class="mmd-l">Volatility</span>
          <span class="mmd-v">{{call.adx > 30 ? 'High' : call.adx > 20 ? 'Medium' : 'Low'}}</span>
        </div>
        <div class="mmd-item" *ngIf="call.smcBias">
          <span class="mmd-l">Smart Money</span>
          <span class="mmd-v" [style.color]="call.smcBias==='BULLISH'?'#00FF88':call.smcBias==='BEARISH'?'#FF3B5C':'#FFB800'">{{call.smcBias}}</span>
        </div>
        <div class="mmd-item" *ngIf="call.regime">
          <span class="mmd-l">Regime</span>
          <span class="mmd-v" style="font-size:9px">{{formatRegime(call.regime)}}</span>
        </div>
      </div>
    </div>
  `,
  styles: [`
    .mm-widget{background:#0F1525;border:1px solid #1E2D4A;border-radius:12px;padding:12px 14px;}
    .mm-title{font-size:9px;color:#4A5568;text-transform:uppercase;letter-spacing:0.5px;margin-bottom:6px;}
    .mm-mood{font-size:18px;font-weight:800;margin-bottom:8px;}
    .mm-details{display:flex;flex-direction:column;gap:4px;}
    .mmd-item{display:flex;justify-content:space-between;font-size:10px;
      .mmd-l{color:#4A5568;}.mmd-v{color:#E8EDF5;font-weight:600;}
    }
  `]
})
export class MarketMoodComponent implements OnChanges {
  @Input() call: TradeCall | null = null;

  mood       = 'Neutral';
  moodColor  = '#FFB800';
  trend      = 'Sideways';
  trendColor = '#FFB800';

  ngOnChanges() {
    if (!this.call) return;
    const c = this.call.confidence;
    const d = this.call.direction;
    if (d === 'LONG') {
      this.mood = c >= 75 ? 'Bullish' : 'Slightly Bullish';
      this.moodColor = '#00FF88';
    } else if (d === 'SHORT') {
      this.mood = c >= 75 ? 'Bearish' : 'Slightly Bearish';
      this.moodColor = '#FF3B5C';
    } else {
      this.mood = 'Neutral'; this.moodColor = '#FFB800';
    }
    const t = this.call.trendEMA || '';
    if (t.includes('STRONG_UP'))       { this.trend='Strong Uptrend';  this.trendColor='#00FF88'; }
    else if (t.includes('UPTREND'))    { this.trend='Uptrend';         this.trendColor='#00D4FF'; }
    else if (t.includes('STRONG_BEAR')){ this.trend='Strong Downtrend';this.trendColor='#FF3B5C'; }
    else if (t.includes('DOWNTREND'))  { this.trend='Downtrend';       this.trendColor='#FF6B35'; }
    else                               { this.trend='Sideways';        this.trendColor='#FFB800'; }
  }

  formatRegime(r: string): string {
    return (r || '').replace(/_/g,' ').toLowerCase().replace(/^\w/,s=>s.toUpperCase());
  }
}
