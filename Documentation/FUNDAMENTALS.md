# Filings and canonical fundamentals (Phase 5)

## Runtime and storage

SEC collection uses submissions history, dated submission archive pages, and one Company Facts
response per issuer. The response is cached durably within a run and shared across share classes.
A filing checkpoint resumes interrupted staging. Each event uses the existing instrument-keyed
outbox/inbox path; canonical filings, facts, and profile observations commit with the inbox.
Unchanged filings deduplicate at issuer level.

V010 owns filings, metric definitions, versioned mappings, facts, instrument share links,
regulated entities, issuer ownership evidence, and regulatory facts. R__fundamental_views
provides statement and cutoff access. Apply central Flyway before deploying these services.
Do not modify an already-applied versioned migration or reset the application volume.

Source bundles retain original SEC JSON response strings and URLs, retrieval time, parser
version, and a SHA-256 manifest in PostgreSQL source_artifacts. FFIEC bundles retain the exact
UTF-8 schedule text and identity manifest. Database backups must include these artifacts.
This bounded implementation stores bundles in JSONB (SEC response limit 32 MiB, FFIEC file
limit 50 MB); it does not download the SEC-wide ZIP archive or original inline-XBRL exhibits.
Large-scale archival/object-storage ingestion remains a separate extension.

## Enable SEC ingestion

Import both complete universe snapshots and run the scoring consumer. Configure the producer:

~~~dotenv
MARKET_DATA_ENABLED=true
SEC_FUNDAMENTALS_ENABLED=true
SEC_USER_AGENT=Your organization actual-contact@your-domain.example
SEC_FILINGS_START_DATE=2010-01-01
SEC_REQUEST_SPACING=200ms
MARKET_DATA_LATEST_BARS_ENABLED=false
MARKET_DATA_BACKFILL_ENABLED=false
INGESTION_MODE=catch-up-and-serve
~~~

Replace the example contact with your real contact. SEC-only operation does not need an Alpaca
calendar or credentials. Enable the two price settings separately when prices are also wanted.
The optional legacy Alpha Vantage OVERVIEW adapter remains disabled by default and is never
read by the canonical fact queries.

The shared PostgreSQL request budget coordinates SEC calls across producer instances using this
database. HTTP 403/429 defers the budget, respecting bounded Retry-After seconds or HTTP dates.
Other failures use durable bounded retries. The default spacing allows five requests per second;
the SEC currently limits a user to ten across all machines, so unrelated clients also count.
See [SEC access guidance](https://www.sec.gov/about/developer-resources).

SEC jobs refresh daily, keyed by retrieval date, configuration, and snapshot pair. An unchanged
same-day restart reuses the work. An audited force-refresh uses a new request ID and reason,
as described in [ingestion commands](INGESTION-STATE.md). SEC_FILINGS_START_DATE controls filing
history; ingestion start/end select the universe/run boundary, not the facts' availability.
A historical load of current Company Facts does not certify historical availability.

## Canonical semantics and cutoff queries

The supported forms are 10-K, 10-Q, and their amendments. Unsupported forms or missing CIKs
produce inspectable collection/profile reasons. SEC Company Facts supplies standard entity-wide
concepts, not issuer-specific extensions or share-class dimensions; see the
[SEC API contract](https://www.sec.gov/search-filings/edgar-application-programming-interfaces).

Mappings retain source concept, unit, period, dimensions, priority, and version. USD-only monetary
facts are accepted by v1; other units, invalid periods/signs, dimensions, and conflicting facts
remain stored but unavailable. Capex is a positive outflow. Negative income is valid.
Operating income is not silently renamed EBIT; stockholders' equity is not common equity;
weighted-average diluted shares are not outstanding shares for market capitalization.
Issuer-wide outstanding shares are linked only when a single effective common share class exists.

Every filing retains acceptance, filing, observation and ingestion times, original accession,
amendment accession where identifiable, source artifact, parser and mapping versions.
Availability is conservatively the first observed version of the downloaded facts, not fiscal
period end or a retroactively assumed acceptance timestamp. Same-accession replacement first
selects the latest observed filing bundle, so removed facts cannot survive from that old bundle.
An amendment with another accession preserves the original and supersedes facts it reports.

~~~sql
SELECT * FROM fundamentals.facts_as_of(
    'ISSUER-UUID'::uuid, '2026-09-15T20:00:00Z'::timestamptz, 'sec-us-gaap-v1');
SELECT * FROM fundamentals.statements WHERE issuer_id='ISSUER-UUID'::uuid;
SELECT * FROM fundamentals.regulatory_facts_as_of(
    'ISSUER-UUID'::uuid, '2026-09-15T20:00:00Z'::timestamptz);
~~~

PointInTimeFundamentals returns annual and TTM values with source fact IDs and maximum
availability. TTM uses a reported annual duration, four contiguous quarters, or annual plus
current YTD minus comparable prior YTD. Fiscal-week alignment has bounded tolerance.
Gaps, ambiguity and non-additive metrics return explicit missing reasons. Phase 6 will assemble
the entire scoring cross-section; collection completion alone is not factor completeness.

## FFIEC file and identity manifest

Download a CDR tab-delimited schedule, extract it locally, and supply both FFIEC_BULK_FILE and
FFIEC_IDENTITY_MANIFEST. Compose can read files under its existing /reference-data mount.
The manifest must contain:

~~~json
{
  "sha256": "SHA256_OF_EXACT_UTF8_SCHEDULE",
  "sourceUri": "https://cdr.ffiec.gov/public/PWS/DownloadBulkData.aspx",
  "periodEnd": "2025-12-31",
  "availableAt": "2026-02-15T12:00:00Z",
  "entities": [{
    "cik": "0000019617",
    "rssd": "852218",
    "legalName": "JPMorgan Chase Bank, National Association",
    "effectiveFrom": "2025-12-31",
    "scope": "SUBSIDIARY_ONLY",
    "evidenceUri": "https://jpmorganchaseco.gcs-web.com/ir/sec-other-filings/other-us-regulatory-filings/"
  }]
}
~~~

This illustrates the shape, not a ready-to-import reviewed historical manifest. Supply actual
publication time, ownership effective dates, evidence and checksum for the downloaded period.
A listed parent CIK is not its bank subsidiary RSSD: JPMorgan's issuer disclosure identifies
RSSD 852218 as its bank, while the parent is a separate entity. See
[issuer regulatory filing instructions](https://jpmorganchaseco.gcs-web.com/ir/sec-other-filings/other-us-regulatory-filings/).
Tests exercise this subsidiary relationship and synthetic same-entity/correction cases.

The v1 mapping accepts RCOAP793/RCFAP793 and RCOWP793/RCFWP793, retaining the separate reported
capital approaches. Ratios remain fractions, without a second percent conversion; the
[FFIEC capital guide](https://test.cdr.ffiec.gov/CDRDownload/IF/UserGuide/v162/FFIEC%20UBPR%20User%20Guide%20Capital%20Analysis--Page%2011_2025-01-08.PDF)
multiplies reported ratios by 100 for percentage display.

Cutoff access permits only SAME_LEGAL_ENTITY links effective for the period. It selects the
latest observed report before filtering quality/scope, so corrections can withdraw an old
value or approach without deleting its history. SUBSIDIARY_ONLY data remains auditable and
cannot certify a listed parent's capital anchor. A missing file, checksum mismatch, duplicate
RSSD, absent mapped values, or invalid number fails the import.

The bank profile still requires additional canonical credit facts and applicable capital
requirements before scoring. Insurers without validated parent solvency/operating facts,
REITs without validated GAAP-to-FFO/AFFO reconciliation, and ambiguous financial business
types explicitly receive MODEL_NOT_SUPPORTED. GAAP facts are retained in all cases.

## Provider evidence and use limits

Verification uses deterministic HTTP fixtures and isolated real TimescaleDB/Kafka containers.
It does not run an account-backed full-universe import or certify commercial redistribution.
Retain source manifests and review storage, research, derived display, backup, and redistribution
rights before any commercial rollout; no paid source has been adopted.

The Apple statement fixture uses 2023 revenue USD 383,285 million, operating cash flow
110,543 million and capex 10,959 million from its
[2023 10-K](https://www.sec.gov/Archives/edgar/data/320193/000032019323000106/aapl-20230930.htm).
Its [2024 Q3 revenue note](https://www.sec.gov/Archives/edgar/data/320193/000032019324000081/R9.htm)
reports current/prior nine-month revenue of 296,105/293,787 million.
The derived TTM is 385,603 million. Fixture observation timestamps are simulated; these values
do not establish what this application knew historically. FFIEC ratio values in tests are
synthetic contract values, not verified production observations.

Run verification from the repository root with Docker Desktop running:

~~~powershell
.\gradlew.bat :database-migrations:test :market-data-producer:test :scoring-service:test :market-data-producer:bootJar :scoring-service:bootJar
docker compose -f infrastructure/docker-compose.yml config --quiet
git diff --check
~~~

Coverage includes clean/upgrade migrations, provider pagination/quota contracts, annual/TTM
lineage, amendments, same-accession corrections/removals, invalid units/dimensions, share-class
deduplication, unsupported profiles, issuer mismatch rollback, regulatory scope/corrections,
and interruption/restart from the cached SEC source.
