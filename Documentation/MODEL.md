# Frozen Python scoring model v1

Phase 7 implements the research hypothesis in `research-engine/src/quant_research/model`.
It does not establish predictive performance. The final historical test remains unopened.
The legacy `quant-research` commands still use the demonstration model. Use the separate
model CLI below for the redesign. Java scoring and parity are now implemented; see
[complete scoring runs](SCORING-RUNS.md).

## Run and inspect

From the repository root, using the existing root virtual environment:

```powershell
.\.venv\Scripts\python.exe -m quant_research.model.cli --input research-engine/fixtures/model-v1/prepared.json
.\.venv\Scripts\python.exe -m quant_research.model.cli --input research-engine/fixtures/model-v1/varied-prepared.json
.\.venv\Scripts\python.exe -m pytest research-engine/tests
.\.venv\Scripts\python.exe -m ruff check research-engine
```

JSON output contains raw/directed/winsorized metrics, cohort bounds, mean and population
standard deviation, unclipped/clipped z-scores, families, weighted contributions, composite,
average-tie percentile, stable UUID ordinal rank, exclusions, warnings and source evidence.
Intermediate arithmetic is binary64; only serialization rounds half-even to six decimals.
Compare unrounded implementations within 1e-9; compare stored outputs at six decimals.
Exit status 2 means fewer than two eligible instruments. Excluded members remain in output.

`--candidate` accepts `primary` (50/30/20), `pure_value` (100/0/0),
`value_quality` (60/40/0), or `balanced` (40/40/20). All retain the same eligibility
and coverage gates. Candidate selection belongs to development/validation, never final-test
retuning. `--research-stage final` verifies the frozen files before reading the input.
It does not run a backtest or certify historical coverage.

## Input boundary

`--format phase6` consumes JSON serialized from `ScoringInputRepository.CrossSection`.
`--jurisdictions jurisdictions.json` supplies a reviewed issuer-UUID-to-jurisdiction map;
USD currency is not evidence of US tax domicile. Facts carry both availability and observation
timestamps, fact/filing/source identities and mapping versions. The adapter rejects duplicate
periods, filters future facts, aligns balances, and assembles TTM from an annual period,
four contiguous quarters, or annual plus current YTD minus comparable prior YTD.
It never sums cumulative YTD as independent quarters or invents zero missing balances.

The adapter preserves SQL exclusions and only enables GENERAL. Supported prepared-input
profiles additionally include BANK, PC_INSURER, LIFE_INSURER and EQUITY_REIT, but these require
reviewed parent-level regulatory data, dated capital requirements, or explicit REIT
reconciliations. Existing ingestion does not supply all of that evidence. Missing evidence
means unsupported or excluded, not automatically inferred bank/insurer/REIT eligibility.
Prepared inputs are an audited import boundary; booleans describing review/scope must be
provided by a trusted caller. They are not independently proven by the calculator.

Every numeric input needs source IDs, unit, period end, available_at and observed_at.
Both timestamps must precede the cutoff. Current raw/adjusted closes must be dated on the
score date. Phase 6 price provenance retains observation keys and conservatively reports
the query cutoff as an upper bound, not as the provider's original timestamp.
The SQL boundary validates trading observations, listing and membership. Offline prepared
imports must establish the same calendar, share-class and corporate-action facts.

Signals are defined at the last regular-session close monthly, with execution at the next
regular-session open, 10 bps costs per side and holding until the next rebalance.
This phase calculates a cross-section; it neither schedules signals nor simulates trades.
The legacy backtest is not evidence for this model. Historical execution and the untouched
60/20/20 research split remain Phase 11 gates.

## Formula review

The manifest's `formula_contract` documents every numerator and denominator. These strings
are documentation, never executable expressions. A closed JSON Schema and canonical SHA-256
allow only the reviewed v1 constants.

| Profile | Value | Quality and anchors |
|---|---|---|
| General | EBIT/EV, FCF/market cap, positive common equity/market cap | Gross profit/average assets, NOPAT/average invested capital, cash earnings/average assets |
| Bank | Common earnings, tangible equity, net payout divided by market cap | ROA, disclosed CET1/RWA, negative nonperforming-loan ratio; CET1 required |
| P&C | Same financial value family | Operating ROE, 1 minus combined ratio, negative 12-quarter operating-ROA deviation; parent RBC required |
| Life/health | Same financial value family | Operating ROE, operating ROA, earnings stability; parent RBC required |
| Equity REIT | Reconciled FFO/market cap, normalized AFFO/market cap, EBITDAre/EV | Same-store NOI growth, cash interest coverage, negative net leverage; FFO required |

Market cap uses raw close and instrument-level point-in-time shares outstanding, never
weighted-average EPS shares. EV adds current/noncurrent debt, preferred equity and minority
interest, then subtracts cash. Balance averages compare current and prior-year periods.
All ratio denominators must be positive. Negative earnings/FCF remain valid values.
Tax uses the clipped median of three valid aligned annual rates, otherwise the versioned
jurisdiction fallback; no available fallback leaves ROIC missing. The US 21% fallback is a
frozen federal-rate research assumption, not a prediction of an issuer's full effective tax.

CET1 excludes a negative surplus over separately dated requirements and warns below one
percentage point. Insurer RBC excludes below 2x and warns below 3x; P&C combined ratio above
1.05 warns. REITs exclude nonpositive EBITDAre, coverage below 1.5x, leverage above 10x, or
AFFO payout above 1.2x for two periods. Coverage below 2x, leverage above 8x and current payout
above 1x warn. Missing risk evidence fails closed. No SBC addback is allowed in AFFO.

Each metric needs 10 primary-peer values, else 20 same-profile values; incompatible profiles
never mix. The normalization population is frozen once after universal, risk and raw coverage
gates. Missing normalized factors do not trigger iterative population changes. Linear 2.5/97.5
percentiles winsorize directed values, population deviation below 1e-12 yields zero, and
z-scores clip to +/-3. Two of three value and quality metrics plus momentum are required.
A missing component contributes zero only after coverage passes, with a fixed denominator
of three. Raw inputs remain intact. Momentum uses trading offsets t-21 and t-252.

## Golden data and freeze

`inputs.json` has compact profile templates and hand-calculated derived expectations.
`prepared.json` / `expected.json` expose every stage of 50 tied instruments in five profiles.
`edge-cases.json` documents risk and missing-data cases; `varied-prepared.json` /
`varied-expected.json` add nonzero ranks, negative factors, partial coverage, peer fallback,
incompatible cohorts and exclusions. Independent numeric tests check percentile interpolation,
population deviation, clipping, weighting and fixed-denominator aggregation.

`freeze.json` records canonical manifest SHA-256, base Git commit, exact file hashes and the
source-tree hash. Source hashes normalize CRLF to LF. The base commit does not claim to
contain the uncommitted implementation: the file hashes identify that exact workspace state.
Refresh the freeze only after an intentional reviewed hypothesis change and passing fixtures,
before opening any final period. Commit the implementation and preserve this provenance.

V011 creates and seeds `research.model_versions` with the manifest, checksum, source commit
and source-tree hash. PostgreSQL verifies the stored canonical JSON hash and JSON equality.
The approval state is FROZEN_RESEARCH_HYPOTHESIS, not production approval. Apply central Flyway
before deployment; tests use isolated databases and do not migrate the application database.
V012 adds complete scoring runs and parity certification. See SCORING-RUNS.md. Historical
acceptance and production approval remain separate; certification alone does not approve the model.
