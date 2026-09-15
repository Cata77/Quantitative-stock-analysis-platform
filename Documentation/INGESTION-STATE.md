# Durable ingestion and Kafka recovery (Phase 3)

## Scope

The producer plans work from the exact dated S&P 500/Nasdaq-100 snapshot union and
stages provider observations through a transactional outbox. The scoring consumer commits
its inbox, append-only observation journal, and existing application projection together.
Kafka offsets advance only after that database transaction commits.

The redesign plan governs implementation order. The older implementation plan and project
conventions apply where compatible. Files in Plan Design remain local and ignored by Git.

V005/V006 supply jobs, attempts, checkpoints, source manifests, outbox/inbox, and coverage.
V007 adds the observation journal, physical event receipts, DLQ/replay audit, calendar
imports, and month-end job identity. V008 adds coverage validity. The repeatable ingestion
reconciliation function validates accepted work and contiguous watermarks. Applied
versioned migrations are never edited.

The observation journal is the Phase 3 canonical durability boundary. Specialized daily
prices/corporate actions now use the [phase 4 pipeline](DAILY-PRICES.md); filings use the [phase 5 canonical pipeline](FUNDAMENTALS.md). The existing equal-weight
demonstration formula is unchanged. Its monthly scheduling guard is not the production
model or historical research gate planned for Phases 7/8.

## Runtime and identity

The logical run key includes the job configuration, snapshot pair, inclusive day window,
and request key. Invocation mode and process startup time do not create another run.
The coordinator plans one run per completed trading session, starting at the first date
supported by both snapshots unless a bounded start is supplied. It requests only planned,
due retry, or expired-lease items. Complete/staged items are not fetched on every restart.

MARKET_DATA_SYMBOLS is ignored by the durable path. Instrument IDs come from the snapshot
union; request tickers come from effective-dated symbols. Import both complete snapshots
before enabling ingestion. Snapshot/configuration changes create distinct audited work.

Calendar sessions, holidays, and early closes are persisted from the Alpaca calendar API,
with source response/hash metadata. The current day is excluded conservatively. Provider
calls happen outside database transactions. Each page atomically stages its artifact,
outbox observations, lineage, and checkpoint. Empty expected sessions and repeated page
tokens remain failures. See [daily prices](DAILY-PRICES.md) for phase 4 coverage and provider contracts.

Current OVERVIEW collection is optional and only runs for today's observation. It does
not relabel today's response as historical data or replace the filing model in Phase 5.

Work and relay claims use leases, fresh tokens, and PostgreSQL SKIP LOCKED. The database
supplies lease/retry timestamps. Attempts use bounded exponential backoff. Job leases
renew before each provider page; choose a lease longer than a request. Exhausted
items/events remain terminal failures. Normal startup never resets them.

The relay uses instrument UUID keys, producer idempotence, acks=all, bounded sends, and
broker receipts. Default topics are market-data-observations-v2 and
market-data-observations-v2-dlq. The consumer uses read_committed, raw bytes, disabled
auto-commit, and record acknowledgement. Legacy jobs/listeners are disabled by default
and must not be enabled against v2 topics.

- eventId identifies a physical envelope.
- observationKey hashes dataset (including provider/version), instrument, event type,
  economic time, adjustment mode, and canonical economic content.
- Object keys and decimal representations normalize before hashing.
- Retrieval timestamps, random IDs, and mutable ticker labels do not enter economic facts.
- Unchanged facts reuse an observation; corrections append a new one.
- The payload SHA-256 header checks consistency, not sender authentication.

The consumer validates schema, key, hash, economic values, source lineage, and physical-ID
collisions. Inbox, receipt, canonical journal, and compatibility projection share a
transaction. Database failure rolls them back together. Redelivery increments delivery
metadata without repeating business effects, including a new physical event for the same
observation.

Coverage requires fully staged work, accepted inbox and canonical records for every event,
and no blocking quality issues. Broker acknowledgement alone never completes a run.
Watermarks stop at missing/invalid session coverage, including absent calendar rows.
New blocking quality issues withdraw existing coverage; invalid records remain for audit.

## Commands and readiness

Build jars and apply central Flyway through the normal deployment process first:

~~~powershell
.\gradlew.bat :market-data-producer:bootJar :scoring-service:bootJar :database-migrations:bootJar
~~~

Supply database/Kafka settings and provider credentials through environment configuration.
The scoring consumer must be running for producer work to reach canonical completion.
Tests use fixture provider responses and need no real provider credentials.

Append these arguments to the market-data-producer runnable jar:

| Mode | Arguments |
|---|---|
| Startup catch-up and serve | --market-data.enabled=true --ingestion.mode=catch-up-and-serve |
| Reconcile and exit | --market-data.enabled=true --ingestion.mode=sync-and-exit |
| Bounded history | --market-data.enabled=true --ingestion.mode=backfill --ingestion.start-date=2026-09-01 --ingestion.end-date=2026-09-11 |
| Audited correction | --market-data.enabled=true --ingestion.mode=force-refresh --ingestion.start-date=2026-09-01 --ingestion.end-date=2026-09-01 --ingestion.request-id=correction-001 --ingestion.reason=provider-correction |

Force refresh handles daily bars and enabled SEC/FFIEC collections. Reuse request ID and reason to resume the
same correction. Producer exit codes are 0 complete, 1 failed, and 2 incomplete/timeout.
Setting ingestion.schedules-enabled=false preserves startup reconciliation but disables
recurring triggers. The sync-timeout, items-per-cycle, max-attempts, job-lease,
retry-backoff, and delivery.* properties bound work and retry behavior.

GET /internal/ingestion/status reports SYNCING, READY, DEGRADED, or FAILED, pending/failed
counts, and the stored completion boundary on the producer's internal service port.

Append these arguments to the scoring-service runnable jar:

| Operation | Arguments |
|---|---|
| Inspect DLQ | --scoring.command.operation=dlq-inspect --scoring.command.limit=20 |
| Replay one record | --scoring.command.operation=dlq-replay --scoring.command.dead-letter-id=UUID --scoring.command.replay-id=STABLE-UUID --scoring.command.operator=NAME --scoring.command.reason=REASON |
| Score month end | --scoring.command.operation=score-and-exit --scoring.command.score-date=2026-08-31 |

SCORING_OPERATION is an environment alternative to the operation argument. Command modes
disable background listeners and coverage/month-end triggers. Exit code 2 means incomplete
score inputs or another live replay lease. Reuse the replay UUID after an uncertain outcome.

DLQ inspection retains original bytes as base64, ordered headers (including duplicates/nulls),
key, source topic/partition/offset, and failure reason. Recovery commits the database audit
before DLQ publication. Publication failure prevents input offset acknowledgement.
Replay is explicit, restricted to the configured source topic/consumer, and audited with
operator, reason, request UUID, lease, and broker acknowledgement. There is no automatic
replay loop. Replaying unchanged poison creates another DLQ location and never accepts it.

## Monthly scheduling

Startup and periodic reconciliation discover completed month ends from persisted calendar
coverage. Each date/model/snapshot pair has one logical job. It waits for validated data
and all expected primary-class inputs. Score writes and job publication share a transaction,
so a new startup clock does not recreate a completed job.

This uses the demonstration calculator and still requires its stored historical inputs.
Phase 3 does not manufacture missing fundamentals or momentum history. The rankings API
retains scoreTime for freshness presentation; complete published-run APIs and search
rebuilding remain Phase 9.

## Verification

With Docker Desktop running:

~~~powershell
$env:DOCKER_HOST = 'npipe:////./pipe/dockerDesktopLinuxEngine'
.\gradlew.bat :database-migrations:test :market-data-producer:test :scoring-service:test
~~~

Tests use isolated TimescaleDB 2.29.1-pg18 and Kafka confluentinc/cp-kafka:7.8.0 containers.
They do not migrate, reset, or use the application database as a fixture.

Coverage includes fresh/repeated/upgrade migrations, snapshot unions, leased work,
checkpoint restart, stale-worker fencing, retry budgets, all three Kafka crash windows,
canonical rollback, duplicate/corrected facts, physical-ID collisions, invalid
prices/JSON/hash/UTF-8, DLQ inspection/replay, withdrawn/gapped watermarks, week-long
startup catch-up with partial provider failure, and stable month-end identity.

A bounded 16-worker virtual-thread test records JFR jdk.VirtualThreadPinned events after
warming the JDK socket poller and checks exactly one business effect. Cold startup on this
Windows JDK showed socket-poller class-initialization pinning (up to about 10 ms). This is a local contention check, not production
throughput certification. Historical bias, Python/Java parity, full provider contract
validation, and production load gates remain later-phase work.

Verified 2026-09-14: 45 tests, zero failures/skips; producer and scoring bootJar builds passed.
