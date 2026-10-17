-- Only objects with no application consumers are retired here. The three legacy
-- JPA/research tables in public are still required by their supported code paths.
-- Keep populated installations intact until their old data is reviewed separately.
SET LOCAL lock_timeout = '5s';

LOCK TABLE public.tick_data, operations.month_end_score_jobs IN ACCESS EXCLUSIVE MODE;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM public.tick_data) THEN
        RAISE EXCEPTION 'Cleanup refused: public.tick_data contains data';
    END IF;
    IF EXISTS (SELECT 1 FROM operations.month_end_score_jobs) THEN
        RAISE EXCEPTION 'Cleanup refused: operations.month_end_score_jobs contains data';
    END IF;
END
$$;

-- RESTRICT also protects unexpected views, foreign keys and schema contents.
-- Flyway executes this migration transactionally, including Timescale metadata.
DROP TABLE public.tick_data RESTRICT;
DROP TABLE operations.month_end_score_jobs RESTRICT;
DROP SCHEMA identity RESTRICT;
DROP SCHEMA portfolio RESTRICT;
