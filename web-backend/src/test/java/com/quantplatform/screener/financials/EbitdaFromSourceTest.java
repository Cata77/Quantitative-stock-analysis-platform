package com.quantplatform.screener.financials;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.quantplatform.screener.financials.EbitdaFromSource.Component;

class EbitdaFromSourceTest {
    static final String DDA = "DepreciationDepletionAndAmortization";
    static final String DA = "DepreciationAndAmortization";

    static Component part(String start, String end, long income, Map<String, List<BigDecimal>> da) {
        return new Component(UUID.randomUUID(), LocalDate.parse(start), LocalDate.parse(end),
                BigDecimal.valueOf(income), "acc-" + end, UUID.randomUUID(), da);
    }

    static Map<String, List<BigDecimal>> da(String concept, long... values) {
        var list = new ArrayList<BigDecimal>();
        for (long v : values) list.add(BigDecimal.valueOf(v));
        return Map.of(concept, list);
    }

    static List<String> ids(List<Component> parts) {
        return parts.stream().map(p -> p.factId().toString()).toList();
    }

    static List<Component> yearToDate(Map<String, List<BigDecimal>> annual, Map<String, List<BigDecimal>> current,
            Map<String, List<BigDecimal>> prior) {
        return List.of(part("2025-01-01", "2025-12-31", 250, annual), part("2026-01-01", "2026-06-30", 120, current),
                part("2025-01-01", "2025-06-30", 70, prior));
    }

    @Test void annualPlusYearToDateMinusPriorYearToDateOverTheSamePeriods() {
        var parts = yearToDate(da(DDA, 40), da(DDA, 22), da(DDA, 18));
        var result = EbitdaFromSource.compute(BigDecimal.valueOf(300), null, ids(parts), parts);
        assertThat(result.value()).isEqualByComparingTo("44");
        assertThat(result.ebitda()).isEqualByComparingTo("344");
        assertThat(result.periodEnd()).isEqualTo(LocalDate.parse("2026-06-30"));
        assertThat(result.concept()).isEqualTo("us-gaap:" + DDA);
        assertThat(result.components()).extracting(InstrumentFinancials.DepreciationComponent::sign).containsExactly(1, 1, -1);
        assertThat(result.partOfScore()).isFalse();
    }

    @Test void singleAnnualAndFourQuarterCombinations() {
        var annual = List.of(part("2025-07-01", "2026-06-30", 500, da(DA, 60)));
        assertThat(EbitdaFromSource.compute(BigDecimal.valueOf(500), null, ids(annual), annual).ebitda())
                .isEqualByComparingTo("560");
        var quarters = List.of(part("2025-07-01", "2025-09-30", 100, da(DDA, 10)),
                part("2025-10-01", "2025-12-31", 100, da(DDA, 10)), part("2026-01-01", "2026-03-31", 100, da(DDA, 10)),
                part("2026-04-01", "2026-06-30", 100, da(DDA, 11)));
        assertThat(EbitdaFromSource.compute(BigDecimal.valueOf(400), null, ids(quarters), quarters).value())
                .isEqualByComparingTo("41");
    }

    @Test void oneConceptMustCoverEveryComponentAndHigherPriorityWins() {
        var mixed = yearToDate(Map.of(DDA, List.of(BigDecimal.valueOf(40)), DA, List.of(BigDecimal.valueOf(39))),
                da(DA, 21), da(DA, 17));
        var result = EbitdaFromSource.compute(BigDecimal.valueOf(300), null, ids(mixed), mixed);
        assertThat(result.concept()).isEqualTo("us-gaap:" + DA);
        assertThat(result.value()).isEqualByComparingTo("43");
        var partial = yearToDate(da(DDA, 40), da(DDA, 22), Map.of());
        assertThat(EbitdaFromSource.compute(BigDecimal.valueOf(300), null, ids(partial), partial).reason())
                .isEqualTo(EbitdaFromSource.NOT_REPORTED);
    }

    @Test void gapsAreExplicitAndNeverZero() {
        var parts = yearToDate(da(DDA, 40), da(DDA, 22, 23), da(DDA, 18));
        assertThat(EbitdaFromSource.compute(BigDecimal.valueOf(300), null, ids(parts), parts).reason())
                .isEqualTo(EbitdaFromSource.AMBIGUOUS);
        var good = yearToDate(da(DDA, 40), da(DDA, 22), da(DDA, 18));
        // The reconstructed combination must reproduce the published value exactly.
        assertThat(EbitdaFromSource.compute(BigDecimal.valueOf(301), null, ids(good), good).reason())
                .isEqualTo(EbitdaFromSource.PERIODS_UNRESOLVED);
        assertThat(EbitdaFromSource.compute(BigDecimal.valueOf(300), null, List.of("a", "b", "c", "d"), good).reason())
                .isEqualTo(EbitdaFromSource.FACTS_NOT_FOUND);
        var noArtifact = new ArrayList<>(good);
        var first = good.getFirst();
        noArtifact.set(0, new Component(first.factId(), first.start(), first.end(), first.operatingIncome(),
                first.accession(), null, first.depreciation()));
        assertThat(EbitdaFromSource.compute(BigDecimal.valueOf(300), null, ids(noArtifact), noArtifact).reason())
                .isEqualTo(EbitdaFromSource.ARTIFACT_NOT_RETAINED);
        var negative = yearToDate(da(DDA, 10), da(DDA, 1), da(DDA, 18));
        assertThat(EbitdaFromSource.compute(BigDecimal.valueOf(300), null, ids(negative), negative).reason())
                .isEqualTo(EbitdaFromSource.NEGATIVE);
        assertThat(EbitdaFromSource.compute(null, null, List.of(), List.of()).reason())
                .isEqualTo(EbitdaFromSource.MISSING_OPERATING_INCOME);
        assertThat(EbitdaFromSource.compute(null, "NOT_REPORTED", List.of(), List.of()).reason())
                .isEqualTo("OPERATING_INCOME_TTM:NOT_REPORTED");
        var twoParts = List.of(good.get(0), good.get(1));
        assertThat(EbitdaFromSource.compute(BigDecimal.valueOf(370), null, ids(twoParts), twoParts).reason())
                .isEqualTo(EbitdaFromSource.PERIODS_UNRESOLVED);
    }
}
