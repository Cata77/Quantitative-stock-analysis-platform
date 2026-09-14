CREATE TABLE operations.data_providers (
    provider_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code VARCHAR(80) NOT NULL UNIQUE CHECK (btrim(code) <> ''),
    name TEXT NOT NULL,
    license_notes TEXT NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE operations.datasets (
    dataset_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider_id UUID NOT NULL REFERENCES operations.data_providers(provider_id),
    code VARCHAR(100) NOT NULL CHECK (btrim(code) <> ''),
    version VARCHAR(50) NOT NULL CHECK (btrim(version) <> ''),
    license_notes TEXT NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (provider_id, code, version)
);

-- A new configuration/version is a new job definition, preserving old run identities.
CREATE TABLE operations.job_definitions (
    job_definition_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    dataset_id UUID NOT NULL REFERENCES operations.datasets(dataset_id),
    code VARCHAR(100) NOT NULL CHECK (btrim(code) <> ''),
    version VARCHAR(50) NOT NULL CHECK (btrim(version) <> ''),
    configuration JSONB NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(configuration) = 'object'),
    max_attempts INTEGER NOT NULL DEFAULT 5 CHECK (max_attempts BETWEEN 1 AND 100),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (code, version),
    UNIQUE (job_definition_id, dataset_id)
);

CREATE TABLE operations.ingestion_runs (
    ingestion_run_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    logical_key CHAR(64) NOT NULL UNIQUE CHECK (logical_key ~ '^[0-9a-f]{64}$'),
    job_definition_id UUID NOT NULL,
    dataset_id UUID NOT NULL,
    run_mode VARCHAR(30) NOT NULL CHECK (run_mode IN (
        'catch-up-and-serve', 'sync-and-exit', 'backfill', 'force-refresh'
    )),
    request_key TEXT NOT NULL CHECK (btrim(request_key) <> ''),
    window_start DATE NOT NULL,
    window_end DATE NOT NULL CHECK (window_end >= window_start),
    status VARCHAR(20) NOT NULL DEFAULT 'PLANNED' CHECK (status IN (
        'PLANNED', 'RUNNING', 'WAITING_RETRY', 'VALIDATING', 'COMPLETE', 'FAILED'
    )),
    expected_count INTEGER NOT NULL CHECK (expected_count > 0),
    staged_count INTEGER NOT NULL DEFAULT 0,
    accepted_count INTEGER NOT NULL DEFAULT 0,
    failed_count INTEGER NOT NULL DEFAULT 0,
    application_version VARCHAR(100) NOT NULL,
    error_summary JSONB CHECK (jsonb_typeof(error_summary) = 'object'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    FOREIGN KEY (job_definition_id, dataset_id)
        REFERENCES operations.job_definitions(job_definition_id, dataset_id),
    UNIQUE (ingestion_run_id, dataset_id),
    CHECK (staged_count BETWEEN 0 AND expected_count),
    CHECK (accepted_count BETWEEN 0 AND staged_count),
    CHECK (failed_count BETWEEN 0 AND expected_count - staged_count),
    CHECK (status <> 'COMPLETE' OR (
        accepted_count = expected_count AND failed_count = 0 AND completed_at IS NOT NULL
    ))
);

CREATE TABLE operations.ingestion_run_snapshots (
    ingestion_run_id UUID NOT NULL REFERENCES operations.ingestion_runs(ingestion_run_id),
    universe_snapshot_id UUID NOT NULL REFERENCES reference.universe_snapshots(universe_snapshot_id),
    PRIMARY KEY (ingestion_run_id, universe_snapshot_id)
);

CREATE TABLE operations.ingestion_run_items (
    ingestion_run_item_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    ingestion_run_id UUID NOT NULL REFERENCES operations.ingestion_runs(ingestion_run_id),
    instrument_id UUID NOT NULL REFERENCES reference.instruments(instrument_id),
    item_key TEXT NOT NULL CHECK (btrim(item_key) <> ''),
    status VARCHAR(20) NOT NULL DEFAULT 'PLANNED' CHECK (status IN (
        'PLANNED', 'RUNNING', 'WAITING_RETRY', 'STAGED', 'COMPLETE', 'FAILED'
    )),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    lease_token UUID,
    lease_owner VARCHAR(150),
    lease_until TIMESTAMPTZ,
    next_retry_at TIMESTAMPTZ,
    failure JSONB CHECK (jsonb_typeof(failure) = 'object'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (ingestion_run_id, item_key),
    UNIQUE (ingestion_run_item_id, ingestion_run_id),
    CHECK ((status = 'RUNNING' AND lease_token IS NOT NULL
                AND lease_owner IS NOT NULL AND btrim(lease_owner) <> '' AND lease_until IS NOT NULL)
        OR (status <> 'RUNNING' AND lease_token IS NULL AND lease_owner IS NULL AND lease_until IS NULL)),
    CHECK ((status = 'WAITING_RETRY') = (next_retry_at IS NOT NULL)),
    CHECK (status NOT IN ('WAITING_RETRY', 'FAILED') OR failure IS NOT NULL)
);

CREATE INDEX idx_ingestion_items_claim
    ON operations.ingestion_run_items (ingestion_run_id, status, next_retry_at, lease_until);

CREATE TABLE operations.ingestion_attempts (
    attempt_id UUID PRIMARY KEY,
    ingestion_run_item_id UUID NOT NULL REFERENCES operations.ingestion_run_items(ingestion_run_item_id),
    attempt_number INTEGER NOT NULL CHECK (attempt_number > 0),
    worker_id VARCHAR(150) NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('RUNNING', 'STAGED', 'FAILED', 'LEASE_EXPIRED')),
    started_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    finished_at TIMESTAMPTZ,
    failure JSONB CHECK (jsonb_typeof(failure) = 'object'),
    UNIQUE (ingestion_run_item_id, attempt_number),
    UNIQUE (attempt_id, ingestion_run_item_id),
    CHECK ((status = 'RUNNING') = (finished_at IS NULL))
);

CREATE TABLE operations.ingestion_checkpoints (
    ingestion_run_item_id UUID PRIMARY KEY REFERENCES operations.ingestion_run_items(ingestion_run_item_id),
    attempt_id UUID NOT NULL,
    checkpoint JSONB NOT NULL CHECK (jsonb_typeof(checkpoint) = 'object'),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    FOREIGN KEY (attempt_id, ingestion_run_item_id)
        REFERENCES operations.ingestion_attempts(attempt_id, ingestion_run_item_id)
);

CREATE TABLE operations.data_quality_issues (
    data_quality_issue_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    dataset_id UUID NOT NULL REFERENCES operations.datasets(dataset_id),
    ingestion_run_id UUID,
    instrument_id UUID REFERENCES reference.instruments(instrument_id),
    severity VARCHAR(10) NOT NULL CHECK (severity IN ('INFO', 'WARNING', 'BLOCKING')),
    issue_type VARCHAR(100) NOT NULL,
    affected_key TEXT NOT NULL,
    evidence JSONB NOT NULL CHECK (jsonb_typeof(evidence) = 'object'),
    status VARCHAR(10) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'RESOLVED')),
    detected_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    resolved_at TIMESTAMPTZ,
    FOREIGN KEY (ingestion_run_id, dataset_id)
        REFERENCES operations.ingestion_runs(ingestion_run_id, dataset_id),
    CHECK ((status = 'RESOLVED') = (resolved_at IS NOT NULL))
);

CREATE INDEX idx_quality_issues_blocking
    ON operations.data_quality_issues (dataset_id, affected_key) WHERE status = 'OPEN' AND severity = 'BLOCKING';

-- Only the canonical consumer/validator may record successful coverage. A provider
-- response or Kafka acknowledgement alone is not evidence of canonical completeness.
CREATE TABLE operations.data_coverage (
    dataset_id UUID NOT NULL REFERENCES operations.datasets(dataset_id),
    partition_key TEXT NOT NULL,
    boundary_date DATE NOT NULL,
    ingestion_run_id UUID NOT NULL,
    expected_count INTEGER NOT NULL CHECK (expected_count > 0),
    accepted_count INTEGER NOT NULL CHECK (accepted_count = expected_count),
    validated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (dataset_id, partition_key, boundary_date),
    FOREIGN KEY (ingestion_run_id, dataset_id)
        REFERENCES operations.ingestion_runs(ingestion_run_id, dataset_id)
);

CREATE TABLE operations.data_watermarks (
    dataset_id UUID NOT NULL REFERENCES operations.datasets(dataset_id),
    partition_key TEXT NOT NULL,
    coverage_start DATE NOT NULL,
    complete_through DATE NOT NULL CHECK (complete_through >= coverage_start),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (dataset_id, partition_key),
    FOREIGN KEY (dataset_id, partition_key, complete_through)
        REFERENCES operations.data_coverage(dataset_id, partition_key, boundary_date)
);
