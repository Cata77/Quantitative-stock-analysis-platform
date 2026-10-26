-- Reviews are new information. Their server timestamp is never backdated.
CREATE TABLE operations.input_reviews (
    review_id UUID PRIMARY KEY,
    kind TEXT NOT NULL CHECK (kind IN ('COMMON_EQUITY','SHARE_CLASS','QUALITY_ISSUE')),
    instrument_id UUID NOT NULL REFERENCES reference.instruments,
    fact_id UUID REFERENCES fundamentals.fundamental_facts,
    issue_id UUID REFERENCES operations.data_quality_issues,
    decision TEXT NOT NULL CHECK (decision IN ('ACCEPT','REVOKE')),
    reviewer TEXT NOT NULL CHECK (length(trim(reviewer)) >= 3),
    evidence JSONB NOT NULL CHECK (jsonb_typeof(evidence)='object'),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CHECK ((kind='QUALITY_ISSUE' AND issue_id IS NOT NULL AND fact_id IS NULL)
        OR (kind IN ('COMMON_EQUITY','SHARE_CLASS') AND fact_id IS NOT NULL AND issue_id IS NULL))
);
CREATE INDEX idx_input_reviews_cutoff ON operations.input_reviews
    (instrument_id,kind,recorded_at DESC);

CREATE FUNCTION operations.validate_input_review() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE f fundamentals.fundamental_facts; i reference.instruments; q operations.data_quality_issues;
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
           OR q.issue_type NOT IN ('CORPORATE_ACTION_REVIEW','SECURITY_IDENTITY_REVIEW') THEN
            RAISE EXCEPTION 'Review must target one supported instrument issue';
        END IF;
        IF NEW.evidence->>'issueEvidence' IS DISTINCT FROM q.evidence::text THEN
            RAISE EXCEPTION 'Issue evidence changed; review the current issue';
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
CREATE TRIGGER validate_input_review BEFORE INSERT OR UPDATE OR DELETE ON operations.input_reviews
    FOR EACH ROW EXECUTE FUNCTION operations.validate_input_review();
GRANT SELECT,INSERT ON operations.input_reviews TO quant_scoring;
GRANT SELECT ON operations.input_reviews TO quant_research;
GRANT EXECUTE ON FUNCTION operations.validate_input_review() TO quant_scoring;
