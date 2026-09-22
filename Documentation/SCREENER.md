# Screener and rebuildable company search

Phase 9 replaces the demonstration `factor_scores` reader with the immutable publications
introduced in V012. Apply central Flyway through V013 before starting this version.
PostgreSQL remains authoritative; no ranking request reads Elasticsearch.

## Rankings

`GET /screener/rankings` selects the latest complete `PUBLISHED` primary-candidate run,
ordered by score date, publication time and stable run ID. Failed, pending and partially
stored runs are never candidates. An empty result before the first publication has
`freshness: UNAVAILABLE` and `run: null`; it is not an empty completed universe.

| Parameter | Default | Meaning |
|---|---|---|
| `asOf` | Current UTC date | ISO date; legacy ISO timestamps are accepted and reduced to a UTC date |
| `modelVersion` | Any | Model version UUID; choose this explicitly when comparing a particular model |
| `runId` | Latest matching publication | Pin the returned run ID for subsequent pages; still obeys date/model/publication checks |
| `instrumentId` | Any | Stable instrument UUID, including a search result identity |
| `universe` | `UNION` | `UNION`, `SP500`, `NASDAQ100`, from captured input membership |
| `profile`, `sector`, `peerGroup` | Any | Exact stored values, from the selected historical run |
| `eligibility` | `ELIGIBLE` | `ELIGIBLE`, `EXCLUDED`, `ALL` |
| `warning` | Any | Exact warning string, including a suffix when the model supplies one |
| `sort` | `COMPOSITE` | `COMPOSITE`, `VALUE`, `QUALITY`, `MOMENTUM`, `CONTRIBUTION` |
| `direction` | `DESC` | `ASC` or `DESC` by selected score/contribution |
| `metric` | None | Required canonical metric name for `CONTRIBUTION`; unknown names sort as null |
| `page`, `size` | `0`, `50` | Zero-based, maximum page 100000 and size 200 |

Filters apply **after** selecting the run, so an empty filtered result never silently falls
back to an older run. Counts describe the filtered result; run coverage describes the full
captured union and each profile. Use `eligibility=ALL&instrumentId=<uuid>` to inspect a
search result, or `eligibility=EXCLUDED` to inspect missing/unsupported instruments.

Responses contain the run/model/checksum/input/classification/snapshot versions, universe
modes and bias labels, cutoffs, publication/effective times, completeness and coverage.
Each item includes stable instrument/issuer IDs, historical symbol, original rank and tied
percentile, composite/family scores and contributions, peer/profile/sector, metric coverage,
warnings, every stored factor stage and explicit exclusions. The wording is
`Highest-ranked candidates`. The model approval state remains visible.

`COMPOSITE` preserves the stored ordinal rank computed from **unrounded** model output.
Other sorts use stored factor values, put missing values last, and break ties by instrument
UUID. Filters never recalculate ranks or percentiles. Count and page reads use a PostgreSQL
repeatable-read transaction; pin `runId` across requests to survive a new publication.

Freshness is an explicit age policy: `CURRENT` through 35 calendar days, `STALE` after that.
`ageDays` is relative to `requestedAsOf`, and `maximumAgeDays` is returned as 35. This is a
monthly display threshold, separate from completeness and the ingestion schedule. An old
complete run stays inspectable with its stale label. A historical request is retrospective
by **score date**, not a claim that the eventual publication was available on that date;
`publishedAt`, `knowledgeCutoff` and `effectiveFrom` retain their separate meanings.

Example:

```text
/screener/rankings?asOf=2026-08-31&universe=NASDAQ100&profile=GENERAL&sort=VALUE&size=20
/screener/rankings?eligibility=EXCLUDED&size=100
```

This is an intentional response-contract change: legacy `scoreTime`, `zValue`, `zQuality`
and `zMomentum` fields are replaced by the run metadata and explicit model family scores.

## Search projection

`GET /screener/search?q=...` searches current canonical symbols, issuer names, sector,
industry and description. Every result resolves to `instrumentId`. A rename changes the
current search symbol without changing the score's `symbolAtScore` or historical ranking.
Relevance ties use instrument UUID. Pagination is limited to Elasticsearch's 10000-result
window; invalid parameters return 400 rather than triggering a dependency error.

The schema-v1 document uses the instrument UUID as its Elasticsearch `_id`. It contains
current symbol/name/exchange/country/classification, schema version, update timestamp and
the latest published primary-run score summary (including excluded instruments). Score
IDs, eligibility, ranks, family scores and warnings have explicit searchable mappings.
Industry currently carries the canonical issuer SIC code. Description remains null because
the canonical security master has no description source; text is never fabricated or
copied from legacy provider metadata. Search is a current read model, not historical research.

The default alias is `companies-read`; immutable generations are named
`companies-read-v1-<uuid>`. Use a new alias when migrating from the old physical `companies`
index. Do not configure an alias name that is already a physical index. Schema changes
require a version bump and full rebuild. Local indices have one shard and no replicas;
production topology remains a deployment concern.

Missing indices, Elasticsearch transport errors, shard failures and timed-out searches
return 503. They never become misleading successful empty searches. PostgreSQL dependency
failures also return 503 without internal connection details. Elasticsearch connect/socket
timeouts are bounded. Compose allows the screener to start once Elasticsearch is started,
even if it is unhealthy; SQL rankings remain independent.

## Reconciliation and rebuild

A startup and recurring job reconciles the entire canonical security master. It takes one
repeatable-read database snapshot under an alias-specific advisory lock, hashes the
ordered canonical documents and skips an unchanged generation only when the alias still
points to the checkpoint's index. Reconciliation handles metadata changes, newly published
runs, lifecycle removals and a deleted index. It runs every five minutes by default.

For changed data or a forced rebuild:

1. Create a fresh versioned index with explicit mappings.
2. Bulk-index canonical documents in batches of at most 200, using stable UUID document IDs.
3. Check every bulk response, refresh, and verify the full document count.
4. Atomically swap the alias from the previous generation.
5. Commit `operations.search_rebuild_checkpoints` with schema version, content hash,
   generation, document count, source snapshot time and completion time.

A failure before alias replacement preserves the previous searchable generation. PostgreSQL
and Elasticsearch cannot commit atomically: a crash or uncertain acknowledgement during/after
alias replacement may leave a valid new index with an older checkpoint. The next pass detects
the mismatch and rebuilds. No failed/partial bulk advances the alias or checkpoint. Busy
scheduled workers skip and retry; a busy manual command fails visibly. Restart recovery
always reads canonical data. No Kafka event or in-memory queue is required for this bounded
full-reconciliation strategy.

`GET /screener/search/status` returns the last completed checkpoint (snake_case storage keys),
or `status: NOT_BUILT`. It reports a completed rebuild, not live dependency health. Its
`source_as_of` is the last changed/forced snapshot, so unchanged polls do not advance it.
Old and failed generation indices are retained for diagnosis; operators can remove exact
unreferenced generations after checking the alias and checkpoint. Never delete PostgreSQL
source data as part of a search rebuild.

Configuration:

| Environment variable | Default |
|---|---|
| `ELASTICSEARCH_URL` | HTTPS required outside `local` |
| `ELASTICSEARCH_COMPANY_INDEX` | `companies-read` |
| `SEARCH_REBUILD_ENABLED` | `false` for reader; `true` in search-indexer |
| `SEARCH_REBUILD_INTERVAL` | `300000` milliseconds |
| `SCREENER_MODE` | `serve` (`rebuild-and-exit` for the command) |

HTTP endpoints only read data. The rebuild worker needs SELECT on canonical/reference/research
data, writes only its operations checkpoint, and needs Elasticsearch index/alias privileges.
The reader uses `quant_screener`; the separate `search-indexer` uses `quant_search` with
checkpoint-write permission and its own Elasticsearch API key. Ranking/status transactions
remain read-only. All HTTP reads require a bearer token with `research:read` scope.
See [security configuration and rollout](SECURITY.md). There is no public rebuild HTTP endpoint.

Build and execute a forced full rebuild using the configured database/Elasticsearch environment:

```powershell
.\gradlew.bat :screener-service:bootJar
java -jar screener-service/build/libs/screener-service-0.0.1-SNAPSHOT.jar --screener.mode=rebuild-and-exit --spring.main.web-application-type=none
```

Or, after building the application image and applying Flyway:

```powershell
docker compose -f infrastructure/docker-compose.yml --profile application run --rm --no-deps search-indexer --screener.mode=rebuild-and-exit --spring.main.web-application-type=none
```

A successful command closes the process; a failed rebuild exits with an application startup
error. The serving mode logs failures and retries without taking SQL rankings offline.

## Verification

```powershell
.\gradlew.bat :screener-service:test :database-migrations:test :screener-service:bootJar
```

Tests use isolated real TimescaleDB 2.29.1/PG18 and Elasticsearch 8.11.0 containers. Fixtures
publish through the V012 guards, including original/final counts, factors, exact membership
and lineage. Coverage includes pending/failed runs with partial scores, as-of/model/universe
filters, SQL injection resistance, pinned supersessions, ties, null scores, exclusions,
freshness, stable identity through renames, bulk/transport/dependency failures, index deletion,
checkpoint loss, deterministic replay and the rebuild command. CI runs screener checks with
the existing migration/scoring/parity suite. This does not certify historical investment
performance or a production-scale deployment.

Verified on 2026-09-21: 16 screener, 70 scoring and 3 migration tests passed with no
failures/errors/skips; all 85 Python tests and Ruff passed. Screener/migration bootJars,
Compose validation and diff checks passed. Verification used isolated fixtures and did not
migrate the live application database or rebuild its Elasticsearch index.
