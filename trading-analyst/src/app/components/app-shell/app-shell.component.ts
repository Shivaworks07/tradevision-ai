import { Component, inject, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule, Router, NavigationEnd } from '@angular/router';
import { filter } from 'rxjs/operators';
import { AuthService } from '../../services/auth.service';
import { CurrencyService } from '../../services/currency.service';
import { IconComponent } from '../../shared/icon.component';
import { FeedbackComponent } from '../feedback/feedback.component';
import { NotificationService, NotificationPrefs } from '../../services/notification.service';

@Component({
  selector: 'app-shell',
  standalone: true,
  imports: [CommonModule, RouterModule, IconComponent, FeedbackComponent],
  templateUrl: './app-shell.component.html',
  styleUrls: ['./app-shell.component.scss']
})
export class AppShellComponent implements OnInit {
  auth     = inject(AuthService);
  currency = inject(CurrencyService);
  router   = inject(Router);
  notif    = inject(NotificationService);
  get notifPrefs() { return this.notif.prefs(); }

  togglePrefArr(key: 'directions'|'markets'|'signalTypes', value: string) {
    const arr = [...(this.notif.prefs()[key] as string[])];
    const idx = arr.indexOf(value);
    if (idx >= 0) { if (arr.length > 1) arr.splice(idx, 1); }
    else arr.push(value);
    this.notif.savePrefs({ [key]: arr } as any);
  }
  updatePref(key: keyof NotificationPrefs, value: any) {
    this.notif.savePrefs({ [key]: value });
  }

  showNotifPanel = false;
  notifTab: 'alerts'|'prefs' = 'alerts';
  get isAdmin(): boolean {
    const u = this.auth.currentUser();
    return (u as any)?.role === 'ADMIN';
  }

  enableNotifications() {
    this.notif.requestPermission().then(ok => {
      if (ok) this.showNotifPanel = false;
    });
  }
  toggleNotifPanel() { this.showNotifPanel = !this.showNotifPanel; }
  get alertHistory() { return this.notif.getHistory().slice(0, 10); }
  clearAlerts() { this.notif.clearHistory(); }

  currentRoute = '/app/stocks';
  menuOpen     = false;

  readonly navItems = [
    { label: 'Indian Stocks', icon: 'stocks',  route: '/app/stocks'   },
    { label: 'Forex',         icon: 'globe',   route: '/app/forex'    },
    { label: 'Crypto',        icon: 'crypto',  route: '/app/crypto'   },
    { label: 'IPO',           icon: 'ipo',     route: '/app/ipo'      },
    { label: 'Backtest',      icon: 'backtest', route: '/app/backtest' },
    { label: 'Analytics',     icon: 'brain',    route: '/app/analytics' },
    { label: 'Replay',        icon: 'history',  route: '/app/replay' },
    { label: 'Watchlist',     icon: 'heart',    route: '/app/watchlist' },
    { label: 'News',          icon: 'news',     route: '/app/news' },
    { label: 'Strategy Plans', icon: 'brain',   route: '/app/strategy-plans' },
    { label: 'Settings',      icon: 'settings', route: '/app/settings' },
    { label: 'Track Record',  icon: 'star',     route: '/app/accuracy' },
  ];

  ngOnInit() {
    if (!this.auth.isLoggedIn) { this.router.navigate(['/']); return; }
    this.auth.refreshProfile().subscribe();
    this.router.events
      .pipe(filter(e => e instanceof NavigationEnd))
      .subscribe((e: any) => { this.currentRoute = e.urlAfterRedirects; this.menuOpen = false; });
    this.currentRoute = this.router.url;
  }

  navigate(route: string) { this.router.navigate([route]); }
  isActive(r: string) { return this.currentRoute.startsWith(r); }
  logout() { this.auth.logout(); this.router.navigate(['/']); }
}
