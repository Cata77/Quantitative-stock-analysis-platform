-- A fresh download is not evidence of what an aggregated API exposed years ago.
-- All revisions must have been observed by the supplied knowledge cutoff.
CREATE OR REPLACE FUNCTION fundamentals.facts_as_of(p_issuer UUID,p_cutoff TIMESTAMPTZ,p_mapping TEXT)
RETURNS TABLE (fact_id UUID,metric_code VARCHAR,period_start DATE,period_end DATE,numeric_value NUMERIC,
    unit VARCHAR,available_at TIMESTAMPTZ,filing_id UUID)
LANGUAGE sql STABLE AS $$
WITH filing_versions AS (
    SELECT l.*, dense_rank() OVER (PARTITION BY issuer_id,accession,mapping_version
        ORDER BY observed_at DESC) vintage
    FROM fundamentals.filings l
    WHERE issuer_id=p_issuer AND mapping_version=p_mapping
        AND available_at<=p_cutoff AND observed_at<=p_cutoff
), revisions AS (
    SELECT f.*,d.unit canonical_unit,
        dense_rank() OVER (PARTITION BY f.metric_code,f.period_start,f.period_end
            ORDER BY l.accepted_at DESC,f.available_at DESC) revision_rank
    FROM fundamentals.fundamental_facts f
    JOIN filing_versions l ON l.filing_id=f.filing_id AND l.vintage=1
    JOIN fundamentals.metric_definitions d USING (metric_code)
    WHERE f.issuer_id=p_issuer AND f.mapping_version=p_mapping
        AND f.available_at<=p_cutoff AND f.observed_at<=p_cutoff
), preferred AS (
    SELECT *, min(priority) OVER (PARTITION BY metric_code,period_start,period_end) best_priority
    FROM revisions WHERE revision_rank=1
), unambiguous AS (
    SELECT metric_code,period_start,period_end
    FROM preferred WHERE priority=best_priority
    GROUP BY metric_code,period_start,period_end
    HAVING count(DISTINCT numeric_value)=1 AND bool_and(quality_state='VALID')
)
SELECT DISTINCT ON (f.metric_code,f.period_start,f.period_end)
    f.fact_id,f.metric_code,f.period_start,f.period_end,f.numeric_value,f.canonical_unit,f.available_at,f.filing_id
FROM preferred f JOIN unambiguous u ON u.metric_code=f.metric_code AND u.period_start IS NOT DISTINCT FROM f.period_start
    AND u.period_end=f.period_end
WHERE f.priority=f.best_priority
ORDER BY f.metric_code,f.period_start,f.period_end,f.fact_id
$$;

CREATE OR REPLACE VIEW fundamentals.statements AS
SELECT f.issuer_id,f.filing_id,d.statement,f.metric_code,f.period_start,f.period_end,f.source_value,
    f.numeric_value,f.unit,f.dimensions,f.quality_state,f.source_artifact_id,f.mapping_version
FROM fundamentals.fundamental_facts f LEFT JOIN fundamentals.metric_definitions d USING (metric_code);

CREATE OR REPLACE FUNCTION fundamentals.regulatory_facts_as_of(p_issuer UUID,p_cutoff TIMESTAMPTZ)
RETURNS SETOF fundamentals.regulatory_facts LANGUAGE sql STABLE AS $$
WITH revisions AS (
    SELECT f AS fact,l.scope,l.effective_from,l.effective_to,
        dense_rank() OVER (PARTITION BY f.regulated_entity_id,f.period_end
            ORDER BY f.available_at DESC) revision_rank
    FROM fundamentals.regulatory_facts f
    JOIN fundamentals.issuer_regulated_entity_links l USING (link_id)
    WHERE l.issuer_id=p_issuer AND l.available_at<=p_cutoff
        AND f.available_at<=p_cutoff AND f.observed_at<=p_cutoff
)
SELECT (fact).* FROM revisions WHERE revision_rank=1 AND scope='SAME_LEGAL_ENTITY'
    AND effective_from<=(fact).period_end AND (effective_to IS NULL OR effective_to>(fact).period_end)
    AND (fact).quality_state='VALID'
$$;
