# Dated universe snapshot import

Phase 2 deliberately imports dated S&P 500 and Nasdaq-100 snapshots instead of treating the
current ticker list as historical truth. Full official constituent history is not available as
an unrestricted free feed, so real snapshot files must be supplied and their source recorded.

Place snapshot CSV files in this directory. The directory is mounted read-only at
`/reference-data` in the `market-data-producer` container. `snapshot-template.csv` shows the
accepted columns.

Required columns:

- `symbol`, `legal_name`, `cik`, and `exchange_mic`;
- each member must have a CIK or a FIGI after enrichment;
- when an issuer has multiple included share classes, exactly one must set
  `primary_liquid_class=true`.

Optional columns:

- `figi`, `security_type`, `share_class`, `currency`, `domicile`, and `sic`;
- `source_classification`, `mapped_sector`, and `primary_liquid_class`.

To import one file, set the `REFERENCE_DATA_IMPORT_*` variables shown in `.env.example` and
recreate `market-data-producer`. Imports are transactional and content-idempotent, so restarting
with the same dated file reuses the existing snapshot.

Use `CURRENT_SNAPSHOT_FORWARD` for a dated current snapshot that will be maintained from the
project start date. `CURRENT_CONSTITUENTS_BACKTEST` is stored with the mandatory
`SURVIVORSHIP_BIASED` label. ETF holdings must use `ETF_HOLDINGS_PROXY`.

The SEC company-ticker/exchange JSON and Nasdaq Trader symbol directories can enrich supplied
membership lists with CIK and listing information. They do not establish complete historical
S&P 500 or Nasdaq-100 membership.
