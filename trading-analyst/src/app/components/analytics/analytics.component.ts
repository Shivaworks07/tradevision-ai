import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { AnalyticsTableComponent } from '../analytics-table/analytics-table.component';
import { AnalyticsService } from '../../services/analytics.service';

@Component({
  selector: 'app-analytics',
  standalone: true,
  imports: [CommonModule, AnalyticsTableComponent],
  templateUrl: './analytics.component.html',
  styleUrls: ['./analytics.component.scss']
})
export class AnalyticsComponent implements OnInit {
  Math = Math;

  readonly pythonQuickstart = `import pandas as pd
from xgboost import XGBClassifier
from sklearn.preprocessing import LabelEncoder

# Load — skip comment line (dataset metadata)
df = pd.read_csv('tradevision_dataset.csv', comment='#')

# Quality filter — exclude noisy rows
df = df[df['quality_score'] >= 80]

# Label — only resolved calls
df = df[df['result'].notna() & ~df['result'].isin(['PENDING','EXPIRED'])]
df['target'] = (df['result'].str.startswith('HIT_T')).astype(int)

# Encode categoricals
for col in ['trendEMA','regime','smc_bias','vp_location','market_session']:
    if col in df.columns:
        df[col] = LabelEncoder().fit_transform(df[col].fillna('UNKNOWN'))

features = ['rsi','adx','macd_bull','volume_ratio','bos','fear_greed',
            'smc_bias','vp_location','regime','rule_score',
            'day_of_week','hour_of_day']

X = df[[f for f in features if f in df.columns]]
y = df['target']

model = XGBClassifier(n_estimators=200, max_depth=5, learning_rate=0.05)
model.fit(X, y)

# Print feature importance
importance = dict(zip(X.columns, model.feature_importances_))
for feat, imp in sorted(importance.items(), key=lambda x: -x[1]):
    print(f"{feat:25} {imp:.3f}")`;
  fmt(s: string): string { return s ? s.replace(/_/g,' ') : ''; }
  absVal(n: number): number { return Math.abs(n); }
  downloadDataset(fmt: 'csv'|'json' = 'csv') { this.svc.downloadDataset(fmt); }

  // Equity curve SVG path
  equityPath(equity: number[]): string {
    if (!equity?.length) return '';
    const w = 300, h = 80;
    const min = Math.min(...equity, 98);
    const max = Math.max(...equity, 102);
    const range = max - min || 1;
    return equity.map((v, i) => {
      const x = (i / (equity.length - 1)) * w;
      const y = h - ((v - min) / range) * (h - 4) - 2;
      return `${i === 0 ? 'M' : 'L'}${x.toFixed(1)},${y.toFixed(1)}`;
    }).join(' ');
  }

  equityColor(equity: number[]): string {
    if (!equity?.length) return '#4A5568';
    return equity[equity.length - 1] >= 100 ? '#00FF88' : '#FF3B5C';
  }

  maxEquity(strategies: any[]): number {
    return Math.max(...strategies.map(s => s.equity?.length || 0), 1);
  }
  asArray(obj: any): any[] { if (!obj) return []; return Object.values(obj).map((v: any) => ({...v, expectedWR: Math.round(((+v.bucket?.split('-')[0]||0) + (+v.bucket?.split('-')[1]||0)) / 2)})); }
  private svc = inject(AnalyticsService);
  featureImportance: any[] = [];
  correlation:  any = null;
  comparison:   any[] = [];

  analytics: any = null;
  calibration: any = null;
  walkForward: any = null;
  loading = true;
  activeTab: 'overview'|'regime'|'confidence'|'smc'|'symbol'|'calibration'|'walkforward'|'features'|'export'|'correlation'|'comparison' = 'overview';

  ngOnInit() {
    this.svc.getAnalytics().subscribe(r => {
      this.analytics = r?.data;
      this.featureImportance = this.svc.calcFeatureImportance(this.analytics);
      this.loading = false;
    });
    this.svc.getCalibration().subscribe(r => this.calibration = r?.data);
    this.svc.getWalkForward().subscribe(r => this.walkForward = r?.data);
    this.svc.getCorrelation().subscribe(r => this.correlation = r?.data);
    this.svc.getStrategyComparison().subscribe(r => this.comparison = r?.data || []);
  }

  pfColor(pf: number): string {
    if (pf >= 2.0) return '#00FF88';
    if (pf >= 1.5) return '#00D4FF';
    if (pf >= 1.2) return '#FFB800';
    if (pf >= 1.0) return '#FF9900';
    return '#FF3B5C';
  }

  wrColor(wr: number): string {
    if (wr >= 60) return '#00FF88';
    if (wr >= 50) return '#FFB800';
    return '#FF3B5C';
  }

  calGapColor(gap: number): string {
    return Math.abs(gap) <= 5 ? '#00FF88' : Math.abs(gap) <= 15 ? '#FFB800' : '#FF3B5C';
  }

  maxPF(rows: any[]): number {
    return Math.max(...(rows||[]).map((r:any) => r.profitFactor || 0), 1);
  }
  pfBar(pf: number, max: number): number { return max > 0 ? Math.min((pf/max)*100, 100) : 0; }
  overfitColor(s: string): string {
    if (!s) return '#8895B3';
    if (s.startsWith('LOW'))      return '#00FF88';
    if (s.startsWith('MODERATE')) return '#FFB800';
    return '#FF3B5C';
  }
}
