CREATE TABLE operations.source_artifacts (
    source_artifact_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    dataset_id UUID NOT NULL REFERENCES operations.datasets(dataset_id),
    request_key TEXT NOT NULL,
    source_uri TEXT NOT NULL,
    retrieved_at TIMESTAMPTZ NOT NULL,
    content_hash CHAR(64) NOT NULL CHECK (content_hash ~ '^[0-9a-f]{64}$'),
    media_type VARCHAR(100) NOT NULL,
    parser_version VARCHAR(100) NOT NULL,
    storage_uri TEXT,
    inline_content JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (dataset_id, request_key, content_hash, parser_version),
    UNIQUE (source_artifact_id, dataset_id),
    CHECK ((storage_uri IS NOT NULL AND btrim(storage_uri) <> '' AND inline_content IS NULL)
        OR (storage_uri IS NULL AND inline_content IS NOT NULL))
);

CREATE TABLE operations.outbox_events (
    event_id UUID PRIMARY KEY,
    observation_key CHAR(64) NOT NULL CHECK (observation_key ~ '^[0-9a-f]{64}$'),
    dataset_id UUID NOT NULL REFERENCES operations.datasets(dataset_id),
    instrument_id UUID NOT NULL REFERENCES reference.instruments(instrument_id),
    source_artifact_id UUID NOT NULL,
    topic VARCHAR(249) NOT NULL CHECK (btrim(topic) <> ''),
    partition_key UUID NOT NULL CHECK (partition_key = instrument_id),
    schema_version INTEGER NOT NULL CHECK (schema_version > 0),
    payload JSONB NOT NULL CHECK (jsonb_typeof(payload) = 'object'),
    payload_hash CHAR(64) NOT NULL CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN (
        'PENDING', 'CLAIMED', 'PUBLISHED', 'FAILED_RETRYABLE', 'FAILED_TERMINAL'
    )),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    lease_token UUID,
    lease_owner VARCHAR(150),
    lease_until TIMESTAMPTZ,
    next_retry_at TIMESTAMPTZ,
    failure JSONB CHECK (jsonb_typeof(failure) = 'object'),
    broker_partition INTEGER CHECK (broker_partition >= 0),
    broker_offset BIGINT CHECK (broker_offset >= 0),
    published_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (topic, observation_key),
    FOREIGN KEY (source_artifact_id, dataset_id)
        REFERENCES operations.source_artifacts(source_artifact_id, dataset_id),
    CHECK ((status = 'CLAIMED' AND lease_token IS NOT NULL AND lease_owner IS NOT NULL
                AND btrim(lease_owner) <> '' AND lease_until IS NOT NULL)
        OR (status <> 'CLAIMED' AND lease_token IS NULL AND lease_owner IS NULL AND lease_until IS NULL)),
    CHECK ((status = 'FAILED_RETRYABLE') = (next_retry_at IS NOT NULL)),
    CHECK (status NOT IN ('FAILED_RETRYABLE', 'FAILED_TERMINAL') OR failure IS NOT NULL),
    CHECK ((status = 'PUBLISHED' AND broker_partition IS NOT NULL AND broker_offset IS NOT NULL
                AND published_at IS NOT NULL)
        OR (status <> 'PUBLISHED' AND broker_partition IS NULL AND broker_offset IS NULL AND published_at IS NULL))
);

CREATE INDEX idx_outbox_claim
    ON operations.outbox_events (status, next_retry_at, lease_until, created_at)
    WHERE status IN ('PENDING', 'CLAIMED', 'FAILED_RETRYABLE');

-- Repeated runs can reuse one observation without losing their expected-work lineage.
CREATE TABLE operations.ingestion_item_events (
    ingestion_run_item_id UUID NOT NULL REFERENCES operations.ingestion_run_items(ingestion_run_item_id),
    event_id UUID NOT NULL REFERENCES operations.outbox_events(event_id),
    PRIMARY KEY (ingestion_run_item_id, event_id)
);

CREATE TABLE operations.ingestion_item_artifacts (
    ingestion_run_item_id UUID NOT NULL REFERENCES operations.ingestion_run_items(ingestion_run_item_id),
    source_artifact_id UUID NOT NULL REFERENCES operations.source_artifacts(source_artifact_id),
    PRIMARY KEY (ingestion_run_item_id, source_artifact_id)
);

-- Insert in the SAME transaction as canonical writes; never commit a claim on its own.
-- No outbox FK: consumers must also accept events delivered from an independent producer.
CREATE TABLE operations.kafka_inbox (
    consumer_name VARCHAR(150) NOT NULL,
    event_id UUID NOT NULL,
    observation_key CHAR(64) NOT NULL CHECK (observation_key ~ '^[0-9a-f]{64}$'),
    payload_hash CHAR(64) NOT NULL CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    source_topic VARCHAR(249) NOT NULL,
    source_partition INTEGER NOT NULL CHECK (source_partition >= 0),
    source_offset BIGINT NOT NULL CHECK (source_offset >= 0),
    processing_result VARCHAR(20) NOT NULL CHECK (processing_result IN ('ACCEPTED', 'REJECTED')),
    first_observed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_observed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    delivery_count BIGINT NOT NULL DEFAULT 1 CHECK (delivery_count > 0),
    PRIMARY KEY (consumer_name, event_id),
    UNIQUE (consumer_name, observation_key),
    CHECK (last_observed_at >= first_observed_at)
);
