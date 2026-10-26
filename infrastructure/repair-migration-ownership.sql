-- Run as the existing owner/DB administrator only for an installation whose
-- repeatable migrations were originally applied by that administrator after V014.
-- Then rerun central Flyway as quant_migrator. Fresh installations do not need this.
-- Deliberately exclude flyway_schema_history: Flyway uses concurrent connections
-- and retains the history owner's grants established by V014.
BEGIN;
SET LOCAL lock_timeout = '5s';

ALTER VIEW market_data.latest_daily_bars OWNER TO quant_migrator;
ALTER VIEW market_data.consolidated_raw_liquidity OWNER TO quant_migrator;
ALTER VIEW market_data.latest_corporate_actions OWNER TO quant_migrator;
ALTER VIEW fundamentals.statements OWNER TO quant_migrator;

ALTER FUNCTION market_data.daily_bars_as_of(UUID,TEXT,DATE,TIMESTAMPTZ) OWNER TO quant_migrator;
ALTER FUNCTION fundamentals.facts_as_of(UUID,TIMESTAMPTZ,TEXT) OWNER TO quant_migrator;
ALTER FUNCTION fundamentals.regulatory_facts_as_of(UUID,TIMESTAMPTZ) OWNER TO quant_migrator;
ALTER FUNCTION operations.reconcile_ingestion_coverage(TEXT) OWNER TO quant_migrator;

-- V020 may also have been applied by the administrator. Keep this repair
-- compatible with older installations that do not yet have audited reviews.
DO $$
BEGIN
    IF to_regclass('operations.input_reviews') IS NOT NULL THEN
        ALTER TABLE operations.input_reviews OWNER TO quant_migrator;
    END IF;
    IF to_regprocedure('operations.validate_input_review()') IS NOT NULL THEN
        ALTER FUNCTION operations.validate_input_review() OWNER TO quant_migrator;
    END IF;
END $$;

COMMIT;
