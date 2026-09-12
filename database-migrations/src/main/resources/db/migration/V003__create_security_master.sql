CREATE TABLE reference.exchanges (
    exchange_mic VARCHAR(4) PRIMARY KEY,
    operating_mic VARCHAR(4) NOT NULL,
    name VARCHAR(150) NOT NULL,
    country VARCHAR(2) NOT NULL,
    timezone VARCHAR(50) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_reference_exchanges_mic
        CHECK (exchange_mic ~ '^[A-Z0-9]{4}$'),
    CONSTRAINT ck_reference_exchanges_operating_mic
        CHECK (operating_mic ~ '^[A-Z0-9]{4}$'),
    CONSTRAINT ck_reference_exchanges_country
        CHECK (country ~ '^[A-Z]{2}$')
);

INSERT INTO reference.exchanges (
    exchange_mic,
    operating_mic,
    name,
    country,
    timezone
)
VALUES
    ('XNAS', 'XNAS', 'Nasdaq Stock Market', 'US', 'America/New_York'),
    ('XNYS', 'XNYS', 'New York Stock Exchange', 'US', 'America/New_York'),
    ('ARCX', 'XNYS', 'NYSE Arca', 'US', 'America/New_York'),
    ('BATS', 'BATS', 'Cboe BZX Exchange', 'US', 'America/New_York')
ON CONFLICT (exchange_mic) DO NOTHING;

CREATE TABLE reference.issuers (
    issuer_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    cik VARCHAR(10),
    legal_name TEXT NOT NULL,
    domicile VARCHAR(2),
    sic VARCHAR(4),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_reference_issuers_cik UNIQUE (cik),
    CONSTRAINT ck_reference_issuers_cik
        CHECK (cik IS NULL OR cik ~ '^[0-9]{10}$'),
    CONSTRAINT ck_reference_issuers_domicile
        CHECK (domicile IS NULL OR domicile ~ '^[A-Z]{2}$'),
    CONSTRAINT ck_reference_issuers_sic
        CHECK (sic IS NULL OR sic ~ '^[0-9]{4}$')
);

CREATE TABLE reference.instruments (
    instrument_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    issuer_id UUID NOT NULL REFERENCES reference.issuers(issuer_id),
    security_type VARCHAR(40) NOT NULL,
    share_class VARCHAR(80) NOT NULL DEFAULT 'UNSPECIFIED',
    currency VARCHAR(3) NOT NULL,
    primary_exchange_mic VARCHAR(4) NOT NULL
        REFERENCES reference.exchanges(exchange_mic),
    valid_from DATE NOT NULL,
    valid_to DATE,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_reference_instruments_currency
        CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_reference_instruments_exchange_mic
        CHECK (primary_exchange_mic ~ '^[A-Z0-9]{4}$'),
    CONSTRAINT ck_reference_instruments_valid_period
        CHECK (valid_to IS NULL OR valid_to > valid_from),
    CONSTRAINT uq_reference_instruments_lifecycle
        UNIQUE (issuer_id, security_type, share_class, valid_from)
);

CREATE UNIQUE INDEX uq_reference_instruments_current_class
    ON reference.instruments (issuer_id, security_type, share_class)
    WHERE valid_to IS NULL;

CREATE INDEX idx_reference_instruments_issuer
    ON reference.instruments (issuer_id, valid_from DESC);

CREATE TABLE reference.instrument_identifiers (
    instrument_identifier_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    instrument_id UUID NOT NULL REFERENCES reference.instruments(instrument_id),
    identifier_scheme VARCHAR(30) NOT NULL,
    identifier_value VARCHAR(100) NOT NULL,
    source VARCHAR(100) NOT NULL,
    confidence NUMERIC(5, 4) NOT NULL DEFAULT 1.0000,
    effective_from DATE NOT NULL,
    effective_to DATE,
    available_at TIMESTAMPTZ NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_reference_identifiers_scheme
        CHECK (identifier_scheme = UPPER(identifier_scheme)),
    CONSTRAINT ck_reference_identifiers_confidence
        CHECK (confidence >= 0 AND confidence <= 1),
    CONSTRAINT ck_reference_identifiers_valid_period
        CHECK (effective_to IS NULL OR effective_to > effective_from),
    CONSTRAINT ck_reference_identifiers_observation
        CHECK (available_at <= observed_at),
    CONSTRAINT uq_reference_identifiers_lifecycle
        UNIQUE (identifier_scheme, identifier_value, effective_from)
);

CREATE UNIQUE INDEX uq_reference_identifiers_current_value
    ON reference.instrument_identifiers (identifier_scheme, identifier_value)
    WHERE effective_to IS NULL;

CREATE INDEX idx_reference_identifiers_instrument
    ON reference.instrument_identifiers (instrument_id, identifier_scheme, effective_from DESC);

CREATE TABLE reference.instrument_symbols (
    instrument_symbol_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    instrument_id UUID NOT NULL REFERENCES reference.instruments(instrument_id),
    symbol VARCHAR(32) NOT NULL,
    exchange_mic VARCHAR(4) NOT NULL REFERENCES reference.exchanges(exchange_mic),
    effective_from DATE NOT NULL,
    effective_to DATE,
    source VARCHAR(100) NOT NULL,
    available_at TIMESTAMPTZ NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_reference_symbols_uppercase
        CHECK (symbol = UPPER(symbol)),
    CONSTRAINT ck_reference_symbols_exchange_mic
        CHECK (exchange_mic ~ '^[A-Z0-9]{4}$'),
    CONSTRAINT ck_reference_symbols_valid_period
        CHECK (effective_to IS NULL OR effective_to > effective_from),
    CONSTRAINT ck_reference_symbols_observation
        CHECK (available_at <= observed_at),
    CONSTRAINT uq_reference_symbols_lifecycle
        UNIQUE (instrument_id, symbol, exchange_mic, effective_from)
);

CREATE UNIQUE INDEX uq_reference_symbols_current_listing
    ON reference.instrument_symbols (symbol, exchange_mic)
    WHERE effective_to IS NULL;

CREATE UNIQUE INDEX uq_reference_symbols_current_instrument
    ON reference.instrument_symbols (instrument_id)
    WHERE effective_to IS NULL;

CREATE INDEX idx_reference_symbols_as_of
    ON reference.instrument_symbols (instrument_id, effective_from DESC, effective_to);

CREATE TABLE reference.classification_versions (
    classification_version_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source VARCHAR(100) NOT NULL,
    version VARCHAR(50) NOT NULL,
    mapping_checksum CHAR(64) NOT NULL,
    description TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_reference_classification_versions UNIQUE (source, version),
    CONSTRAINT ck_reference_classification_checksum
        CHECK (mapping_checksum ~ '^[0-9a-f]{64}$')
);

CREATE TABLE reference.issuer_classifications (
    issuer_classification_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    issuer_id UUID NOT NULL REFERENCES reference.issuers(issuer_id),
    classification_version_id UUID NOT NULL
        REFERENCES reference.classification_versions(classification_version_id),
    source_classification VARCHAR(100),
    mapped_sector VARCHAR(100) NOT NULL,
    fallback_reason TEXT,
    effective_from DATE NOT NULL,
    effective_to DATE,
    available_at TIMESTAMPTZ NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_reference_classifications_valid_period
        CHECK (effective_to IS NULL OR effective_to > effective_from),
    CONSTRAINT ck_reference_classifications_observation
        CHECK (available_at <= observed_at),
    CONSTRAINT uq_reference_classifications_lifecycle
        UNIQUE (issuer_id, classification_version_id, effective_from)
);

CREATE UNIQUE INDEX uq_reference_classifications_current
    ON reference.issuer_classifications (issuer_id, classification_version_id)
    WHERE effective_to IS NULL;

CREATE INDEX idx_reference_classifications_as_of
    ON reference.issuer_classifications (issuer_id, effective_from DESC, effective_to);
