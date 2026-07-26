package com.quantplatform.screener.ranking;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class RankingQueryRepository {

    private static final String LATEST_BATCH_SQL = """
            SELECT time
            FROM factor_scores
            WHERE time <= :asOf
            ORDER BY time DESC
            LIMIT 1
            """;

    private static final String COUNT_BATCH_SQL = """
            SELECT COUNT(*)
            FROM factor_scores
            WHERE time = :scoreTime
            """;

    private static final String PAGE_SQL = """
            SELECT
                DENSE_RANK() OVER (
                    ORDER BY composite_score DESC
                ) AS ranking_position,
                symbol,
                composite_score,
                z_value,
                z_momentum,
                z_quality
            FROM factor_scores
            WHERE time = :scoreTime
            ORDER BY composite_score DESC, symbol ASC
            LIMIT :pageSize OFFSET :offset
            """;

    private final JdbcClient jdbcClient;

    public RankingQueryRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public Optional<Instant> findLatestBatchAtOrBefore(Instant asOf) {
        return jdbcClient.sql(LATEST_BATCH_SQL)
                .param("asOf", utc(asOf))
                .query((resultSet, rowNumber) ->
                        resultSet.getObject("time", OffsetDateTime.class).toInstant())
                .optional();
    }

    public long countBatch(Instant scoreTime) {
        return jdbcClient.sql(COUNT_BATCH_SQL)
                .param("scoreTime", utc(scoreTime))
                .query(Long.class)
                .single();
    }

    public List<RankingItem> findPage(
            Instant scoreTime,
            int pageSize,
            long offset
    ) {
        return jdbcClient.sql(PAGE_SQL)
                .param("scoreTime", utc(scoreTime))
                .param("pageSize", pageSize)
                .param("offset", offset)
                .query(this::mapRanking)
                .list();
    }

    private RankingItem mapRanking(ResultSet resultSet, int rowNumber) throws SQLException {
        return new RankingItem(
                resultSet.getLong("ranking_position"),
                resultSet.getString("symbol"),
                resultSet.getBigDecimal("composite_score"),
                resultSet.getBigDecimal("z_value"),
                resultSet.getBigDecimal("z_momentum"),
                resultSet.getBigDecimal("z_quality"));
    }

    private OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
