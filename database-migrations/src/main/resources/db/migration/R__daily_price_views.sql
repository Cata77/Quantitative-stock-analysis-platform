CREATE OR REPLACE VIEW market_data.latest_daily_bars AS
SELECT DISTINCT ON (instrument_id, session_date, dataset_id, adjustment_mode, adjustment_as_of) *
FROM market_data.daily_bar_observations WHERE quality_state = 'VALID'
ORDER BY instrument_id, session_date, dataset_id, adjustment_mode, adjustment_as_of,
    available_at DESC, observed_at DESC, ingested_at DESC, observation_key;

-- The caller must select one dataset and one adjustment vintage for the entire range.
CREATE OR REPLACE FUNCTION market_data.daily_bars_as_of(
    p_dataset UUID, p_adjustment TEXT, p_basis DATE, p_cutoff TIMESTAMPTZ)
RETURNS SETOF market_data.daily_bar_observations LANGUAGE sql STABLE AS $$
    SELECT DISTINCT ON (instrument_id, session_date) *
    FROM market_data.daily_bar_observations
    WHERE dataset_id = p_dataset AND adjustment_mode = p_adjustment
      AND ((p_adjustment = 'RAW' AND p_basis IS NULL AND adjustment_as_of IS NULL)
        OR (p_adjustment = 'SPLIT_DIVIDEND' AND adjustment_as_of = p_basis))
      AND available_at <= p_cutoff AND observed_at <= p_cutoff AND ingested_at <= p_cutoff
      AND quality_state = 'VALID'
    ORDER BY instrument_id, session_date, available_at DESC, observed_at DESC, ingested_at DESC, observation_key
$$;

CREATE OR REPLACE VIEW market_data.consolidated_raw_liquidity AS
SELECT *, close * volume AS dollar_volume
FROM market_data.latest_daily_bars WHERE adjustment_mode = 'RAW' AND feed = 'sip';

CREATE OR REPLACE VIEW market_data.latest_corporate_actions AS
SELECT DISTINCT ON (provider_id, provider_action_id, instrument_id) *
FROM market_data.corporate_actions
ORDER BY provider_id, provider_action_id, instrument_id, observed_at DESC, ingested_at DESC, revision_hash;
