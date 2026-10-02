import { Component, Input } from '@angular/core';


@Component({
    selector: 'tv-skeleton',
    imports: [],
    template: `
    <div class="sk-wrap" [class]="type + '-sk'" [style.width]="width" [style.height]="height" [style.border-radius]="radius"></div>
  `,
    styles: [`
    .sk-wrap {
      background: linear-gradient(90deg, #141B2D 25%, #1E2D4A 50%, #141B2D 75%);
      background-size: 200% 100%;
      animation: shimmer 1.4s infinite;
      border-radius: 8px;
      display: block;
    }
    @keyframes shimmer { 0%{background-position:200% 0} 100%{background-position:-200% 0} }
  `]
})
export class SkeletonComponent {
  @Input() type   = 'rect';
  @Input() width  = '100%';
  @Input() height = '16px';
  @Input() radius = '8px';
}

@Component({
    selector: 'tv-skeleton-card',
    imports: [SkeletonComponent],
    template: `
    <div class="sk-card">
      <div class="sk-card-top">
        <tv-skeleton width="60px" height="12px" radius="4px"></tv-skeleton>
        <tv-skeleton width="80px" height="10px" radius="4px"></tv-skeleton>
      </div>
      <tv-skeleton width="120px" height="28px" radius="6px" style="margin:10px 0 6px"></tv-skeleton>
      <tv-skeleton width="80px" height="12px" radius="4px"></tv-skeleton>
      <tv-skeleton width="100%" height="36px" radius="8px" style="margin-top:14px"></tv-skeleton>
    </div>
  `,
    styles: [`
    .sk-card { background:#0F1525; border:1px solid #1E2D4A; border-radius:12px; padding:16px; }
    .sk-card-top { display:flex; justify-content:space-between; align-items:center; margin-bottom:6px; }
  `]
})
export class SkeletonCardComponent {}

@Component({
    selector: 'tv-skeleton-chart',
    imports: [SkeletonComponent],
    template: `
    <div class="sk-chart-wrap">
      <div class="sk-chart-header">
        <tv-skeleton width="140px" height="14px" radius="4px"></tv-skeleton>
        <tv-skeleton width="80px" height="14px" radius="4px"></tv-skeleton>
      </div>
      <div class="sk-chart-body">
        <!-- Fake candlestick bars -->
        <div class="sk-candles">
          @for (b of bars; track b) {
            <div class="sk-bar" [style.height.px]="b"></div>
          }
        </div>
      </div>
    </div>
    `,
    styles: [`
    .sk-chart-wrap { background:#0F1525; border:1px solid #1E2D4A; border-radius:12px; overflow:hidden; }
    .sk-chart-header { display:flex; justify-content:space-between; padding:12px 16px; border-bottom:1px solid #1A2340; }
    .sk-chart-body { height:300px; padding:16px; display:flex; align-items:flex-end; gap:3px; }
    .sk-candles { display:flex; align-items:flex-end; gap:3px; width:100%; height:100%; }
    .sk-bar { flex:1; background:linear-gradient(180deg,#1E2D4A,#141B2D); border-radius:2px 2px 0 0; animation: pulse 1.4s ease-in-out infinite alternate; }
    @keyframes pulse { 0%{opacity:0.4} 100%{opacity:0.9} }
  `]
})
export class SkeletonChartComponent {
  bars = Array.from({length:40}, () => Math.floor(Math.random()*220)+30);
}
