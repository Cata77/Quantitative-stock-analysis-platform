package com.quantplatform.screener.financials;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Informational D&amp;A and EBITDA, version {@value #VERSION}. Never part of a score.
 *
 * <p>The periods are those of the publication's {@code OPERATING_INCOME_TTM}: its source facts
 * are a single annual value, four quarters, or annual + current YTD − prior-year YTD. The signs
 * are reconstructed from the periods and accepted only if they reproduce the published value
 * exactly. D&amp;A is then read from the same retained SEC companyfacts response (the original
 * artifact behind each fact), for the same filing (accession) and the same start/end dates, using
 * one standard entity-wide concept for every component. Nothing is filled with zero, no other
 * filing or period is substituted, and EBITDA = OPERATING_INCOME_TTM + D&amp;A over identical periods.
 */
final class EbitdaFromSource {
    static final String VERSION = "EBITDA_FROM_SOURCE_V1";
    static final String METHOD = "OPERATING_INCOME_TTM + D&A of the same filings and periods";
    /** Standard us-gaap concepts in priority order; accretion or PP&amp;E-only variants are excluded. */
    static final List<String> CONCEPTS = List.of("DepreciationDepletionAndAmortization", "DepreciationAndAmortization");

    static final String MISSING_OPERATING_INCOME = "MISSING_INPUT:OPERATING_INCOME_TTM";
    static final String FACTS_NOT_FOUND = "OPERATING_INCOME_FACTS_NOT_FOUND";
    static final String PERIODS_UNRESOLVED = "OPERATING_INCOME_PERIODS_UNRESOLVED";
    static final String ARTIFACT_NOT_RETAINED = "SOURCE_ARTIFACT_NOT_RETAINED";
    static final String NOT_REPORTED = "DEPRECIATION_AMORTIZATION_NOT_REPORTED";
    static final String AMBIGUOUS = "DEPRECIATION_AMORTIZATION_AMBIGUOUS";
    static final String NEGATIVE = "NEGATIVE_DEPRECIATION_AMORTIZATION";

    private EbitdaFromSource() {}

    /**
     * One source fact of OPERATING_INCOME_TTM and the D&amp;A values found for the same filing and
     * period, by concept (distinct values; more than one means the source is ambiguous).
     * {@code sourceArtifactId} is null when no retained response at or before the cutoff exists.
     */
    record Component(UUID factId, LocalDate start, LocalDate end, BigDecimal operatingIncome, String accession,
            UUID sourceArtifactId, Map<String, List<BigDecimal>> depreciation) {}

    static InstrumentFinancials.DepreciationAmortization compute(BigDecimal operatingIncomeTtm, String operatingIncomeReason,
            List<String> sourceIds, List<Component> components) {
        if (operatingIncomeTtm == null) {
            return gap(operatingIncomeReason == null ? MISSING_OPERATING_INCOME
                    : "OPERATING_INCOME_TTM:" + operatingIncomeReason);
        }
        if (sourceIds.isEmpty() || components.size() != sourceIds.size()) return gap(FACTS_NOT_FOUND);
        int[] signs = signs(components);
        if (signs == null) return gap(PERIODS_UNRESOLVED);
        BigDecimal check = BigDecimal.ZERO;
        for (int i = 0; i < components.size(); i++) {
            check = check.add(components.get(i).operatingIncome().multiply(BigDecimal.valueOf(signs[i])));
        }
        if (check.compareTo(operatingIncomeTtm) != 0) return gap(PERIODS_UNRESOLVED);
        if (components.stream().anyMatch(c -> c.sourceArtifactId() == null)) return gap(ARTIFACT_NOT_RETAINED);

        boolean ambiguous = false;
        for (String concept : CONCEPTS) {
            BigDecimal total = BigDecimal.ZERO;
            var parts = new ArrayList<InstrumentFinancials.DepreciationComponent>();
            boolean complete = true;
            for (int i = 0; i < components.size(); i++) {
                var c = components.get(i);
                var values = c.depreciation().getOrDefault(concept, List.of());
                if (values.size() > 1) ambiguous = true;
                if (values.size() != 1) {
                    complete = false;
                    break;
                }
                total = total.add(values.getFirst().multiply(BigDecimal.valueOf(signs[i])));
                parts.add(new InstrumentFinancials.DepreciationComponent(c.start(), c.end(), c.accession(), signs[i],
                        values.getFirst(), c.operatingIncome(), c.sourceArtifactId()));
            }
            if (!complete) continue;
            if (total.signum() < 0) return gap(NEGATIVE);
            LocalDate end = components.stream().map(Component::end).max(LocalDate::compareTo).orElseThrow();
            return new InstrumentFinancials.DepreciationAmortization(total, operatingIncomeTtm.add(total), end,
                    "us-gaap:" + concept, VERSION, METHOD, List.copyOf(parts), false, "AVAILABLE", null);
        }
        return gap(ambiguous ? AMBIGUOUS : NOT_REPORTED);
    }

    /** Signs of the TTM combination used by the model, reconstructed only from the periods. */
    static int[] signs(List<Component> components) {
        int n = components.size();
        if (n == 1) return annual(components.getFirst()) ? new int[] {1} : null;
        if (n == 4) {
            if (components.stream().anyMatch(c -> !between(days(c), 70, 110))) return null;
            return new int[] {1, 1, 1, 1};
        }
        if (n != 3) return null;
        LocalDate end = components.stream().map(Component::end).max(LocalDate::compareTo).orElseThrow();
        int[] signs = new int[3];
        int years = 0, current = 0, prior = 0;
        for (int i = 0; i < 3; i++) {
            var c = components.get(i);
            if (c.end().equals(end) && !annual(c)) {
                signs[i] = 1;
                current++;
            } else if (annual(c)) {
                signs[i] = 1;
                years++;
            } else {
                signs[i] = -1;
                prior++;
            }
        }
        return years == 1 && current == 1 && prior == 1 ? signs : null;
    }

    private static boolean annual(Component c) {
        return c.start() != null && between(days(c), 350, 380);
    }

    private static long days(Component c) {
        return c.start() == null ? -1 : ChronoUnit.DAYS.between(c.start(), c.end()) + 1;
    }

    private static boolean between(long value, long low, long high) {
        return value >= low && value <= high;
    }

    static InstrumentFinancials.DepreciationAmortization gap(String reason) {
        return new InstrumentFinancials.DepreciationAmortization(null, null, null, null, VERSION, METHOD, List.of(),
                false, "UNAVAILABLE", reason);
    }
}
