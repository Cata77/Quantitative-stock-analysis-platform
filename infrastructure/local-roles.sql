-- INSECURE LOCAL DEVELOPMENT ONLY. Never use these credentials in deployed environments.
\i /bootstrap/bootstrap-roles.sql
ALTER ROLE quant_migrator LOGIN PASSWORD 'local-quant_migrator-development-only';
ALTER ROLE quant_ingestion LOGIN PASSWORD 'local-quant_ingestion-development-only';
ALTER ROLE quant_scoring LOGIN PASSWORD 'local-quant_scoring-development-only';
ALTER ROLE quant_research LOGIN PASSWORD 'local-quant_research-development-only';
ALTER ROLE quant_web LOGIN PASSWORD 'local-quant_web-development-only';
