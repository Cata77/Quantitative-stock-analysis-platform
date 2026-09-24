# Operations, monitoring and recovery

Phase 11 engineering tools support recovery and diagnostics. They do not certify the model's
historical performance or a deployed environment. See [validation limits](PHASE11-VALIDATION.md)
and [security provisioning](SECURITY.md). Never reset an existing database volume to upgrade it.

## Build reproducibly

Use JDK 25 and the Gradle wrapper. Each module has a strict `gradle.lockfile`. Regenerate only
for an intentional dependency change with `gradlew resolveLockedDependencies --write-locks`.
The normal `gradlew test bootJar` build rejects dependency drift. All service images copy the
lockfiles; the migration image copies its own lockfile.

Python uses uv 0.8.22 and the checked-in universal `research-engine/uv.lock`:

```powershell
.\.venv\Scripts\uv.exe sync --project research-engine --extra dev --locked
.\research-engine\.venv\Scripts\python.exe -m pytest research-engine/tests
.\research-engine\.venv\Scripts\python.exe -m ruff check research-engine
$env:PARITY_PYTHON = (Resolve-Path 'research-engine/.venv/Scripts/python.exe').Path
.\gradlew.bat test bootJar
```

CI performs the same locked installation, Java compilation/static compiler diagnostics,
unit/integration tests, event compatibility, frozen-model parity, bias fixtures and restore
exercise. It uploads test reports, query plans and JFR evidence. Polars 1.43.0 was yanked;
1.44.2 is pinned. Plotly 6.9.0 is explicitly pinned because vectorbt 1.1.0's templates fail
with Plotly 7. The frozen scoring model and fixtures have not been altered.

## Liveness, readiness and business freshness

All HTTP services expose `/actuator/health/liveness` and `/actuator/health/readiness` without
sensitive details. Liveness checks process availability. Database-service readiness checks a
required migrated table using the actual service role. Ingestion/scoring also check Kafka's
cluster metadata with bounded timeouts. Compose still requires successful central Flyway
before starting database services; a readiness probe is not a replacement for Flyway validate.
Gateway readiness is its process state; downstream outages return normal gateway errors.
Search availability is reported by the search API; SQL rankings remain usable during a search
outage. Business data can be stale while infrastructure readiness is UP.

`/actuator/prometheus` requires a JWT with `operations:read`; ordinary user tokens receive 403.
It is not routed through the public gateway to internal services. Provision an internal
scraper identity; never give the scraper the auth signing key. Auth now verifies bearer tokens
on its protected metrics endpoint too. Rotate the scraper token before expiry.

The optional monitoring overlay starts pinned Prometheus with loopback-only UI access:

```powershell
docker compose -f infrastructure/docker-compose.yml -f infrastructure/docker-compose.monitoring.yml --profile monitoring up -d
```

Supply `METRICS_TOKEN_FILE` containing only the current bearer token (no `Bearer ` prefix),
readable by the container user. Use the explicit local overlay for a local service stack.
`monitoring/alerts.yml` defines scrape/credential failure, telemetry failure, stalled delivery,
DLQ, blocking quality, publication absence/staleness, quota and low-coverage alerts.
Prometheus evaluates alerts; production notification delivery still requires an operator's
Alertmanager/receiver configuration and a tested notification route. No external message has
been sent by this implementation. Tune thresholds against actual schedules before paging.

Metrics are sampled every 30 seconds from a read-only, repeatable-read database snapshot.
Queries have five-second statement timeouts. Fixed metric names avoid instrument/run label
cardinality. UUIDs belong in logs, not metric labels. A missing publication/watermark/profile
produces NaN (and publication-present=0), not fabricated 100% coverage. Query failures clear the
snapshot and set `quant_telemetry_up=0`. Scrapes never execute the database queries.

| Metric family | Meaning and response |
|---|---|
| `quant_outbox_pending`, `quant_outbox_oldest_seconds` | Non-published transport work, including terminal failures; inspect persisted attempts before retry |
| `quant_canonical_pending` | STAGED items awaiting canonical acceptance; the inbox has no durable pending state |
| `quant_inbox_accepted`, `quant_inbox_redeliveries` | Persisted accepted observation count and duplicate deliveries |
| `quant_dlq_unreplayed` | Dead letters without a published audited replay; published replay does not itself prove canonical acceptance |
| `quant_ingestion_retries`, `quant_ingestion_duration_seconds` | Persisted retries and latest complete run's wall duration |
| `quant_watermark_lag_seconds` | Age of oldest contiguous watermark, including weekends; evaluate against the trading calendar |
| `quant_quality_blocking` | Open blocking issues |
| `quant_publication_present`, `quant_publication_timestamp`, `quant_publication_age_seconds` | Latest complete primary run and signal age |
| `quant_universe_coverage`, `quant_profile_coverage_*` | Scored / expected membership of the latest publication; unsupported profiles are not made supported by this gauge |
| `quant_exclusions`, `quant_scoring_retries`, `quant_scoring_duration_seconds` | Latest excluded count, persisted retries and last publication duration |
| `quant_provider_requests_seconds_*` | HTTP response latency/count by allow-listed provider and success/error/quota/transport_error; excludes body parse time |

Business metrics are emitted by producer/scoring/screener according to their existing grants.
Use one application label when summing shared-database state to avoid double counting.
Structured ECS logs include provider and run/instrument/event/dataset IDs at durable staging and canonical
commit, and run/date at scoring publication. Provider metrics never include URI/query strings,
authorization headers or secrets. Do not turn on HTTP wire logging.

## Automated backup and retention

Back up the complete database with the matching PostgreSQL/Timescale client versions.
`backup/provision-role.sql` provisions a non-administrative `quant_backup` reader; supply its
LOGIN password separately using the secret manager. It can read identity data, so treat backup
credentials/output as sensitive. If RLS is introduced later, revisit backup privileges and
verify complete row coverage before relying on that backup.

The optional `docker-compose.backup.yml` service runs `backup.sh` daily (24-hour recovery-point
objective, not point-in-time recovery). Supply `BACKUP_DB_USER`, `BACKUP_DIRECTORY` and
`BACKUP_PGPASS_FILE`. The pgpass file must have mode 0600 and be readable by the backup process.
Output includes custom-format dump, SHA-256 sidecar and server/extension version evidence.
Partial dumps are not published as completed backups. The loop fails on backup errors and
restarts via Compose; monitor process failures and `last-success.timestamp` older than 26h.
There is no automatic pruning. Encrypt output and replicate it off-host; a Docker volume alone
is not a disaster-recovery policy. Set a deployment-specific restore-time objective after a
measured full-size drill. WAL archiving/PITR is a later operator option if a 24h RPO is insufficient.

Keep canonical prices, filings/facts, historical membership, model versions, score inputs,
lineage, corrections and source hashes indefinitely unless a documented license requires
otherwise. Keep at least 30 daily backups plus monthly recovery points; test them before any
manual expiry. Raw JSON artifacts currently live in PostgreSQL and are included in dumps.
External source files referenced by `storage_uri` need independent durable object storage and
backup with the same retention. Search generations can be pruned only after confirming they
are not referenced by the active alias/checkpoint. Kafka is transport, not a canonical backup.

## Restore drill and deployment recovery

1. Record source PostgreSQL/Timescale versions, freeze deployment changes and preserve the dump,
   checksum, role definitions and key/configuration version references. Never store role passwords
   in the dump manifest.
2. Provision the application roles on an isolated target cluster using `bootstrap-roles.sql`,
   without supplying application LOGIN credentials until recovery validation. Use the same
   extension versions as the backup. Do not run the fresh-bootstrap ownership script on a live
   populated deployment.
3. Run `sh restore.sh /backups/name.dump new_database` with target `PGHOST` and restore-operator
   credentials. It verifies the checksum and creates a new database; an existing target fails.
   It invokes Timescale pre/post-restore hooks and preserves original ownership/grants.
   A failed target remains quarantined for diagnosis; it is not promoted or silently deleted.
4. Run Flyway validate, compare canonical row counts/source hashes/model manifests and sample
   point-in-time scoring results, check hypertables and role denials. Rebuild Elasticsearch from
   restored PostgreSQL using the separate search worker.
5. Start consumer/scoring and ingestion in bounded reconciliation mode with provider collection
   intentionally configured. Replay retained outbox work; inbox identities prevent duplicate
   business writes. Inspect DLQ and coverage before explicitly replaying any poison record.
6. Confirm protected API behavior, latest complete publication, fresh coverage and monitoring.
   Cut over service connection settings only after the drill passes. Keep the source and old
   backup until recovery is accepted. Measure achieved RPO/RTO and repeat monthly.

`BackupRestoreIntegrationTest` exercises the checked-in scripts against an isolated real
TimescaleDB, using the provisioned restricted backup reader, then validates Flyway, restored hypertable
rows, reference data and role denial.
It proves the procedure on fixtures, not the completeness or restore time of a live backup.
A real deployment drill, off-host backup verification and alert delivery remain release gates.

## Local startup and bounded recovery

Explicit local startup uses both base and local Compose files, as described in SECURITY.md.
Existing volumes require the ownership migration and credential provisioning; do not delete
volumes to make startup pass. Startup reconciliation stays enabled after deployment.
`INGESTION_MODE=sync-and-exit` checks due coverage and exits; `catch-up-and-serve` reconciles
then serves, with `INGESTION_SCHEDULES_ENABLED` controlling recurring triggers. Use explicit
bounded `backfill`, audited `force-refresh`, `SCORING_OPERATION=score-and-exit`, and audited
DLQ inspection/replay for recovery. Inspect persisted status, not process uptime, before
reporting current rankings. Provider quotas, contact identity and license rights still apply.

Implementation references: [Gradle locking](https://docs.gradle.org/current/userguide/dependency_locking.html),
[Spring health probes](https://docs.spring.io/spring-boot/reference/actuator/endpoints.html),
[Timescale restore hooks](https://docs.tigerdata.com/api/latest/administration),
[Polars release notice](https://pypi.org/project/polars/1.43.0/).
