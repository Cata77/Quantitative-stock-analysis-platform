package com.quantplatform.screener.search;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.quantplatform.screener.ranking.RankingQueryRepository;

/**
 * Company search directly on PostgreSQL (P2s): the security master at the request time plus the
 * latest complete publication, the same documents the Elasticsearch indexer used to build. Every
 * query token must match a field; relevance follows the former boosts (symbol over name over
 * sector/industry). Matching is case-insensitive and literal; there is no fuzzy matching.
 */
@Repository
public class CompanySearchRepository {
    private final JdbcClient jdbc;

    public CompanySearchRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Object> search(List<String> tokens, int page, int size, Instant now) {
        return jdbc.sql("""
            WITH latest AS (
                SELECT * FROM research.scoring_runs WHERE state='PUBLISHED' AND candidate='primary'
                    AND as_of_date<=:day AND expected_count=scored_count+excluded_count
                    AND scored_count=eligible_count AND scored_count>=2 AND published_at IS NOT NULL
                ORDER BY as_of_date DESC,published_at DESC,score_run_id LIMIT 1
            ),
            documents AS (
                SELECT i.instrument_id,lower(coalesce(sym.symbol,'')) symbol,
                    lower(coalesce(issuer.legal_name,'')) name,
                    lower(coalesce(classification.mapped_sector,'')) sector,
                    lower(coalesce(issuer.sic,'')) industry,
                    jsonb_build_object('instrumentId',i.instrument_id,'symbol',sym.symbol,
                    'name',issuer.legal_name,'exchange',i.primary_exchange_mic,'country',issuer.domicile,
                    'sector',classification.mapped_sector,'industry',issuer.sic,'description',null,
                    'updatedAt',greatest(i.updated_at,issuer.updated_at,sym.observed_at,classification.observed_at),
                    'schemaVersion',1,
                    'score',CASE WHEN s.instrument_id IS NULL THEN NULL ELSE jsonb_build_object(
                        'runId',r.score_run_id,'asOfDate',r.as_of_date,'modelVersionId',r.model_version_id,
                        'symbolAtScore',l.symbol,'eligible',s.eligible,'rank',s.ordinal_rank,
                        'percentile',s.composite_percentile,'compositeScore',s.composite_z,
                        'valueScore',s.value_score,'qualityScore',s.quality_score,'momentumScore',s.momentum_score,
                        'profile',s.scoring_profile,'peerGroup',s.peer_group,'warnings',s.warnings) END) document
                FROM reference.instruments i JOIN reference.issuers issuer USING(issuer_id)
                LEFT JOIN LATERAL (
                    SELECT symbol,observed_at FROM reference.instrument_symbols WHERE instrument_id=i.instrument_id
                        AND effective_from<=:day AND (effective_to IS NULL OR effective_to>:day)
                        AND available_at<=:now AND observed_at<=:now
                    ORDER BY effective_from DESC,observed_at DESC,instrument_symbol_id LIMIT 1
                ) sym ON true
                LEFT JOIN LATERAL (
                    SELECT mapped_sector,observed_at FROM reference.issuer_classifications WHERE issuer_id=i.issuer_id
                        AND effective_from<=:day AND (effective_to IS NULL OR effective_to>:day)
                        AND available_at<=:now AND observed_at<=:now
                    ORDER BY effective_from DESC,observed_at DESC,issuer_classification_id LIMIT 1
                ) classification ON true
                LEFT JOIN latest r ON true
                LEFT JOIN research.stock_scores s ON s.score_run_id=r.score_run_id AND s.instrument_id=i.instrument_id
                LEFT JOIN research.score_lineage l ON l.score_run_id=s.score_run_id AND l.instrument_id=s.instrument_id
                WHERE i.valid_from<=:day AND (i.valid_to IS NULL OR i.valid_to>:day) AND i.active
            ),
            tokens AS (
                SELECT lower(t) token,
                    replace(replace(replace(lower(t),'\\','\\\\'),'%','\\%'),'_','\\_') pattern
                FROM jsonb_array_elements_text(CAST(:tokens AS jsonb)) t
            ),
            points AS (
                SELECT d.instrument_id,k.token,greatest(
                    CASE WHEN d.symbol=k.token THEN 10 WHEN d.symbol LIKE k.pattern||'%' THEN 5 ELSE 0 END,
                    CASE WHEN d.name LIKE k.pattern||'%' THEN 4
                         WHEN d.name LIKE '% '||k.pattern||'%' THEN 3
                         WHEN d.name LIKE '%'||k.pattern||'%' THEN 2 ELSE 0 END,
                    CASE WHEN d.sector LIKE '%'||k.pattern||'%' THEN 2 ELSE 0 END,
                    CASE WHEN d.industry LIKE k.pattern||'%' THEN 2 ELSE 0 END) points
                FROM documents d CROSS JOIN tokens k
            ),
            matched AS (
                SELECT d.instrument_id,d.document,sum(p.points) relevance
                FROM documents d JOIN points p USING(instrument_id)
                GROUP BY d.instrument_id,d.document
                HAVING bool_and(p.points>0)
            )
            SELECT jsonb_build_object('total',(SELECT count(*) FROM matched),
                'content',coalesce((SELECT jsonb_agg(jsonb_build_object('document',document,'relevance',relevance)
                        ORDER BY relevance DESC,instrument_id)
                    FROM (SELECT * FROM matched ORDER BY relevance DESC,instrument_id
                          LIMIT :size OFFSET :offset) pageRows),'[]'::jsonb))::text
            """)
            .param("day", now.atOffset(ZoneOffset.UTC).toLocalDate()).param("now", now.atOffset(ZoneOffset.UTC))
            .param("tokens", RankingQueryRepository.toJson(tokens)).param("size", size)
            .param("offset", (long) page * size)
            .query((rs, n) -> RankingQueryRepository.json(rs.getString(1))).single();
    }
}
