import { Injectable, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { interval } from 'rxjs';
import { startWith, switchMap, catchError } from 'rxjs/operators';
import { of } from 'rxjs';
import { environment } from '../../environments/environment';

export type Currency = 'USD' | 'INR';

@Injectable({ providedIn: 'root' })
export class CurrencyService {
  currency = signal<Currency>('USD');
  usdToInr = signal<number>(83.42);
  rateSource = signal<string>('Default');

  constructor(private http: HttpClient) {
    this.startRateFetching();
  }

  private startRateFetching() {
    interval(120000).pipe(
      startWith(0),
      switchMap(() =>
        // Use proxy to avoid CORS - routes through Angular dev server
        this.http.get<any>('/forex-api/latest?from=USD&to=INR').pipe(
          catchError(() =>
            // Fallback to CoinGecko USDT/INR for rate
            this.http.get<any>(`${environment.coingeckoUrl}/api/v3/simple/price?ids=tether&vs_currencies=inr`).pipe(
              catchError(() => of(null))
            )
          )
        )
      )
    ).subscribe(data => {
      if (data?.rates?.INR) {
        this.usdToInr.set(parseFloat(data.rates.INR.toFixed(2)));
        this.rateSource.set('ECB via Proxy');
      } else if (data?.tether?.inr) {
        this.usdToInr.set(parseFloat(data.tether.inr.toFixed(2)));
        this.rateSource.set('CoinGecko');
      }
      // else keeps default 83.42
    });
  }

  toggle() { this.currency.set(this.currency() === 'USD' ? 'INR' : 'USD'); }
  symbol(): string { return this.currency() === 'INR' ? '₹' : '$'; }

  convert(usdPrice: number): number {
    return this.currency() === 'INR' ? usdPrice * this.usdToInr() : usdPrice;
  }

  formatPrice(usdPrice: number): string {
    const p = this.convert(usdPrice);
    const sym = this.symbol();
    if (this.currency() === 'INR') {
      if (p >= 10000000) return `${sym}${(p/10000000).toFixed(2)}Cr`;
      if (p >= 100000)   return `${sym}${(p/100000).toFixed(2)}L`;
      if (p >= 1000)     return `${sym}${p.toLocaleString('en-IN', {maximumFractionDigits:2})}`;
      if (p >= 1)        return `${sym}${p.toFixed(4)}`;
      return `${sym}${p.toFixed(6)}`;
    }
    if (p >= 10000) return `${sym}${p.toLocaleString('en-US', {maximumFractionDigits:2})}`;
    if (p >= 1)     return `${sym}${p.toFixed(4)}`;
    return `${sym}${p.toFixed(6)}`;
  }
}
