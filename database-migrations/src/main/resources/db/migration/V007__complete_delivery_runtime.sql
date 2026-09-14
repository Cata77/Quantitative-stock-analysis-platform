-- Validated, append-only event journal. Phase 4/5 add the specialized price/filing models.
CREATE TABLE market_data.observations (
    observation_key CHAR(64) PRIMARY KEY CHECK (observation_key ~ '^[0-9a-f]{64}$'),
    dataset_id UUID NOT NULL REFERENCES operations.datasets(dataset_id),
    instrument_id UUID NOT NULL REFERENCES reference.instruments(instrument_id),
    event_type VARCHAR(80) NOT NULL,
    economic_time TIMESTAMPTZ NOT NULL,
    adjustment_mode VARCHAR(50) NOT NULL,
    payload JSONB NOT NULL CHECK (jsonb_typeof(payload) = 'object'),
    source_artifact_id UUID REFERENCES operations.source_artifacts(source_artifact_id),
    observed_at TIMESTAMPTZ NOT NULL,
    ingested_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX idx_observations_instrument ON market_data.observations (instrument_id, dataset_id, economic_time DESC);

CREATE TABLE operations.kafka_event_receipts (
    consumer_name VARCHAR(150) NOT NULL,
    event_id UUID NOT NULL,
    observation_key CHAR(64) NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    PRIMARY KEY (consumer_name, event_id),
    FOREIGN KEY (consumer_name, observation_key) REFERENCES operations.kafka_inbox(consumer_name, observation_key)
);

CREATE TABLE operations.dead_letter_records (
    dead_letter_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    consumer_name VARCHAR(150) NOT NULL,
    source_topic VARCHAR(249) NOT NULL,
    source_partition INTEGER NOT NULL CHECK (source_partition >= 0),
    source_offset BIGINT NOT NULL CHECK (source_offset >= 0),
    record_key TEXT,
    payload BYTEA,
    headers JSONB NOT NULL CHECK (jsonb_typeof(headers) = 'array'),
    reason TEXT NOT NULL,
    detected_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    dlq_published_at TIMESTAMPTZ,
    UNIQUE (consumer_name, source_topic, source_partition, source_offset)
);

CREATE TABLE operations.dead_letter_replays (
    replay_id UUID PRIMARY KEY,
    dead_letter_id UUID NOT NULL REFERENCES operations.dead_letter_records(dead_letter_id),
    operator_name VARCHAR(150) NOT NULL CHECK (btrim(operator_name) <> ''),
    reason TEXT NOT NULL CHECK (btrim(reason) <> ''),
    status VARCHAR(15) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'CLAIMED', 'PUBLISHED')),
    lease_token UUID,
    lease_until TIMESTAMPTZ,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    published_at TIMESTAMPTZ,
    broker_partition INTEGER,
    broker_offset BIGINT,
    CHECK ((status = 'CLAIMED') = (lease_token IS NOT NULL AND lease_until IS NOT NULL)),
    CHECK ((status = 'PUBLISHED') = (published_at IS NOT NULL AND broker_partition IS NOT NULL AND broker_offset IS NOT NULL))
);

CREATE TABLE operations.calendar_imports (
    exchange_mic VARCHAR(4) NOT NULL REFERENCES reference.exchanges(exchange_mic),
    from_date DATE NOT NULL,
    through_date DATE NOT NULL CHECK (through_date >= from_date),
    source_uri TEXT NOT NULL,
    source_content JSONB NOT NULL,
    source_hash CHAR(64) NOT NULL,
    imported_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (exchange_mic, from_date, through_date, source_hash)
);

-- Scheduling identity for the existing demonstration calculator, not a production model manifest.
CREATE TABLE operations.month_end_score_jobs (
    score_job_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    as_of_date DATE NOT NULL,
    cutoff TIMESTAMPTZ NOT NULL,
    model_code VARCHAR(100) NOT NULL,
    sp500_snapshot_id UUID NOT NULL REFERENCES reference.universe_snapshots(universe_snapshot_id),
    nasdaq100_snapshot_id UUID NOT NULL REFERENCES reference.universe_snapshots(universe_snapshot_id),
    status VARCHAR(20) NOT NULL DEFAULT 'WAITING_FOR_DATA'
        CHECK (status IN ('WAITING_FOR_DATA', 'PUBLISHED')),
    scored_count INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    published_at TIMESTAMPTZ,
    UNIQUE (as_of_date, model_code, sp500_snapshot_id, nasdaq100_snapshot_id),
    CHECK (status <> 'PUBLISHED' OR (scored_count > 0 AND published_at IS NOT NULL))
);

CREATE INDEX idx_item_events_event ON operations.ingestion_item_events(event_id, ingestion_run_item_id);
CREATE INDEX idx_ingestion_runs_boundary ON operations.ingestion_runs(dataset_id, window_start, window_end, status);
