-- P2s: search reads PostgreSQL directly; the Elasticsearch rebuild checkpoint has no consumer.
-- Its rows only describe old index builds (alias swaps), not platform data. The local row
-- was recorded in Documentation/evidence/p2s/README.md before this migration.
-- The quant_search role is cluster-wide and Flyway (NOCREATEROLE) cannot drop it:
-- R__security_grants revokes its privileges; an administrator may drop the role afterwards.
SET LOCAL lock_timeout = '5s';

LOCK TABLE operations.search_rebuild_checkpoints IN ACCESS EXCLUSIVE MODE;

-- RESTRICT refuses unexpected views, foreign keys or functions depending on the table.
DROP TABLE operations.search_rebuild_checkpoints RESTRICT;
