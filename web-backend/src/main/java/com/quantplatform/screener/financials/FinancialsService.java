package com.quantplatform.screener.financials;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.quantplatform.screener.financials.InstrumentFinancials.*;

/**
 * Assembles the financial view of one instrument from what the publication stored:
 * prepared inputs and provenance (score_lineage), the model's derivations and reasons
 * (stock_scores.output), normalized metrics (factor_observations) and exclusions.
 * Calculated here, informational only: the RSI from the publication's own adjusted price
 * dataset, and D&amp;A/EBITDA from the retained SEC responses behind its operating income.
 */
@Service
public class FinancialsService {
    static final int RSI_SESSIONS = 253;
    private final FinancialsRepository repository;

    public FinancialsService(FinancialsRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public InstrumentFinancials find(UUID runId, UUID instrumentId) {
        return load(runId, instrumentId).full();
    }

    /** Compact user-facing view built only from the same publication data as {@link #find}. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public FinancialSummary summary(UUID runId, UUID instrumentId) {
        var loaded = load(runId, instrumentId);
        return DisplayMetrics.build(loaded.full(), loaded.closes());
    }

    private record Loaded(InstrumentFinancials full, List<RsiCalculator.SessionClose> closes) {}

    private Loaded load(UUID runId, UUID instrumentId) {
        var run = repository.publication(runId)
                .orElseThrow(() -> new FinancialsNotFoundException("Publication not found"));
        var row = repository.instrument(runId, instrumentId)
                .orElseThrow(() -> new FinancialsNotFoundException("Instrument is not part of the publication"));
        var publication = new Publication(runId, date(run.get("asOfDate")), time(run.get("marketCutoff")),
                time(run.get("knowledgeCutoff")), time(run.get("publishedAt")), str(run.get("modelVersion")),
                str(run.get("preprocessingVersion")), str(run.get("mappingVersion")), str(run.get("timingPolicy")),
                integer(run.get("scoredCount")));
        var output = map(row.get("output"));
        var canonical = map(row.get("canonical"));
        var prepared = map(row.get("prepared"));
        String profile = str(row.get("profile"));
        String currency = str(row.get("currency"));
        var reasons = map(output.get("input_reasons"));
        var instrument = new Instrument(instrumentId, uuid(row.get("issuerId")), str(row.get("symbol")), profile,
                str(canonical.get("sector")), str(row.get("peerGroup")), bool(canonical.get("inSp500")),
                bool(canonical.get("inNasdaq100")), date(prepared.get("period_end")), date(prepared.get("filing_date")));
        var score = new Score(Boolean.TRUE.equals(row.get("eligible")), str(output.get("score_status")),
                integer(row.get("rank")), dec(row.get("percentile")), dec(row.get("compositeScore")),
                dec(row.get("valueScore")), dec(row.get("qualityScore")), dec(row.get("momentumScore")),
                dec(row.get("valueContribution")), dec(row.get("qualityContribution")),
                dec(row.get("momentumContribution")), output.get("coverage"), output.get("effective_weights"),
                strings(row.get("warnings")), exclusions(list(row.get("exclusions"))));
        var contract = map(run.get("formulaContract"));
        var periods = map(run.get("periodRules"));
        var metrics = metrics(contract, periods, profile, list(row.get("factors")));
        var metricCodes = metrics.stream().map(MetricValue::metric).toList();
        var closes = closes(run, publication, instrumentId);
        var inputs = inputs(prepared, reasons);
        var full = new InstrumentFinancials(publication, instrument, score, inputs,
                derived(contract, periods, profile, currency, map(output.get("derived")), reasons, metricCodes),
                metrics, new Technical(rsi(run, publication, closes)),
                new Supplemental(depreciation(instrument.issuerId(), publication, inputs, prepared)));
        return new Loaded(full, closes == null ? List.of() : closes);
    }

    /** D&amp;A and EBITDA over the exact periods of the publication's accepted OPERATING_INCOME_TTM. */
    private DepreciationAmortization depreciation(UUID issuerId, Publication publication, List<InputValue> inputs,
            Map<String, Object> prepared) {
        var operatingIncome = inputs.stream().filter(i -> i.code().equals("OPERATING_INCOME_TTM")).findFirst();
        if (operatingIncome.isEmpty() || !"AVAILABLE".equals(operatingIncome.get().status())) {
            return EbitdaFromSource.compute(null, operatingIncome.map(InputValue::reason).orElse(null), List.of(), List.of());
        }
        var sources = strings(map(map(prepared.get("provenance")).get("OPERATING_INCOME_TTM")).get("source_ids"));
        var components = issuerId == null ? List.<EbitdaFromSource.Component>of()
                : repository.depreciationSources(issuerId, sources, publication.knowledgeCutoff());
        return EbitdaFromSource.compute(operatingIncome.get().value(), null, sources, components);
    }

    /** Adjusted closes of the publication's dataset, or null when the run did not record one. */
    private List<RsiCalculator.SessionClose> closes(Map<String, Object> run, Publication publication, UUID instrumentId) {
        UUID dataset = uuid(run.get("adjustedDataset"));
        LocalDate basis = date(run.get("adjustmentBasis"));
        if (dataset == null || basis == null) return null;
        return repository.adjustedCloses(instrumentId, publication.asOfDate(), publication.marketCutoff(),
                publication.knowledgeCutoff(), dataset, basis, RSI_SESSIONS);
    }

    private static List<InputValue> inputs(Map<String, Object> prepared, Map<String, Object> modelReasons) {
        var values = map(prepared.get("values"));
        var provenance = map(prepared.get("provenance"));
        var diagnostics = map(prepared.get("preparation_diagnostics"));
        var codes = new TreeSet<String>(values.keySet());
        codes.addAll(diagnostics.keySet());
        modelReasons.keySet().stream().filter(FinancialsService::upper).forEach(codes::add);
        var result = new ArrayList<InputValue>();
        for (String code : codes) {
            var evidence = map(provenance.get(code));
            BigDecimal value = dec(values.get(code));
            var sources = strings(evidence.get("source_ids"));
            String derivation = str(evidence.get("derivation"));
            String kind = FinancialCatalog.market(code) ? "MARKET"
                    : derivation != null ? "DERIVED_FROM_REPORTED"
                    : code.endsWith("_TTM") && sources.size() > 1 ? "DERIVED_TTM" : "REPORTED";
            String reason = str(modelReasons.get(code));
            if (reason == null) reason = str(diagnostics.get(code));
            if (reason == null && value == null) reason = "NOT_REPORTED";
            String status = value == null ? "UNAVAILABLE" : reason == null ? "AVAILABLE" : "NOT_USED_BY_MODEL";
            result.add(new InputValue(code, FinancialCatalog.category(code), FinancialCatalog.periodType(code), kind,
                    value, str(evidence.get("unit")), date(evidence.get("period_end")), date(evidence.get("filed_date")),
                    time(evidence.get("available_at")), time(evidence.get("observed_at")),
                    str(evidence.get("availability_basis")), derivation, sources, status, reason));
        }
        return result;
    }

    private static List<DerivedValue> derived(Map<String, Object> contract, Map<String, Object> periods,
            String profile, String currency, Map<String, Object> derived, Map<String, Object> reasons,
            Collection<String> metricCodes) {
        var result = new ArrayList<DerivedValue>();
        for (String code : new TreeSet<>(derived.keySet())) {
            if (upper(code) || metricCodes.contains(code)) continue; // inputs and metrics have own sections
            BigDecimal value = dec(derived.get(code));
            String reason = value == null ? str(reasons.get(code)) : null;
            if (value == null && reason == null) reason = missingDependency(code, derived);
            result.add(new DerivedValue(code, value, FinancialCatalog.derivedUnit(code, currency),
                    FinancialCatalog.formula(contract, periods, profile, code), value == null ? "UNAVAILABLE" : "AVAILABLE", reason));
        }
        return result;
    }

    /** Inputs of model derivations that the model stores without a reason of their own. */
    private static final Map<String, List<String>> DEPENDENCIES = Map.of(
            "nopat", List.of("ebit", "normalized_tax_rate"),
            "fcf", List.of("OPERATING_CASH_FLOW_TTM", "CAPEX_TTM"),
            "gross_profit", List.of("REVENUE_TTM", "COST_OF_REVENUE_TTM"),
            "cash_earnings_top", List.of("OPERATING_CASH_FLOW_TTM", "NET_INCOME_TTM"),
            "market_cap", List.of("RAW_CLOSE", "SHARES_OUTSTANDING"),
            "debt", List.of("DEBT_CURRENT", "DEBT_NONCURRENT"),
            "normalized_tax_rate", List.of("ANNUAL_TAX_1", "ANNUAL_PRETAX_1"));

    private static String missingDependency(String code, Map<String, Object> derived) {
        for (String input : DEPENDENCIES.getOrDefault(code, List.of())) {
            if (derived.get(input) == null) return "MISSING_INPUT:" + input;
        }
        return "NOT_DERIVED_BY_MODEL";
    }

    private static List<MetricValue> metrics(Map<String, Object> contract, Map<String, Object> periods,
            String profile, List<Object> factors) {
        return factors.stream().map(FinancialsService::map).map(f -> new MetricValue(str(f.get("metric")),
                str(f.get("family")), "RATIO", FinancialCatalog.formula(contract, periods, profile, str(f.get("metric"))),
                dec(f.get("raw_value")), str(f.get("reason")), dec(f.get("transformed_value")),
                dec(f.get("winsorized_value")), str(f.get("cohort")), integer(f.get("cohort_count")),
                dec(f.get("lower_bound")), dec(f.get("upper_bound")), dec(f.get("cohort_mean")),
                dec(f.get("cohort_deviation")), dec(f.get("z_score")), dec(f.get("clipped_score")),
                dec(f.get("weight")), dec(f.get("contribution")))).toList();
    }

    private static List<Exclusion> exclusions(List<Object> rows) {
        return rows.stream().map(FinancialsService::map).map(e -> new Exclusion(str(e.get("stage")),
                str(e.get("reason_code")), emptyToNull(str(e.get("affected_metric"))), str(e.get("detail")))).toList();
    }

    private Rsi rsi(Map<String, Object> run, Publication publication, List<RsiCalculator.SessionClose> closes) {
        UUID dataset = uuid(run.get("adjustedDataset"));
        LocalDate basis = date(run.get("adjustmentBasis"));
        if (closes == null) {
            return rsi(null, dataset, basis, 0, null, null, "UNAVAILABLE", "PRICE_DATASET_NOT_RECORDED");
        }
        var result = RsiCalculator.compute(closes, publication.asOfDate());
        return rsi(result.value(), dataset, basis, result.sessionsUsed(), result.firstSession(), result.lastSession(),
                result.value() == null ? "UNAVAILABLE" : "AVAILABLE", result.reason());
    }

    private static Rsi rsi(BigDecimal value, UUID dataset, LocalDate basis, int used, LocalDate first, LocalDate last,
            String status, String reason) {
        return new Rsi(value, RsiCalculator.PERIOD, RsiCalculator.METHOD, RsiCalculator.VERSION,
                "SPLIT_DIVIDEND_ADJUSTED_CLOSE", dataset, basis, RSI_SESSIONS, used, first, last, false, status, reason);
    }

    private static boolean upper(String code) {
        return code.equals(code.toUpperCase());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return value instanceof List<?> l ? (List<Object>) l : List.of();
    }

    private static List<String> strings(Object value) {
        return list(value).stream().map(String::valueOf).toList();
    }

    private static String str(Object value) {
        return value == null ? null : value.toString();
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private static BigDecimal dec(Object value) {
        return value instanceof Number n ? new BigDecimal(n.toString()) : null;
    }

    private static Integer integer(Object value) {
        return value instanceof Number n ? n.intValue() : null;
    }

    private static Boolean bool(Object value) {
        return value instanceof Boolean b ? b : null;
    }

    private static UUID uuid(Object value) {
        return value == null ? null : UUID.fromString(value.toString());
    }

    private static LocalDate date(Object value) {
        return value == null ? null : LocalDate.parse(value.toString().substring(0, 10));
    }

    private static OffsetDateTime time(Object value) {
        if (value == null) return null;
        String text = value.toString();
        // PostgreSQL jsonb renders "+00:00"; provenance may use "Z" or a space separator.
        return OffsetDateTime.parse(text.replace(' ', 'T'));
    }
}
