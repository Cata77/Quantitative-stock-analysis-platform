-- Enable the TimescaleDB extension in the database
CREATE EXTENSION IF NOT EXISTS timescaledb CASCADE;

-- 1. Standard PostgreSQL Table for Users
CREATE TABLE IF NOT EXISTS users (
   id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
   username VARCHAR(50) UNIQUE NOT NULL,
   password_hash VARCHAR(255) NOT NULL,
   created_at TIMESTAMPTZ DEFAULT NOW()
);

-- 2. Standard PostgreSQL Table for Portfolio (Portfolios)
CREATE TABLE IF NOT EXISTS portfolios (
   id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
   user_id UUID REFERENCES users(id) ON DELETE CASCADE,
   ticker VARCHAR(10) NOT NULL,
   quantity NUMERIC(15, 4) NOT NULL,
   entry_price NUMERIC(15, 4) NOT NULL,
   purchased_at TIMESTAMPTZ NOT NULL
);

-- 3. Time-Series Table (Tick Data) -> Converted into a Hypertable
CREATE TABLE IF NOT EXISTS tick_data (
   time TIMESTAMPTZ NOT NULL,
   symbol VARCHAR(10) NOT NULL,
   bid NUMERIC(20, 8) NOT NULL,
   ask NUMERIC(20, 8) NOT NULL,
   PRIMARY KEY (time, symbol)
);
SELECT create_hypertable('tick_data', 'time');

-- 4. Time-Series Table (Option Chain Snapshots) -> Converted into a Hypertable
CREATE TABLE IF NOT EXISTS option_chain (
   time TIMESTAMPTZ NOT NULL,
   symbol VARCHAR(20) NOT NULL,
   underlying VARCHAR(10) NOT NULL,
   expiry TIMESTAMPTZ NOT NULL,
   strike NUMERIC(15, 4) NOT NULL,
   option_type VARCHAR(5) NOT NULL, -- "CALL" or "PUT"
   ltp NUMERIC(15, 4) NOT NULL,      -- Last Traded Price
   oi BIGINT NOT NULL,              -- Open Interest
   iv NUMERIC(10, 6) NOT NULL,      -- Implied Volatility
   delta NUMERIC(10, 6) NOT NULL,   -- Greeks: Delta
   PRIMARY KEY (time, symbol)
);
SELECT create_hypertable('option_chain', 'time');

-- 5. Time-Series Table (Factor Scores - Result calculated by Scoring)
CREATE TABLE IF NOT EXISTS factor_scores (
   time TIMESTAMPTZ NOT NULL,
   symbol VARCHAR(10) NOT NULL,
   composite_score NUMERIC(10, 6) NOT NULL,
   z_value NUMERIC(10, 6) NOT NULL,
   z_momentum NUMERIC(10, 6) NOT NULL,
   z_quality NUMERIC(10, 6) NOT NULL,
   PRIMARY KEY (time, symbol)
);
SELECT create_hypertable('factor_scores', 'time');
