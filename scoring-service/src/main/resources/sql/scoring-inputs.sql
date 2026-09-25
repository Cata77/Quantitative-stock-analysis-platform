WITH
p AS (SELECT CAST(:scoreDate AS date) score_date, CAST(:marketCutoff AS timestamptz) market_cutoff,
    CAST(:knowledgeCutoff AS timestamptz) knowledge_cutoff, CAST(:basis AS date) basis,
    CAST(:rawDataset AS uuid) raw_dataset, CAST(:adjustedDataset AS uuid) adjusted_dataset),
snapshots AS MATERIALIZED (
    SELECT s.*, u.code FROM reference.universe_snapshots s JOIN reference.universes u USING(universe_id)
    WHERE (s.universe_snapshot_id=:sp500 AND u.code='SP500')
       OR (s.universe_snapshot_id=:nasdaq AND u.code='NASDAQ100')
),
members AS MATERIALIZED (
    SELECT m.instrument_id, bool_or(s.code='SP500') in_sp500, bool_or(s.code='NASDAQ100') in_nasdaq,
        bool_and(m.primary_liquid_class) primary_class,
        count(DISTINCT m.primary_liquid_class)>1 primary_conflict
    FROM reference.universe_memberships m JOIN snapshots s USING(universe_snapshot_id)
    GROUP BY m.instrument_id
),
instruments AS MATERIALIZED (
    SELECT m.*, i.issuer_id, i.security_type, i.currency, i.valid_from, i.valid_to,
        count(*) FILTER (WHERE m.primary_class) OVER (PARTITION BY i.issuer_id)>1 primary_ambiguous
    FROM members m JOIN reference.instruments i USING(instrument_id)
),
issuers AS MATERIALIZED (SELECT DISTINCT issuer_id FROM instruments),
sessions AS MATERIALIZED (
    SELECT s.session_date, row_number() OVER (ORDER BY s.session_date DESC) n
    FROM reference.trading_sessions s CROSS JOIN p
    WHERE s.exchange_mic='XNYS' AND NOT s.holiday AND s.session_date<=p.score_date
      AND s.closes_at<=p.market_cutoff AND s.available_at<=p.knowledge_cutoff
      AND s.observed_at<=p.knowledge_cutoff
    ORDER BY s.session_date DESC LIMIT 253
),
validation AS (
    SELECT ARRAY_REMOVE(ARRAY[
        CASE WHEN :timingPolicy='NEXT_OPEN_MINUS_30M_V1' AND NOT EXISTS (
            SELECT 1 FROM reference.trading_sessions n CROSS JOIN p
            WHERE n.exchange_mic='XNYS' AND n.session_date=(SELECT min(session_date)
                FROM reference.trading_sessions WHERE exchange_mic='XNYS' AND session_date>p.score_date AND NOT holiday)
              AND n.available_at<=p.market_cutoff AND n.observed_at<=p.market_cutoff
              AND n.opens_at=p.knowledge_cutoff+interval '30 minutes'
              AND (SELECT count(*) FROM reference.trading_sessions c WHERE c.exchange_mic='XNYS'
                AND c.session_date>p.score_date AND c.session_date<=n.session_date
                AND c.available_at<=p.market_cutoff AND c.observed_at<=p.market_cutoff)=n.session_date-p.score_date
        ) THEN 'INVALID_PRE_OPEN_CUTOFF' END,
        CASE WHEN (SELECT count(*) FROM snapshots)<>2 THEN 'INVALID_SNAPSHOT_PAIR' END,
        CASE WHEN EXISTS (SELECT 1 FROM snapshots s CROSS JOIN p
            WHERE s.completeness_status<>'COMPLETE' OR s.effective_date>p.score_date
               OR s.observed_at>p.knowledge_cutoff OR s.import_mode<>'CURRENT_SNAPSHOT_FORWARD'
               OR s.expected_member_count IS DISTINCT FROM (SELECT count(*) FROM reference.universe_memberships m
                    WHERE m.universe_snapshot_id=s.universe_snapshot_id))
            THEN 'SNAPSHOT_NOT_POINT_IN_TIME_COMPLETE' END,
        CASE WHEN EXISTS (SELECT 1 FROM snapshots s JOIN reference.universe_snapshots newer
            ON newer.universe_id=s.universe_id CROSS JOIN p
            WHERE newer.import_mode='CURRENT_SNAPSHOT_FORWARD'
              AND newer.effective_date<=p.score_date AND newer.observed_at<=p.knowledge_cutoff
              AND (newer.effective_date>s.effective_date OR
                  (newer.effective_date=s.effective_date AND newer.observed_at>s.observed_at)))
            THEN 'SUPERSEDED_UNIVERSE_SNAPSHOT' END,
        CASE WHEN NOT EXISTS (SELECT 1 FROM reference.trading_sessions s CROSS JOIN p
            WHERE s.exchange_mic='XNYS' AND s.session_date=p.score_date AND NOT s.holiday
              AND s.closes_at=p.market_cutoff AND s.available_at<=p.knowledge_cutoff
              AND s.observed_at<=p.knowledge_cutoff) THEN 'INVALID_SIGNAL_CLOSE' END,
        CASE WHEN (SELECT count(*) FROM sessions)>0 AND
            (SELECT count(*) FROM reference.trading_sessions s CROSS JOIN p
             WHERE s.exchange_mic='XNYS' AND s.session_date BETWEEN (SELECT min(session_date) FROM sessions) AND p.score_date
               AND s.available_at<=p.knowledge_cutoff AND s.observed_at<=p.knowledge_cutoff)
             <> (SELECT p.score_date-min(s.session_date)+1 FROM sessions s CROSS JOIN p GROUP BY p.score_date)
            THEN 'INCOMPLETE_TRADING_CALENDAR' END,
        CASE WHEN NOT EXISTS (SELECT 1 FROM operations.datasets WHERE dataset_id=:rawDataset)
            OR NOT EXISTS (SELECT 1 FROM operations.datasets WHERE dataset_id=:adjustedDataset)
            THEN 'UNKNOWN_PRICE_DATASET' END,
        CASE WHEN NOT EXISTS (SELECT 1 FROM reference.classification_versions WHERE classification_version_id=:classification)
            THEN 'UNKNOWN_CLASSIFICATION_VERSION' END,
        CASE WHEN EXISTS (SELECT 1 FROM unnest(CAST(:requiredMetrics AS text[])) m
            WHERE NOT EXISTS (SELECT 1 FROM fundamentals.metric_definitions d WHERE d.metric_code=m))
            THEN 'UNKNOWN_REQUIRED_METRIC' END
    ],NULL) errors
),
symbols_ranked AS (
    SELECT s.*, row_number() OVER (PARTITION BY s.instrument_id
        ORDER BY s.effective_from DESC,s.available_at DESC,s.instrument_symbol_id) r
    FROM reference.instrument_symbols s JOIN members USING(instrument_id) CROSS JOIN p
    WHERE s.effective_from<=p.score_date AND (s.effective_to IS NULL OR s.effective_to>p.score_date)
      AND s.available_at<=p.knowledge_cutoff AND s.observed_at<=p.knowledge_cutoff
),
sectors_ranked AS (
    SELECT s.*, row_number() OVER (PARTITION BY s.issuer_id ORDER BY s.effective_from DESC,
        s.available_at DESC,s.issuer_classification_id) r
    FROM reference.issuer_classifications s JOIN issuers USING(issuer_id) CROSS JOIN p
    WHERE s.classification_version_id=:classification AND s.effective_from<=p.score_date
      AND (s.effective_to IS NULL OR s.effective_to>p.score_date)
      AND s.available_at<=p.knowledge_cutoff AND s.observed_at<=p.knowledge_cutoff
),
profiles_ranked AS (
    SELECT f.*, row_number() OVER (PARTITION BY f.issuer_id
        ORDER BY f.available_at DESC,f.observation_key) r
    FROM fundamentals.profile_observations f JOIN issuers USING(issuer_id) CROSS JOIN p
    WHERE f.available_at<=p.knowledge_cutoff
),
filing_versions AS MATERIALIZED (
    SELECT l.*, dense_rank() OVER (PARTITION BY l.issuer_id,l.accession,l.mapping_version
        ORDER BY l.observed_at DESC) vintage
    FROM fundamentals.filings l JOIN issuers USING(issuer_id) CROSS JOIN p
    WHERE l.mapping_version=:mapping AND l.available_at<=p.knowledge_cutoff
      AND l.observed_at<=p.knowledge_cutoff AND l.fiscal_period_end<=p.score_date
),
fact_revisions AS (
    SELECT f.*, l.filed_date, l.accepted_at, l.parser_version, l.accession, d.unit canonical_unit,
        dense_rank() OVER (PARTITION BY f.issuer_id,f.metric_code,f.period_start,f.period_end
            ORDER BY l.accepted_at DESC,f.available_at DESC) revision_rank
    FROM fundamentals.fundamental_facts f JOIN filing_versions l USING(filing_id)
    JOIN fundamentals.metric_definitions d USING(metric_code) CROSS JOIN p
    WHERE l.vintage=1 AND f.mapping_version=:mapping AND f.available_at<=p.knowledge_cutoff
      AND f.observed_at<=p.knowledge_cutoff AND f.period_end<=p.score_date
),
preferred_facts AS (
    SELECT *, min(priority) OVER (PARTITION BY issuer_id,metric_code,period_start,period_end) best
    FROM fact_revisions WHERE revision_rank=1
),
fact_groups AS (
    SELECT f.*,
        min(numeric_value) OVER fact_period minimum_value,
        max(numeric_value) OVER fact_period maximum_value,
        bool_and(quality_state='VALID') OVER fact_period all_valid
    FROM preferred_facts f WHERE priority=best
    WINDOW fact_period AS (PARTITION BY issuer_id,metric_code,period_start,period_end)
),
facts AS MATERIALIZED (
    SELECT DISTINCT ON (issuer_id,metric_code,period_start,period_end) *
    FROM fact_groups WHERE minimum_value=maximum_value AND all_valid
    ORDER BY issuer_id,metric_code,period_start,period_end,fact_id
),
fact_bundles AS (
    SELECT issuer_id,
        array_agg(DISTINCT metric_code) FILTER (WHERE filed_date>=p.score_date-180) recent_metrics,
        jsonb_agg(jsonb_build_object(
        'factId',fact_id,'filingId',filing_id,'metric',metric_code,'start',period_start,'end',period_end,
        'value',numeric_value,'unit',canonical_unit,'availableAt',available_at,'observedAt',observed_at,'filedDate',filed_date,
        'sourceArtifactId',source_artifact_id,'mappingVersion',mapping_version,'parserVersion',parser_version)
        ORDER BY metric_code,period_end DESC,period_start) facts
    FROM facts CROSS JOIN p GROUP BY issuer_id
),
filing_recency AS (
    SELECT issuer_id,bool_or(filed_date>=p.score_date-180) recent
    FROM filing_versions CROSS JOIN p WHERE vintage=1 GROUP BY issuer_id
),
shares_ranked AS (
    SELECT s.instrument_id,f.fact_id,f.numeric_value,f.period_end,f.available_at,
        row_number() OVER (PARTITION BY s.instrument_id ORDER BY f.period_end DESC,f.available_at DESC,f.fact_id) r
    FROM fundamentals.instrument_share_facts s JOIN facts f USING(fact_id) CROSS JOIN p
    WHERE f.metric_code='SHARES_OUTSTANDING' AND s.available_at<=p.knowledge_cutoff
),
bars_ranked AS MATERIALIZED (
    SELECT b.instrument_id,b.session_date,b.adjustment_mode,b.close,b.volume,b.observation_key,
        b.source_artifact_id,b.quality_state,b.feed,b.currency,s.n,row_number() OVER (PARTITION BY b.instrument_id,b.session_date,b.adjustment_mode
        ORDER BY b.available_at DESC,b.observed_at DESC,b.ingested_at DESC,b.observation_key) r
    FROM market_data.daily_bar_observations b JOIN members USING(instrument_id)
    JOIN sessions s USING(session_date) CROSS JOIN p
    WHERE b.session_date BETWEEN (SELECT min(session_date) FROM sessions) AND p.score_date
      AND ((b.dataset_id=p.raw_dataset AND b.adjustment_mode='RAW' AND b.adjustment_as_of IS NULL)
        OR (b.dataset_id=p.adjusted_dataset AND b.adjustment_mode='SPLIT_DIVIDEND' AND b.adjustment_as_of=p.basis))
      AND b.available_at<=p.knowledge_cutoff AND b.observed_at<=p.knowledge_cutoff
      AND b.ingested_at<=p.knowledge_cutoff
),
bars AS MATERIALIZED (
    SELECT b.* FROM bars_ranked b JOIN instruments i USING(instrument_id)
    WHERE r=1 AND quality_state='VALID' AND feed='sip' AND b.currency=i.currency
      AND b.session_date>=i.valid_from AND (i.valid_to IS NULL OR b.session_date<i.valid_to)
),
prices AS (
    SELECT instrument_id,
        max(close) FILTER (WHERE adjustment_mode='RAW' AND n=1) raw_close,
        max(close) FILTER (WHERE adjustment_mode='SPLIT_DIVIDEND' AND n=1) adjusted_close,
        max(close) FILTER (WHERE adjustment_mode='SPLIT_DIVIDEND' AND n=22) momentum_recent,
        max(close) FILTER (WHERE adjustment_mode='SPLIT_DIVIDEND' AND n=253) momentum_old,
        count(*) FILTER (WHERE adjustment_mode='SPLIT_DIVIDEND' AND n<=252) history_count,
        count(*) FILTER (WHERE adjustment_mode='RAW' AND n<=20) liquidity_count,
        percentile_cont(0.5) WITHIN GROUP (ORDER BY (close*volume)::double precision)
            FILTER (WHERE adjustment_mode='RAW' AND n<=20) median_dollar_volume,
        jsonb_agg(jsonb_build_object('date',session_date,'mode',adjustment_mode,
            'observationKey',observation_key,'sourceArtifactId',source_artifact_id)
            ORDER BY session_date,adjustment_mode) FILTER (WHERE
                (adjustment_mode='RAW' AND n<=20) OR (adjustment_mode='SPLIT_DIVIDEND' AND n IN (1,22,253))) lineage
    FROM bars GROUP BY instrument_id
),
quality AS (
    SELECT i.instrument_id,jsonb_agg(jsonb_build_object('code',q.issue_type,'detail',q.affected_key)
        ORDER BY q.data_quality_issue_id) issues
    FROM instruments i JOIN operations.data_quality_issues q ON q.instrument_id=i.instrument_id
        OR q.instrument_id IS NULL
    CROSS JOIN p
    WHERE q.severity='BLOCKING' AND q.detected_at<=p.knowledge_cutoff
      AND (q.resolved_at IS NULL OR q.resolved_at>p.knowledge_cutoff)
      AND (q.instrument_id IS NOT NULL OR q.dataset_id IN (p.raw_dataset,p.adjusted_dataset))
    GROUP BY i.instrument_id
),
rows AS (
    SELECT i.instrument_id, jsonb_build_object(
        'instrumentId',i.instrument_id,'issuerId',i.issuer_id,'symbol',s.symbol,
        'inSp500',i.in_sp500,'inNasdaq100',i.in_nasdaq,'primaryClass',i.primary_class,
        'profile',coalesce(pr.profile,'UNKNOWN'),'sector',c.mapped_sector,'peerGroup',
            CASE WHEN pr.profile='GENERAL' THEN c.mapped_sector END,
        'rawClose',v.raw_close,'adjustedClose',v.adjusted_close,'momentumRecent',v.momentum_recent,
        'momentumOld',v.momentum_old,'historyCount',coalesce(v.history_count,0),
        'liquidityCount',coalesce(v.liquidity_count,0),'medianDollarVolume',v.median_dollar_volume,
        'sharesOutstanding',sh.numeric_value,'shareFactId',sh.fact_id,
        'facts',coalesce(f.facts,'[]'::jsonb),'priceLineage',coalesce(v.lineage,'[]'::jsonb),
        'reasons',(
            SELECT coalesce(jsonb_agg(jsonb_build_object('code',code,'detail',detail)),'[]'::jsonb)
            FROM (VALUES
                (CASE WHEN i.security_type<>'COMMON_STOCK' OR i.valid_from>p.score_date
                    OR i.valid_to<=p.score_date THEN 'INACTIVE_OR_UNSUPPORTED_SECURITY' END,NULL::text),
                (CASE WHEN NOT i.primary_class OR i.primary_conflict OR i.primary_ambiguous THEN 'NON_PRIMARY_SHARE_CLASS' END,NULL),
                (CASE WHEN s.symbol IS NULL THEN 'MISSING_EFFECTIVE_SYMBOL' END,NULL),
                (CASE WHEN c.mapped_sector IS NULL THEN 'MISSING_SECTOR' END,NULL),
                (CASE WHEN pr.profile IS DISTINCT FROM 'GENERAL' OR pr.status IS DISTINCT FROM 'FACTS_AVAILABLE'
                    THEN 'MODEL_NOT_SUPPORTED' END,pr.reason),
                (CASE WHEN v.raw_close IS NULL OR v.adjusted_close IS NULL THEN 'MISSING_SCORE_DATE_PRICE' END,NULL),
                (CASE WHEN v.adjusted_close<5 THEN 'PRICE_BELOW_MINIMUM' END,NULL),
                (CASE WHEN coalesce(v.history_count,0)<252 OR (SELECT count(*) FROM sessions)<253
                    THEN 'INSUFFICIENT_PRICE_HISTORY' END,NULL),
                (CASE WHEN v.momentum_recent IS NULL OR v.momentum_old IS NULL THEN 'MISSING_MOMENTUM_BOUNDARY' END,NULL),
                (CASE WHEN coalesce(v.liquidity_count,0)<20 THEN 'INCOMPLETE_LIQUIDITY_WINDOW' END,NULL),
                (CASE WHEN v.median_dollar_volume<5000000 THEN 'ILLIQUID' END,NULL),
                (CASE WHEN sh.numeric_value IS NULL OR sh.numeric_value<=0 THEN 'MISSING_INSTRUMENT_SHARES' END,NULL),
                (CASE WHEN sh.period_end<p.score_date-180 THEN 'STALE_SHARES' END,NULL),
                (CASE WHEN NOT coalesce(fr.recent,false) THEN 'STALE_OR_MISSING_FILING' END,NULL)
            ) reasons(code,detail) WHERE code IS NOT NULL
        ) || coalesce(q.issues,'[]'::jsonb) || (
            SELECT coalesce(jsonb_agg(jsonb_build_object('code','MISSING_REQUIRED_METRIC','detail',m)),'[]'::jsonb)
            FROM unnest(CAST(:requiredMetrics AS text[])) m
            WHERE NOT (m=ANY(coalesce(f.recent_metrics,ARRAY[]::varchar[])))
        )
    ) data
    FROM instruments i CROSS JOIN p
    LEFT JOIN symbols_ranked s ON s.instrument_id=i.instrument_id AND s.r=1
    LEFT JOIN sectors_ranked c ON c.issuer_id=i.issuer_id AND c.r=1
    LEFT JOIN profiles_ranked pr ON pr.issuer_id=i.issuer_id AND pr.r=1
    LEFT JOIN fact_bundles f ON f.issuer_id=i.issuer_id
    LEFT JOIN filing_recency fr ON fr.issuer_id=i.issuer_id
    LEFT JOIN shares_ranked sh ON sh.instrument_id=i.instrument_id AND sh.r=1
    LEFT JOIN prices v ON v.instrument_id=i.instrument_id
    LEFT JOIN quality q ON q.instrument_id=i.instrument_id
)
SELECT jsonb_build_object('errors',to_jsonb(validation.errors),
    'inputs',coalesce((SELECT jsonb_agg(data ORDER BY instrument_id) FROM rows),'[]'::jsonb))::text
FROM validation
