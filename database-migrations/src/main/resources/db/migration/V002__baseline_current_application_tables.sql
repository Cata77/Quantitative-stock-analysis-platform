-- Transitional baseline for the tables used by the existing services. These remain in public
-- until the later domain migrations move identity, portfolio, market, and scoring data into the
-- logical schemas created by V001. Flyway owns all application DDL from this version onward.

CREATE TABLE IF NOT EXISTS public.users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username VARCHAR(50) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS public.portfolios (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
    ticker VARCHAR(10) NOT NULL,
    quantity NUMERIC(15, 4) NOT NULL,
    entry_price NUMERIC(15, 4) NOT NULL,
    purchased_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS public.tick_data (
    time TIMESTAMPTZ NOT NULL,
    symbol VARCHAR(10) NOT NULL,
    bid NUMERIC(20, 8) NOT NULL,
    ask NUMERIC(20, 8) NOT NULL,
    PRIMARY KEY (time, symbol)
);
SELECT create_hypertable('public.tick_data', 'time', if_not_exists => TRUE);

CREATE TABLE IF NOT EXISTS public.market_bars (
    time TIMESTAMPTZ NOT NULL,
    symbol VARCHAR(10) NOT NULL,
    provider VARCHAR(30) NOT NULL,
    open_price NUMERIC(20, 8) NOT NULL,
    high_price NUMERIC(20, 8) NOT NULL,
    low_price NUMERIC(20, 8) NOT NULL,
    close_price NUMERIC(20, 8) NOT NULL,
    volume BIGINT NOT NULL,
    vwap NUMERIC(20, 8),
    trade_count BIGINT NOT NULL,
    CHECK (open_price > 0 AND high_price > 0 AND low_price > 0 AND close_price > 0),
    CHECK (high_price >= low_price),
    CHECK (vwap IS NULL OR vwap > 0),
    CHECK (volume >= 0 AND trade_count >= 0),
    PRIMARY KEY (time, symbol)
);
SELECT create_hypertable('public.market_bars', 'time', if_not_exists => TRUE);
CREATE INDEX IF NOT EXISTS idx_market_bars_symbol_time
    ON public.market_bars (symbol, time DESC);

CREATE TABLE IF NOT EXISTS public.fundamental_snapshots (
    time TIMESTAMPTZ NOT NULL,
    symbol VARCHAR(10) NOT NULL,
    provider VARCHAR(30) NOT NULL,
    company_name VARCHAR(200) NOT NULL,
    asset_type VARCHAR(40),
    exchange VARCHAR(30),
    currency VARCHAR(10),
    country VARCHAR(80),
    sector VARCHAR(100),
    industry VARCHAR(150),
    latest_quarter DATE,
    market_capitalization NUMERIC(30, 6),
    revenue_ttm NUMERIC(30, 6),
    ebitda NUMERIC(30, 6),
    pe_ratio NUMERIC(20, 8),
    peg_ratio NUMERIC(20, 8),
    price_to_book_ratio NUMERIC(20, 8),
    earnings_per_share NUMERIC(20, 8),
    profit_margin NUMERIC(20, 8),
    operating_margin_ttm NUMERIC(20, 8),
    return_on_assets_ttm NUMERIC(20, 8),
    return_on_equity_ttm NUMERIC(20, 8),
    quarterly_revenue_growth_yoy NUMERIC(20, 8),
    quarterly_earnings_growth_yoy NUMERIC(20, 8),
    analyst_target_price NUMERIC(20, 8),
    beta NUMERIC(20, 8),
    PRIMARY KEY (time, symbol)
);
SELECT create_hypertable('public.fundamental_snapshots', 'time', if_not_exists => TRUE);
CREATE INDEX IF NOT EXISTS idx_fundamental_snapshots_symbol_time
    ON public.fundamental_snapshots (symbol, time DESC);

CREATE TABLE IF NOT EXISTS public.factor_scores (
    time TIMESTAMPTZ NOT NULL,
    symbol VARCHAR(10) NOT NULL,
    composite_score NUMERIC(10, 6) NOT NULL,
    z_value NUMERIC(10, 6) NOT NULL,
    z_momentum NUMERIC(10, 6) NOT NULL,
    z_quality NUMERIC(10, 6) NOT NULL,
    PRIMARY KEY (time, symbol)
);
SELECT create_hypertable('public.factor_scores', 'time', if_not_exists => TRUE);
CREATE INDEX IF NOT EXISTS idx_factor_scores_time_composite
    ON public.factor_scores (time DESC, composite_score DESC, symbol ASC);
