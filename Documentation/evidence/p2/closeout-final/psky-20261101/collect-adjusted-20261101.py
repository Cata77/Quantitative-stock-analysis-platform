"""Adjusted price vintage 2026-11-01 for the P2 closeout publication (2026-11-01).

The 2026-10-31 vintage is complete and stays unchanged. A publication whose cutoff
follows the PSKY reviews has New York date 2026-11-01, and Scoring requires the
adjustment vintage to equal that date. This plans the 253 sessions 2025-10-29..2026-10-30
for the current universe with symbols as of 2026-11-01 (SKYD for PSKY). RAW prices are
not re-collected. Run from the repository root; pass a worker tag (e.g. 1, 2, 3).
"""
import runpy
import sys
from pathlib import Path

tag = sys.argv[1] if len(sys.argv) > 1 else "1"
port = str(18100 + int(tag))
sys.argv = ["operate.py", "noop"]
helpers = runpy.run_path("build/local-step3/operate.py")
out = Path.cwd() / "build/p2-psky-20261101/collection"
out.mkdir(parents=True, exist_ok=True)
helpers["start"].__globals__["OUT"] = out
settings = helpers["env"]()
settings.update(
    MARKET_DATA_DB_URL="jdbc:postgresql://127.0.0.1:6543/quant_platform",
    MARKET_DATA_ENABLED="true", SEC_FUNDAMENTALS_ENABLED="false",
    MARKET_DATA_LATEST_BARS_ENABLED="true", MARKET_DATA_BACKFILL_ENABLED="false",
    MARKET_DATA_FUNDAMENTALS_ENABLED="false", REFERENCE_DATA_IMPORT_ENABLED="false",
    INGESTION_MODE="backfill", INGESTION_START_DATE="2025-10-29", INGESTION_END_DATE="2026-10-30",
    INGESTION_PRICE_HISTORY_UNIVERSE_DATE="2026-11-01",
    INGESTION_SYNC_TIMEOUT="4h", INGESTION_ITEMS_PER_CYCLE="1000",
    INGESTION_SCHEDULES_ENABLED="false", MARKET_DATA_PORT=port,
    INGESTION_MAX_ATTEMPTS="10", DAILY_PRICE_SYMBOLS_PER_REQUEST="200", DAILY_PRICE_RAW_ENABLED="false",
    DAILY_PRICE_CORPORATE_ACTIONS_ENABLED="false", DAILY_PRICE_ADJUSTED_ENABLED="true",
    SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE="3", FFIEC_BULK_FILE="", FFIEC_IDENTITY_MANIFEST="",
)
helpers["start"]("market-data-producer", "adjusted-" + port, ["--ingestion.delivery.batch-size=1000"], settings)
