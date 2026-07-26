package com.quantplatform.screener;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:screener;MODE=PostgreSQL",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.hikari.read-only=false",
        "spring.sql.init.mode=always",
        "screener.elasticsearch.url=http://localhost:9200"
})
@AutoConfigureMockMvc
@Transactional
class ScreenerApplicationTest {

    private static final Instant FIRST_BATCH = Instant.parse("2026-07-24T00:05:00Z");
    private static final Instant LATEST_BATCH = Instant.parse("2026-07-25T00:05:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void seedScores() {
        jdbcClient.sql("DELETE FROM factor_scores").update();
        insert(FIRST_BATCH, "OLD", "0.500000");
        insert(LATEST_BATCH, "MSFT", "0.750000");
        insert(LATEST_BATCH, "AAPL", "1.500000");
        insert(LATEST_BATCH, "NVDA", "1.500000");
    }

    @Test
    void returnsTheLatestCompleteBatchWithStableDenseRanksAndPagination()
            throws Exception {
        mockMvc.perform(get("/screener/rankings")
                        .param("asOf", "2026-07-26T00:00:00Z")
                        .param("page", "0")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scoreTime").value("2026-07-25T00:05:00Z"))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].symbol").value("AAPL"))
                .andExpect(jsonPath("$.content[0].rank").value(1))
                .andExpect(jsonPath("$.content[1].symbol").value("NVDA"))
                .andExpect(jsonPath("$.content[1].rank").value(1));

        mockMvc.perform(get("/screener/rankings")
                        .param("asOf", "2026-07-26T00:00:00Z")
                        .param("page", "1")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].symbol").value("MSFT"))
                .andExpect(jsonPath("$.content[0].rank").value(2));
    }

    @Test
    void honorsAsOfWithoutReadingANewerBatch() throws Exception {
        mockMvc.perform(get("/screener/rankings")
                        .param("asOf", "2026-07-24T12:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scoreTime").value("2026-07-24T00:05:00Z"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].symbol").value("OLD"));
    }

    @Test
    void returnsAnEmptyPageBeforeTheFirstScoreBatch() throws Exception {
        mockMvc.perform(get("/screener/rankings")
                        .param("asOf", "2026-07-23T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scoreTime").doesNotExist())
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.content.length()").value(0));
    }

    @Test
    void validatesPagingSearchTextAndTimestamp() throws Exception {
        mockMvc.perform(get("/screener/rankings").param("size", "201"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/screener/rankings").param("asOf", "not-an-instant"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/screener/search").param("q", "   "))
                .andExpect(status().isBadRequest());
    }

    private void insert(Instant time, String symbol, String composite) {
        var score = new BigDecimal(composite);
        jdbcClient.sql("""
                        INSERT INTO factor_scores (
                            time,
                            symbol,
                            composite_score,
                            z_value,
                            z_momentum,
                            z_quality
                        ) VALUES (
                            :time,
                            :symbol,
                            :composite,
                            :zValue,
                            :zMomentum,
                            :zQuality
                        )
                        """)
                .param("time", time.atOffset(ZoneOffset.UTC))
                .param("symbol", symbol)
                .param("composite", score)
                .param("zValue", score)
                .param("zMomentum", score)
                .param("zQuality", score)
                .update();
    }
}
