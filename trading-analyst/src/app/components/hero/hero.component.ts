import { Component, OnInit, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';

@Component({
  selector: 'app-hero',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './hero.component.html',
  styleUrls: ['./hero.component.scss']
})
export class HeroComponent implements OnInit, OnDestroy {
  tickers = [
    { sym: 'RELIANCE', val: '+1.22%', up: true },
    { sym: 'BTC', val: '+1.87%', up: true },
    { sym: 'EUR/USD', val: '+0.32%', up: true },
    { sym: 'TCS', val: '-0.73%', up: false },
    { sym: 'ETH', val: '-1.26%', up: false },
    { sym: 'ONDO', val: '+7.11%', up: true },
    { sym: 'NIFTY50', val: '+0.94%', up: true },
    { sym: 'GBP/USD', val: '-0.44%', up: false },
    { sym: 'SOL', val: '+5.25%', up: true },
    { sym: 'SENSEX', val: '+0.81%', up: true },
    { sym: 'USD/INR', val: '+0.22%', up: true },
    { sym: 'FET', val: '-4.35%', up: false },
  ];
  stats = [
    { label: 'Instruments Tracked', value: '500+' },
    { label: 'Analysis Accuracy', value: '78%' },
    { label: 'Markets Covered', value: '4' },
    { label: 'Indicators Used', value: '12+' },
  ];
  private interval: any;
  time = new Date();

  ngOnInit() {
    this.interval = setInterval(() => { this.time = new Date(); }, 1000);
  }
  ngOnDestroy() { clearInterval(this.interval); }

  scrollTo(id: string) {
    document.getElementById(id)?.scrollIntoView({ behavior: 'smooth' });
  }
}
