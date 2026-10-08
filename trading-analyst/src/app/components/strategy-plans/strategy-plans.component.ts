import { Component, inject, OnInit, signal } from '@angular/core';

import { FormsModule } from '@angular/forms';
import { BrokerService, BrokerCredentialResponse, RiskProfile } from '../../services/broker.service';
import { StrategyPlanService, StrategyPlan, StrategyPlanRequest, TradeDirection, SessionMode, EndOfSessionAction, DayOfWeek } from '../../services/strategy-plan.service';

/**
 * UI for creating and managing multi-strategy trading plans: per-plan scanning,
 * two-tier risk, exit-policy checks, and session scheduling. Follows
 * BrokerSettingsComponent's shape (plain FormsModule + ngModel, not ReactiveForms;
 * a `message` signal for inline success/error banners; a credential selector reusing
 * BrokerService, since a strategy plan always belongs to one specific broker credential).
 *
 * Duplicate-plan is intentionally not offered here: there is no backend endpoint for it,
 * and client-side duplication (fetch a plan, strip its id, re-POST it) would be new,
 * untested logic rather than a thin wrapper over an existing API.
 */
@Component({
    selector: 'app-strategy-plans',
    imports: [FormsModule],
    templateUrl: './strategy-plans.component.html',
    styleUrls: ['./strategy-plans.component.scss']
})
export class StrategyPlansComponent implements OnInit {
  broker = inject(BrokerService);
  plansService = inject(StrategyPlanService);

  credentials = signal<BrokerCredentialResponse[]>([]);
  selectedCredentialId = signal<string | null>(null);
  plans = signal<StrategyPlan[]>([]);
  // The account's actual risk ceiling, fetched once per credential selection, so the plan
  // list and create/edit form can show the effective risk (min(plan risk, account ceiling))
  // alongside whatever risk value the plan itself specifies.
  accountRiskProfile = signal<RiskProfile | null>(null);

  loading = signal(false);
  message = signal<{ type: 'ok' | 'error'; text: string } | null>(null);

  // Create/edit form state. Null editingId means "creating a new plan"; a real id means "editing that one".
  showForm = signal(false);
  editingId: string | null = null;
  form: StrategyPlanRequest = this.blankForm();

  readonly timeframes = ['1m', '3m', '5m', '15m', '30m', '1h', '2h', '4h', '6h', '12h', '1d'];
  readonly directions: TradeDirection[] = ['LONG', 'SHORT', 'BOTH'];
  readonly sessionModes: SessionMode[] = ['ALWAYS_ON', 'DAILY', 'CUSTOM_DAYS'];
  readonly endOfSessionActions: EndOfSessionAction[] = ['CLOSE_POSITIONS', 'KEEP_OPEN'];
  readonly allDays: DayOfWeek[] = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'];
  // Common IANA zones -- not exhaustive, but covers the user's own named examples plus the
  // common cases. A free-text fallback input is still offered in the template for anything else.
  readonly commonTimezones = ['UTC', 'Asia/Kolkata', 'America/New_York', 'Europe/London', 'Asia/Singapore', 'Asia/Tokyo', 'Australia/Sydney'];

  enabledSymbolsCsv = '';

  ngOnInit() {
    this.broker.list().subscribe({
      next: (r: any) => {
        this.credentials.set(r.data || []);
        if (r.data?.length) this.selectCredential(r.data[0].id);
      }
    });
  }

  private show(type: 'ok' | 'error', text: string) {
    this.message.set({ type, text });
    setTimeout(() => this.message.set(null), 6000);
  }

  private blankForm(): StrategyPlanRequest {
    return {
      credentialId: '',
      name: 'New Plan',
      timeframe: '15m',
      direction: 'LONG',
      enabledSymbols: [],
      dynamicUniverseEnabled: false,
      dynamicUniverseMaxSymbols: 10,
      riskPerTradePercent: 1.0,
      maxConcurrentTrades: 1,
      maxCapital: null,
      maxHoldMinutes: null,
      exitOnSignalReversal: false,
      exitOnRiskEmergency: true,
      minConfidence: 75,
      cooldownMinutes: 15,
      sessionMode: 'ALWAYS_ON',
      sessionStart: null,
      sessionEnd: null,
      sessionTimezone: 'UTC',
      sessionDays: [],
      endOfSessionAction: 'CLOSE_POSITIONS',
      enabled: true
    };
  }

  selectCredential(credentialId: string) {
    this.selectedCredentialId.set(credentialId);
    this.broker.getRiskProfile(credentialId).subscribe({
      next: (r: any) => this.accountRiskProfile.set(r.data || r || null),
      error: () => this.accountRiskProfile.set(null)
    });
    this.refreshPlans();
  }

  /** The actual value that will be used for this specific plan's own trades -- min(account, plan). Null account profile means "unknown yet", shown as-is rather than guessed. */
  effectiveRisk(plan: StrategyPlan): number | null {
    const account = this.accountRiskProfile();
    if (!account) return null;
    return Math.min(account.riskPerTradePercent, plan.riskPerTradePercent);
  }

  /** Same as effectiveRisk, but against the form's own in-progress (not-yet-saved) value -- Angular templates can't call the global Math object directly, so this is exposed as a component method instead. */
  effectiveFormRisk(): number | null {
    const account = this.accountRiskProfile();
    if (!account) return null;
    return Math.min(account.riskPerTradePercent, this.form.riskPerTradePercent);
  }

  /** The actual minimum confidence that will be enforced -- max(account, plan), since a HIGHER confidence requirement is the stricter one. */
  effectiveMinConfidence(plan: StrategyPlan): number | null {
    const account = this.accountRiskProfile();
    if (!account) return null;
    return Math.max(account.minConfidence, plan.minConfidence);
  }

  /** Same as effectiveMinConfidence, but against the form's own in-progress value. */
  effectiveFormMinConfidence(): number | null {
    const account = this.accountRiskProfile();
    if (!account) return null;
    return Math.max(account.minConfidence, this.form.minConfidence);
  }

  refreshPlans() {
    const credentialId = this.selectedCredentialId();
    if (!credentialId) return;
    this.loading.set(true);
    this.plansService.list(credentialId).subscribe({
      next: (r: any) => { this.plans.set(r.data || []); this.loading.set(false); },
      error: (e) => { this.show('error', e?.error?.message || 'Could not load strategy plans.'); this.loading.set(false); }
    });
  }

  openCreateForm() {
    const credentialId = this.selectedCredentialId();
    if (!credentialId) { this.show('error', 'Connect a broker credential first.'); return; }
    this.editingId = null;
    this.form = this.blankForm();
    this.form.credentialId = credentialId;
    this.enabledSymbolsCsv = '';
    this.showForm.set(true);
  }

  /**
   * Lets a user quickly start a new plan based on an existing one, e.g. cloning a
   * "1m Scalper" into a "5m Scalper". No backend duplicate endpoint is needed: this
   * pre-fills the CREATE form with the source plan's values (minus its id/enabled/
   * defaultPlan/timestamps, which a new plan must not inherit) and opens it as a normal
   * new-plan creation, so the user reviews and saves it themselves rather than a copy
   * silently appearing.
   */
  duplicatePlan(plan: StrategyPlan) {
    this.editingId = null; // duplicating always creates a NEW plan, never edits the source
    this.form = {
      credentialId: plan.credentialId, name: plan.name + ' (copy)', timeframe: plan.timeframe, direction: plan.direction,
      enabledSymbols: [...(plan.enabledSymbols || [])], dynamicUniverseEnabled: plan.dynamicUniverseEnabled,
      dynamicUniverseMaxSymbols: plan.dynamicUniverseMaxSymbols, riskPerTradePercent: plan.riskPerTradePercent,
      maxConcurrentTrades: plan.maxConcurrentTrades, maxCapital: plan.maxCapital, maxHoldMinutes: plan.maxHoldMinutes,
      exitOnSignalReversal: plan.exitOnSignalReversal, exitOnRiskEmergency: plan.exitOnRiskEmergency,
      minConfidence: plan.minConfidence, cooldownMinutes: plan.cooldownMinutes, sessionMode: plan.sessionMode,
      sessionStart: plan.sessionStart, sessionEnd: plan.sessionEnd, sessionTimezone: plan.sessionTimezone,
      sessionDays: [...(plan.sessionDays || [])], endOfSessionAction: plan.endOfSessionAction,
      enabled: true // a duplicate always starts enabled, regardless of the source's own current state
    };
    this.enabledSymbolsCsv = (plan.enabledSymbols || []).join(',');
    this.showForm.set(true);
  }

  openEditForm(plan: StrategyPlan) {
    this.editingId = plan.id;
    // Spread rather than reuse the same object reference -- editing the form must never mutate
    // the list's own displayed copy until the user actually saves.
    this.form = {
      credentialId: plan.credentialId, name: plan.name, timeframe: plan.timeframe, direction: plan.direction,
      enabledSymbols: [...(plan.enabledSymbols || [])], dynamicUniverseEnabled: plan.dynamicUniverseEnabled,
      dynamicUniverseMaxSymbols: plan.dynamicUniverseMaxSymbols, riskPerTradePercent: plan.riskPerTradePercent,
      maxConcurrentTrades: plan.maxConcurrentTrades, maxCapital: plan.maxCapital, maxHoldMinutes: plan.maxHoldMinutes,
      exitOnSignalReversal: plan.exitOnSignalReversal, exitOnRiskEmergency: plan.exitOnRiskEmergency,
      minConfidence: plan.minConfidence, cooldownMinutes: plan.cooldownMinutes, sessionMode: plan.sessionMode,
      sessionStart: plan.sessionStart, sessionEnd: plan.sessionEnd, sessionTimezone: plan.sessionTimezone,
      sessionDays: [...(plan.sessionDays || [])], endOfSessionAction: plan.endOfSessionAction, enabled: plan.enabled
    };
    this.enabledSymbolsCsv = (plan.enabledSymbols || []).join(',');
    this.showForm.set(true);
  }

  cancelForm() {
    this.showForm.set(false);
    this.editingId = null;
  }

  toggleSessionDay(day: DayOfWeek) {
    const i = this.form.sessionDays.indexOf(day);
    if (i >= 0) this.form.sessionDays.splice(i, 1); else this.form.sessionDays.push(day);
  }

  save() {
    this.form.enabledSymbols = this.enabledSymbolsCsv.split(',').map(s => s.trim().toUpperCase()).filter(Boolean);
    // The backend is the actual source of truth for session-field validation (StrategyPlanService
    // .validateSessionConfig) -- this is just a same-shape client-side check so a user gets
    // immediate feedback instead of waiting on a round-trip for an obviously incomplete form.
    if (this.form.sessionMode !== 'ALWAYS_ON' && (!this.form.sessionStart || !this.form.sessionEnd)) {
      this.show('error', 'Session start and end times are required unless the session mode is 24/7.');
      return;
    }
    if (this.form.sessionMode === 'CUSTOM_DAYS' && this.form.sessionDays.length === 0) {
      this.show('error', 'Select at least one day for a custom-days session.');
      return;
    }

    const req$ = this.editingId
      ? this.plansService.update(this.editingId, this.form)
      : this.plansService.create(this.form);
    req$.subscribe({
      next: (r: any) => {
        this.show('ok', r.message || 'Strategy plan saved.');
        this.showForm.set(false);
        this.editingId = null;
        this.refreshPlans();
      },
      error: (e) => this.show('error', e?.error?.message || 'Could not save strategy plan.')
    });
  }

  toggleEnabled(plan: StrategyPlan) {
    this.plansService.setEnabled(plan.id, !plan.enabled).subscribe({
      next: (r: any) => { this.show('ok', r.message || 'Updated.'); this.refreshPlans(); },
      error: (e) => this.show('error', e?.error?.message || 'Could not update plan status.')
    });
  }

  deletePlan(plan: StrategyPlan) {
    if (!confirm(`Delete strategy plan "${plan.name}"? This does not close any positions it may still have open.`)) return;
    this.plansService.delete(plan.id).subscribe({
      next: () => { this.show('ok', 'Strategy plan removed.'); this.refreshPlans(); },
      error: (e) => this.show('error', e?.error?.message || 'Could not delete strategy plan.')
    });
  }
}
