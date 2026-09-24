# Phase 11 validation matrix

Engineering verification completed on 2026-09-23; phase 11 historical/model and live deployment
acceptance remain open.
The repository does not contain the licensed historical data required to close every gate.

| Requirement | Executable evidence / status |
|---|---|
| Fresh/upgrade Flyway, idempotence, constraints, hypertables | DatabaseMigrationIntegrationTest and DatabaseRoleIntegrationTest |
| Kafka crash windows, duplicates, inbox/outbox, DLQ | DurableDeliveryIntegrationTest; transaction fault injection and real dependency containers |
| Partial provider runs, retries, quotas and pages | IngestionRunStoreIntegrationTest, AlpacaDailyDataClientTest, SecDataClientTest, FfiecBulkCollectorTest |
| Amendments, cutoff leakage, coverage | FundamentalsIntegrationTest, ScoringInputIntegrationTest, Python model-period tests |
| Symbol/share-class/split/dividend handling | UniverseSnapshotImporterIntegrationTest, DailyPricesIntegrationTest |
| Merger/spinoff/delisting economic history | Review/exclusion handling exists; complete terminal-return history remains unavailable |
| Scoring rollback, retry, supersession, publication guards | ScoringRunIntegrationTest and screener integration tests |
| Java/Python frozen parity | FrozenModelParityTest, CanonicalModelInputsTest and Python fixtures; CI uses locked Python environment |
| Event compatibility | ObservationCompatibilityTest rejects unknown versions/wrong partition keys and accepts additive envelope metadata |
| Authorization/role denial | Shared JWT matrix, API security tests, real-role migration tests |
| Metrics/readiness/secret-safe provider labels | OperationalMetricsIntegrationTest, ProviderTelemetryFilterTest and protected gateway scrape/probe tests |
| Virtual-thread load diagnostics | DurableDeliveryIntegrationTest JFR fixture; full deployed throughput/latency load certification still required |
| Database recovery | BackupRestoreIntegrationTest runs checked-in dump/restore scripts in isolated TimescaleDB, validates restored data/schema/permissions and rejects an existing target |
| Search reconstruction | Screener integration tests rebuild after index/checkpoint loss and reject partial/failed generations |
| Dependency reproducibility | Strict per-module Gradle locks; universal uv lock; CI frozen installation |
| Full-size off-host backup and restore-time objective | Requires a deployed environment and operator-provisioned storage; fixture restoration is not a live drill |
| Alerts delivered to operators | Rules supplied; configure/test an actual Alertmanager/receiver before deployment |
| Historical acceptance | Open: licensed history, terminal returns, complete profile inputs and preregistered final-period evidence |

Runbooks: [operations/recovery](OPERATIONS.md), [security/deployment](SECURITY.md),
[research diagnostics](RESEARCH-VALIDATION.md), [screener rebuild](SCREENER.md).

## Verification recorded on 2026-09-23

170 distinct Java tests passed across the full build and affected-suite reruns, with no
remaining failures/errors/skips: auth 6, migrations 12, gateway 10, ingestion contract 1,
producer 37, observability 4, portfolio 4, scoring 70, screener 17, shared security 9.
Python: 101 tests and Ruff passed. All seven bootJars built. Locked Python sync passed after
correcting a stale Plotly lock entry. Test imports now exercise production monitoring settings
with explicit fixture-only dependency-probe overrides. Both auth and gateway prove scrape
access requires operations:read.

Base/local and monitoring/backup Compose configuration checks passed. Pinned Prometheus
validated nine rules and alert fixtures for service/credential failure, telemetry failure,
stalled delivery and their healthy states. Remote GitHub CI execution awaits a push.

The restricted backup role created the dump used by the restore fixture. Restored Flyway
validation/idempotence, hypertable/reference rows and database-role denial passed. No live
application backup was substituted for this fixture. The full 70-test scoring run passed
Kafka/DLQ failure-window and warmed 16-worker virtual-thread/JFR checks. Its XML evidence is
retained locally in scoring-service/build/reports/phase11-full-scoring. The scale query used
600 instruments and ran in 3,465.716 ms (32.991 ms planning).

The CLI generated research-engine/build/reports/validation/synthetic-validation.json with
input hashes, DIAGNOSTIC_ONLY, production_ready=false and final_test_opened=false. This
verifies reporting only. Actual walk-forward, robustness and final historical acceptance
still require the reviewed history and experiments listed in RESEARCH-VALIDATION.md.

Starting Docker Desktop resumed existing application containers via their restart policies.
Tests used isolated dependency containers and mock providers. No manual live migration,
volume reset, provider import or search rebuild was performed.

The standalone JFR check initially exposed test-order-dependent class initialization: the
cold recording contained 49 pinning events. The fixture now warms sockets, Jackson and both
processing branches inside a rolled-back transaction before the 16-worker measurement.
The isolated rerun passed with zero pinning events, preserved as
scoring-service/build/reports/phase11-ingestion.jfr; the earlier local recording is retained
as phase11-cold-start.jfr. This certifies the warmed fixture only, not cold-start or deployed
load performance. CI now retains the generated JFR instead of losing it with a temporary directory.
