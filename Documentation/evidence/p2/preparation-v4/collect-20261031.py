"""Resume dated collection from existing checkpoints; no retroactive adjustment vintage."""
import runpy
import sys
from pathlib import Path


def main():
    mode = sys.argv[1]
    port = sys.argv[2]
    if mode not in {"raw", "adjusted"}:
        raise ValueError("Expected raw or adjusted")
    sys.argv = ["operate.py", "noop"]
    helpers = runpy.run_path("build/local-step3/operate.py")
    out = Path.cwd() / "build/p2-final-20261031/collection"
    out.mkdir(parents=True, exist_ok=True)
    if (out / (mode + "-" + port + "-launch.json")).exists():
        raise ValueError("Inspect existing launch before starting another worker on this port")
    helpers["start"].__globals__["OUT"] = out
    settings = helpers["env"]()
    settings.update(
        MARKET_DATA_DB_URL="jdbc:postgresql://127.0.0.1:6543/quant_platform",
        MARKET_DATA_ENABLED="true", SEC_FUNDAMENTALS_ENABLED="false",
        MARKET_DATA_LATEST_BARS_ENABLED="true", MARKET_DATA_BACKFILL_ENABLED="false",
        MARKET_DATA_FUNDAMENTALS_ENABLED="false", REFERENCE_DATA_IMPORT_ENABLED="false",
        INGESTION_MODE="backfill",
        INGESTION_START_DATE="2025-10-29" if mode == "adjusted" else "2026-10-30",
        INGESTION_END_DATE="2026-10-30", INGESTION_PRICE_HISTORY_UNIVERSE_DATE="2026-10-31",
        INGESTION_SYNC_TIMEOUT="2h", INGESTION_ITEMS_PER_CYCLE="1000",
        INGESTION_SCHEDULES_ENABLED="false", MARKET_DATA_PORT=port,
        DAILY_PRICE_SYMBOLS_PER_REQUEST="200", DAILY_PRICE_RAW_ENABLED=str(mode == "raw").lower(),
        DAILY_PRICE_CORPORATE_ACTIONS_ENABLED="false",
        DAILY_PRICE_ADJUSTED_ENABLED=str(mode == "adjusted").lower(),
        SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE="3", FFIEC_BULK_FILE="", FFIEC_IDENTITY_MANIFEST="",
    )
    helpers["start"]("market-data-producer", mode + "-" + port,
                     ["--ingestion.delivery.batch-size=1000"], settings)


if __name__ == "__main__":
    main()
