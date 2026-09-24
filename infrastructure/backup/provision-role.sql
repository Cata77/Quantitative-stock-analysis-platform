-- Execute once as a database administrator; provision LOGIN credentials separately.
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='quant_backup') THEN
        CREATE ROLE quant_backup NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
    END IF;
END $$;
GRANT pg_read_all_data TO quant_backup;
SELECT format('GRANT CONNECT ON DATABASE %I TO quant_backup',current_database()) \gexec
-- Backup covers sensitive identity data too. Protect its credentials and encrypted output.
