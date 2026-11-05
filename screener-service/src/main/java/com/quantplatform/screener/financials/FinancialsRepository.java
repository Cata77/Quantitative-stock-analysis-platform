package com.quantplatform.screener.financials;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.quantplatform.screener.ranking.RankingQueryRepository;

@Repository
public class FinancialsRepository {
    private final JdbcClient jdbc;

    public FinancialsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Same publication rules as rankings: complete, published primary runs only. */
    public Optional<Map<String, Object>> publication(UUID runId) {
        return jdbc.sql("""
            SELECT jsonb_build_object('id',r.score_run_id,'asOfDate',r.as_of_date,
                'marketCutoff',r.market_cutoff,'knowledgeCutoff',r.knowledge_cutoff,
                'publishedAt',r.published_at,'modelVersion',m.semantic_version,'scoredCount',r.scored_count,
                'preprocessingVersion',r.preprocessing_version,
                'mappingVersion',r.request->'inputs'->>'mappingVersion',
                'timingPolicy',coalesce(r.request->'inputs'->>'timingPolicy','CLOSE_V1'),
                'adjustedDataset',r.request->'inputs'->>'adjustedDataset',
                'adjustmentBasis',r.request->'inputs'->>'adjustmentBasis',
                'formulaContract',m.manifest->'formula_contract','periodRules',m.manifest->'periods')::text
            FROM research.scoring_runs r JOIN research.model_versions m USING(model_version_id)
            WHERE r.score_run_id=:run AND r.state='PUBLISHED' AND r.candidate='primary'
              AND r.expected_count=r.scored_count+r.excluded_count AND r.scored_count=r.eligible_count
              AND r.scored_count>=2 AND r.published_at IS NOT NULL
            """).param("run", runId)
            .query((rs, n) -> RankingQueryRepository.json(rs.getString(1))).optional();
    }

    public Optional<Map<String, Object>> instrument(UUID runId, UUID instrumentId) {
        return jdbc.sql("""
            SELECT jsonb_build_object('instrumentId',s.instrument_id,'issuerId',l.issuer_id,'symbol',l.symbol,
                'profile',s.scoring_profile,'peerGroup',s.peer_group,'eligible',s.eligible,
                'rank',s.ordinal_rank,'percentile',s.composite_percentile,'compositeScore',s.composite_z,
                'valueScore',s.value_score,'qualityScore',s.quality_score,'momentumScore',s.momentum_score,
                'valueContribution',s.value_contribution,'qualityContribution',s.quality_contribution,
                'momentumContribution',s.momentum_contribution,'warnings',s.warnings,
                'currency',i.currency,'output',s.output,'canonical',l.canonical_input,'prepared',l.prepared_input,
                'factors',COALESCE((SELECT jsonb_agg(to_jsonb(f)-'score_run_id'-'instrument_id' ORDER BY f.family,f.metric)
                    FROM research.factor_observations f
                    WHERE f.score_run_id=s.score_run_id AND f.instrument_id=s.instrument_id),'[]'),
                'exclusions',COALESCE((SELECT jsonb_agg(to_jsonb(e)-'score_run_id'-'instrument_id'
                        ORDER BY e.stage,e.reason_code,e.affected_metric)
                    FROM research.score_exclusions e
                    WHERE e.score_run_id=s.score_run_id AND e.instrument_id=s.instrument_id),'[]'))::text
            FROM research.stock_scores s JOIN research.score_lineage l USING(score_run_id,instrument_id)
            JOIN reference.instruments i ON i.instrument_id=s.instrument_id
            WHERE s.score_run_id=:run AND s.instrument_id=:instrument
            """).param("run", runId).param("instrument", instrumentId)
            .query((rs, n) -> RankingQueryRepository.json(rs.getString(1))).optional();
    }

    /**
     * The OPERATING_INCOME_TTM source facts of one issuer with the D&amp;A values of the same filing
     * (accession) and period, read from the retained SEC companyfacts response behind each fact.
     * Reprocessed artifacts are followed to their original response. Responses retrieved after the
     * knowledge cutoff are not used (null artifact). Only VALID entity-wide facts of the issuer count.
     */
    public List<EbitdaFromSource.Component> depreciationSources(UUID issuerId, List<String> factIds,
            OffsetDateTime knowledgeCutoff) {
        var ids = factIds.stream().map(id -> {
            try {
                return UUID.fromString(id);
            } catch (IllegalArgumentException invalid) {
                return null;
            }
        }).filter(java.util.Objects::nonNull).toArray(UUID[]::new);
        if (ids.length == 0) return List.of();
        return jdbc.sql("""
            WITH facts AS (
                SELECT f.fact_id,f.period_start,f.period_end,f.numeric_value,g.accession,f.source_artifact_id
                FROM fundamentals.fundamental_facts f JOIN fundamentals.filings g USING(filing_id)
                WHERE f.fact_id = ANY(string_to_array(:ids, ',')::uuid[]) AND f.issuer_id=:issuer AND f.metric_code='OPERATING_INCOME'
                  AND f.quality_state='VALID' AND f.period_start IS NOT NULL AND f.dimensions='{}'::jsonb
                  AND f.available_at<=:cutoff AND f.observed_at<=:cutoff
            ),
            sources AS (
                SELECT f.fact_id,a.source_artifact_id,a.inline_content
                FROM facts f JOIN operations.source_artifacts x ON x.source_artifact_id=f.source_artifact_id
                JOIN operations.source_artifacts a
                  ON a.source_artifact_id=coalesce((x.inline_content->>'originalArtifactId')::uuid,x.source_artifact_id)
                WHERE x.retrieved_at<=:cutoff AND a.retrieved_at<=:cutoff
                  AND a.inline_content->'companyFacts' IS NOT NULL
            ),
            matched AS (
                SELECT s.fact_id,k.concept,
                    array_agg(DISTINCT (e->>'val')::numeric ORDER BY (e->>'val')::numeric) amounts
                FROM sources s JOIN facts f USING(fact_id)
                CROSS JOIN unnest(string_to_array(:concepts, ',')) k(concept)
                CROSS JOIN LATERAL jsonb_array_elements(coalesce(
                    s.inline_content->'companyFacts'->'facts'->'us-gaap'->k.concept->'units'->'USD','[]'::jsonb)) e
                WHERE e->>'accn'=f.accession AND e->>'start'=f.period_start::text AND e->>'end'=f.period_end::text
                  AND e->>'form' IN ('10-K','10-Q','10-K/A','10-Q/A')
                GROUP BY s.fact_id,k.concept
            )
            SELECT f.fact_id,f.period_start,f.period_end,f.numeric_value,f.accession,s.source_artifact_id,
                coalesce((SELECT jsonb_object_agg(v.concept,to_jsonb(v.amounts::text[])) FROM matched v WHERE v.fact_id=f.fact_id),
                    '{}'::jsonb)::text depreciation
            FROM facts f LEFT JOIN sources s USING(fact_id)
            ORDER BY f.period_end,f.period_start
            """)
            .param("ids", java.util.Arrays.stream(ids).map(UUID::toString).collect(java.util.stream.Collectors.joining(",")))
            .param("issuer", issuerId).param("cutoff", knowledgeCutoff)
            .param("concepts", String.join(",", EbitdaFromSource.CONCEPTS))
            .query((rs, n) -> new EbitdaFromSource.Component(rs.getObject(1, UUID.class),
                    rs.getObject(2, LocalDate.class), rs.getObject(3, LocalDate.class), rs.getBigDecimal(4),
                    rs.getString(5), rs.getObject(6, UUID.class), amounts(rs.getString(7))))
            .list();
    }

    private static Map<String, List<BigDecimal>> amounts(String json) {
        var result = new java.util.LinkedHashMap<String, List<BigDecimal>>();
        RankingQueryRepository.json(json).forEach((concept, values) -> result.put(concept,
                ((List<?>) values).stream().map(v -> new BigDecimal(v.toString())).toList()));
        return result;
    }

    /**
     * Adjusted closes of the publication's own dataset and vintage for the last 253 XNYS
     * sessions up to the score date. Selection mirrors scoring-inputs.sql: cutoff filters on
     * sessions and bars, latest observation per session, then VALID/sip/currency/validity.
     * Sessions without a usable bar are returned with a null close.
     */
    public List<RsiCalculator.SessionClose> adjustedCloses(UUID instrumentId, LocalDate scoreDate,
            OffsetDateTime marketCutoff, OffsetDateTime knowledgeCutoff, UUID dataset, LocalDate basis,
            int sessions) {
        return jdbc.sql("""
            WITH p AS (SELECT CAST(:scoreDate AS date) score_date, CAST(:marketCutoff AS timestamptz) market_cutoff,
                    CAST(:knowledgeCutoff AS timestamptz) knowledge_cutoff, CAST(:dataset AS uuid) dataset,
                    CAST(:basis AS date) basis),
            sessions AS MATERIALIZED (
                SELECT s.session_date FROM reference.trading_sessions s CROSS JOIN p
                WHERE s.exchange_mic='XNYS' AND NOT s.holiday AND s.session_date<=p.score_date
                  AND s.closes_at<=p.market_cutoff AND s.available_at<=p.knowledge_cutoff
                  AND s.observed_at<=p.knowledge_cutoff
                ORDER BY s.session_date DESC LIMIT :sessions
            ),
            ranked AS (
                SELECT b.session_date,b.close,b.quality_state,b.feed,b.currency,
                    row_number() OVER (PARTITION BY b.session_date
                        ORDER BY b.available_at DESC,b.observed_at DESC,b.ingested_at DESC,b.observation_key) r
                FROM market_data.daily_bar_observations b JOIN sessions USING(session_date) CROSS JOIN p
                WHERE b.instrument_id=:instrument AND b.dataset_id=p.dataset
                  AND b.adjustment_mode='SPLIT_DIVIDEND' AND b.adjustment_as_of=p.basis
                  AND b.available_at<=p.knowledge_cutoff AND b.observed_at<=p.knowledge_cutoff
                  AND b.ingested_at<=p.knowledge_cutoff
            ),
            bars AS (
                SELECT r.session_date,r.close FROM ranked r JOIN reference.instruments i ON i.instrument_id=:instrument
                WHERE r.r=1 AND r.quality_state='VALID' AND r.feed='sip' AND r.currency=i.currency
                  AND r.session_date>=i.valid_from AND (i.valid_to IS NULL OR r.session_date<i.valid_to)
            )
            SELECT s.session_date,b.close FROM sessions s LEFT JOIN bars b USING(session_date)
            ORDER BY s.session_date
            """)
            .param("scoreDate", scoreDate).param("marketCutoff", marketCutoff)
            .param("knowledgeCutoff", knowledgeCutoff).param("dataset", dataset).param("basis", basis)
            .param("instrument", instrumentId).param("sessions", sessions)
            .query((rs, n) -> new RsiCalculator.SessionClose(rs.getObject(1, LocalDate.class),
                    rs.getObject(2, BigDecimal.class)))
            .list();
    }
}
