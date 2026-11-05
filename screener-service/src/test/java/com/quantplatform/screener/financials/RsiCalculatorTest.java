package com.quantplatform.screener.financials;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.quantplatform.screener.financials.RsiCalculator.SessionClose;

class RsiCalculatorTest {
    // Closes of the widely published StockCharts RSI example; first RSI after 14 changes is 70.46.
    static final double[] CLOSES = {44.34, 44.09, 44.15, 43.61, 44.33, 44.83, 45.10, 45.42, 45.84, 46.08,
            45.89, 46.03, 45.61, 46.28, 46.28, 46.00, 46.03, 46.41, 46.22, 45.64};
    static final LocalDate START = LocalDate.parse("2026-01-01");

    static List<SessionClose> series(double... closes) {
        var result = new ArrayList<SessionClose>();
        for (int i = 0; i < closes.length; i++) {
            result.add(new SessionClose(START.plusDays(i), Double.isNaN(closes[i]) ? null : BigDecimal.valueOf(closes[i])));
        }
        return result;
    }

    static LocalDate last(int count) {
        return START.plusDays(count - 1);
    }

    @Test void seedMatchesThePublishedReferenceAndSmoothingFollowsWilder() {
        var seed = RsiCalculator.compute(series(java.util.Arrays.copyOf(CLOSES, 15)), last(15));
        assertThat(seed.value()).isEqualByComparingTo("70.4641");
        assertThat(seed.sessionsUsed()).isEqualTo(15);
        var full = RsiCalculator.compute(series(CLOSES), last(CLOSES.length));
        // Independent reference: avg = (avg * 13 + x) / 14 for every change after the seed.
        assertThat(full.value()).isEqualByComparingTo("57.9150");
        assertThat(full.firstSession()).isEqualTo(START);
        assertThat(full.lastSession()).isEqualTo(last(CLOSES.length));
        assertThat(full.reason()).isNull();
    }

    @Test void aMissingSessionEndsTheWindowAndShortWindowsAreExplicit() {
        double[] gap = CLOSES.clone();
        gap[3] = Double.NaN;
        var result = RsiCalculator.compute(series(gap), last(gap.length));
        assertThat(result.sessionsUsed()).isEqualTo(16);
        assertThat(result.firstSession()).isEqualTo(START.plusDays(4));
        assertThat(result.value()).isNotNull();
        double[] shortGap = CLOSES.clone();
        shortGap[10] = Double.NaN;
        var tooShort = RsiCalculator.compute(series(shortGap), last(shortGap.length));
        assertThat(tooShort.value()).isNull();
        assertThat(tooShort.reason()).isEqualTo(RsiCalculator.INSUFFICIENT_PRICE_HISTORY);
        assertThat(tooShort.sessionsUsed()).isEqualTo(9);
    }

    @Test void scoreDatePriceIsRequiredAndNeverReplacedByAnEarlierClose() {
        double[] missingLast = CLOSES.clone();
        missingLast[missingLast.length - 1] = Double.NaN;
        assertThat(RsiCalculator.compute(series(missingLast), last(missingLast.length)).reason())
                .isEqualTo(RsiCalculator.MISSING_SCORE_DATE_PRICE);
        assertThat(RsiCalculator.compute(series(CLOSES), last(CLOSES.length).plusDays(1)).reason())
                .isEqualTo(RsiCalculator.MISSING_SCORE_DATE_PRICE);
        assertThat(RsiCalculator.compute(List.of(), START).reason()).isEqualTo(RsiCalculator.MISSING_SCORE_DATE_PRICE);
    }

    @Test void boundaryValuesAreDefined() {
        double[] rising = new double[20];
        double[] flat = new double[20];
        for (int i = 0; i < 20; i++) {
            rising[i] = 10 + i;
            flat[i] = 10;
        }
        assertThat(RsiCalculator.compute(series(rising), last(20)).value()).isEqualByComparingTo("100");
        var unchanged = RsiCalculator.compute(series(flat), last(20));
        assertThat(unchanged.value()).isNull();
        assertThat(unchanged.reason()).isEqualTo(RsiCalculator.FLAT_PRICE_WINDOW);
    }
}
