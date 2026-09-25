# Point-in-time scoring inputs (Phase 6)

## Contract

ScoringInputRepository.load(request) executes one parameterized SQL statement and returns
an immutable CrossSection. Its inputs contain every distinct instrument in the supplied
S&P 500/Nasdaq-100 snapshot pair. Missing inputs produce structured Reason(code, detail)
entries instead of dropping the member.

A request pins the score date, market cutoff, knowledge cutoff, both snapshot IDs, raw and
adjusted dataset IDs, adjustment vintage, classification version, SEC mapping version and
required canonical metric codes. The result retains that request so a later score run can
store the exact selection contract.

The market cutoff must equal the regular XNYS session close on the score date. Knowledge
cannot be later than that close under legacy CLOSE_V1. The runtime NEXT_OPEN_MINUS_30M_V1
policy fixes knowledge to 30 minutes before the next regular opening, with the intervening
calendar known by the close and adjustment vintage equal to the decision date in New York.
See [versioned timing policy](SCORING-RUNS.md#version-110-timing-policy-local-checklist-step-2).
Effective dates are inclusive at the start and exclusive at the end. Snapshot dates and observations must be known by the cutoff. Only complete
CURRENT_SNAPSHOT_FORWARD snapshots are accepted; biased/proxy research needs its own explicit
future contract. A wrong pair, unknown dataset/classification/metric or incomplete calendar
fails the request rather than presenting an apparently complete cross-section.
A newer effective forward snapshot known at the cutoff supersedes an older selection,
even when the newer import is partial. Later observations cannot change the old cutoff.

## Data selection

- Reference symbols and the pinned sector classification use effective and knowledge dates.
  Today's mutable active flags are not projected backward over instrument lifecycle dates.
- Exactly one primary class per issuer is required; conflicting flags across the two snapshots
  and multiple primary instruments are excluded.
- SEC filing bundles are ranked by observed version within accession before their facts are
  selected. Source priorities and ambiguity/quality rules match phase 5. All selected canonical
  fact periods are returned, with source artifact, filing, parser, mapping and availability
  lineage, for pure in-memory annual/TTM and later model calculations.
- Instrument outstanding shares must link to a selected canonical fact. Weighted-average
  diluted shares never substitute for outstanding shares.
- Raw prices supply market value and liquidity. Adjusted prices use exactly the requested
  vintage. SIP, currency, lifecycle, quality and availability checks apply before use.
  The newest known rejected price revision withdraws the old value.
- Prices also require ingested_at <= knowledge cutoff, matching phase 4's strict operational
  price history contract. SEC facts use their phase 5 observed/available contract.
- Momentum boundaries are session offsets t-21 and t-252: rows 22 and 253 when the score
  session is row 1. Missing prices cannot shift these boundaries. Price-history readiness
  requires the newest 252 sessions plus the older momentum boundary.
- Liquidity requires all 20 raw SIP sessions. Median dollar volume uses PostgreSQL's continuous
  percentile calculation (double precision); monetary source facts and prices retain NUMERIC.
- Blocking issues are evaluated by detected/resolved timestamps, including an issue now
  resolved that was still open at the historical cutoff.

Returned price lineage covers the 20 liquidity observations, score adjusted price and the two
momentum boundaries. The request's dataset/vintage/cutoffs identify the full history used by
the coverage count. This avoids returning hundreds of unused price-source records per stock.

## Readiness and phase boundaries

inputReady means the phase 6 input gates passed, not that the final model has scored the
company. Required metrics are declared by the caller and must be known canonical codes.
The query checks their presence in a filing no more than 180 days old; it returns the complete
canonical history for downstream period/reconciliation checks. A present concept alone does
not guarantee enough contiguous quarters for TTM or enough components for a factor.

Other reasons include non-primary/unsupported securities, missing symbols/sectors/shares,
stale shares or filings, unsupported company profiles, missing prices, insufficient history,
incomplete liquidity, low price, illiquidity and blocking data-quality issues.
readyCount + excludedCount always equals the number of returned members.

Bank, insurer and REIT profiles that phase 5 cannot certify remain explicitly unsupported.
Profile-specific factor coverage, regulatory requirements, annual/TTM transformations, peer
normalization and score publication remain the phase 7/8 model pipeline. The existing demo
FactorInputAssembler still serves the legacy calculator; this query does not silently wire
the unfinished model into published rankings. No calculation code needs per-symbol DB calls
when consuming this new CrossSection.

Daily observations collected after the signal close cannot be used at that close. The
repository will report missing inputs rather than relax the knowledge cutoff. The operational
collection schedule must deliver appropriate vintages for a complete strict historical run.
Version 1.1.0 permits delayed bars only through its fixed pre-open cutoff; it preserves all
economic-date, source-observation and ingestion-time filters.

## Verification and query plan

The SQL is in scoring-service/src/main/resources/sql/scoring-inputs.sql and is packaged in the
scoring service JAR. It uses set-based CTEs for universe, sessions, filings, canonical facts,
profiles, prices and quality. The repository has a 60-second SQL timeout.

With Docker running:

~~~powershell
.\gradlew.bat :scoring-service:test --tests '*ScoringInputIntegrationTest'
~~~

The target-scale test seeds 600 instruments, overlapping 500/101-member snapshots,
15,600 canonical facts across 13 quarterly periods, and 392,400 raw/adjusted bars
across 327 sessions. It verifies one JDBC connection/statement
for the complete load and writes EXPLAIN (ANALYZE, BUFFERS) to
scoring-service/build/reports/scoring-inputs/explain.txt. The plan is a local generated artifact.
Additional fixtures cover member preservation, superseded snapshots, revisions, withdrawals,
calendar holes/boundaries, share-class conflicts, stale facts, unsupported profiles, liquidity feed and historical quality issues.

Existing index candidates include membership(snapshot,instrument), symbol/classification
effective-date indexes, dataset/adjustment/vintage/session price indexes, the fact lookup index
and primary-key filing/fact links. Add indexes only when the measured plan demonstrates a
benefit. Published scoring-run/ranking indexes belong to phase 8, when those tables exist.

Measured on 2026-09-16 with TimescaleDB 2.29.1/PG18: 3,511.499 ms execution and
37.711 ms planning. All 600 rows were input-ready, with one JDBC connection and
one prepared statement. Window-based fact validation avoids repeated aggregation;
freshness is grouped once per issuer and price intermediates carry only needed columns.
The plan still uses temporary disk for sorts under the test server's default memory
settings. This is a local synthetic benchmark, not a production latency guarantee.

All 57 scoring tests passed, including the 12 Phase 6 integration cases and existing
Kafka recovery, DLQ and warmed virtual-thread/JFR tests. Producer and migration checks
were up-to-date (36 and 2 previously passing tests). The scoring bootJar build passed.
No schema changes or live provider import were required for this phase.


## Phase 7 research consumer

The immutable fact DTO now includes `observedAt` alongside `availableAt`, allowing the
Python adapter to independently reject knowledge acquired after the cutoff. Serialize the
complete `CrossSection` record for `quant_research.model.cli --format phase6`.
See [MODEL.md](MODEL.md) for period assembly, prepared profile inputs, model freeze and CLI
usage. Java production scoring and publication remain Phase 8.
