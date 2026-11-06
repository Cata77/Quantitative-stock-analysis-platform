package com.quantplatform.screener.sectors;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Reads one published run only; the sector is the frozen input of that publication. */
@Repository
public class SectorComparisonRepository {
    private final JdbcClient jdbc;

    public SectorComparisonRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Same publication rules as rankings and financials: complete, published primary runs. */
    public Optional<Run> run(UUID runId) {
        return jdbc.sql("""
            SELECT r.score_run_id, r.as_of_date, m.semantic_version,
                   r.request->'inputs'->>'classificationVersion'
            FROM research.scoring_runs r JOIN research.model_versions m USING(model_version_id)
            WHERE r.score_run_id=:run AND r.state='PUBLISHED' AND r.candidate='primary'
              AND r.expected_count=r.scored_count+r.excluded_count AND r.scored_count=r.eligible_count
              AND r.scored_count>=2 AND r.published_at IS NOT NULL
            """).param("run", runId)
            .query((rs, n) -> new Run(rs.getObject(1, UUID.class), rs.getObject(2, LocalDate.class),
                    rs.getString(3), rs.getString(4) == null ? null : UUID.fromString(rs.getString(4))))
            .optional();
    }

    public List<SectorCount> sectors(UUID runId) {
        return jdbc.sql("""
            SELECT l.canonical_input->>'sector' sector, count(*), count(*) FILTER (WHERE s.eligible),
                   count(*) FILTER (WHERE s.scoring_profile='GENERAL')
            FROM research.stock_scores s JOIN research.score_lineage l USING(score_run_id,instrument_id)
            WHERE s.score_run_id=:run AND l.canonical_input->>'sector' IS NOT NULL
            GROUP BY 1 ORDER BY 1
            """).param("run", runId)
            .query((rs, n) -> new SectorCount(rs.getString(1), rs.getInt(2), rs.getInt(3), rs.getInt(4)))
            .list();
    }

    public List<Member> members(UUID runId, String sector) {
        var members = new LinkedHashMap<UUID, Member>();
        jdbc.sql("""
            SELECT s.instrument_id, l.issuer_id, l.symbol, s.scoring_profile, s.peer_group, s.eligible,
                   s.ordinal_rank, s.composite_percentile, s.composite_z,
                   coalesce(s.output->>'composite', s.composite_z::text),
                   s.output->>'score_status', s.warnings::text
            FROM research.stock_scores s JOIN research.score_lineage l USING(score_run_id,instrument_id)
            WHERE s.score_run_id=:run AND l.canonical_input->>'sector'=:sector
            ORDER BY s.ordinal_rank NULLS LAST, l.symbol, s.instrument_id
            """).param("run", runId).param("sector", sector).query((rs, n) -> {
                var id = rs.getObject(1, UUID.class);
                members.put(id, new Member(id, rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getBoolean(6), (Integer) rs.getObject(7), rs.getBigDecimal(8),
                        rs.getBigDecimal(9), rs.getString(10) == null ? null : new BigDecimal(rs.getString(10)),
                        rs.getString(11), rs.getString(12).contains("\"LIMITATION:NON_PRIMARY_SHARE_CLASS\""),
                        new ArrayList<>()));
                return id;
            }).list();
        if (members.isEmpty()) return List.of();
        jdbc.sql("""
            SELECT f.instrument_id, f.metric, f.family, f.raw_value, f.cohort, f.cohort_count, f.reason
            FROM research.factor_observations f
            JOIN research.score_lineage l USING(score_run_id,instrument_id)
            WHERE f.score_run_id=:run AND l.canonical_input->>'sector'=:sector
            ORDER BY f.instrument_id, f.metric
            """).param("run", runId).param("sector", sector).query((rs, n) -> {
                members.get(rs.getObject(1, UUID.class)).factors().add(new Factor(rs.getString(2),
                        rs.getString(3), rs.getBigDecimal(4), rs.getString(5), rs.getInt(6), rs.getString(7)));
                return 1;
            }).list();
        return List.copyOf(members.values());
    }

    public record Run(UUID id, LocalDate asOfDate, String modelVersion, UUID classificationVersionId) {}

    public record SectorCount(String sector, int members, int eligible, int general) {}

    public record Member(UUID instrumentId, UUID issuerId, String symbol, String profile, String peerGroup,
            boolean eligible, Integer globalRank, BigDecimal globalPercentile, BigDecimal compositeScore,
            BigDecimal unroundedComposite, String scoreStatus, boolean nonPrimaryClass, List<Factor> factors) {
        public Map<String, Factor> byMetric() {
            var result = new LinkedHashMap<String, Factor>();
            factors.forEach(f -> result.put(f.metric(), f));
            return result;
        }
    }

    public record Factor(String metric, String family, BigDecimal raw, String cohort, int cohortCount,
            String reason) {}
}
