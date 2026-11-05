-- P2 closeout (2026-11-01): an audited QUALITY_ISSUE review may also target a
-- MISSING_SESSION issue, but only when the database can verify one of two narrow
-- identity explanations. A review never deletes, resolves or edits the issue, never
-- copies or re-dates a price and, as before, is visible only after its recorded_at.
--
-- * SYMBOL_RETIRED_SESSION_RECOVERED_UNDER_SUCCESSOR_SYMBOL: the instrument's symbol
--   changed on or before the session (one symbol row ends where a different one begins)
--   and the same dataset already holds a VALID bar of this instrument for the session,
--   ingested after the issue was detected.
-- * SESSION_BEFORE_INSTRUMENT_LISTING: the session precedes the first effective date of
--   the instrument's symbol history and no VALID bar of the instrument exists for it.
--
-- Every other MISSING_SESSION issue remains unreviewable and blocking.
CREATE OR REPLACE FUNCTION operations.validate_input_review() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE f fundamentals.fundamental_facts; i reference.instruments; q operations.data_quality_issues;
    session_day DATE;
BEGIN
    IF TG_OP <> 'INSERT' THEN RAISE EXCEPTION 'Input reviews are immutable; append a revocation'; END IF;
    NEW.recorded_at := clock_timestamp();
    IF length(coalesce(NEW.evidence->>'rationale','')) < 20
       OR coalesce(NEW.evidence->>'sourceUri','') !~ '^https://'
       OR coalesce(NEW.evidence->>'sourceSha256','') !~ '^[0-9a-f]{64}$' THEN
        RAISE EXCEPTION 'Review requires rationale, HTTPS source URI and retained source SHA-256';
    END IF;
    SELECT * INTO STRICT i FROM reference.instruments WHERE instrument_id=NEW.instrument_id;
    IF NEW.kind='QUALITY_ISSUE' THEN
        SELECT * INTO STRICT q FROM operations.data_quality_issues WHERE data_quality_issue_id=NEW.issue_id;
        IF q.instrument_id IS DISTINCT FROM NEW.instrument_id
           OR q.issue_type NOT IN ('CORPORATE_ACTION_REVIEW','SECURITY_IDENTITY_REVIEW','MISSING_SESSION') THEN
            RAISE EXCEPTION 'Review must target one supported instrument issue';
        END IF;
        IF NEW.evidence->>'issueEvidence' IS DISTINCT FROM q.evidence::text THEN
            RAISE EXCEPTION 'Issue evidence changed; review the current issue';
        END IF;
        IF q.issue_type='MISSING_SESSION' AND NEW.decision='ACCEPT' THEN
            IF q.affected_key !~ '^\d{4}-\d{2}-\d{2}$' OR q.dataset_id IS NULL THEN
                RAISE EXCEPTION 'Missing-session review requires a dated dataset issue';
            END IF;
            session_day := q.affected_key::date;
            IF NEW.evidence->>'scope'='SYMBOL_RETIRED_SESSION_RECOVERED_UNDER_SUCCESSOR_SYMBOL' THEN
                IF NOT EXISTS (SELECT 1 FROM reference.instrument_symbols retired
                        JOIN reference.instrument_symbols successor
                          ON successor.instrument_id=retired.instrument_id AND successor.effective_from=retired.effective_to
                         AND successor.symbol<>retired.symbol
                        WHERE retired.instrument_id=NEW.instrument_id AND retired.effective_to<=session_day)
                   OR NOT EXISTS (SELECT 1 FROM market_data.daily_bar_observations b
                        WHERE b.instrument_id=NEW.instrument_id AND b.dataset_id=q.dataset_id
                          AND b.session_date=session_day AND b.quality_state='VALID'
                          AND b.ingested_at>q.detected_at AND b.ingested_at<=NEW.recorded_at) THEN
                    RAISE EXCEPTION 'Retired-symbol review requires a symbol change and a later valid bar';
                END IF;
            ELSIF NEW.evidence->>'scope'='SESSION_BEFORE_INSTRUMENT_LISTING' THEN
                IF NOT session_day < (SELECT min(effective_from) FROM reference.instrument_symbols
                                      WHERE instrument_id=NEW.instrument_id)
                   OR EXISTS (SELECT 1 FROM market_data.daily_bar_observations b
                        WHERE b.instrument_id=NEW.instrument_id AND b.session_date=session_day
                          AND b.quality_state='VALID') THEN
                    RAISE EXCEPTION 'Pre-listing review requires a session before the first symbol date';
                END IF;
            ELSE
                RAISE EXCEPTION 'Missing-session review requires a verified identity scope';
            END IF;
        END IF;
    ELSE
        PERFORM pg_advisory_xact_lock(hashtextextended('input-review:'||NEW.fact_id::text,0));
        SELECT * INTO STRICT f FROM fundamentals.fundamental_facts WHERE fact_id=NEW.fact_id;
        IF f.issuer_id <> i.issuer_id OR f.quality_state <> 'VALID' OR f.numeric_value IS NULL
            OR f.period_start IS NOT NULL OR f.observed_at>NEW.recorded_at THEN
            RAISE EXCEPTION 'Review requires a valid retained instant fact of the same issuer';
        END IF;
        IF NEW.kind='COMMON_EQUITY' AND (f.metric_code IS DISTINCT FROM 'STOCKHOLDERS_EQUITY' OR f.unit <> 'USD'
            OR NEW.evidence->>'scope' IS DISTINCT FROM 'TOTAL_EQUITY_IS_COMMON_ONLY') THEN
            RAISE EXCEPTION 'Common equity review requires explicit common-only scope';
        END IF;
        IF NEW.kind='SHARE_CLASS' AND (f.metric_code IS DISTINCT FROM 'SHARES_OUTSTANDING' OR f.unit <> 'shares'
            OR f.numeric_value <= 0 OR NEW.evidence->>'scope' IS DISTINCT FROM 'EXACT_INSTRUMENT_SHARES'
            OR coalesce(NEW.evidence->>'shareClass','')='') THEN
            RAISE EXCEPTION 'Share review requires explicit instrument class; weighted shares are forbidden';
        END IF;
        IF NEW.kind='SHARE_CLASS' AND NEW.decision='ACCEPT' AND (
            EXISTS (SELECT 1 FROM fundamentals.instrument_share_facts s
                WHERE s.fact_id=NEW.fact_id AND s.instrument_id<>NEW.instrument_id)
            OR EXISTS (SELECT 1 FROM (
                SELECT DISTINCT ON (instrument_id) instrument_id,decision FROM operations.input_reviews
                WHERE kind='SHARE_CLASS' AND fact_id=NEW.fact_id
                ORDER BY instrument_id,recorded_at DESC,review_id DESC
            ) r WHERE r.instrument_id<>NEW.instrument_id AND r.decision='ACCEPT')) THEN
            RAISE EXCEPTION 'Share fact is already allocated to another instrument';
        END IF;
    END IF;
    RETURN NEW;
END $$;
GRANT EXECUTE ON FUNCTION operations.validate_input_review() TO quant_scoring;
