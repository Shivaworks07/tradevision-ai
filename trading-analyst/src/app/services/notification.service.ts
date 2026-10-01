import { Injectable, signal } from '@angular/core';

export interface TradeAlert {
  symbol:     string;
  signal:     string;
  direction:  string;
  confidence: number;
  price:      number;
  market?:    string;
  prevSignal?: string;
  timestamp:  number;
}

export interface NotificationPrefs {
  enabled:          boolean;  // master switch
  minConfidence:    number;   // 0-100, only alert above this
  directions:       ('LONG' | 'SHORT')[];   // BUY only, SELL only, or both
  markets:          ('CRYPTO' | 'STOCK' | 'FOREX')[];
  signalTypes:      string[]; // STRONG BUY, BUY, STRONG SELL, SELL, or all
  onlyOnChange:     boolean;  // only when signal changes, not every refresh
}

const PREFS_KEY = 'tv_notif_prefs';

const DEFAULT_PREFS: NotificationPrefs = {
  enabled:       true,
  minConfidence: 65,
  directions:    ['LONG','SHORT'],
  markets:       ['CRYPTO','STOCK','FOREX'],
  signalTypes:   ['STRONG BUY','BUY','STRONG SELL','SELL'],
  onlyOnChange:  true,
};

@Injectable({ providedIn: 'root' })
export class NotificationService {

  // ── Preferences (reactive signal) ────────────────────────
  readonly prefs = signal<NotificationPrefs>(this.loadPrefs());

  private loadPrefs(): NotificationPrefs {
    try {
      const stored = localStorage.getItem(PREFS_KEY);
      return stored ? { ...DEFAULT_PREFS, ...JSON.parse(stored) } : { ...DEFAULT_PREFS };
    } catch { return { ...DEFAULT_PREFS }; }
  }

  savePrefs(p: Partial<NotificationPrefs>) {
    const updated = { ...this.prefs(), ...p };
    this.prefs.set(updated);
    try { localStorage.setItem(PREFS_KEY, JSON.stringify(updated)); } catch {}
  }

  resetPrefs() { this.savePrefs(DEFAULT_PREFS); }

  // ── Permission ────────────────────────────────────────────
  async requestPermission(): Promise<boolean> {
    if (!('Notification' in window)) return false;
    if (Notification.permission === 'granted') return true;
    if (Notification.permission === 'denied')  return false;
    return (await Notification.requestPermission()) === 'granted';
  }

  get isPermitted(): boolean {
    return typeof Notification !== 'undefined' && Notification.permission === 'granted';
  }

  // ── History ───────────────────────────────────────────────
  private history: TradeAlert[] = [];
  getHistory(): TradeAlert[] { return this.history; }
  clearHistory() { this.history = []; }

  // ── Core notify — checks all preferences before firing ────
  notify(alert: TradeAlert): void {
    const p = this.prefs();

    // Check master switch
    if (!p.enabled) return;

    // Check min confidence
    if (alert.confidence < p.minConfidence) return;

    // Check direction filter
    const dir = alert.direction as 'LONG' | 'SHORT';
    if (!p.directions.includes(dir)) return;

    // Check market filter
    if (alert.market && !p.markets.includes(alert.market as any)) return;

    // Check signal type filter
    if (p.signalTypes.length && !p.signalTypes.includes(alert.signal)) return;

    // Store in history regardless of browser permission
    this.history.unshift(alert);
    if (this.history.length > 100) this.history.pop();

    // Browser push notification
    if (!this.isPermitted) return;
    const icon  = dir === 'LONG' ? '📈' : '📉';
    const title = `${icon} ${alert.signal} — ${alert.symbol}`;
    const body  = [
      `Confidence: ${alert.confidence}%`,
      alert.market ? `Market: ${alert.market}` : '',
      alert.prevSignal ? `Changed from: ${alert.prevSignal}` : '',
    ].filter(Boolean).join(' · ');

    try {
      const n = new Notification(title, {
        body, icon:'/favicon.svg', badge:'/favicon.svg',
        tag: `tradevision-${alert.symbol}`,
        requireInteraction: alert.confidence >= 85,
      });
      n.onclick = () => { window.focus(); n.close(); };
      setTimeout(() => n.close(), 12000);
    } catch {}
  }

  notifySignalChange(symbol: string, prevSignal: string, newSignal: string,
                     confidence: number, price: number, market?: string) {
    // If onlyOnChange pref is set, only alert when signal actually changed
    const p = this.prefs();
    if (p.onlyOnChange && prevSignal === newSignal) return;

    this.notify({
      symbol, signal: newSignal, price, confidence, market,
      direction: newSignal.includes('BUY') ? 'LONG' : 'SHORT',
      prevSignal, timestamp: Date.now(),
    });
  }
}
