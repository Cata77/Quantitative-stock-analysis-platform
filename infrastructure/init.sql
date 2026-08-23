-- Privileged bootstrap only. Application schemas and tables are owned by Flyway migrations in
-- database-migrations/src/main/resources/db/migration.
CREATE EXTENSION IF NOT EXISTS timescaledb CASCADE;
