# Historical research diagnostics and remaining acceptance gates

`quant-validation` consumes a reviewed monthly return panel and generates diagnostic evidence.
It does not retrieve licensed history, manufacture delisting returns, or approve a model.
The final historical period has not been opened. The frozen manifest is unchanged.

The repository contains a dated current-universe snapshot and synthetic regression fixtures,
not 15-20 years of complete point-in-time index membership and security outcomes. Consequently
phase 11's historical acceptance remains open. Even passing diagnostic statistics are
insufficient without the independent evidence listed in the generated report.

## Input contract

Each CSV row is one scored stable instrument in a month-end cross-section. Required fields:

| Fields | Meaning |
|---|---|
| `signal_at`, `knowledge_at` | Month-end signal close and latest input availability/observation cutoff; knowledge must not exceed signal |
| `execution_at`, `next_session_open` | Execution timestamp and independently sourced next regular-session open; must be equal and after signal |
| `exit_at` | Next rebalance execution open; intervals must join across months |
| `instrument_id`, `profile`, `sector` | Stable identity and classifications known at the signal cutoff |
| `score`, `market_cap` | Frozen-model score and positive point-in-time capitalization |
| `total_return` | Open-to-open total security return, incorporating splits, cash dividends, mergers and terminal/delisting outcomes |
| `forward_6m`, `forward_end` | Six-month forward total return and its actual end timestamp |
| `expected_count` | Reconciled expected union count, repeated consistently for the month |

All timestamps must be explicitly UTC/timezone-aware in prepared inputs. The source adapter
must verify calendar identity and economic return lineage; equal timestamp fields alone are
not proof of correctness. Rows need reviewed corporate-action/dividend/delisting reconciliation,
including securities that disappear. Missing prices/outcomes must not be dropped to improve
results. Stable IDs, not current ticker strings, carry returns through symbol changes.

Evidence JSON includes `model_manifest_sha256`, `candidate` (currently `primary`),
`universe_mode` and `synthetic`. Use `CURRENT_CONSTITUENTS_BACKTEST` for exploratory current
snapshots. `POINT_IN_TIME_LICENSED` is a provenance claim requiring external license/source
evidence; merely changing that string never makes `production_ready` true.

## Evaluation

```powershell
.\research-engine\.venv\Scripts\python.exe -m quant_research.validation --panel panel.csv --evidence evidence.json --output validation.json
```

The evaluator partitions ordered monthly signals 60/20/20, defaults to validation, and
requires `--partition final --open-final-test` to evaluate the holdout. Default validation does
not inspect final-partition outcomes. Keep actual holdout outcomes under separate access
control until formulas, costs, candidate and experiments have been preregistered.
Six-month IC labels crossing the evaluated partition boundary are purged. Annual expanding
training/test windows are supplied with a six-month training embargo; actual walk-forward
model refits and candidate selection remain separately controlled research work.

Portfolio views are top 20, all five quintiles (Q1 highest scores), equal-weight and
capitalization-weighted universe, and a sector-neutral top-quintile view whose sector budgets
match eligible-universe equal weights. Holdings drift with realized returns between rebalances.
Costs apply on both buys and sells at 5/10/25/50 bps per side, with 10 bps baseline. Entry costs
are included; terminal forced liquidation is not assumed because the final interval ends at
a rebalance open. The top-minus-bottom result is a diagnostic difference between two funded
long portfolios, not a leveraged long/short trading simulation.

Reports include Spearman six-month IC, Newey-West HAC mean/t statistics with six monthly lags,
quintile monotonicity, top-20 active mean/information ratio and cost sensitivity. Too few usable
observations yields missing statistics, never a passing t-test. Report hashes identify exact
input/evidence files. Reports always say `DIAGNOSTIC_ONLY` and `production_ready: false` until
the separately required reviewed evidence exists. The legacy `quant-research backtest` remains
an exploratory next-price-bar tool; it must not be used to certify next-open historical gates.

## Still required for phase 11 historical acceptance

- Licensed historical union membership and primary share classes, including vanished issuers;
  at least 15 complete years, preferably 20.
- Reviewed point-in-time filing revisions, classifications and corporate-action/terminal
  return lineage, with independent bias audit and final-period separation.
- Actual expanding-window yearly evaluations and at least 60% positive predefined yearly/regime
  partitions; sector/peer/regime concentration diagnostics.
- Coverage of at least 90% of the union and 80% of each supported profile, with explicit
  profile-specific expected membership (current canonical financial/REIT evidence is incomplete).
- Preregistered liquidity, winsorization, peer fallback, REIT gates and weight sensitivity runs.
- Out-of-sample Java/Python parity, final untouched-period metrics meeting every roadmap
  threshold, and independent review before any model approval-state change.

Synthetic fixtures validate software and bias rejection only. No predictive-performance claim
or historical acceptance is made by this implementation.
