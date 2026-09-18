# 12 — New Trading Strategies

## Current Performance Baseline (VPS, 2026-04-20)

Win rates from live production data (VPS is authoritative — ~3x more trades than local):

| Strategy | VPS WR | VPS Trades | Category |
|---|---|---|---|
| **CCI** | **61.7%** | 60 | Oscillator ✅ |
| **STOCH_RSI** | **55.3%** | 114 | Oscillator ✅ |
| BOLLINGER | 45.7% | 46 | Mean reversion ✅ |
| EMA_CROSSOVER | 41.3% | 46 | Crossover 🟡 |
| ADX_DI | 40.0% | 15 | Trend 🟡 |
| MFI | 37.0% | 27 | Oscillator 🟡 |
| PARABOLIC_SAR | 32.5% | 77 | Trend 🟡 |
| MACD | 27.5% | 91 | Crossover ❌ |
| RSI_MOMENTUM | 23.1% | 26 | Oscillator ❌ |
| TRIPLE_EMA | 20.3% | 59 | Crossover ❌ |
| ICHIMOKU | 18.2% | 11 | Trend ❌ |

**What the data tells us:**
- Oscillators with sharp overbought/oversold thresholds (CCI, STOCH_RSI) consistently outperform
- Simple MA crossovers (TRIPLE_EMA, MACD) are the worst performers — they generate too many signals in crypto's choppy 15m price action
- RSI_MOMENTUM has collapsed from its early peak — small sample bias; not actually an edge
- BOLLINGER recovered to 45.7% — the "bleeding" period was early noise, not structural

---

## What to Fix Before Adding Anything New

1. **MACD** — restrict to 4h+ only. At 15m it has 70 trades at ~27% WR across all pairs; at 1h BTC is 40% but ETH/SOL drag it down. It's a trend tool in a scalping slot.
2. **RSI_MOMENTUM** — consider suspending until a clear market-regime filter is added. A naked RSI threshold generates entries in both trending and ranging markets equally.
3. **TRIPLE_EMA** — 20.3% WR across 59 trades. Three moving averages add latency, not accuracy. Strong case for removal.
4. **ICHIMOKU** — 18.2% WR, 11 trades. Still too few signals; parameters too tight for crypto volatility. Tune or remove.
5. **Do NOT disable BOLLINGER** — it recovered to 45.7%. The earlier loss was small-sample.

---

## Strategies to Implement (Revised Priority Order)

### 1. Supertrend ⭐⭐
- **What:** ATR-based line that flips above/below price to signal BUY/SELL on trend change
- **Why:** Directly addresses MACD's weakness — ATR scaling means less whipsaw in volatile crypto. Single signal type (flip), no ambiguity. Used as replacement for MACD-style crossovers.
- **Key params:** ATR period (10), multiplier (3.0) — standard crypto starting point
- **Expected edge:** Less frequent but cleaner signals vs MACD; works on all intervals
- **Priority:** High — MACD needs a worthy successor

### 2. Squeeze Momentum ⭐⭐
- **What:** Bollinger Bands inside Keltner Channels = market "squeeze" (coiling). When Bollinger expands outside Keltner, momentum burst fires. Entry direction from momentum histogram.
- **Why:** Combines the two mean-reversion strategies we already run — Bollinger (45.7%) and Keltner logic — into a volatility breakout signal. Trades the expansion, not the mean. Naturally quiet in choppy markets (no squeeze = no trade).
- **Key params:** BB period (20, 2.0 std), Keltner (20, 1.5 ATR)
- **Expected edge:** Better signal selectivity than standalone Bollinger; targets the breakout moment, not arbitrary band touch

### 3. Connors RSI ⭐⭐
- **What:** Composite of three components — short RSI(3), RSI of consecutive up/down streak length, and percentile rank of today's return vs last 100 days. Outputs 0–100.
- **Why:** RSI_MOMENTUM runs a plain 14-period RSI and is at 23.1% WR — worst oscillator. Connors RSI uses 3-period RSI so it's far more responsive, the streak component penalises extended runs (mean reversion bias), and the percentile rank adapts to the asset's own distribution. Directly replaces RSI_MOMENTUM.
- **Key params:** RSI period (3), streak period (2), percentile lookback (100), oversold (10), overbought (90)
- **Expected edge:** In the same oscillator family as CCI/STOCH_RSI which are the top performers; more signal precision than plain RSI

### 4. Ultimate Oscillator ⭐
- **What:** Weighted average of buying pressure across three timeframes (7, 14, 28 periods). Reduces false divergences from single-period oscillators.
- **Why:** CCI (61.7%) and STOCH_RSI (55.3%) are the two best performers — both are oscillators. The Ultimate Oscillator extends that family with multi-period smoothing. Likely outperforms MFI (37%) which is volume-weighted but single-period.
- **Key params:** Short (7), medium (14), long (28), weights (4, 2, 1), oversold (30), overbought (70)
- **Expected edge:** Fewer whipsaws than single-period oscillators; volume-aware like MFI but less noisy

### 5. VWAP Deviation Bands (15m / 1h only) ⭐
- **What:** Daily VWAP ± N standard deviations of volume-weighted price. Trade reversals when price hits ±2σ band; use VWAP cross as trend confirmation.
- **Why:** The existing RSI+VWAP idea was sound but VWAP as a daily anchor only makes sense intraday. Scoped strictly to 15m and 1h intervals. Adds genuine volume-weighted context that none of our current strategies use.
- **Key params:** Resets daily, bands at ±1.5σ and ±2.5σ, only trade at outer band
- **Constraint:** Must be disabled for 4h / 1d / 1w intervals — VWAP anchor loses meaning beyond intraday

---

## Dropped From Previous Plan

| Strategy | Reason dropped |
|---|---|
| **Williams %R** | STOCH_RSI is 55.3% — replacing your 2nd-best performer makes no sense |
| **HMA Crossover** | EMA_CROSSOVER is 41.3% and stable; HMA is marginal gain, not a step change |
| **Keltner Channel (standalone)** | Superseded by Squeeze Momentum which uses Keltner as a component; no need for both |

---

## Implementation Notes

Each strategy lives in:
`revolut-trading-bot/src/main/java/com/stefo/revolut_trading_bot/strategy/impl/`

Steps per strategy:
1. Create `XxxStrategy.java` implementing `TradingStrategy`
2. Add enum value to `StrategyName`
3. Register as `@Component`
4. Test on paper with 15m + 1h intervals first (except VWAP — 15m/1h only)
5. Monitor for minimum 50 trades before drawing conclusions
