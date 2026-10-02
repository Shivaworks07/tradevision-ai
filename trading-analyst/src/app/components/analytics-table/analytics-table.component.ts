import { Component, Input } from '@angular/core';


@Component({
    selector: 'app-analytics-table',
    imports: [],
    template: `
    <div class="at-wrap">
      <div class="at-header">
        <span>Name</span><span>Trades</span><span>Win %</span><span>Profit Factor</span><span>Avg R</span><span>Bar</span>
      </div>
      @for (r of rows; track r) {
        <div class="at-row">
          <span class="at-name">{{fmt(r.label)}}</span>
          <span class="at-num">{{r.trades}}</span>
          <span [style.color]="wrColor(r.winRate)">{{r.winRate}}%</span>
          <span class="at-pf" [style.color]="pfColor(r.profitFactor)">{{r.profitFactor}}</span>
          <span [style.color]="r.avgR>=0?'#00FF88':'#FF3B5C'">{{r.avgR>=0?'+':''}}{{r.avgR}}R</span>
          <div class="at-bar-wrap">
            <div class="at-bar" [style.width.%]="pfBar(r.profitFactor)" [style.background]="pfColor(r.profitFactor)"></div>
          </div>
        </div>
      }
      @if (!rows?.length) {
        <div class="at-empty">No data yet for this breakdown.</div>
      }
    </div>
    `,
    styles: [`
    .at-wrap { border:1px solid #1E2D4A; border-radius:10px; overflow:hidden; }
    .at-header { display:grid; grid-template-columns:1.5fr 70px 70px 110px 70px 120px; gap:8px; padding:8px 14px; background:#080C18; font-size:9px; color:#4A5568; text-transform:uppercase; letter-spacing:0.5px; }
    .at-row { display:grid; grid-template-columns:1.5fr 70px 70px 110px 70px 120px; gap:8px; padding:8px 14px; font-size:11px; border-bottom:1px solid #0D1424; align-items:center; transition:background 0.1s;
      &:hover { background:rgba(255,255,255,0.02); }
      &:last-child { border-bottom:none; }
      .at-name { color:#E8EDF5; font-weight:600; text-transform:capitalize; }
      .at-num { color:#8895B3; font-family:'JetBrains Mono',monospace; }
      .at-pf { font-family:'JetBrains Mono',monospace; font-weight:700; }
    }
    .at-bar-wrap { height:6px; background:#1A2340; border-radius:3px; overflow:hidden;
      .at-bar { height:100%; border-radius:3px; opacity:0.7; transition:width 0.5s ease; }
    }
    .at-empty { padding:20px; text-align:center; color:#4A5568; font-size:12px; }
  `]
})
export class AnalyticsTableComponent {
  @Input() rows: any[] = [];
  @Input() maxPF = 3;
  @Input() pfColor: (pf: number) => string = () => '#8895B3';
  @Input() wrColor: (wr: number) => string = () => '#8895B3';
  pfBar(pf: number): number { return this.maxPF > 0 ? Math.min((pf/this.maxPF)*100, 100) : 0; }
  fmt(s: string): string { return s ? s.replace(/_/g,' ') : ''; }
}
