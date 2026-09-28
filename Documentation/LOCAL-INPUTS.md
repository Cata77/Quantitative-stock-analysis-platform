# Local input setup

Status: Step 3 collection in progress, reviewed 2026-09-26. This is current-universe
local development data, not a historical membership dataset or an executable past score.
Credentials live only in ignored infrastructure/.env. Do not copy that file into documentation.

## Verified identities

| Setting / dataset | UUID |
| --- | --- |
| SCORING_MODEL_RAW_DATASET | 482ffe35-ab2a-40a7-b300-9197c2066e42 |
| SCORING_MODEL_ADJUSTED_DATASET | 9f5e4667-6b14-46da-a8f6-3088c95d9409 |
| SCORING_MODEL_CLASSIFICATION_VERSION | e53b26e0-7e40-4ea1-b6b4-6d2867d8ae24 |
| SEC companyfacts | a79a6547-ad54-4f17-a08e-e8e4afb3f06f |
| Alpaca corporate actions | 7d6fd3b1-3099-4f9f-a176-47c7f8e576e4 |
| S&P 500 snapshot, 2026-09-26 | e6d6670f-f45f-467a-8eb2-9bab9add9074 |
| Nasdaq-100 snapshot, 2026-09-26 | d444039a-db92-4c0a-9491-2a5579dfb09f |

The three scoring settings above are written to the ignored local environment. These UUIDs
belong to this database; a fresh installation must query its own imported identities.
Classification source/version: PUBLIC_SECTOR_LOCAL_REVIEW / local-sectors-2026-09-26.
This is a current public-sector development mapping, not licensed historical GICS.

## Collection scope

Alpaca SIP raw and split/dividend requests, calendar and actions endpoints were verified.
SEC submissions/companyfacts accepted the supplied organization/contact User-Agent.
SEC requires a descriptive real contact identity, not a separately issued API credential.
Provider secrets and contact details are omitted from logs and this guide.

Collection was deliberately enabled in bounded child-process environments. Defaults in
infrastructure/.env remain disabled so starting an unrelated service cannot silently launch
a broad collection. The producer and canonical consumer are enough for Step 3; full-stack
startup and scoring publication are later checklist steps.

Price history uses INGESTION_PRICE_HISTORY_UNIVERSE_DATE=2026-09-26 with explicit start/end,
SEC disabled and corporate actions disabled in that price-only process. It pins current
membership and Alpaca's symbol mapping date without fabricating historical membership or
observation times. Adjusted observations retain their real September 26 retrieval vintage.
For speed, remaining work prioritizes adjusted history from 2025-09-15 through 2026-09-25
and raw history from 2026-08-20 through 2026-09-25, with buffers beyond required offsets.
Earlier raw observations already collected remain preserved. Unneeded older planned work
is not presented as completed coverage.

SEC collection uses the September 26 universe and filings beginning 2022-01-01. All 518
instrument items are accepted (515 issuers, deduplicated filing facts). Foreign forms and
financial/REIT profiles remain explicitly unsupported where applicable.

Corporate actions cover provider process dates 2025-08-01 through 2026-09-25. The all-market
response is retained across 63 pages. The reviewed import uses current subjects plus reverse
same-CUSIP rename chains, through the ordinary durable outbox/canonical consumer. SPCX's
reused ticker is explicitly blocked; old fund actions are not attributed to SpaceX.

## Review and recovery evidence

Local, ignored build/local-step3/ contains:

- Original provider pages, retrieval timestamps and checksums; current asset identities.
- Snapshot review/provenance and dated CSV imports (reviewed files also in reference-data).
- Corporate-action preparation, ImportLocalActions.java and its log.
- identity-review-before.json, identity-review.sql and transaction result: bounded evidence
  of existence and historical symbol intervals for 511 instruments. Existing snapshot rows
  are preserved; earlier intervals are newly observed now, not backdated knowledge.
- corporate-review-before.json and resolve-reviewed-actions.sql/result: 1,745 validated
  split/dividend reviews, current review timestamps and 3,518 restored single-class share
  links. Unsupported reorganizations were not resolved by this procedure.
- Immutable worker jar hashes, launch arguments, logs and PID files. Credentials are never
  included in launch manifests. Do not rebuild a jar in place underneath a running process.
- Failed SEC event and replay log. Dead-letter ID 4f9522ba-e890-4dde-9be6-621727984498;
  replay ID 60e4c6f5-a5ea-4e88-b09d-6991ea3e158b. The original audit row is retained.

The operator helpers in that directory are dated to this local database and reviewed
snapshots. They use the application's IngestionRunStore, BulkDailyCollector and OutboxRelay,
including durable leases, shared request budgets, canonical validation and replay audit.
They are not generic deployment commands. Do not rerun identity scripts against a different
snapshot or silently clear quality flags to make a run appear ready.

## Earlier completion gates (superseded by final September 28 review)

Finish canonical delivery and retries for the required price windows. Inspect per-instrument
missing history, factor availability, quality exclusions and peer sizes; preserve the report.
A successful HTTP download alone does not complete Step 3. Keep the unfinished checklist
items open until that inspection succeeds.

The next genuine monthly signal is September 30, with the fixed decision cutoff October 1
before the next opening. At that time collect the remaining sessions and refresh the adjusted
history to the October 1 vintage. Data downloaded September 26 cannot qualify retroactively
for an August decision. No scoring publication is claimed by this setup work.


## September 28 continuation (verification in progress)

V017 was tested on isolated databases and applied with central Flyway as quant_migrator.
It preserves sec-us-gaap-v1 and adds opt-in sec-us-gaap-v2. The consumer owns
common-equity-zero-preferred-v1: it requires same-filing, same-instant, entity-wide USD
StockholdersEquity and explicit zero PreferredStockValue plus explicit zero preferred
shares issued/outstanding. Missing, dimensioned, conflicting or nonzero evidence does not
qualify. A direct common-equity disclosure is never overridden. Reconciled facts retain
source-fact hashes and accession in source_context; raw facts and source artifacts remain.
The frozen scoring formulas are unchanged. Mapping v2 must be selected explicitly for
collection and scoring; v1 remains the default for compatibility.

The retained-source reprocessing helper prepares an audited force-refresh job. Its source
artifacts identify the original artifact ID, content hash and retrieval time; the new
canonical observations use actual reprocessing timestamps. Reprocessing completion has
not yet been verified. Do not infer completion from this implementation note.

A bounded provider probe reproduced BULK_REQUEST_FAILED: a zero-volume, zero-trade SPCX
bar had vw=0. The client now treats that undefined volume-weighted average as unavailable
only when both volume and trade count are zero. The raw response is retained, OHLC bounds
remain strict, and zero VWAP with positive volume still fails. SPCX's identity exclusion
is preserved. Evidence: build/local-step3/bulk-failure-probe-20260928.json.

September 28 adjusted collection uses a separate actual retrieval vintage; September 26
and 27 observations and attempts are preserved. Final coverage and eligibility gates
remain open until the new reports are verified.


### Step 3 completed - 2026-09-28

This final review supersedes the dated in-progress notes above. All three remaining
Step 3 gates are verified for the current-input scope; every one of the 518 expected
instruments has eligibility or explicit exclusion evidence. Nothing remains to close
this collection/readiness phase. This is not a published or point-in-time historical run.

- Fixed the provider zero-trade/zero-volume VWAP edge case without weakening OHLC checks.
  Added and tested versioned common-equity reconciliation, applied V017 with central
  Flyway, and completed audited SEC v2 reprocessing for all 518 instruments. Original
  artifacts, attempts, mapping v1 and honest observation timestamps remain preserved.
  Local SEC_MAPPING_VERSION and SCORING_MODEL_MAPPING_VERSION now select sec-us-gaap-v2.
- Final canonical audit at 13:33 UTC: 427,709 outbox events PUBLISHED, no undelivered
  backlog, 989,921 retained facts and 405,149 price observations. The one original DLQ
  record remains with its successful, canonically accepted replay; no new dead letters.
- All 518 have the required 20 raw liquidity sessions. 515 have all 253 adjusted sessions,
  including both momentum boundaries and the latest bar, under the September 28 vintage.
  FDXF (82), HONA (72) and Q (225) lack pre-listing/provider history and stay excluded.
  Identity-valid scoring history further excludes SPCX, XOM, CCL, DOC and OKE; retained
  bars are not silently assigned across unreviewed identities. Every eligible row passed
  the required-window audit. Both September 25 dataset runs have valid 518/518 coverage.
- Reviewed all 108 remaining non-missing quality issues across 70 instruments against
  action source content and symbol intervals. Unsupported continuity, distributions,
  renames and ticker reuse retain blocking exclusions. Corporate-action coverage remains
  honestly 448/518 accepted; 70 staged items are quality-blocked, not undelivered events.
  Older planned/failed jobs and gaps remain audited; no full-history watermark is claimed.
- Refreshed all 518 per-instrument input/factor/exclusion records: 279 input-ready,
  49 model-eligible and 469 excluded under the frozen rules. Supported GENERAL peers
  include primary IT 16, Health Care 10 and compatible fallback 49, satisfying 10/20
  thresholds. Missing/ambiguous common equity, stale filings/shares and unsupported
  profiles remain explicit exclusions, not inferred values or silently dropped members.
- Verification passed: 140 Java tests (1 contract, 12 migration, 41 producer, 86 scoring),
  zero failures/errors/skips; 101 Python tests and Ruff. The Java run includes live
  Python parity, target-scale SQL, JFR carrier-pinning and durable DLQ/replay checks.
  All three affected bootJars, Compose validation and git diff --check passed.
  Model formulas and golden outputs are unchanged; reviewed input-integration freeze
  hashes are recorded and the preceding freeze is archived. Elasticsearch was restored
  and is healthy after temporarily freeing memory for verification.

Evidence: ../build/local-step3/readiness-20260928/ contains readiness-summary.json,
readiness-instruments.csv, readiness-cross-section.json, readiness-model-evidence.json,
operations-audit.json, quality-review-details.json and completion-evidence.json with
artifact hashes and test counts. These are local ignored reports; the setup guide
records their scope and results. No credentials are included.

Remaining work belongs to Steps 4 and 5: rebuild/start the application stack and perform
an actual coherent monthly run. The diagnostic retains INVALID_PRE_OPEN_CUTOFF and
SNAPSHOT_NOT_POINT_IN_TIME_COMPLETE and publication_ready=false. September 30 requires
its remaining sessions and the October 1 adjustment vintage/decision cutoff; newly
observed September data cannot certify an earlier historical decision. Historical
research acceptance and the unopened holdout are unchanged.

