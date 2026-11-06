-- New tables/functions are denied until deliberately added here; no broad future write grants.
-- P2s identities (V026): quant_ingestion = collector incl. canonical processing, quant_scoring =
-- Python publisher, quant_web = single web backend, quant_research = read-only research.
-- Retired roles keep no privileges; an administrator may drop them (quant_search after V025;
-- quant_auth, quant_portfolio and quant_screener after V026).
DO $$ DECLARE retired TEXT;
BEGIN
    FOREACH retired IN ARRAY ARRAY['quant_search','quant_auth','quant_portfolio','quant_screener'] LOOP
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname=retired) THEN
            EXECUTE format('REVOKE ALL ON ALL TABLES IN SCHEMA public,reference,operations,market_data,fundamentals,research FROM %I', retired);
            EXECUTE format('REVOKE ALL ON SCHEMA reference,operations,market_data,fundamentals,research FROM %I', retired);
            EXECUTE format('REVOKE USAGE ON SCHEMA public FROM %I', retired);
        END IF;
    END LOOP;
END $$;
REVOKE ALL ON SCHEMA reference,operations,market_data,fundamentals,research FROM PUBLIC;
REVOKE ALL ON ALL TABLES IN SCHEMA reference,operations,market_data,fundamentals,research FROM PUBLIC;
REVOKE EXECUTE ON ALL FUNCTIONS IN SCHEMA reference,operations,market_data,fundamentals,research FROM PUBLIC;
REVOKE ALL ON public.users,public.portfolios,public.market_bars,public.fundamental_snapshots,public.factor_scores FROM PUBLIC;

-- Web backend: accounts, the caller's holdings and the screener read model.
GRANT USAGE ON SCHEMA public TO quant_web,quant_ingestion,quant_scoring,quant_research;
GRANT SELECT,INSERT,UPDATE ON public.users TO quant_web;
GRANT SELECT,INSERT,UPDATE,DELETE ON public.portfolios TO quant_web;
GRANT USAGE ON SCHEMA reference,research,operations,market_data,fundamentals TO quant_web;
GRANT SELECT ON ALL TABLES IN SCHEMA reference,research TO quant_web;
-- P2b: RSI of a publication reads only the adjusted daily bars; no other market or fundamentals table.
GRANT SELECT ON market_data.daily_bar_observations TO quant_web;
-- P2b: EBITDA_FROM_SOURCE_V1 reads D&A from the retained SEC response behind the published
-- operating income facts; read-only, no other fundamentals or operations table.
GRANT SELECT ON fundamentals.fundamental_facts,fundamentals.filings,operations.source_artifacts TO quant_web;

-- Legacy read model of the old research CLI, written by the collector's projection.
GRANT SELECT,INSERT,UPDATE ON public.market_bars,public.fundamental_snapshots TO quant_ingestion;
GRANT SELECT ON public.market_bars,public.fundamental_snapshots,public.factor_scores TO quant_scoring,quant_research;

GRANT USAGE ON SCHEMA reference,operations,market_data,fundamentals,research TO quant_ingestion,quant_scoring;
GRANT USAGE ON SCHEMA reference,market_data,fundamentals,research,operations TO quant_research;
GRANT SELECT ON ALL TABLES IN SCHEMA reference TO quant_ingestion,quant_scoring,quant_research;
GRANT SELECT ON ALL TABLES IN SCHEMA market_data,fundamentals TO quant_ingestion,quant_scoring,quant_research;
GRANT SELECT ON ALL TABLES IN SCHEMA research TO quant_scoring,quant_research;
-- Collector: reference data, staging and, since P2s stage B, canonical data in the same transaction.
GRANT INSERT,UPDATE ON ALL TABLES IN SCHEMA reference TO quant_ingestion;
GRANT INSERT,UPDATE ON ALL TABLES IN SCHEMA market_data,fundamentals TO quant_ingestion;
-- Python publisher.
GRANT INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA research TO quant_scoring;
GRANT SELECT ON operations.source_artifacts,operations.datasets,operations.data_providers TO quant_research;
DO $$ DECLARE item RECORD;
BEGIN
    FOR item IN SELECT tablename FROM pg_tables WHERE schemaname='operations'
        AND tablename <> 'flyway_schema_history'
    LOOP EXECUTE format('GRANT SELECT ON operations.%I TO quant_ingestion,quant_scoring',item.tablename); END LOOP;
END $$;
GRANT INSERT,UPDATE ON operations.calendar_imports,operations.data_providers,operations.datasets,
    operations.data_quality_issues,operations.ingestion_attempts,operations.ingestion_checkpoints,
    operations.ingestion_item_artifacts,operations.ingestion_item_events,operations.ingestion_run_items,
    operations.ingestion_run_snapshots,operations.ingestion_runs,operations.job_definitions,
    operations.outbox_events,operations.provider_request_budgets,operations.source_artifacts TO quant_ingestion;
-- Canonical consumer inside the collector: inbox, receipts and the dead-letter quarantine.
GRANT INSERT,UPDATE ON operations.dead_letter_records,operations.dead_letter_replays,
    operations.kafka_event_receipts,operations.kafka_inbox TO quant_ingestion;
GRANT INSERT,UPDATE ON operations.data_coverage TO quant_ingestion;
GRANT INSERT,UPDATE,DELETE ON operations.data_watermarks TO quant_ingestion;
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA reference,market_data,fundamentals TO quant_ingestion,quant_scoring,quant_research;
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA research TO quant_scoring;
GRANT EXECUTE ON FUNCTION operations.reconcile_ingestion_coverage(TEXT) TO quant_ingestion;
