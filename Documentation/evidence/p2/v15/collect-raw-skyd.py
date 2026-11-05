"""Audited raw-price force refresh after the PSKY -> SKYD reference correction (2026-10-31).

The original raw runs for 2026-10-06..2026-10-30 resolved PSKY, which no longer trades;
they are retained unchanged. This request re-collects the same sessions with the
corrected symbol mapping (symbolAsOf 2026-10-31) under a stable request id and reason.
"""
import runpy
import sys
from pathlib import Path

sys.argv = ['operate.py', 'noop']
helpers = runpy.run_path('build/local-step3/operate.py')
helpers['start'].__globals__['OUT'] = Path.cwd() / 'build/p2-v15-20261031/collection'
settings = helpers['env']()
settings.update(
    MARKET_DATA_DB_URL='jdbc:postgresql://127.0.0.1:6543/quant_platform',
    MARKET_DATA_ENABLED='true', SEC_FUNDAMENTALS_ENABLED='false',
    MARKET_DATA_LATEST_BARS_ENABLED='true', MARKET_DATA_BACKFILL_ENABLED='false',
    MARKET_DATA_FUNDAMENTALS_ENABLED='false', REFERENCE_DATA_IMPORT_ENABLED='false',
    INGESTION_MODE='force-refresh', INGESTION_START_DATE='2026-10-06', INGESTION_END_DATE='2026-10-30',
    INGESTION_REQUEST_ID='p2-20261031-psky-skyd-symbol-correction',
    INGESTION_REASON='reference-symbol-correction-psky-to-skyd-2026-10-06',
    INGESTION_PRICE_HISTORY_UNIVERSE_DATE='2026-10-31',
    INGESTION_SYNC_TIMEOUT='1h', INGESTION_ITEMS_PER_CYCLE='1000',
    INGESTION_SCHEDULES_ENABLED='false', MARKET_DATA_PORT='18093',
    INGESTION_MAX_ATTEMPTS='10', DAILY_PRICE_SYMBOLS_PER_REQUEST='200', DAILY_PRICE_RAW_ENABLED='true',
    DAILY_PRICE_CORPORATE_ACTIONS_ENABLED='false', DAILY_PRICE_ADJUSTED_ENABLED='false',
    SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE='3', FFIEC_BULK_FILE='', FFIEC_IDENTITY_MANIFEST='',
)
helpers['start']('market-data-producer', 'raw-skyd-18093', ['--ingestion.delivery.batch-size=1000'], settings)
