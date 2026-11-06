-- P2s, final step: three application identities remain.
--   quant_ingestion  collector; since stage B it also writes canonical data, the inbox and the DLQ
--   quant_scoring    Python publisher (quant_research.publication): research writes, reads its inputs
--   quant_web        single web backend; replaces quant_auth, quant_portfolio and quant_screener
-- quant_research stays the read-only research identity. The current grants live in
-- R__security_grants.sql; this migration removes what the retired processes held.
--
-- Roles are cluster-wide. Like V014, the block below only creates quant_web where the migrating
-- identity may do so (a fresh test database or an administrator). The restricted migrator
-- (NOCREATEROLE) needs infrastructure/bootstrap-roles.sql to be run first; otherwise this
-- migration fails without changing anything. LOGIN/passwords are provisioned separately.
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='quant_web') THEN
        CREATE ROLE quant_web NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
    END IF;
END $$;

-- The canonical consumer no longer runs as quant_scoring (scoring-service was removed): exactly the
-- writes it held move to quant_ingestion (R__security_grants). The publisher keeps its research
-- writes, its reads and the audited input reviews (V020/V024).
REVOKE INSERT,UPDATE ON reference.instrument_symbols FROM quant_scoring;
REVOKE INSERT,UPDATE ON ALL TABLES IN SCHEMA market_data,fundamentals FROM quant_scoring;
REVOKE INSERT,UPDATE ON operations.data_quality_issues,operations.dead_letter_records,operations.dead_letter_replays,
    operations.kafka_event_receipts,operations.kafka_inbox,operations.data_coverage FROM quant_scoring;
REVOKE INSERT,UPDATE,DELETE ON operations.data_watermarks FROM quant_scoring;
REVOKE UPDATE ON operations.ingestion_run_items,operations.ingestion_runs,operations.outbox_events FROM quant_scoring;
REVOKE INSERT,UPDATE ON public.market_bars,public.fundamental_snapshots,public.factor_scores FROM quant_scoring;
-- The function is a repeatable migration: on a fresh database it does not exist yet at this point.
DO $$ BEGIN
    IF to_regprocedure('operations.reconcile_ingestion_coverage(text)') IS NOT NULL THEN
        REVOKE EXECUTE ON FUNCTION operations.reconcile_ingestion_coverage(TEXT) FROM quant_scoring;
    END IF;
END $$;

-- Retired service identities keep no privileges; an administrator may drop them afterwards
-- (after checking that they have no sessions), as was done for quant_search.
DO $$ DECLARE retired TEXT;
BEGIN
    FOREACH retired IN ARRAY ARRAY['quant_auth','quant_portfolio','quant_screener'] LOOP
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname=retired) THEN
            EXECUTE format('REVOKE ALL ON ALL TABLES IN SCHEMA public,reference,operations,market_data,fundamentals,research FROM %I', retired);
            EXECUTE format('REVOKE ALL ON ALL FUNCTIONS IN SCHEMA reference,operations,market_data,fundamentals,research FROM %I', retired);
            EXECUTE format('REVOKE ALL ON SCHEMA public,reference,operations,market_data,fundamentals,research FROM %I', retired);
        END IF;
    END LOOP;
END $$;
