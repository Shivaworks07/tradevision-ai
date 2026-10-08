import { Routes } from '@angular/router';
import { authGuard } from './guards/auth.guard';

export const routes: Routes = [
  {
    path: '',
    loadComponent: () => import('./components/landing/landing.component').then(m => m.LandingComponent)
  },
  {
    path: 'app',
    // canActivate runs before navigation completes, so the authenticated shell and
    // everything it lazy-loads as children never starts initializing until authGuard has
    // confirmed isLoggedIn. The backend remains the actual security boundary regardless;
    // this guard is purely about not showing authenticated UI prematurely.
    canActivate: [authGuard],
    loadComponent: () => import('./components/app-shell/app-shell.component').then(m => m.AppShellComponent),
    children: [
      { path: '', redirectTo: 'stocks', pathMatch: 'full' },
      { path: 'stocks',   loadComponent: () => import('./components/indian-market/indian-market.component').then(m => m.IndianMarketComponent) },
      { path: 'forex',    loadComponent: () => import('./components/forex/forex.component').then(m => m.ForexComponent) },
      { path: 'crypto',   loadComponent: () => import('./components/crypto-terminal/crypto-terminal.component').then(m => m.CryptoTerminalComponent) },
      { path: 'ipo',      loadComponent: () => import('./components/ipo/ipo.component').then(m => m.IpoComponent) },
      { path: 'backtest',  loadComponent: () => import('./components/backtest/backtest.component').then(m => m.BacktestComponent) },
      { path: 'analytics', loadComponent: () => import('./components/analytics/analytics.component').then(m => m.AnalyticsComponent) },
      { path: 'replay',    loadComponent: () => import('./components/replay/replay.component').then(m => m.ReplayComponent) },
      { path: 'accuracy',  loadComponent: () => import('./components/accuracy/accuracy.component').then(m => m.AccuracyComponent) },
      { path: 'watchlist', loadComponent: () => import('./components/watchlist/watchlist.component').then(m => m.WatchlistComponent) },
      { path: 'admin',     loadComponent: () => import('./components/admin/admin.component').then(m => m.AdminComponent) },
      { path: 'settings',  loadComponent: () => import('./components/settings/settings.component').then(m => m.SettingsComponent) },
      { path: 'broker',    loadComponent: () => import('./components/broker-settings/broker-settings.component').then(m => m.BrokerSettingsComponent) },
      // Multi-strategy-plan management UI, full context in the backend's StrategyPlan class
      // javadoc. Reachable at /app/strategy-plans.
      { path: 'strategy-plans', loadComponent: () => import('./components/strategy-plans/strategy-plans.component').then(m => m.StrategyPlansComponent) },
      // Position/execution dashboard. Reachable at /app/positions.
      { path: 'positions', loadComponent: () => import('./components/positions/positions.component').then(m => m.PositionsComponent) },
      { path: 'news',      loadComponent: () => import('./components/newsroom/newsroom.component').then(m => m.NewsroomComponent) },
    ]
  },
  { path: '**', redirectTo: '' }
];
