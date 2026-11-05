package com.quantplatform.screener.financials;

import java.util.Map;
import java.util.Set;

/**
 * Presentation metadata for stored model inputs and derivations. It classifies values and
 * reads formula documentation from the model manifest; it computes nothing.
 * Values themselves always come from the publication.
 */
final class FinancialCatalog {
    private FinancialCatalog() {}

    private static final Set<String> INCOME = Set.of("REVENUE", "COST_OF_REVENUE", "GROSS_PROFIT",
            "OPERATING_INCOME", "PRETAX_INCOME", "NET_INCOME", "NET_INCOME_COMMON", "TAX_EXPENSE",
            "INTEREST_EXPENSE", "ANNUAL_PRETAX", "ANNUAL_TAX");
    private static final Set<String> CASH_FLOW = Set.of("OPERATING_CASH_FLOW", "CAPEX",
            "COMMON_DIVIDENDS", "COMMON_REPURCHASES", "STOCK_ISSUANCE");
    private static final Set<String> BALANCE = Set.of("CASH", "TOTAL_ASSETS", "DEBT_CURRENT",
            "DEBT_NONCURRENT", "COMMON_EQUITY", "PREFERRED_EQUITY", "MINORITY_INTEREST", "GOODWILL",
            "INTANGIBLES");
    private static final Set<String> MARKET = Set.of("RAW_CLOSE", "ADJUSTED_CLOSE", "MOMENTUM_RECENT",
            "MOMENTUM_OLD", "MEDIAN_DOLLAR_VOLUME");

    static String base(String code) {
        String base = code.replaceFirst("_(TTM|PRIOR)$", "");
        return base.matches("ANNUAL_(TAX|PRETAX)_\\d+") ? base.replaceFirst("_\\d+$", "") : base;
    }

    static boolean market(String code) {
        return MARKET.contains(code);
    }

    static String category(String code) {
        String base = base(code);
        if (INCOME.contains(base)) return "INCOME_STATEMENT";
        if (CASH_FLOW.contains(base)) return "CASH_FLOW";
        if (BALANCE.contains(base)) return "BALANCE_SHEET";
        if (code.equals("SHARES_OUTSTANDING")) return "SHARES";
        if (MARKET.contains(code)) return "MARKET";
        return "OTHER";
    }

    static String periodType(String code) {
        if (code.endsWith("_TTM")) return "TTM";
        if (code.matches("ANNUAL_(TAX|PRETAX)_\\d+")) return "ANNUAL";
        if (code.equals("RAW_CLOSE") || code.equals("ADJUSTED_CLOSE")) return "SCORE_DATE_CLOSE";
        if (code.equals("MOMENTUM_RECENT")) return "CLOSE_21_SESSIONS_BEFORE";
        if (code.equals("MOMENTUM_OLD")) return "CLOSE_252_SESSIONS_BEFORE";
        if (code.equals("MEDIAN_DOLLAR_VOLUME")) return "MEDIAN_20_SESSIONS";
        if (code.endsWith("_PRIOR")) return "BALANCE_SHEET_PRIOR_YEAR";
        if (code.equals("SHARES_OUTSTANDING")) return "INSTANT";
        if (BALANCE.contains(base(code))) return "BALANCE_SHEET";
        return "UNCLASSIFIED";
    }

    private static final Set<String> MONETARY = Set.of("market_cap", "ev", "debt", "ebit", "fcf",
            "gross_profit", "invested", "invested_PRIOR", "avg_assets", "avg_invested", "nopat",
            "cash_earnings_top", "tce", "tce_PRIOR", "avg_tce", "payout", "ffo", "affo", "ebitdare",
            "net_debt");

    static String derivedUnit(String code, String currency) {
        return MONETARY.contains(code) ? currency : "RATIO";
    }

    private static final Map<String, String> CONTRACT_ALIASES = Map.of(
            "normalized_tax_rate", "tax", "avg_assets", "averages", "avg_invested", "averages",
            "avg_tce", "averages", "invested_PRIOR", "invested");
    private static final Map<String, String> PERIOD_RULES = Map.of("debt", "debt", "ebit", "ebit");

    /**
     * Documentation-only formula text from the publication's own model manifest
     * ({@code formula_contract}, expression policy DOCUMENTATION_ONLY_NOT_EVALUATED).
     * Lookup order: the instrument's profile group, SHARED, then any other group.
     * Returns null when the manifest does not document the code.
     */
    static String formula(Map<String, Object> contract, Map<String, Object> periods, String profile, String code) {
        String key = CONTRACT_ALIASES.getOrDefault(code, code);
        var groups = new java.util.ArrayList<String>();
        if (profile != null) groups.add(profile);
        groups.add("SHARED");
        contract.keySet().stream().sorted().filter(g -> !groups.contains(g)).forEach(groups::add);
        for (String group : groups) {
            if (contract.get(group) instanceof Map<?, ?> formulas && formulas.get(key) instanceof String text) {
                return text;
            }
        }
        String period = PERIOD_RULES.get(code);
        return period != null && periods.get(period) instanceof String rule ? rule : null;
    }
}
