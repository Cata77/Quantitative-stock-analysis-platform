-- New tables/functions are denied until deliberately added here; no broad future write grants.
-- P2s (V025): the Elasticsearch indexer role keeps no privileges; an administrator may drop it.
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname='quant_search') THEN
        REVOKE ALL ON ALL TABLES IN SCHEMA reference,operations,market_data,fundamentals,research FROM quant_search;
        REVOKE ALL ON SCHEMA reference,operations,market_data,fundamentals,research FROM quant_search;
    END IF;
END $$;
REVOKE ALL ON SCHEMA reference,operations,market_data,fundamentals,research FROM PUBLIC;
REVOKE ALL ON ALL TABLES IN SCHEMA reference,operations,market_data,fundamentals,research FROM PUBLIC;
REVOKE EXECUTE ON ALL FUNCTIONS IN SCHEMA reference,operations,market_data,fundamentals,research FROM PUBLIC;
REVOKE ALL ON public.users,public.portfolios,public.market_bars,public.fundamental_snapshots,public.factor_scores FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO quant_auth,quant_portfolio,quant_scoring,quant_research;
GRANT SELECT,INSERT,UPDATE ON public.users TO quant_auth;
GRANT SELECT,INSERT,UPDATE,DELETE ON public.portfolios TO quant_portfolio;
GRANT SELECT,INSERT,UPDATE ON public.market_bars,public.fundamental_snapshots,public.factor_scores TO quant_scoring;
GRANT SELECT ON public.market_bars,public.fundamental_snapshots,public.factor_scores TO quant_research;
GRANT USAGE ON SCHEMA reference,operations,market_data,fundamentals,research TO quant_ingestion,quant_scoring;
GRANT USAGE ON SCHEMA reference,research,operations TO quant_screener;
GRANT USAGE ON SCHEMA reference,market_data,fundamentals,research,operations TO quant_research;
GRANT SELECT ON ALL TABLES IN SCHEMA reference TO quant_ingestion,quant_scoring,quant_screener,quant_research;
GRANT SELECT ON ALL TABLES IN SCHEMA market_data,fundamentals TO quant_ingestion,quant_scoring,quant_research;
GRANT SELECT ON ALL TABLES IN SCHEMA research TO quant_scoring,quant_screener,quant_research;
GRANT INSERT,UPDATE ON ALL TABLES IN SCHEMA reference TO quant_ingestion;
GRANT INSERT,UPDATE ON reference.instrument_symbols TO quant_scoring;
GRANT INSERT,UPDATE ON ALL TABLES IN SCHEMA market_data,fundamentals TO quant_scoring;
GRANT INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA research TO quant_scoring;
-- P2b: RSI of a publication reads only the adjusted daily bars; no other market or fundamentals table.
GRANT USAGE ON SCHEMA market_data TO quant_screener;
GRANT SELECT ON market_data.daily_bar_observations TO quant_screener;
-- P2b: EBITDA_FROM_SOURCE_V1 reads D&A from the retained SEC response behind the published
-- operating income facts; read-only, no other fundamentals or operations table.
GRANT USAGE ON SCHEMA fundamentals TO quant_screener;
GRANT SELECT ON fundamentals.fundamental_facts,fundamentals.filings,operations.source_artifacts TO quant_screener;
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
GRANT INSERT,UPDATE ON operations.data_quality_issues,operations.dead_letter_records,operations.dead_letter_replays,
    operations.kafka_event_receipts,operations.kafka_inbox TO quant_scoring;
GRANT UPDATE ON operations.ingestion_run_items,operations.ingestion_runs TO quant_scoring;
-- P2s: the scoring worker claims, acknowledges and retries outbox events directly (no Kafka relay).
GRANT UPDATE ON operations.outbox_events TO quant_scoring;
GRANT INSERT,UPDATE ON operations.data_coverage TO quant_scoring,quant_ingestion;
GRANT INSERT,UPDATE,DELETE ON operations.data_watermarks TO quant_scoring,quant_ingestion;
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA reference,market_data,fundamentals TO quant_ingestion,quant_scoring,quant_research;
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA research TO quant_scoring;
GRANT EXECUTE ON FUNCTION operations.reconcile_ingestion_coverage(TEXT) TO quant_ingestion,quant_scoring;
