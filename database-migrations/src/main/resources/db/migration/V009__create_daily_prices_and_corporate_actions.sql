CREATE TABLE market_data.daily_bar_observations (
    session_date DATE NOT NULL,
    observation_key CHAR(64) NOT NULL REFERENCES market_data.observations(observation_key),
    instrument_id UUID NOT NULL REFERENCES reference.instruments(instrument_id),
    provider_id UUID NOT NULL REFERENCES operations.data_providers(provider_id),
    dataset_id UUID NOT NULL REFERENCES operations.datasets(dataset_id),
    exchange_mic VARCHAR(4) NOT NULL,
    feed VARCHAR(20) NOT NULL CHECK (feed IN ('sip','iex')),
    adjustment_mode VARCHAR(30) NOT NULL CHECK (adjustment_mode IN ('RAW','SPLIT_DIVIDEND')),
    adjustment_as_of DATE,
    currency CHAR(3) NOT NULL,
    bar_time TIMESTAMPTZ NOT NULL,
    open NUMERIC NOT NULL CHECK (open > 0),
    high NUMERIC NOT NULL CHECK (high > 0),
    low NUMERIC NOT NULL CHECK (low > 0),
    close NUMERIC NOT NULL CHECK (close > 0),
    volume BIGINT NOT NULL CHECK (volume >= 0),
    vwap NUMERIC CHECK (vwap > 0),
    trade_count BIGINT NOT NULL CHECK (trade_count >= 0),
    source_revision CHAR(64) NOT NULL,
    source_artifact_id UUID NOT NULL REFERENCES operations.source_artifacts(source_artifact_id),
    available_at TIMESTAMPTZ NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL,
    ingested_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    quality_state VARCHAR(20) NOT NULL DEFAULT 'VALID' CHECK (quality_state IN ('VALID','REJECTED')),
    PRIMARY KEY (session_date, observation_key),
    FOREIGN KEY (exchange_mic, session_date) REFERENCES reference.trading_sessions(exchange_mic, session_date),
    CHECK (high >= GREATEST(open, close, low) AND low <= LEAST(open, close)),
    CHECK (currency ~ '^[A-Z]{3}$'),
    CHECK (available_at <= observed_at),
    CHECK ((adjustment_mode = 'RAW' AND adjustment_as_of IS NULL)
        OR (adjustment_mode = 'SPLIT_DIVIDEND' AND adjustment_as_of >= session_date))
);
SELECT create_hypertable('market_data.daily_bar_observations', 'session_date', chunk_time_interval => INTERVAL '1 year');
CREATE INDEX idx_daily_prices_range ON market_data.daily_bar_observations
    (instrument_id, session_date DESC, provider_id, observed_at DESC);
CREATE INDEX idx_daily_prices_dataset ON market_data.daily_bar_observations
    (dataset_id, adjustment_mode, adjustment_as_of, session_date DESC);

CREATE TABLE market_data.corporate_actions (
    corporate_action_observation_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider_id UUID NOT NULL REFERENCES operations.data_providers(provider_id),
    dataset_id UUID NOT NULL REFERENCES operations.datasets(dataset_id),
    provider_action_id TEXT NOT NULL,
    revision_hash CHAR(64) NOT NULL,
    instrument_id UUID NOT NULL REFERENCES reference.instruments(instrument_id),
    related_instrument_id UUID REFERENCES reference.instruments(instrument_id),
    action_type VARCHAR(40) NOT NULL,
    announcement_date DATE,
    process_date DATE NOT NULL,
    effective_date DATE,
    ex_date DATE,
    record_date DATE,
    payment_date DATE,
    currency CHAR(3),
    cash_amount NUMERIC CHECK (cash_amount >= 0),
    old_rate NUMERIC CHECK (old_rate > 0),
    new_rate NUMERIC CHECK (new_rate > 0),
    old_symbol TEXT,
    new_symbol TEXT,
    source_artifact_id UUID NOT NULL REFERENCES operations.source_artifacts(source_artifact_id),
    observation_key CHAR(64) NOT NULL REFERENCES market_data.observations(observation_key),
    provider_content JSONB NOT NULL,
    available_at TIMESTAMPTZ NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL,
    ingested_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    quality_state VARCHAR(20) NOT NULL CHECK (quality_state IN ('VALID','REVIEW_REQUIRED')),
    UNIQUE (provider_id, provider_action_id, revision_hash, instrument_id),
    CHECK (available_at <= observed_at)
);
CREATE INDEX idx_corporate_actions_instrument ON market_data.corporate_actions
    (instrument_id, effective_date, observed_at DESC);
CREATE TABLE operations.provider_request_budgets (
    provider_code VARCHAR(80) PRIMARY KEY,
    next_request_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE UNIQUE INDEX uq_open_price_quality_issue ON operations.data_quality_issues
    (dataset_id, ingestion_run_id, instrument_id, issue_type, affected_key) WHERE status = 'OPEN';
