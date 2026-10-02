import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { OptionChainService, OptionChainData } from '../../services/option-chain.service';

@Component({
    selector: 'app-option-chain',
    imports: [CommonModule, FormsModule],
    templateUrl: './option-chain.component.html',
    styleUrls: ['./option-chain.component.scss']
})
export class OptionChainComponent implements OnInit {
  private ocSvc = inject(OptionChainService);

  readonly SYMBOLS = ['NIFTY','BANKNIFTY','RELIANCE','TCS','INFY','HDFCBANK','ICICIBANK','SBIN','BAJFINANCE','WIPRO'];

  selectedSymbol = 'NIFTY';
  selectedExpiry = '';
  data: OptionChainData | null = null;
  loading = false;
  error   = '';
  expanded = false;

  ngOnInit() { this.load(); }

  load() {
    this.loading = true; this.error = '';
    this.ocSvc.getOptionChain(this.selectedSymbol, this.selectedExpiry).subscribe({
      next: d => {
        this.data    = d;
        this.loading = false;
        if (!this.selectedExpiry && d.expiryDates.length) this.selectedExpiry = d.expiryDates[0];
      },
      error: e => { this.error = 'NSE data unavailable'; this.loading = false; }
    });
  }

  onExpiryChange() { this.load(); }
  onSymbolChange() { this.selectedExpiry = ''; this.load(); }

  pcrColor(pcr: number): string {
    if (pcr >= 1.3) return '#00FF88'; if (pcr <= 0.7) return '#FF3B5C'; return '#FFB800';
  }

  oiBar(oi: number, max: number): number { return max > 0 ? (oi / max) * 100 : 0; }

  get maxCEOI(): number { return Math.max(...(this.data?.strikes.map(s=>s.CE.oi)||[1])); }
  get maxPEOI(): number { return Math.max(...(this.data?.strikes.map(s=>s.PE.oi)||[1])); }

  fmt(n: number): string {
    if (n >= 10000000) return (n/10000000).toFixed(1)+'Cr';
    if (n >= 100000)   return (n/100000).toFixed(1)+'L';
    if (n >= 1000)     return (n/1000).toFixed(0)+'K';
    return n.toFixed(0);
  }
}
