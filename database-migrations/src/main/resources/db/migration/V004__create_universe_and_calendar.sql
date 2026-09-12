CREATE TABLE reference.trading_sessions (
    exchange_mic VARCHAR(4) NOT NULL REFERENCES reference.exchanges(exchange_mic),
    session_date DATE NOT NULL,
    opens_at TIMESTAMPTZ,
    closes_at TIMESTAMPTZ,
    timezone VARCHAR(50) NOT NULL,
    holiday BOOLEAN NOT NULL DEFAULT FALSE,
    early_close BOOLEAN NOT NULL DEFAULT FALSE,
    source VARCHAR(100) NOT NULL,
    available_at TIMESTAMPTZ NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (exchange_mic, session_date),
    CONSTRAINT ck_reference_trading_sessions_exchange_mic
        CHECK (exchange_mic ~ '^[A-Z0-9]{4}$'),
    CONSTRAINT ck_reference_trading_sessions_times
        CHECK (
            (holiday AND opens_at IS NULL AND closes_at IS NULL AND NOT early_close)
            OR
            (NOT holiday AND opens_at IS NOT NULL AND closes_at IS NOT NULL AND closes_at > opens_at)
        ),
    CONSTRAINT ck_reference_trading_sessions_observation
        CHECK (available_at <= observed_at)
);

CREATE INDEX idx_reference_trading_sessions_latest_complete
    ON reference.trading_sessions (exchange_mic, session_date DESC)
    WHERE NOT holiday;

CREATE TABLE reference.universes (
    universe_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code VARCHAR(30) NOT NULL UNIQUE,
    name VARCHAR(150) NOT NULL,
    methodology_uri TEXT,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_reference_universes_code
        CHECK (code = UPPER(code))
);

INSERT INTO reference.universes (code, name, methodology_uri)
VALUES
    ('SP500', 'S&P 500', 'https://www.spglobal.com/spdji/en/indices/equity/sp-500/'),
    ('NASDAQ100', 'Nasdaq-100', 'https://indexes.nasdaqomx.com/Index/Overview/NDX')
ON CONFLICT (code) DO NOTHING;

CREATE TABLE reference.universe_snapshots (
    universe_snapshot_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    universe_id UUID NOT NULL REFERENCES reference.universes(universe_id),
    effective_date DATE NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL,
    source VARCHAR(100) NOT NULL,
    source_uri TEXT,
    source_checksum CHAR(64) NOT NULL,
    import_mode VARCHAR(40) NOT NULL,
    research_bias_label VARCHAR(40),
    completeness_status VARCHAR(20) NOT NULL,
    expected_member_count INTEGER,
    imported_member_count INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_reference_universe_snapshot_content
        UNIQUE (universe_id, effective_date, source_checksum),
    CONSTRAINT ck_reference_universe_snapshot_checksum
        CHECK (source_checksum ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_reference_universe_snapshot_import_mode
        CHECK (import_mode IN (
            'CURRENT_SNAPSHOT_FORWARD',
            'CURRENT_CONSTITUENTS_BACKTEST',
            'ETF_HOLDINGS_PROXY'
        )),
    CONSTRAINT ck_reference_universe_snapshot_bias
        CHECK (
            (import_mode = 'CURRENT_SNAPSHOT_FORWARD' AND research_bias_label IS NULL)
            OR
            (import_mode = 'CURRENT_CONSTITUENTS_BACKTEST'
                AND research_bias_label = 'SURVIVORSHIP_BIASED')
            OR
            (import_mode = 'ETF_HOLDINGS_PROXY'
                AND research_bias_label = 'ETF_HOLDINGS_PROXY')
        ),
    CONSTRAINT ck_reference_universe_snapshot_completeness
        CHECK (completeness_status IN ('COMPLETE', 'PARTIAL')),
    CONSTRAINT ck_reference_universe_snapshot_counts
        CHECK (
            imported_member_count >= 0
            AND (expected_member_count IS NULL OR expected_member_count >= imported_member_count)
            AND (
                completeness_status <> 'COMPLETE'
                OR expected_member_count = imported_member_count
            )
        )
);

CREATE INDEX idx_reference_universe_snapshots_as_of
    ON reference.universe_snapshots (universe_id, effective_date DESC, observed_at DESC);

CREATE TABLE reference.universe_memberships (
    universe_snapshot_id UUID NOT NULL
        REFERENCES reference.universe_snapshots(universe_snapshot_id) ON DELETE CASCADE,
    instrument_id UUID NOT NULL REFERENCES reference.instruments(instrument_id),
    source_symbol VARCHAR(32) NOT NULL,
    source_exchange_mic VARCHAR(4) NOT NULL REFERENCES reference.exchanges(exchange_mic),
    primary_liquid_class BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (universe_snapshot_id, instrument_id),
    CONSTRAINT uq_reference_universe_membership_source_listing
        UNIQUE (universe_snapshot_id, source_symbol, source_exchange_mic),
    CONSTRAINT ck_reference_universe_membership_symbol
        CHECK (source_symbol = UPPER(source_symbol)),
    CONSTRAINT ck_reference_universe_membership_exchange_mic
        CHECK (source_exchange_mic ~ '^[A-Z0-9]{4}$')
);

CREATE INDEX idx_reference_universe_memberships_instrument
    ON reference.universe_memberships (instrument_id, universe_snapshot_id);

CREATE FUNCTION reference.research_universe_for_snapshots(
    p_sp500_snapshot_id UUID,
    p_nasdaq100_snapshot_id UUID
)
RETURNS TABLE (
    instrument_id UUID,
    in_sp500 BOOLEAN,
    in_nasdaq100 BOOLEAN,
    primary_liquid_class BOOLEAN
)
LANGUAGE SQL
STABLE
AS $$
    SELECT
        membership.instrument_id,
        BOOL_OR(universe.code = 'SP500') AS in_sp500,
        BOOL_OR(universe.code = 'NASDAQ100') AS in_nasdaq100,
        BOOL_OR(membership.primary_liquid_class) AS primary_liquid_class
    FROM reference.universe_memberships membership
    JOIN reference.universe_snapshots snapshot
      ON snapshot.universe_snapshot_id = membership.universe_snapshot_id
    JOIN reference.universes universe
      ON universe.universe_id = snapshot.universe_id
    WHERE membership.universe_snapshot_id IN (
        p_sp500_snapshot_id,
        p_nasdaq100_snapshot_id
    )
    GROUP BY membership.instrument_id
$$;
