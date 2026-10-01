import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FearGreedService, FearGreedData } from '../../services/fear-greed.service';

@Component({
  selector: 'app-fear-greed',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './fear-greed.component.html',
  styleUrls: ['./fear-greed.component.scss']
})
export class FearGreedComponent implements OnInit {
  fgSvc = inject(FearGreedService);
  data: FearGreedData | null = null;
  loading = true;

  ngOnInit() {
    this.fgSvc.get().subscribe({ next: d => { this.data = d; this.loading = false; }, error: () => this.loading = false });
  }

  // Gauge arc calculation (SVG)
  get gaugeArc(): string {
    if (!this.data) return '';
    const val    = this.data.value / 100;
    const r      = 70; const cx = 90; const cy = 90;
    const startAngle = Math.PI;
    const endAngle   = startAngle + val * Math.PI;
    const x1 = cx + r * Math.cos(startAngle);
    const y1 = cy + r * Math.sin(startAngle);
    const x2 = cx + r * Math.cos(endAngle);
    const y2 = cy + r * Math.sin(endAngle);
    const large = val > 0.5 ? 1 : 0;
    return `M${x1},${y1} A${r},${r} 0 ${large},1 ${x2},${y2}`;
  }

  getZoneColor(val: number): string { return this.fgSvc.getColor(val); }
  formatDate(ts: number): string { return new Date(ts).toLocaleDateString('en-IN', {day:'2-digit',month:'short'}); }
}
