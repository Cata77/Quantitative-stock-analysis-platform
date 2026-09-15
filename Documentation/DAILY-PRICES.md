# Daily prices and corporate actions (Phase 4)

## Runtime

The durable coordinator collects the dated S&P 500/Nasdaq-100 union by instrument ID.
It requests multi-symbol daily pages, with a default batch of 100 and an explicit SIP feed.
Both raw and split-and-dividend-adjusted series use the phase 3 outbox/inbox delivery path.
Legacy per-symbol polling and the demonstration scoring projection are separate from these
canonical daily observations.

Each page commits the original provider response, hash, source URI, parser version,
observations, and per-instrument checkpoint together. A restart retains the exact requested
symbol set and page token. Identical daily rows are idempotent, including repeats on another
page. Conflicting rows inside the same paginated request and repeated tokens cause retry;
a later correction request appends a new canonical revision.

The shared PostgreSQL request budget spaces daily requests by 350 ms by default. HTTP 429
postpones this budget and persists the retry delay in job attempts. External clients using
the same account must share the quota separately. Provider calls occur outside transactions.

The current New York date is excluded. The preceding date becomes eligible only after its
whole daily interval is outside the configured 16-minute publication delay. Calendar holidays
and early closes remain explicit. Empty daily responses after all pages create blocking
MISSING_SESSION issues, never a silently shortened history. A successful retry resolves the
gap; canonical acceptance is still required before coverage advances.

GET /internal/ingestion/status includes latestEligibleSession, completeThrough, feed, and
consolidatedLiquidityEligible. completeThrough requires contiguous accepted coverage across
the required runs, including corporate-action work. HTTP health is independent of freshness.

## Storage and time

Central Flyway V009 creates market_data.daily_bar_observations, market_data.corporate_actions,
and the shared request budget. The daily table is a TimescaleDB hypertable with one-year
chunks; its primary key contains session_date. No retention or compression policy is enabled.
Range indexes support instrument/session/provider reads.

Daily prices keep currency, feed, OHLCV, VWAP/trade count, adjustment mode, retrieval vintage,
source artifact/revision, availability/observation/ingestion times, and quality state.
Provider corrections append observations. The consumer validates daily OHLCV and the actual
exchange calendar inside the same transaction as its inbox and journal.

Views/functions:

- market_data.latest_daily_bars: latest valid revision within each dataset/mode/vintage.
- market_data.daily_bars_as_of(dataset, adjustment, basis, cutoff): one consistent series,
  using only versions available, observed, and ingested by the cutoff.
- market_data.consolidated_raw_liquidity: SIP raw close times volume; IEX rows are excluded.
- market_data.latest_corporate_actions: latest observed revision of each action.

Use raw close for market capitalization and consolidated raw volume for liquidity.
Select one explicit adjusted vintage across the entire momentum window.

Alpaca's asof parameter controls symbol mapping, not historical adjustment knowledge.
Requests use asof=- and effective symbols. adjustment_as_of records the retrieval date;
it does not claim the provider reconstructs the adjustment state at an old economic date.
Adjusted requests refuse an older vintage, including a request that crosses New York midnight.
New retrieval dates produce separate adjusted runs. This intentionally refreshes the requested
adjusted history, while complete raw jobs remain reusable. It can be expensive for long windows.
Backfills discovered today cannot supply facts to an older knowledge cutoff.

## Corporate actions

Actions are requested by process_date, including non-trading days and incomplete provider
records. The recent 14-day window is revisited under a dated job configuration because Alpaca
may deliver actions late. Older corrections require an explicit force-refresh.

| Action | Behavior |
|---|---|
| Forward/reverse split | Store positive old/new ratios and ex-date; raw and adjusted bars remain separate |
| Cash dividend | Store nonnegative cash amount, ex/record/payment dates and original terms |
| Same-security symbol rename | Close the old symbol period and open the new period on the same instrument, only with matching CUSIPs and unambiguous identity |
| Merger or spinoff | Preserve terms and resolvable related instrument; block completeness pending continuity/terminal-return review |
| Changed CUSIP, ambiguous identity, incomplete/unsupported action | Preserve raw content with REVIEW_REQUIRED and a blocking issue |

The consumer never joins the return histories of two instruments merely because an action
mentions both. Spinoff distribution returns are not covered by the chosen split/dividend
adjustment mode. Terminal returns, merger consideration, and distribution reconciliation
remain required before affected histories can pass the later historical-research gate.
Identical action revisions are not applied again, even when they appear in different batches.

## Configuration and operation

Collection remains disabled by default. Import both dated universe snapshots, apply central
Flyway, and run the canonical scoring consumer before enabling collection.

The producer application configuration and infrastructure/.env.example document:

| Setting | Default |
|---|---|
| MARKET_DATA_ENABLED | false |
| DAILY_PRICE_FEED | sip |
| DAILY_PRICE_SYMBOLS_PER_REQUEST | 100 |
| DAILY_PRICE_PAGE_LIMIT | 10000 |
| DAILY_PRICE_REQUEST_SPACING | 350ms |
| DAILY_PRICE_PUBLICATION_DELAY | 16m |
| DAILY_PRICE_ADJUSTED_ENABLED | true |
| DAILY_PRICE_CORPORATE_ACTIONS_ENABLED | true |
| DAILY_PRICE_ACTIONS_REFRESH_DAYS | 14 |

Set ALPACA_API_KEY_ID and ALPACA_API_SECRET_KEY in the ignored local environment.
Compose forwards the credentials and daily-ingestion settings to the producer.
For an explicit bounded load, use the existing CLI mode, for example:

~~~powershell
.\gradlew.bat :market-data-producer:bootRun --args="--ingestion.mode=backfill --ingestion.start-date=2026-09-01 --ingestion.end-date=2026-09-10 --ingestion.schedules-enabled=false --market-data.enabled=true"
~~~

Both snapshots must support the requested dates. A current snapshot is not historical index
membership. Use force-refresh with stable request-id and reason for corrections, as described
in [durable operating notes](INGESTION-STATE.md). Do not enable the legacy producer alongside
the daily path against the same quota.

## Provider contract checked 2026-09-15

[Alpaca's subscription documentation](https://docs.alpaca.markets/us/docs/about-market-data-api)
lists Basic historical equity access since 2016, a 15-minute exclusion window, and 200 requests
per minute. Real-time Basic equity coverage is IEX. Account entitlements can still reject
requests; this implementation does not silently fall back from SIP to IEX.

[Historical bars](https://docs.alpaca.markets/us/reference/stockbars) documents multi-symbol
pagination, explicit feed, combined adjustment flags, and asof symbol mapping.
[Corporate actions](https://docs.alpaca.markets/us/reference/corporateactions-1) documents
process-date filtering, a 1,000-record page maximum, incomplete records, and possible delays.
Field mapping was cross-checked with the
[official SDK models](https://github.com/alpacahq/alpaca-py/blob/master/alpaca/data/models/corporate_actions.py).

API access is not a verified right to redistribute source data or derived outputs.
Account-specific storage, research, display, backup, cancellation and redistribution rights
must be established from the applicable
[Alpaca agreements](https://alpaca.markets/disclosures) before a public rollout.
No live account entitlement or full-universe import was exercised in this implementation run.

## Verification

Provider fixtures cover request parameters, pagination tokens, malformed OHLCV, unexpected
symbols, adjustment vintage, publication delay, action subjects, and quota backoff.
Real TimescaleDB tests cover partial-page restart, duplicate delivery, repeated tokens,
calendar gaps/recovery, corrections/cutoffs, raw/adjusted split-dividend fixtures, corporate
action review, symbol rename, rollback on a holiday bar, and latest eligible/complete status.
Central migration tests cover clean installation, repeat validation, upgrade from V008,
daily hypertable creation, and absence of retention/compression policies.

The existing Kafka crash/replay regression tests also run against real Kafka containers.
Application database volumes and live provider accounts are not used by these tests.

Verified on 2026-09-15: 59 tests passed (27 producer, 30 scoring, 2 migrations), with no
failures, errors, or skips. Both service bootJar builds, Compose configuration validation,
and git diff --check passed. Fourteen tests are new phase 4 cases.
