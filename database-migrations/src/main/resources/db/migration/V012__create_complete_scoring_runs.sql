-- Complete, immutable research publications. Predictive acceptance remains a separate gate.
CREATE TABLE research.model_parity_certifications (
    certification_id TEXT PRIMARY KEY CHECK (certification_id ~ '^[0-9a-f]{64}$'),
    model_version_id UUID NOT NULL REFERENCES research.model_versions,
    manifest_sha256 TEXT NOT NULL,
    implementation_version TEXT NOT NULL,
    absolute_tolerance NUMERIC NOT NULL CHECK (absolute_tolerance = 0.000000001),
    verified_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (certification_id, model_version_id)
);
CREATE TABLE research.scoring_runs (
    score_run_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    logical_key TEXT NOT NULL UNIQUE CHECK (logical_key ~ '^[0-9a-f]{64}$'),
    as_of_date DATE NOT NULL,
    market_cutoff TIMESTAMPTZ NOT NULL,
    knowledge_cutoff TIMESTAMPTZ NOT NULL CHECK (knowledge_cutoff <= market_cutoff),
    effective_from TIMESTAMPTZ NOT NULL CHECK (effective_from > market_cutoff),
    sp500_snapshot_id UUID NOT NULL REFERENCES reference.universe_snapshots,
    nasdaq100_snapshot_id UUID NOT NULL REFERENCES reference.universe_snapshots,
    model_version_id UUID NOT NULL REFERENCES research.model_versions,
    certification_id TEXT NOT NULL,
    candidate TEXT NOT NULL CHECK (candidate IN ('primary','pure_value','value_quality','balanced')),
    input_version TEXT NOT NULL CHECK (input_version = 'prepared-canonical-v1'),
    preprocessing_version TEXT NOT NULL DEFAULT 'profiles-v1',
    request JSONB NOT NULL,
    input_document TEXT,
    input_sha256 TEXT,
    expected_count INTEGER NOT NULL DEFAULT 0 CHECK (expected_count >= 0),
    eligible_count INTEGER NOT NULL DEFAULT 0 CHECK (eligible_count >= 0),
    excluded_count INTEGER NOT NULL DEFAULT 0 CHECK (excluded_count >= 0),
    scored_count INTEGER NOT NULL DEFAULT 0 CHECK (scored_count >= 0),
    state TEXT NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING','RUNNING','FAILED','PUBLISHED')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    published_at TIMESTAMPTZ,
    failure_reason TEXT,
    supersedes UUID REFERENCES research.scoring_runs,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY (certification_id,model_version_id) REFERENCES research.model_parity_certifications(certification_id,model_version_id),
    CHECK (sp500_snapshot_id <> nasdaq100_snapshot_id),
    CHECK ((input_document IS NULL AND input_sha256 IS NULL) OR
        (input_document IS NOT NULL AND input_sha256 IS NOT NULL
         AND input_sha256 = encode(sha256(convert_to(input_document,'UTF8')),'hex'))),
    CHECK (state <> 'PUBLISHED' OR
        (expected_count = scored_count + excluded_count AND scored_count = eligible_count
         AND scored_count >= 2 AND published_at IS NOT NULL AND input_document IS NOT NULL))
);
CREATE INDEX idx_scoring_runs_published ON research.scoring_runs(as_of_date DESC,published_at DESC,score_run_id)
    WHERE state='PUBLISHED';
CREATE TABLE research.score_lineage (
    score_run_id UUID NOT NULL REFERENCES research.scoring_runs,
    instrument_id UUID NOT NULL REFERENCES reference.instruments,
    issuer_id UUID NOT NULL REFERENCES reference.issuers,
    symbol TEXT NOT NULL,
    canonical_input JSONB NOT NULL,
    prepared_input JSONB NOT NULL,
    maximum_input_availability_timestamp TIMESTAMPTZ,
    PRIMARY KEY(score_run_id,instrument_id)
);
CREATE TABLE research.score_source_artifacts (
    score_run_id UUID NOT NULL,
    instrument_id UUID NOT NULL,
    source_artifact_id UUID NOT NULL REFERENCES operations.source_artifacts,
    PRIMARY KEY(score_run_id,instrument_id,source_artifact_id),
    FOREIGN KEY(score_run_id,instrument_id) REFERENCES research.score_lineage
);
CREATE TABLE research.stock_scores (
    score_run_id UUID NOT NULL,
    instrument_id UUID NOT NULL,
    scoring_profile TEXT NOT NULL,
    peer_group TEXT,
    eligible BOOLEAN NOT NULL,
    value_score NUMERIC(24,6),
    quality_score NUMERIC(24,6),
    momentum_score NUMERIC(24,6),
    value_contribution NUMERIC(24,6),
    quality_contribution NUMERIC(24,6),
    momentum_contribution NUMERIC(24,6),
    composite_z NUMERIC(24,6),
    composite_percentile NUMERIC(24,6),
    ordinal_rank INTEGER,
    available_metric_count INTEGER NOT NULL CHECK (available_metric_count BETWEEN 0 AND 7),
    missing_metric_count INTEGER NOT NULL CHECK (missing_metric_count BETWEEN 0 AND 7),
    warnings JSONB NOT NULL,
    output JSONB NOT NULL,
    calculated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    published_at TIMESTAMPTZ,
    PRIMARY KEY(score_run_id,instrument_id),
    FOREIGN KEY(score_run_id,instrument_id) REFERENCES research.score_lineage,
    UNIQUE(score_run_id,ordinal_rank),
    CHECK ((eligible AND composite_z IS NOT NULL AND composite_percentile BETWEEN 0 AND 100
        AND ordinal_rank > 0 AND value_score IS NOT NULL AND quality_score IS NOT NULL AND momentum_score IS NOT NULL)
        OR (NOT eligible AND composite_z IS NULL AND ordinal_rank IS NULL AND composite_percentile IS NULL))
);
CREATE INDEX idx_stock_scores_ranking ON research.stock_scores(score_run_id,ordinal_rank,instrument_id) WHERE eligible;
CREATE TABLE research.factor_observations (
    score_run_id UUID NOT NULL,
    instrument_id UUID NOT NULL,
    metric TEXT NOT NULL,
    family TEXT NOT NULL CHECK (family IN ('value','quality','momentum')),
    raw_value NUMERIC, transformed_value NUMERIC, winsorized_value NUMERIC,
    cohort TEXT, cohort_count INTEGER NOT NULL CHECK (cohort_count >= 0),
    lower_bound NUMERIC, upper_bound NUMERIC, cohort_mean NUMERIC, cohort_deviation NUMERIC,
    z_score NUMERIC, clipped_score NUMERIC,
    weight NUMERIC NOT NULL, contribution NUMERIC, reason TEXT,
    PRIMARY KEY(score_run_id,instrument_id,metric),
    FOREIGN KEY(score_run_id,instrument_id) REFERENCES research.stock_scores
);
CREATE TABLE research.score_exclusions (
    score_run_id UUID NOT NULL,
    instrument_id UUID NOT NULL,
    stage TEXT NOT NULL,
    reason_code TEXT NOT NULL,
    affected_metric TEXT NOT NULL DEFAULT '',
    detail TEXT,
    PRIMARY KEY(score_run_id,instrument_id,stage,reason_code,affected_metric),
    FOREIGN KEY(score_run_id,instrument_id) REFERENCES research.stock_scores
);

CREATE FUNCTION research.guard_scoring_run() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE actual_scored INTEGER; actual_excluded INTEGER; actual_expected INTEGER;
BEGIN
    IF TG_OP='DELETE' THEN
        RAISE EXCEPTION 'Scoring runs are audit records and cannot be deleted';
    END IF;
    IF TG_OP='INSERT' THEN
        IF NEW.state <> 'PENDING' OR NEW.input_document IS NOT NULL OR NEW.expected_count <> 0 THEN
            RAISE EXCEPTION 'Runs must start pending without captured inputs';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.state='PUBLISHED' THEN RAISE EXCEPTION 'Published scoring runs are immutable'; END IF;
    IF (NEW.logical_key,NEW.request,NEW.as_of_date,NEW.market_cutoff,NEW.knowledge_cutoff,NEW.effective_from,
        NEW.sp500_snapshot_id,NEW.nasdaq100_snapshot_id,NEW.model_version_id,NEW.candidate,NEW.input_version,NEW.preprocessing_version,NEW.supersedes)
       IS DISTINCT FROM
       (OLD.logical_key,OLD.request,OLD.as_of_date,OLD.market_cutoff,OLD.knowledge_cutoff,OLD.effective_from,
        OLD.sp500_snapshot_id,OLD.nasdaq100_snapshot_id,OLD.model_version_id,OLD.candidate,OLD.input_version,OLD.preprocessing_version,OLD.supersedes)
       THEN RAISE EXCEPTION 'Logical run identity is immutable'; END IF;
    IF OLD.input_document IS NOT NULL AND
        (NEW.input_document,NEW.input_sha256,NEW.expected_count) IS DISTINCT FROM
        (OLD.input_document,OLD.input_sha256,OLD.expected_count)
        THEN RAISE EXCEPTION 'Captured scoring inputs are immutable'; END IF;
    IF NEW.state='PUBLISHED' THEN
        SELECT count(*) FILTER(WHERE eligible),count(*) FILTER(WHERE NOT eligible)
          INTO actual_scored,actual_excluded FROM research.stock_scores WHERE score_run_id=NEW.score_run_id;
        SELECT count(*) INTO actual_expected FROM research.score_lineage WHERE score_run_id=NEW.score_run_id;
        IF actual_scored <> NEW.scored_count OR actual_excluded <> NEW.excluded_count
            OR actual_expected <> NEW.expected_count OR actual_scored + actual_excluded <> actual_expected
            OR actual_scored < 2 THEN RAISE EXCEPTION 'Incomplete scoring run'; END IF;
        IF EXISTS (
            (SELECT instrument_id FROM reference.research_universe_for_snapshots(NEW.sp500_snapshot_id,NEW.nasdaq100_snapshot_id)
             EXCEPT SELECT instrument_id FROM research.score_lineage WHERE score_run_id=NEW.score_run_id)
            UNION ALL
            (SELECT instrument_id FROM research.score_lineage WHERE score_run_id=NEW.score_run_id
             EXCEPT SELECT instrument_id FROM reference.research_universe_for_snapshots(NEW.sp500_snapshot_id,NEW.nasdaq100_snapshot_id))
        ) THEN RAISE EXCEPTION 'Run does not account for exact snapshot union'; END IF;
        IF EXISTS (SELECT 1 FROM research.stock_scores s WHERE s.score_run_id=NEW.score_run_id AND
            ((NOT s.eligible AND NOT EXISTS (SELECT 1 FROM research.score_exclusions e WHERE e.score_run_id=s.score_run_id AND e.instrument_id=s.instrument_id AND e.stage='ELIGIBILITY'))
             OR (s.eligible AND (s.ordinal_rank > actual_scored
                 OR EXISTS (SELECT 1 FROM research.score_exclusions e WHERE e.score_run_id=s.score_run_id AND e.instrument_id=s.instrument_id AND e.stage='ELIGIBILITY')
                 OR (SELECT count(*) FROM research.factor_observations f WHERE f.score_run_id=s.score_run_id AND f.instrument_id=s.instrument_id) <> 7
                 OR EXISTS(SELECT family FROM (VALUES ('value',2),('quality',2),('momentum',1)) required(family,minimum)
                    WHERE (SELECT count(*) FROM research.factor_observations f WHERE f.score_run_id=s.score_run_id
                        AND f.instrument_id=s.instrument_id AND f.family=required.family AND f.clipped_score IS NOT NULL)<required.minimum)
                 OR NOT EXISTS(SELECT 1 FROM research.score_source_artifacts a WHERE a.score_run_id=s.score_run_id AND a.instrument_id=s.instrument_id)))))
            THEN RAISE EXCEPTION 'Missing factors, exclusions, ranks or source lineage'; END IF;
        IF EXISTS (SELECT 1 FROM research.score_lineage l WHERE l.score_run_id=NEW.score_run_id AND
            (l.symbol IS DISTINCT FROM l.canonical_input->>'symbol'
             OR l.maximum_input_availability_timestamp > NEW.knowledge_cutoff
             OR NOT EXISTS(SELECT 1 FROM jsonb_array_elements(NEW.input_document::jsonb->'canonical'->'inputs') c
                WHERE c->>'instrumentId'=l.instrument_id::text AND c=l.canonical_input)
             OR NOT EXISTS(SELECT 1 FROM jsonb_array_elements(NEW.input_document::jsonb->'prepared'->'rows') p
                WHERE p->>'instrument_id'=l.instrument_id::text AND p=l.prepared_input)
             OR NOT EXISTS(SELECT 1 FROM reference.instruments i WHERE i.instrument_id=l.instrument_id AND i.issuer_id=l.issuer_id)))
            THEN RAISE EXCEPTION 'Score lineage differs from captured inputs or knowledge cutoff'; END IF;

        IF NOT EXISTS(SELECT 1 FROM research.model_versions m JOIN research.model_parity_certifications p USING(model_version_id)
            WHERE m.model_version_id=NEW.model_version_id AND p.certification_id=NEW.certification_id
                AND p.manifest_sha256=m.manifest_sha256 AND m.approval_state <> 'RETIRED')
            THEN RAISE EXCEPTION 'Model lacks matching parity certification'; END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER guard_scoring_run BEFORE INSERT OR UPDATE OR DELETE ON research.scoring_runs
    FOR EACH ROW EXECUTE FUNCTION research.guard_scoring_run();

CREATE FUNCTION research.guard_scoring_output() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE run_state TEXT;
BEGIN
    SELECT state INTO run_state FROM research.scoring_runs
        WHERE score_run_id=CASE WHEN TG_OP='DELETE' THEN OLD.score_run_id ELSE NEW.score_run_id END FOR UPDATE;
    IF run_state='PUBLISHED' THEN RAISE EXCEPTION 'Published scoring output is immutable'; END IF;
    IF TG_OP='UPDATE' AND (NEW.score_run_id,NEW.instrument_id) IS DISTINCT FROM (OLD.score_run_id,OLD.instrument_id)
        THEN RAISE EXCEPTION 'Output identity is immutable'; END IF;
    IF TG_OP='DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER guard_score_lineage BEFORE INSERT OR UPDATE OR DELETE ON research.score_lineage FOR EACH ROW EXECUTE FUNCTION research.guard_scoring_output();
CREATE TRIGGER guard_score_artifacts BEFORE INSERT OR UPDATE OR DELETE ON research.score_source_artifacts FOR EACH ROW EXECUTE FUNCTION research.guard_scoring_output();
CREATE TRIGGER guard_stock_scores BEFORE INSERT OR UPDATE OR DELETE ON research.stock_scores FOR EACH ROW EXECUTE FUNCTION research.guard_scoring_output();
CREATE TRIGGER guard_factor_observations BEFORE INSERT OR UPDATE OR DELETE ON research.factor_observations FOR EACH ROW EXECUTE FUNCTION research.guard_scoring_output();
CREATE TRIGGER guard_score_exclusions BEFORE INSERT OR UPDATE OR DELETE ON research.score_exclusions FOR EACH ROW EXECUTE FUNCTION research.guard_scoring_output();

CREATE FUNCTION research.guard_model_approval() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.approval_state='APPROVED' AND NOT EXISTS(SELECT 1 FROM research.model_parity_certifications
        WHERE model_version_id=NEW.model_version_id AND manifest_sha256=NEW.manifest_sha256)
        THEN RAISE EXCEPTION 'Cannot approve model without matching parity'; END IF;
    IF TG_OP='UPDATE' AND EXISTS(SELECT 1 FROM research.scoring_runs WHERE model_version_id=OLD.model_version_id)
        AND (to_jsonb(NEW)-'approval_state') IS DISTINCT FROM (to_jsonb(OLD)-'approval_state')
        THEN RAISE EXCEPTION 'Referenced model definition is immutable'; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER guard_model_approval BEFORE INSERT OR UPDATE ON research.model_versions FOR EACH ROW EXECUTE FUNCTION research.guard_model_approval();

CREATE FUNCTION research.guard_parity_certification() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Parity certifications are immutable audit records';
END $$;
CREATE TRIGGER guard_parity_certification BEFORE UPDATE OR DELETE ON research.model_parity_certifications
    FOR EACH ROW EXECUTE FUNCTION research.guard_parity_certification();