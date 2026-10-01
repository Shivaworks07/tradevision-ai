import { Injectable, inject } from '@angular/core';
import { Observable, forkJoin, of } from 'rxjs';
import { map, catchError } from 'rxjs/operators';
import { LiveDataService } from './live-data.service';
import { TaEngineService, TradeCall } from './ta-engine.service';

export interface FeaturedTrade {
  symbol:     string;
  name:       string;
  market:     'CRYPTO' | 'STOCK';
  timeframes: string[];    // best TFs for this asset
  call:       TradeCall | null;
  loading:    boolean;
  price:      number;
  change24h:  number;
  sidewaysMsg: string | null;
}

// ── Top 15 Crypto coins for scalp/intraday trading ────────
export const TOP_CRYPTO: Array<{symbol:string; name:string; tfs:string[]}> = [
  { symbol:'BTCUSDT',  name:'Bitcoin',        tfs:['15m','30m','1h'] },
  { symbol:'ETHUSDT',  name:'Ethereum',       tfs:['15m','30m','1h'] },
  { symbol:'BNBUSDT',  name:'BNB',            tfs:['5m','15m','30m'] },
  { symbol:'SOLUSDT',  name:'Solana',         tfs:['5m','15m','30m'] },
  { symbol:'XRPUSDT',  name:'XRP',            tfs:['5m','15m','30m'] },
  { symbol:'ADAUSDT',  name:'Cardano',        tfs:['15m','1h']       },
  { symbol:'DOTUSDT',  name:'Polkadot',       tfs:['15m','30m']      },
  { symbol:'AVAXUSDT', name:'Avalanche',      tfs:['5m','15m','30m'] },
  { symbol:'LINKUSDT', name:'Chainlink',      tfs:['5m','15m','30m'] },
  { symbol:'MATICUSDT',name:'Polygon',        tfs:['5m','15m']       },
  { symbol:'DOGEUSDT', name:'Dogecoin',       tfs:['5m','15m','30m'] },
  { symbol:'LTCUSDT',  name:'Litecoin',       tfs:['15m','30m','1h'] },
  { symbol:'ATOMUSDT', name:'Cosmos',         tfs:['15m','30m']      },
  { symbol:'NEARUSDT', name:'NEAR Protocol',  tfs:['5m','15m','30m'] },
  { symbol:'INJUSDT',  name:'Injective',      tfs:['5m','15m','30m'] },
];

// ── Top 15 NSE stocks for intraday trading ────────────────
export const TOP_STOCKS: Array<{symbol:string; name:string; tfs:string[]}> = [
  { symbol:'RELIANCE.NS', name:'Reliance Industries', tfs:['15m','30m','1h'] },
  { symbol:'TCS.NS',      name:'TCS',                 tfs:['15m','30m','1h'] },
  { symbol:'HDFCBANK.NS', name:'HDFC Bank',           tfs:['15m','30m','1h'] },
  { symbol:'INFY.NS',     name:'Infosys',             tfs:['15m','30m','1h'] },
  { symbol:'ICICIBANK.NS',name:'ICICI Bank',          tfs:['15m','30m','1h'] },
  { symbol:'SBIN.NS',     name:'State Bank of India', tfs:['15m','30m','1h'] },
  { symbol:'TATAMOTORS.NS',name:'Tata Motors',        tfs:['5m','15m','30m'] },
  { symbol:'BAJFINANCE.NS',name:'Bajaj Finance',      tfs:['15m','30m','1h'] },
  { symbol:'WIPRO.NS',    name:'Wipro',               tfs:['15m','30m','1h'] },
  { symbol:'AXISBANK.NS', name:'Axis Bank',           tfs:['5m','15m','30m'] },
  { symbol:'ADANIENT.NS', name:'Adani Enterprises',   tfs:['15m','30m']      },
  { symbol:'MARUTI.NS',   name:'Maruti Suzuki',       tfs:['15m','30m','1h'] },
  { symbol:'DRREDDY.NS',  name:'Dr. Reddy\'s',        tfs:['15m','30m']      },
  { symbol:'LT.NS',       name:'Larsen & Toubro',     tfs:['15m','30m','1h'] },
  { symbol:'ITC.NS',      name:'ITC',                 tfs:['5m','15m','30m'] },
];

const SIDEWAYS_MSGS = [
  'Market is ranging — wait for a breakout above resistance or below support before entering.',
  'No clear trend detected. Best to stay on the sidelines or reduce position size.',
  'Consolidation phase — watch for volume spike to confirm the next move.',
  'Price action is choppy. Higher risk of stop-loss hits. Patience is the trade right now.',
  'Sideways movement detected. Set alerts at key levels and re-analyze on breakout.',
];

@Injectable({ providedIn: 'root' })
export class FeaturedTradesService {
  private liveData = inject(LiveDataService);
  private taEngine = inject(TaEngineService);

  getFeaturedCrypto(limit = 10): FeaturedTrade[] {
    return TOP_CRYPTO.slice(0, limit).map(c => ({
      symbol: c.symbol, name: c.name, market: 'CRYPTO',
      timeframes: c.tfs, call: null, loading: true,
      price: 0, change24h: 0, sidewaysMsg: null,
    }));
  }

  getFeaturedStocks(limit = 10): FeaturedTrade[] {
    return TOP_STOCKS.slice(0, limit).map(s => ({
      symbol: s.symbol.replace('.NS',''), name: s.name, market: 'STOCK',
      timeframes: s.tfs, call: null, loading: true,
      price: 0, change24h: 0, sidewaysMsg: null,
    }));
  }

  loadCryptoSignal(trade: FeaturedTrade, tf: string): void {
    const sym = trade.symbol;
    this.liveData.getCryptoKlinesOnce(sym, this.mapTf(tf), 300).pipe(
      catchError(() => of([]))
    ).subscribe(candles => {
      if (!candles?.length) { trade.loading = false; return; }
      trade.price = candles[candles.length - 1].close;
      const call  = this.taEngine.analyze(candles, sym, tf);
      trade.call  = call;
      trade.loading = false;
      if (call.direction === 'WAIT') {
        trade.sidewaysMsg = SIDEWAYS_MSGS[Math.floor(Math.random() * SIDEWAYS_MSGS.length)];
      } else {
        trade.sidewaysMsg = null;
      }
    });
  }

  loadStockSignal(trade: FeaturedTrade, tf: string): void {
    const nse = trade.symbol + '.NS';
    const interval = tf === '1h' ? '60m' : tf === '30m' ? '30m' : tf === '15m' ? '15m' : '5m';
    this.liveData.getIndianKlinesOnce(nse, '5d', interval).pipe(
      catchError(() => of([]))
    ).subscribe(candles => {
      if (!candles?.length) { trade.loading = false; return; }
      trade.price = candles[candles.length - 1].close;
      const call  = this.taEngine.analyze(candles, nse, tf);
      trade.call  = call;
      trade.loading = false;
      if (call.direction === 'WAIT') {
        trade.sidewaysMsg = SIDEWAYS_MSGS[Math.floor(Math.random() * SIDEWAYS_MSGS.length)];
      } else {
        trade.sidewaysMsg = null;
      }
    });
  }

  private mapTf(tf: string): string {
    const m: Record<string,string> = { '5m':'5m','15m':'15m','30m':'30m','1h':'1h','4h':'4h' };
    return m[tf] || '15m';
  }
}
