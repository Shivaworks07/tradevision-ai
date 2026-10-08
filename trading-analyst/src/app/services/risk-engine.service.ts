import { Injectable } from '@angular/core';

/**
 * Client-side position-sizing calculator for the crypto-terminal risk panel. It only
 * tracks accountSize and riskPerTrade as manual estimate inputs — daily-loss limits,
 * consecutive-loss limits and max-open-trades are enforced entirely server-side
 * (RiskEngineService.java's RiskProfile, configured under Settings → Broker & Auto-Trade),
 * so this calculator deliberately does not duplicate or imply ownership of them.
 */
export interface RiskParameters {
  accountSize:      number;    // user's account size in USD -- a manual estimate input, never sent to or read from the server
  riskPerTrade:     number;    // % of account per trade (e.g. 1.0 = 1%) -- for this calculator's own sizing math only
}

export interface TradeRisk {
  // Position Sizing
  positionSize:       number;    // USD amount to risk
  positionSizePct:    number;    // % of account
  units:              number;    // qty (for crypto = coins, for stock = shares)
  // Levels
  entry:              number;
  stopLoss:           number;
  target1:            number;
  target2:            number;
  target3:            number;
  trailingStop:       number;    // initial trailing stop distance
  // Risk metrics
  riskAmount:         number;    // USD at risk
  riskPct:            number;    // % of account at risk
  rewardT1:           number;    // USD gain at T1
  rewardT2:           number;    // USD gain at T2
  rewardT3:           number;    // USD gain at T3
  rrRatioT1:          string;
  rrRatioT2:          string;
  rrRatioT3:          string;
  // Kelly
  kellyCriterion:     number;    // optimal bet size (%)
  kellyHalfSize:      number;    // half-Kelly (safer)
  // Assessment
  riskRating:         'EXCELLENT'|'GOOD'|'ACCEPTABLE'|'RISKY'|'AVOID';
  riskColor:          string;
  warnings:           string[];
  recommendations:    string[];
  // Breakdown
  slDistancePct:      number;    // SL distance as % from entry
  regimeMultiplier:   number;    // position size adjustment for regime
  summary:            string;
}

const DEFAULT_PARAMS: RiskParameters = {
  accountSize:     10000,   // starting value until the user edits it via the risk panel's account-size input
  riskPerTrade:    1.0,     // 1% per trade
};

@Injectable({ providedIn: 'root' })
export class RiskEngineService {

  private params: RiskParameters = { ...DEFAULT_PARAMS };

  constructor() { this.loadParams(); }

  // ── Calculate full risk for a trade ──────────────────────
  calculateRisk(
    entry: number,
    stopLoss: number,
    target1: number,
    target2: number,
    target3: number,
    atr: number,
    confidence: number,
    winRate: number,     // ML win rate 0-100, or 50 if unknown
    regimeMultiplier = 1.0
  ): TradeRisk {
    const direction = entry > stopLoss ? 'LONG' : 'SHORT';
    const slDist    = Math.abs(entry - stopLoss);
    const slDistPct = (slDist / entry) * 100;

    // Base position size from risk %
    const baseRiskUSD  = this.params.accountSize * (this.params.riskPerTrade / 100);

    // Adjust for regime
    const adjustedRiskUSD = baseRiskUSD * regimeMultiplier;

    // Adjust for confidence (scale from 0.5x at 50% conf to 1.3x at 90% conf)
    const confMultiplier = 0.5 + ((Math.min(confidence, 95) - 50) / 45) * 0.8;

    // Kelly Criterion: f* = (bp - q) / b
    // where b = odds (R multiple), p = win rate, q = 1-p
    const wr  = winRate / 100;
    const qr  = 1 - wr;
    const b   = Math.abs(target2 - entry) / slDist;  // R multiple (use T2)
    const kelly     = ((b * wr) - qr) / b;
    const kellyPct  = Math.max(0, Math.min(25, kelly * 100));
    const kellyHalf = kellyPct / 2;  // Half-Kelly is safer in practice

    // Final position size (capped at half-Kelly or 2x base risk)
    const kellyCap = this.params.accountSize * (Math.min(kellyHalf, this.params.riskPerTrade * 2) / 100);
    const finalRiskUSD = Math.min(adjustedRiskUSD * confMultiplier, kellyCap);

    // Units (position size in coin/shares)
    const units       = slDist > 0 ? finalRiskUSD / slDist : 0;
    const positionSize = units * entry;   // total $ exposure

    // P&L calculations
    const riskAmount = finalRiskUSD;
    const rewardT1   = Math.abs(target1 - entry) * units;
    const rewardT2   = Math.abs(target2 - entry) * units;
    const rewardT3   = Math.abs(target3 - entry) * units;

    // Trailing stop: start at 1x ATR behind entry after moving 1.5x ATR in profit
    const trailingStop = direction === 'LONG' ? entry - atr : entry + atr;

    // RR ratios
    const rrT1 = `1 : ${(rewardT1/riskAmount).toFixed(1)}`;
    const rrT2 = `1 : ${(rewardT2/riskAmount).toFixed(1)}`;
    const rrT3 = `1 : ${(rewardT3/riskAmount).toFixed(1)}`;

    // Risk assessment
    const { rating, color } = this.assessRisk(slDistPct, rewardT2/riskAmount, confidence, winRate);
    const warnings       = this.buildWarnings(slDistPct, confidence, winRate, regimeMultiplier);
    const recommendations= this.buildRecommendations(direction, slDistPct, atr, confidence, kelly, regimeMultiplier);

    const positionSizePct = (positionSize / this.params.accountSize) * 100;

    return {
      positionSize:    +positionSize.toFixed(2),
      positionSizePct: +positionSizePct.toFixed(1),
      units:           +units.toFixed(8),
      entry:           +entry.toFixed(8),
      stopLoss:        +stopLoss.toFixed(8),
      target1:         +target1.toFixed(8),
      target2:         +target2.toFixed(8),
      target3:         +target3.toFixed(8),
      trailingStop:    +trailingStop.toFixed(8),
      riskAmount:      +riskAmount.toFixed(2),
      riskPct:         +(finalRiskUSD/this.params.accountSize*100).toFixed(2),
      rewardT1:        +rewardT1.toFixed(2),
      rewardT2:        +rewardT2.toFixed(2),
      rewardT3:        +rewardT3.toFixed(2),
      rrRatioT1:       rrT1,
      rrRatioT2:       rrT2,
      rrRatioT3:       rrT3,
      kellyCriterion:  +kellyPct.toFixed(1),
      kellyHalfSize:   +kellyHalf.toFixed(1),
      slDistancePct:   +slDistPct.toFixed(2),
      regimeMultiplier: +regimeMultiplier.toFixed(2),
      riskRating:      rating,
      riskColor:       color,
      warnings, recommendations,
      summary: this.buildSummary(finalRiskUSD, positionSizePct, riskAmount, rrT2, rating, this.params)
    };
  }

  getParams():  RiskParameters { return { ...this.params }; }

  updateParams(p: Partial<RiskParameters>) {
    this.params = { ...this.params, ...p };
    this.saveParams();
  }

  // ── Private helpers ───────────────────────────────────────
  private assessRisk(slPct: number, rrRatio: number, confidence: number, winRate: number): { rating: TradeRisk['riskRating']; color: string } {
    let score = 0;
    if (slPct < 1)   score += 2; else if (slPct < 2) score += 1; else if (slPct > 4) score -= 2;
    if (rrRatio > 3) score += 2; else if (rrRatio > 2) score += 1; else if (rrRatio < 1.5) score -= 2;
    if (confidence > 75) score += 2; else if (confidence > 60) score += 1; else if (confidence < 50) score -= 2;
    if (winRate > 60) score += 1; else if (winRate < 40) score -= 1;

    const rating: TradeRisk['riskRating'] =
      score >= 5 ? 'EXCELLENT' : score >= 3 ? 'GOOD' : score >= 1 ? 'ACCEPTABLE' : score >= -1 ? 'RISKY' : 'AVOID';
    const colors = { EXCELLENT:'#00FF88', GOOD:'#00D4FF', ACCEPTABLE:'#FFB800', RISKY:'#FF9900', AVOID:'#FF3B5C' };
    return { rating, color: colors[rating] };
  }

  private buildWarnings(slPct: number, conf: number, wr: number, regimeMult: number): string[] {
    const w: string[] = [];
    if (slPct > 3)          w.push(`⚠️ Wide SL ${slPct.toFixed(1)}% — reduce position size`);
    if (conf < 55)          w.push('⚠️ Low confidence signal — consider skipping or halving size');
    if (wr < 40)            w.push(`⚠️ ML shows only ${wr.toFixed(0)}% win rate for this asset`);
    if (regimeMult < 0.7)   w.push('⚠️ Regime says reduce size — dangerous market conditions');
    return w;
  }

  private buildRecommendations(dir: string, slPct: number, atr: number, conf: number, kelly: number, regimeMult: number): string[] {
    const r: string[] = [];
    r.push(`Entry: ${dir === 'LONG' ? 'Limit buy at entry or market on confirmation' : 'Limit sell at entry or market on confirmation'}`);
    r.push(`SL: Place at 1.5×ATR (${(atr*1.5).toFixed(4)}) below/above entry — DO NOT widen after entry`);
    r.push(`T1 (50%): Take half off — locks in profit, removes emotion`);
    r.push(`T2 (30%): Move SL to breakeven after T1 hit — risk-free trade`);
    r.push(`T3 (20%): Trail with ATR — ride momentum to max`);
    if (kelly > 0) r.push(`Kelly suggests ${kelly.toFixed(1)}% account size — using safer half-Kelly`);
    return r;
  }

  private buildSummary(riskUSD: number, posPct: number, riskAmt: number, rr: string, rating: string, p: RiskParameters): string {
    return `Risk ${riskAmt.toFixed(0)} USD (${(riskAmt/p.accountSize*100).toFixed(1)}% of account). Position $${(riskUSD).toFixed(0)} (${posPct.toFixed(1)}% exposure). RR ${rr}. Rating: ${rating}. Account: $${p.accountSize.toLocaleString()}.`;
  }

  private loadParams() {
    try { const s = localStorage.getItem('tv_risk_params'); if (s) this.params = { ...DEFAULT_PARAMS, ...JSON.parse(s) }; } catch {}
  }
  private saveParams() {
    try { localStorage.setItem('tv_risk_params', JSON.stringify(this.params)); } catch {}
  }
}
