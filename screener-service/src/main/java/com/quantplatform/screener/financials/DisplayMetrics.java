package com.quantplatform.screener.financials;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.quantplatform.screener.financials.FinancialSummary.Figure;
import com.quantplatform.screener.financials.InstrumentFinancials.DerivedValue;
import com.quantplatform.screener.financials.InstrumentFinancials.InputValue;
import com.quantplatform.screener.financials.InstrumentFinancials.MetricValue;
import com.quantplatform.screener.financials.RsiCalculator.SessionClose;

/**
 * Display indicators, version {@value #VERSION}: common ratios for people (P/E, margins, ...).
 * They are not model metrics and never change a score. Inputs are only the values the
 * publication stored and the model accepted (status AVAILABLE), plus the model's own
 * derivations (market_cap, ev, debt, fcf, avg_assets). Nothing is filled with zero.
 * V2 adds D&amp;A and EBITDA ({@link EbitdaFromSource}); V1 always reported EBITDA as not collected.
 */
final class DisplayMetrics {
    static final String VERSION = "DISPLAY_METRICS_V2";
    static final int YEAR_SESSIONS = 252;

    private DisplayMetrics() {}

    static FinancialSummary build(InstrumentFinancials full, List<SessionClose> closes) {
        var in = new Inputs(full.inputs(), full.derived());
        var run = full.run();
        var instrument = full.instrument();

        var financials = new LinkedHashMap<String, Figure>();
        financials.put("revenue", in.money("REVENUE_TTM"));
        financials.put("grossProfit", in.money("GROSS_PROFIT_TTM", "gross_profit"));
        financials.put("operatingIncome", in.money("OPERATING_INCOME_TTM"));
        var da = full.supplemental().depreciationAmortization();
        String daPeriod = da.periodEnd() == null ? null : "TTM to " + da.periodEnd();
        financials.put("depreciationAmortization", new Figure(da.value(), "USD", daPeriod, da.reason()));
        financials.put("ebitda", new Figure(da.ebitda(), "USD", daPeriod, da.reason()));
        financials.put("netIncome", in.money("NET_INCOME_TTM"));
        financials.put("operatingCashFlow", in.money("OPERATING_CASH_FLOW_TTM"));
        financials.put("capex", in.money("CAPEX_TTM"));
        financials.put("freeCashFlow", in.derivedMoney("fcf", in.period("OPERATING_CASH_FLOW_TTM")));
        financials.put("cash", in.money("CASH"));
        financials.put("totalDebt", in.derivedMoney("debt", in.period("DEBT_CURRENT")));
        financials.put("netDebt", in.difference("debt", "CASH"));
        financials.put("totalAssets", in.money("TOTAL_ASSETS"));
        financials.put("sharesOutstanding", in.plain("SHARES_OUTSTANDING", "shares"));

        var valuation = new LinkedHashMap<String, Figure>();
        valuation.put("marketCap", in.derivedMoney("market_cap", run.asOfDate().toString()));
        valuation.put("enterpriseValue", in.derivedMoney("ev", run.asOfDate().toString()));
        valuation.put("priceToEarnings", in.multiple("market_cap", "NET_INCOME_TTM", "NEGATIVE_EARNINGS"));
        valuation.put("priceToSales", in.multiple("market_cap", "REVENUE_TTM", "NON_POSITIVE_REVENUE"));
        valuation.put("priceToBook", in.multiple("market_cap", "COMMON_EQUITY", "NON_POSITIVE_EQUITY"));
        valuation.put("priceToFreeCashFlow", in.multiple("market_cap", "fcf", "NEGATIVE_FREE_CASH_FLOW"));
        valuation.put("evToEbit", in.multiple("ev", "ebit", "NON_POSITIVE_EBIT"));
        valuation.put("freeCashFlowYield", in.percent("fcf", "market_cap"));
        valuation.put("dividendYield", in.percent("COMMON_DIVIDENDS_TTM", "market_cap"));

        var profitability = new LinkedHashMap<String, Figure>();
        profitability.put("grossMargin", in.percent("gross_profit", "REVENUE_TTM"));
        profitability.put("operatingMargin", in.percent("OPERATING_INCOME_TTM", "REVENUE_TTM"));
        profitability.put("netMargin", in.percent("NET_INCOME_TTM", "REVENUE_TTM"));
        profitability.put("returnOnAssets", in.percent("NET_INCOME_TTM", "avg_assets"));
        profitability.put("returnOnEquity", in.percent("NET_INCOME_TTM", "COMMON_EQUITY"));

        var market = new LinkedHashMap<String, Figure>();
        String session = run.asOfDate().toString();
        market.put("price", in.plain("RAW_CLOSE", "USD"));
        var rsi = full.technical().rsi();
        market.put("rsi14", new Figure(rsi.value(), "INDEX", rsi.value() == null ? null : session, rsi.reason()));
        market.put("return1y", in.change("ADJUSTED_CLOSE", "MOMENTUM_OLD", session));
        market.put("momentum12m1m", modelMetric(full.metrics(), "momentum", session));
        market.putAll(priceRange(closes, run.asOfDate().toString()));
        market.put("medianDollarVolume20d", in.plain("MEDIAN_DOLLAR_VOLUME", "USD"));

        var score = full.score();
        var gaps = new LinkedHashMap<String, String>();
        for (MetricValue metric : full.metrics()) {
            if (metric.rawValue() == null) gaps.put(metric.metric(), metric.reason());
        }
        var scoreSummary = new FinancialSummary.ScoreSummary(score.eligible(), score.scoreStatus(), score.rank(),
                run.scoredCount(), score.percentile(), score.compositeScore(), score.valueScore(),
                score.qualityScore(), score.momentumScore(),
                score.exclusions().stream().filter(e -> !"METRIC".equals(e.stage()))
                        .map(InstrumentFinancials.Exclusion::reasonCode).distinct().toList(),
                gaps);
        var company = new FinancialSummary.Company(instrument.instrumentId(), instrument.symbol(),
                instrument.sector(), instrument.profile(), run.id(), run.asOfDate(), instrument.financialPeriodEnd(),
                instrument.filingDate());
        return new FinancialSummary(company, valuation, profitability, financials, market, scoreSummary,
                VERSION, "Display indicators are informational; only the score section comes from the model ranking.");
    }

    private static Figure modelMetric(List<MetricValue> metrics, String code, String session) {
        return metrics.stream().filter(m -> m.metric().equals(code)).findFirst()
                .map(m -> m.rawValue() == null ? new Figure(null, "PERCENT", null, m.reason())
                        : new Figure(percent(m.rawValue()), "PERCENT", session, null))
                .orElse(new Figure(null, "PERCENT", null, "NOT_IN_PROFILE"));
    }

    /** 52-week high/low, distance from the high and annualized volatility over 252 contiguous sessions. */
    private static Map<String, Figure> priceRange(List<SessionClose> ascending, String session) {
        var window = new ArrayList<BigDecimal>();
        for (int i = ascending.size() - 1; i >= 0 && ascending.get(i).close() != null; i--) {
            window.addFirst(ascending.get(i).close());
        }
        var result = new LinkedHashMap<String, Figure>();
        boolean scoreDate = !ascending.isEmpty() && ascending.getLast().close() != null
                && ascending.getLast().session().toString().equals(session);
        if (!scoreDate || window.size() < YEAR_SESSIONS + 1) {
            String reason = scoreDate ? RsiCalculator.INSUFFICIENT_PRICE_HISTORY : RsiCalculator.MISSING_SCORE_DATE_PRICE;
            for (String key : List.of("high52w", "low52w", "fromHigh52w", "volatility1y")) {
                result.put(key, new Figure(null, key.equals("high52w") || key.equals("low52w") ? "USD" : "PERCENT",
                        null, reason));
            }
            return result;
        }
        var year = window.subList(window.size() - YEAR_SESSIONS - 1, window.size());
        BigDecimal high = year.stream().max(BigDecimal::compareTo).orElseThrow();
        BigDecimal low = year.stream().min(BigDecimal::compareTo).orElseThrow();
        BigDecimal last = year.getLast();
        double[] returns = new double[year.size() - 1];
        for (int i = 1; i < year.size(); i++) {
            returns[i - 1] = Math.log(year.get(i).doubleValue() / year.get(i - 1).doubleValue());
        }
        double mean = java.util.Arrays.stream(returns).average().orElse(0);
        double squares = java.util.Arrays.stream(returns).map(r -> (r - mean) * (r - mean)).sum();
        double variance = Math.max(0, squares / (returns.length - 1)); // sample variance of daily log returns
        String period = "252 sessions to " + session;
        result.put("high52w", new Figure(high, "USD", period, null));
        result.put("low52w", new Figure(low, "USD", period, null));
        result.put("fromHigh52w", new Figure(percent(last.doubleValue() / high.doubleValue() - 1), "PERCENT", period, null));
        result.put("volatility1y", new Figure(percent(Math.sqrt(variance * YEAR_SESSIONS)), "PERCENT", period, null));
        return result;
    }

    static BigDecimal percent(double fraction) {
        return BigDecimal.valueOf(fraction * 100).setScale(2, RoundingMode.HALF_EVEN);
    }

    static BigDecimal percent(BigDecimal fraction) {
        return fraction.movePointRight(2).setScale(2, RoundingMode.HALF_EVEN);
    }

    /** Accepted inputs (by code) and model derivations (lowercase codes) of one publication row. */
    private static final class Inputs {
        private final Map<String, InputValue> inputs = new LinkedHashMap<>();
        private final Map<String, DerivedValue> derived = new LinkedHashMap<>();

        Inputs(List<InputValue> inputs, List<DerivedValue> derived) {
            inputs.forEach(i -> this.inputs.put(i.code(), i));
            derived.forEach(d -> this.derived.put(d.code(), d));
        }

        /** Value usable for display: an input the model accepted, or a model derivation. */
        BigDecimal value(String code) {
            var input = inputs.get(code);
            if (input != null) return "AVAILABLE".equals(input.status()) ? input.value() : null;
            var d = derived.get(code);
            return d == null ? null : d.value();
        }

        String reason(String code) {
            var input = inputs.get(code);
            if (input != null) return input.reason() == null ? null : code + ":" + input.reason();
            var d = derived.get(code);
            if (d == null) return "MISSING_INPUT:" + code;
            return d.reason() == null ? null : code + ":" + d.reason();
        }

        String period(String code) {
            var input = inputs.get(code);
            if (input == null || input.periodEnd() == null) return null;
            return switch (input.periodType()) {
                case "TTM" -> "TTM to " + input.periodEnd();
                case "BALANCE_SHEET", "INSTANT" -> "as of " + input.periodEnd();
                default -> input.periodEnd().toString();
            };
        }

        Figure money(String code) {
            BigDecimal v = value(code);
            return new Figure(v, "USD", v == null ? null : period(code), v == null ? reason(code) : null);
        }

        Figure money(String code, String fallbackDerived) {
            return value(code) != null ? money(code) : derivedMoney(fallbackDerived, period("REVENUE_TTM"));
        }

        Figure plain(String code, String unit) {
            BigDecimal v = value(code);
            return new Figure(v, unit, v == null ? null : period(code), v == null ? reason(code) : null);
        }

        Figure derivedMoney(String code, String period) {
            BigDecimal v = value(code);
            return new Figure(v, "USD", v == null ? null : period, v == null ? reason(code) : null);
        }

        Figure difference(String left, String right) {
            BigDecimal a = value(left);
            BigDecimal b = value(right);
            if (a == null || b == null) return new Figure(null, "USD", null, firstReason(left, right));
            return new Figure(a.subtract(b), "USD", period(right), null);
        }

        /** numerator / denominator as a multiple ("x"); null when the denominator is not positive. */
        Figure multiple(String numerator, String denominator, String nonPositiveReason) {
            BigDecimal a = value(numerator);
            BigDecimal b = value(denominator);
            if (a == null || b == null) return new Figure(null, "MULTIPLE", null, firstReason(numerator, denominator));
            if (b.signum() <= 0) return new Figure(null, "MULTIPLE", null, nonPositiveReason);
            return new Figure(a.divide(b, 2, RoundingMode.HALF_EVEN), "MULTIPLE", period(denominator), null);
        }

        /** numerator / denominator as a percentage; null when the denominator is not positive. */
        Figure percent(String numerator, String denominator) {
            BigDecimal a = value(numerator);
            BigDecimal b = value(denominator);
            if (a == null || b == null) return new Figure(null, "PERCENT", null, firstReason(numerator, denominator));
            if (b.signum() <= 0) return new Figure(null, "PERCENT", null, "NON_POSITIVE_DENOMINATOR:" + denominator);
            return new Figure(a.movePointRight(2).divide(b, 2, RoundingMode.HALF_EVEN), "PERCENT",
                    period(numerator.equals(numerator.toUpperCase()) ? numerator : denominator), null);
        }

        Figure change(String current, String earlier, String session) {
            BigDecimal a = value(current);
            BigDecimal b = value(earlier);
            if (a == null || b == null || b.signum() <= 0) {
                return new Figure(null, "PERCENT", null, a == null || b == null ? firstReason(current, earlier)
                        : "NON_POSITIVE_DENOMINATOR:" + earlier);
            }
            return new Figure(DisplayMetrics.percent(a.doubleValue() / b.doubleValue() - 1), "PERCENT",
                    "252 sessions to " + session, null);
        }

        private String firstReason(String a, String b) {
            return value(a) == null ? reason(a) : reason(b);
        }
    }
}
