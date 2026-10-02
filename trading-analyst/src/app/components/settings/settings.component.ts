import { Component, inject, effect } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { NotificationService, NotificationPrefs } from '../../services/notification.service';
import { AuthService } from '../../services/auth.service';

@Component({
    selector: 'app-settings',
    imports: [CommonModule, FormsModule],
    templateUrl: './settings.component.html',
    styleUrls: ['./settings.component.scss']
})
export class SettingsComponent {
  notif = inject(NotificationService);
  auth  = inject(AuthService);

  // Local copy to edit before saving
  prefs: NotificationPrefs = { ...this.notif.prefs() };
  saved = false;
  permStatus = '';

  readonly minConfOptions = [50, 60, 65, 70, 75, 80, 85, 90];
  readonly signalOptions  = [
    { label:'🔥 Strong Buy',  value:'STRONG BUY'  },
    { label:'▲ Buy',          value:'BUY'          },
    { label:'▼ Sell',         value:'SELL'         },
    { label:'🔻 Strong Sell', value:'STRONG SELL'  },
  ];
  readonly marketOptions  = [
    { label:'₿ Crypto',      value:'CRYPTO' as const },
    { label:'🇮🇳 Stocks',   value:'STOCK'  as const },
    { label:'💱 Forex',      value:'FOREX'  as const },
  ];

  // ── Checkbox toggles ─────────────────────────────────────
  toggleDir(dir: 'LONG'|'SHORT') {
    const idx = this.prefs.directions.indexOf(dir);
    if (idx >= 0) {
      if (this.prefs.directions.length > 1) // keep at least one
        this.prefs.directions = this.prefs.directions.filter(d => d !== dir);
    } else {
      this.prefs.directions = [...this.prefs.directions, dir];
    }
  }
  hasDir(dir: 'LONG'|'SHORT'): boolean { return this.prefs.directions.includes(dir); }

  toggleMarket(m: 'CRYPTO'|'STOCK'|'FOREX') {
    if (this.prefs.markets.includes(m)) {
      if (this.prefs.markets.length > 1)
        this.prefs.markets = this.prefs.markets.filter(x => x !== m);
    } else {
      this.prefs.markets = [...this.prefs.markets, m];
    }
  }
  hasMkt(m: 'CRYPTO'|'STOCK'|'FOREX'): boolean { return this.prefs.markets.includes(m); }

  toggleSignal(s: string) {
    if (this.prefs.signalTypes.includes(s)) {
      if (this.prefs.signalTypes.length > 1)
        this.prefs.signalTypes = this.prefs.signalTypes.filter(x => x !== s);
    } else {
      this.prefs.signalTypes = [...this.prefs.signalTypes, s];
    }
  }
  hasSig(s: string): boolean { return this.prefs.signalTypes.includes(s); }

  // ── Actions ───────────────────────────────────────────────
  async enableBrowserNotifs() {
    const ok = await this.notif.requestPermission();
    this.permStatus = ok ? 'enabled' : 'denied';
    if (ok) this.prefs.enabled = true;
  }

  save() {
    this.notif.savePrefs(this.prefs);
    this.saved = true;
    setTimeout(() => this.saved = false, 2500);
  }

  reset() {
    this.notif.resetPrefs();
    this.prefs = { ...this.notif.prefs() };
  }

  get browserPermission(): string { return typeof Notification !== 'undefined' ? Notification.permission : 'unsupported'; }
  get permColor(): string { return this.browserPermission === 'granted' ? '#00FF88' : this.browserPermission === 'denied' ? '#FF3B5C' : '#FFB800'; }
}
