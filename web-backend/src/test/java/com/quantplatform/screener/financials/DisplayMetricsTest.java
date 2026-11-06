package com.quantplatform.screener.financials;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.quantplatform.screener.financials.InstrumentFinancials.*;
import com.quantplatform.screener.financials.RsiCalculator.SessionClose;

class DisplayMetricsTest {
    static final LocalDate SCORE = LocalDate.parse("2026-10-30");

    static InputValue input(String code, String periodType, double value, String unit) {
        return new InputValue(code, "X", periodType, "REPORTED", BigDecimal.valueOf(value), unit,
                LocalDate.parse("2026-06-30"), null, null, null, null, null, List.of("s"), "AVAILABLE", null);
    }

    static InstrumentFinancials full(List<InputValue> inputs, List<DerivedValue> derived) {
        var run = new Publication(UUID.randomUUID(), SCORE, OffsetDateTime.parse("2026-10-30T20:00Z"),
                OffsetDateTime.parse("2026-10-31T20:00Z"), null, "1.5.0", "v4", "m", "CURRENT_SNAPSHOT_V1", 500);
        var score = new Score(true, "PARTIAL", 10, null, null, null, null, null, null, null, null, null, null,
                List.of(), List.of());
        var rsi = new Rsi(null, 14, "m", "v", "b", null, null, 253, 0, null, null, false, "UNAVAILABLE", "x");
        return new InstrumentFinancials(run, new Instrument(UUID.randomUUID(), null, "T", "GENERAL", "S", "S",
                true, true, null, null), score, inputs, derived, List.of(), new Technical(rsi),
                new Supplemental(EbitdaFromSource.gap(EbitdaFromSource.NOT_REPORTED)));
    }

    @Test void ebitdaAndDepreciationShareThePeriodAndReasonOfTheSourceCalculation() {
        var summary = DisplayMetrics.build(full(List.of(), List.of()), List.of());
        assertThat(summary.financials().get("ebitda").reason()).isEqualTo(EbitdaFromSource.NOT_REPORTED);
        assertThat(summary.financials().get("depreciationAmortization").value()).isNull();
        assertThat(summary.definitionsVersion()).isEqualTo("DISPLAY_METRICS_V2");
    }

    @Test void ratiosUseAcceptedInputsAndNeverDivideByNonPositiveValues() {
        var inputs = List.of(input("NET_INCOME_TTM", "TTM", -50, "USD"), input("REVENUE_TTM", "TTM", 400, "USD"),
                input("OPERATING_INCOME_TTM", "TTM", 100, "USD"),
                new InputValue("COMMON_EQUITY", "X", "BALANCE_SHEET", "REPORTED", BigDecimal.TEN, "USD",
                        LocalDate.parse("2026-06-30"), null, null, null, null, null, List.of("s"), "NOT_USED_BY_MODEL", "STALE"));
        var derived = List.of(new DerivedValue("market_cap", BigDecimal.valueOf(2000), "USD", null, "AVAILABLE", null));
        var summary = DisplayMetrics.build(full(inputs, derived), List.of());
        assertThat(summary.valuation().get("priceToEarnings").reason()).isEqualTo("NEGATIVE_EARNINGS");
        assertThat(summary.valuation().get("priceToSales").value()).isEqualByComparingTo("5.00");
        assertThat(summary.valuation().get("priceToBook").reason()).isEqualTo("COMMON_EQUITY:STALE");
        assertThat(summary.profitability().get("operatingMargin").value()).isEqualByComparingTo("25.00");
        assertThat(summary.profitability().get("netMargin").value()).isEqualByComparingTo("-12.50");
        assertThat(summary.market().get("high52w").reason()).isEqualTo(RsiCalculator.MISSING_SCORE_DATE_PRICE);
    }

    @Test void yearRangeAndVolatilityNeedAFullContiguousYear() {
        var closes = new ArrayList<SessionClose>();
        for (int i = 0; i < 253; i++) {
            closes.add(new SessionClose(SCORE.minusDays(252 - i), BigDecimal.valueOf(100 * Math.pow(1.001, i))));
        }
        var market = DisplayMetrics.build(full(List.of(), List.of()), closes).market();
        assertThat(market.get("low52w").value()).isEqualByComparingTo("100");
        assertThat(market.get("fromHigh52w").value()).isEqualByComparingTo("0.00");
        assertThat(market.get("volatility1y").value()).isEqualByComparingTo("0.00"); // constant log return
        closes.set(5, new SessionClose(closes.get(5).session(), null));
        assertThat(DisplayMetrics.build(full(List.of(), List.of()), closes).market().get("high52w").reason())
                .isEqualTo(RsiCalculator.INSUFFICIENT_PRICE_HISTORY);
    }
}
