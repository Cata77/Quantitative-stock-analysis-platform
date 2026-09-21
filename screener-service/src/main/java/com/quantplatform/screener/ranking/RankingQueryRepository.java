package com.quantplatform.screener.ranking;

import java.time.LocalDate;
import java.util.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class RankingQueryRepository {
    private final JdbcClient jdbc;
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    public RankingQueryRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public static Map<String,Object> json(String value) {
        try { return JSON.readValue(value, new TypeReference<LinkedHashMap<String,Object>>() {}); }
        catch (java.io.IOException e) { throw new IllegalStateException("Invalid stored read model", e); }
    }

    public Optional<Map<String,Object>> latest(LocalDate asOf, UUID model, UUID runId) {
        return jdbc.sql("""
            SELECT jsonb_build_object('id',r.score_run_id,'asOfDate',r.as_of_date,
                'marketCutoff',r.market_cutoff,'knowledgeCutoff',r.knowledge_cutoff,
                'effectiveFrom',r.effective_from,'publishedAt',r.published_at,'candidate',r.candidate,'supersedes',r.supersedes,
                'modelVersionId',r.model_version_id,'modelVersion',m.semantic_version,
                'modelStatus',m.approval_state,'manifestSha256',m.manifest_sha256,
                'inputVersion',r.input_version,'preprocessingVersion',r.preprocessing_version,
                'classificationVersionId',r.request->'inputs'->'classificationVersion',
                'sp500SnapshotId',r.sp500_snapshot_id,'nasdaq100SnapshotId',r.nasdaq100_snapshot_id,
                'sp500Mode',sp.import_mode,'nasdaq100Mode',nq.import_mode,
                'sp500Bias',sp.research_bias_label,'nasdaq100Bias',nq.research_bias_label,
                'expectedCount',r.expected_count,'scoredCount',r.scored_count,
                'excludedCount',r.excluded_count,'complete',true,
                'coverage',r.scored_count::numeric / NULLIF(r.expected_count,0),
                'profileCoverage',(SELECT jsonb_object_agg(profile,counts) FROM (
                    SELECT scoring_profile profile,jsonb_build_object('expected',count(*),
                      'scored',count(*) FILTER(WHERE eligible),'excluded',count(*) FILTER(WHERE NOT eligible)) counts
                    FROM research.stock_scores WHERE score_run_id=r.score_run_id GROUP BY scoring_profile) c))::text
            FROM research.scoring_runs r JOIN research.model_versions m USING(model_version_id)
            JOIN reference.universe_snapshots sp ON sp.universe_snapshot_id=r.sp500_snapshot_id
            JOIN reference.universe_snapshots nq ON nq.universe_snapshot_id=r.nasdaq100_snapshot_id
            WHERE r.state='PUBLISHED' AND r.candidate='primary' AND r.as_of_date<=:asOf
              AND r.expected_count=r.scored_count+r.excluded_count AND r.scored_count=r.eligible_count
              AND r.scored_count>=2 AND r.published_at IS NOT NULL
              AND (CAST(:model AS uuid) IS NULL OR r.model_version_id=CAST(:model AS uuid))
              AND (CAST(:run AS uuid) IS NULL OR r.score_run_id=CAST(:run AS uuid))
            ORDER BY r.as_of_date DESC,r.published_at DESC,r.score_run_id LIMIT 1
            """).param("asOf",asOf).param("model",model).param("run",runId)
            .query((rs,n)->json(rs.getString(1))).optional();
    }

    private static final String FROM = """
        FROM research.stock_scores s JOIN research.score_lineage l USING(score_run_id,instrument_id)
        WHERE s.score_run_id=:run
          AND (CAST(:instrument AS uuid) IS NULL OR s.instrument_id=CAST(:instrument AS uuid))
          AND (:universe='UNION' OR (:universe='SP500' AND (l.canonical_input->>'inSp500')::boolean)
              OR (:universe='NASDAQ100' AND (l.canonical_input->>'inNasdaq100')::boolean))
          AND (:eligibility='ALL' OR s.eligible=(:eligibility='ELIGIBLE'))
          AND (CAST(:profile AS text) IS NULL OR s.scoring_profile=:profile)
          AND (CAST(:sector AS text) IS NULL OR l.canonical_input->>'sector'=:sector)
          AND (CAST(:peer AS text) IS NULL OR s.peer_group=:peer)
          AND (CAST(:warning AS text) IS NULL OR s.warnings @> jsonb_build_array(CAST(:warning AS text)))
        """;

    private JdbcClient.StatementSpec bind(String sql, UUID run, RankingFilter f) {
        return jdbc.sql(sql).param("run",run).param("universe",f.universe().name())
            .param("instrument",f.instrumentId()).param("eligibility",f.eligibility().name()).param("profile",f.profile())
            .param("sector",f.sector()).param("peer",f.peerGroup()).param("warning",f.warning());
    }
    public long count(UUID run, RankingFilter f) {
        return bind("SELECT count(*) " + FROM,run,f).query(Long.class).single();
    }
    public List<Map<String,Object>> page(UUID run, RankingFilter f, int size, long offset) {
        String sort = switch(f.sort()) {
            case COMPOSITE -> "s.ordinal_rank"; // Original unrounded model ordering, including UUID ties.
            case VALUE -> "s.value_score";
            case QUALITY -> "s.quality_score";
            case MOMENTUM -> "s.momentum_score";
            case CONTRIBUTION -> "(SELECT contribution FROM research.factor_observations f WHERE f.score_run_id=s.score_run_id AND f.instrument_id=s.instrument_id AND f.metric=:metric)";
        };
        String direction = f.sort()==RankingFilter.Sort.COMPOSITE
            ? (f.direction()==RankingFilter.Direction.DESC?"ASC":"DESC") : f.direction().name();
        String sql = """
            SELECT jsonb_build_object('instrumentId',s.instrument_id,'issuerId',l.issuer_id,'symbol',l.symbol,
                'maximumInputAvailabilityTimestamp',l.maximum_input_availability_timestamp,
                'rank',s.ordinal_rank,'percentile',s.composite_percentile,'compositeScore',s.composite_z,
                'valueScore',s.value_score,'qualityScore',s.quality_score,'momentumScore',s.momentum_score,
                'valueContribution',s.value_contribution,'qualityContribution',s.quality_contribution,
                'momentumContribution',s.momentum_contribution,'eligible',s.eligible,
                'profile',s.scoring_profile,'sector',l.canonical_input->>'sector','peerGroup',s.peer_group,
                'inSp500',l.canonical_input->'inSp500','inNasdaq100',l.canonical_input->'inNasdaq100',
                'availableMetricCount',s.available_metric_count,'missingMetricCount',s.missing_metric_count,
                'warnings',s.warnings,
                'factors',COALESCE((SELECT jsonb_agg(to_jsonb(f)-'score_run_id'-'instrument_id' ORDER BY metric)
                    FROM research.factor_observations f WHERE f.score_run_id=s.score_run_id AND f.instrument_id=s.instrument_id),'[]'),
                'exclusions',COALESCE((SELECT jsonb_agg(to_jsonb(e)-'score_run_id'-'instrument_id' ORDER BY stage,reason_code,affected_metric)
                    FROM research.score_exclusions e WHERE e.score_run_id=s.score_run_id AND e.instrument_id=s.instrument_id),'[]'))::text
            """ + FROM + " ORDER BY " + sort + " " + direction + " NULLS LAST,s.instrument_id LIMIT :size OFFSET :offset";
        var query=bind(sql,run,f).param("size",size).param("offset",offset);
        if(f.sort()==RankingFilter.Sort.CONTRIBUTION) query=query.param("metric",f.metric());
        return query.query((rs,n)->json(rs.getString(1))).list();
    }
}
