-- Ownership transition: first upgrade must run as the existing owner/administrator.
-- Passwords/login are provisioned separately; no credentials belong in Flyway history.
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='quant_migrator') THEN CREATE ROLE quant_migrator NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS; END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='quant_auth') THEN CREATE ROLE quant_auth NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS; END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='quant_portfolio') THEN CREATE ROLE quant_portfolio NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS; END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='quant_ingestion') THEN CREATE ROLE quant_ingestion NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS; END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='quant_scoring') THEN CREATE ROLE quant_scoring NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS; END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='quant_screener') THEN CREATE ROLE quant_screener NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS; END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='quant_search') THEN CREATE ROLE quant_search NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS; END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='quant_research') THEN CREATE ROLE quant_research NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS; END IF;
END $$;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
DO $$ DECLARE item RECORD; schema_name TEXT;
BEGIN
    FOR schema_name IN SELECT unnest(ARRAY['identity','portfolio','reference','operations','market_data','fundamentals','research']) LOOP
        EXECUTE format('ALTER SCHEMA %I OWNER TO quant_migrator',schema_name);
    END LOOP;
    FOR item IN SELECT n.nspname,c.relname,c.relkind FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
        WHERE (n.nspname IN ('identity','portfolio','reference','operations','market_data','fundamentals','research')
            AND c.relname <> 'flyway_schema_history')
           OR (n.nspname='public' AND c.relname IN ('users','portfolios','tick_data','market_bars','fundamental_snapshots','factor_scores'))
    LOOP
        IF item.relkind IN ('r','p') THEN EXECUTE format('ALTER TABLE %I.%I OWNER TO quant_migrator',item.nspname,item.relname);
        ELSIF item.relkind='v' THEN EXECUTE format('ALTER VIEW %I.%I OWNER TO quant_migrator',item.nspname,item.relname);
        ELSIF item.relkind='S' THEN EXECUTE format('ALTER SEQUENCE %I.%I OWNER TO quant_migrator',item.nspname,item.relname);
        END IF;
    END LOOP;
    FOR item IN SELECT p.oid::regprocedure signature FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
        WHERE n.nspname IN ('reference','operations','market_data','fundamentals','research')
    LOOP EXECUTE format('ALTER FUNCTION %s OWNER TO quant_migrator',item.signature); END LOOP;
    EXECUTE format('GRANT CONNECT,CREATE ON DATABASE %I TO quant_migrator',current_database());
END $$;
GRANT USAGE,CREATE ON SCHEMA public TO quant_migrator;
ALTER DEFAULT PRIVILEGES FOR ROLE quant_migrator REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;
ALTER DEFAULT PRIVILEGES REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;

-- Flyway holds a history-table read lock on another connection during migrations.
-- GRANT is compatible; ALTER OWNER would deadlock against that lock during upgrades.
GRANT SELECT,INSERT,UPDATE,DELETE ON operations.flyway_schema_history TO quant_migrator;
