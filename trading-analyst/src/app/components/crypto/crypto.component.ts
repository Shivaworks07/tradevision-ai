import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { CryptoTerminalComponent } from '../crypto-terminal/crypto-terminal.component';

@Component({
  selector: 'app-crypto',
  standalone: true,
  imports: [CommonModule, CryptoTerminalComponent],
  template: `
    <div class="section-container">
      <div class="section-header">
        <div class="section-icon" style="background:rgba(247,147,26,0.1);">₿</div>
        <div class="section-title">
          <h2>Crypto Trading Terminal</h2>
          <p>Real-time Binance OHLCV · Live Charts · LONG/SHORT Signals · RSI · MACD · EMA · BB · Stochastic · Patterns</p>
        </div>
        <span class="section-badge" style="background:rgba(247,147,26,0.1);color:#F7931A;border:1px solid rgba(247,147,26,0.2)">BINANCE LIVE</span>
      </div>
      <app-crypto-terminal></app-crypto-terminal>
    </div>
  `
})
export class CryptoComponent {}
