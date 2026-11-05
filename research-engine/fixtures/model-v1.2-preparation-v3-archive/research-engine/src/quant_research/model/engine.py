"""Pure, deterministic cross-sectional scoring over reviewed point-in-time inputs."""

from __future__ import annotations

import math
import statistics
from datetime import date, datetime, timedelta
from decimal import ROUND_HALF_EVEN, Decimal
from uuid import UUID

from .manifest import checksum, load_manifest


def timestamp(value: str) -> datetime:
    result = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if result.tzinfo is None or result.utcoffset() is None:
        raise ValueError("A knowledge timestamp must include its timezone")
    return result


def percentile(values: list[float], probability: float) -> float:
    ordered = sorted(values)
    position = (len(ordered) - 1) * probability
    lower = math.floor(position)
    upper = math.ceil(position)
    return ordered[lower] + (ordered[upper] - ordered[lower]) * (position - lower)


class Calculation:
    def __init__(self, row: dict, cutoff: datetime):
        self.row = row
        self.cutoff = cutoff
        self.score_date = date.fromisoformat(row.get("score_date", cutoff.date().isoformat()))
        if not 0 <= (cutoff.date() - self.score_date).days <= 7:
            raise ValueError("Invalid economic score date")
        self.derived: dict[str, float | None] = {}
        self.reasons: dict[str, str] = {}

    def get(self, key: str) -> float | None:
        if key in self.derived:
            return self.derived[key]
        value = self.row.get("values", {}).get(key)
        evidence = self.row.get("provenance", {}).get(key)
        reason = None
        if value is None:
            reason = "NOT_REPORTED"
        elif (
            isinstance(value, bool)
            or not isinstance(value, (int, float))
            or not math.isfinite(value)
            or not evidence
            or not isinstance(evidence.get("source_ids"), list)
            or not evidence["source_ids"]
            or not all(isinstance(source, str) and source for source in evidence["source_ids"])
        ):
            reason = "FAILED_QUALITY_CHECK"
        else:
            try:
                if (
                    timestamp(evidence["available_at"]) > self.cutoff
                    or timestamp(evidence["observed_at"]) > self.cutoff
                    or date.fromisoformat(evidence["period_end"]) > self.score_date
                ):
                    reason = "FUTURE_INFORMATION"
                elif evidence.get("unit") != (
                    "pure"
                    if key.startswith(("OPERATING_ROA_Q", "CET1_REQUIREMENT_"))
                    else "shares"
                    if key == "SHARES_OUTSTANDING"
                    else "square_feet"
                    if key in ("OCCUPIED_AREA", "AVAILABLE_AREA")
                    else "USD"
                ):
                    reason = "INVALID_UNIT"
                elif key in ("RAW_CLOSE", "ADJUSTED_CLOSE") and (
                    evidence["period_end"] != self.score_date.isoformat()
                ):
                    reason = "MISSING_SCORE_DATE_BAR"
                elif key.startswith("CET1_REQUIREMENT_"):
                    signal_date = self.score_date.isoformat()
                    if not (evidence["effective_from"] <= signal_date < evidence["effective_to"]):
                        reason = "STALE"
                elif key.endswith("_PRIOR") or key == "SAME_STORE_NOI_PRIOR_TTM":
                    span = (
                        date.fromisoformat(self.row["period_end"])
                        - date.fromisoformat(evidence["period_end"])
                    ).days
                    if not 350 <= span <= 380:
                        reason = "INSUFFICIENT_HISTORY"
                elif key in ("AFFO_PRIOR_TTM", "DIVIDENDS_DECLARED_PRIOR_TTM"):
                    span = (
                        date.fromisoformat(self.row["period_end"])
                        - date.fromisoformat(evidence["period_end"])
                    ).days
                    if not 70 <= span <= 110:
                        reason = "INSUFFICIENT_HISTORY"
                elif (
                    key.endswith("_TTM")
                    or key
                    in {
                        "TOTAL_ASSETS",
                        "COMMON_EQUITY",
                        "CASH",
                        "DEBT_CURRENT",
                        "DEBT_NONCURRENT",
                        "PREFERRED_EQUITY",
                        "MINORITY_INTEREST",
                        "GOODWILL",
                        "INTANGIBLES",
                        "CET1_CAPITAL",
                        "RISK_WEIGHTED_ASSETS",
                        "NONACCRUAL_LOANS",
                        "PAST_DUE_90_LOANS",
                        "GROSS_LOANS",
                        "TOTAL_ADJUSTED_CAPITAL",
                        "AUTHORIZED_CONTROL_LEVEL_RBC",
                    }
                ) and evidence["period_end"] != self.row["period_end"]:
                    reason = "STALE"
            except (KeyError, ValueError, TypeError):
                reason = "FAILED_QUALITY_CHECK"
        if reason:
            self.reasons[key] = reason
            self.derived[key] = None
        else:
            self.derived[key] = float(value)
        return self.derived[key]

    def combine(self, name: str, terms: list[tuple[str, float]]) -> float | None:
        values = [(self.get(key), weight) for key, weight in terms]
        value = None if any(v is None for v, _ in values) else sum(v * w for v, w in values)
        self.derived[name] = value
        if value is None:
            self.reasons[name] = next(
                (
                    self.reasons.get(key, "NOT_REPORTED")
                    for key, _ in terms
                    if self.get(key) is None
                ),
                "NOT_REPORTED",
            )
        return value

    def ratio(
        self, name: str, numerator: str, denominator: str, offset: float = 0.0
    ) -> float | None:
        top, bottom = self.get(numerator), self.get(denominator)
        if top is None or bottom is None:
            self.reasons[name] = self.reasons.get(
                numerator if top is None else denominator, "NOT_REPORTED"
            )
            value = None
        elif bottom <= 0:
            self.reasons[name] = "INVALID_DENOMINATOR"
            value = None
        else:
            value = top / bottom + offset
        self.derived[name] = value
        return value


def _tax(c: Calculation, manifest: dict, warnings: list[str]) -> float | None:
    rates = []
    ends = []
    for number in range(1, 4):
        tax, pretax = c.get(f"ANNUAL_TAX_{number}"), c.get(f"ANNUAL_PRETAX_{number}")
        evidence = c.row.get("provenance", {})
        a, b = evidence.get(f"ANNUAL_TAX_{number}", {}), evidence.get(f"ANNUAL_PRETAX_{number}", {})
        if (
            tax is not None
            and pretax is not None
            and pretax > 0
            and a.get("period_end") == b.get("period_end")
        ):
            rates.append(tax / pretax)
            ends.append(date.fromisoformat(a["period_end"]))
    if (
        len(rates) == 3
        and 0 <= (date.fromisoformat(c.row["period_end"]) - ends[0]).days <= 380
        and all(350 <= (ends[i] - ends[i + 1]).days <= 380 for i in (0, 1))
    ):
        return min(0.4, max(0.0, statistics.median(rates)))
    for rule in manifest["tax"]["fallbacks"]:
        if (
            rule["jurisdiction"] == c.row.get("jurisdiction")
            and rule["effective_from"] <= c.score_date.isoformat() < rule["effective_to"]
        ):
            warnings.append("STATUTORY_TAX_FALLBACK:" + rule["version"])
            return rule["rate"]
    return None


def _raw(row: dict, cutoff: datetime, model: dict) -> dict:
    c = Calculation(row, cutoff)
    errors = list(row.get("blocking_reasons", []))
    warnings: list[str] = []
    profile = row.get("profile")
    contract = model["profiles"].get(profile)
    if profile in ("BANK", "PC_INSURER", "LIFE_INSURER"):
        if row.get("regulatory_scope") != "PUBLIC_PARENT":
            errors.append("MODEL_NOT_SUPPORTED")
        if profile == "BANK" and row.get("capital_approach") not in ("STANDARDIZED", "ADVANCED"):
            errors.append("MODEL_NOT_SUPPORTED")
    if not contract or not row.get("profile_supported", False):
        errors.append("MODEL_NOT_SUPPORTED")
    for flag, reason in (
        ("in_universe", "NOT_IN_UNIVERSE"),
        ("active", "INACTIVE_SECURITY"),
        ("primary_class", "NON_PRIMARY_SHARE_CLASS"),
    ):
        if not row.get(flag, False):
            errors.append(reason)
    if row.get("security_type") != "COMMON_STOCK":
        errors.append("UNSUPPORTED_SECURITY")
    gates = model["eligibility"]
    for key, threshold, reason in (
        ("ADJUSTED_CLOSE", gates["minimum_adjusted_price"], "PRICE_BELOW_MINIMUM"),
        ("MEDIAN_DOLLAR_VOLUME", gates["minimum_median_dollar_volume"], "ILLIQUID"),
    ):
        value = c.get(key)
        if value is None or value < threshold:
            errors.append(reason if value is not None else "NOT_REPORTED:" + key)
    if row.get("history_count", 0) < gates["history_sessions"]:
        errors.append("INSUFFICIENT_HISTORY")
    if row.get("liquidity_count", 0) < gates["liquidity_sessions"]:
        errors.append("INCOMPLETE_LIQUIDITY_WINDOW")
    try:
        age = (c.score_date - date.fromisoformat(row["filing_date"])).days
        if not 0 <= age <= gates["maximum_filing_age_days"]:
            errors.append("STALE")
    except (KeyError, ValueError):
        errors.append("STALE")
    close, shares = c.get("RAW_CLOSE"), c.get("SHARES_OUTSTANDING")
    c.derived["market_cap"] = (
        close * shares
        if (close is not None and shares is not None and close > 0 and shares > 0)
        else None
    )
    if c.derived["market_cap"] is None:
        errors.append("INVALID_MARKET_CAP")
    c.combine("debt", [("DEBT_CURRENT", 1), ("DEBT_NONCURRENT", 1)])
    c.combine(
        "ev",
        [
            ("market_cap", 1),
            ("debt", 1),
            ("PREFERRED_EQUITY", 1),
            ("MINORITY_INTEREST", 1),
            ("CASH", -1),
        ],
    )
    c.combine("avg_assets", [("TOTAL_ASSETS", 0.5), ("TOTAL_ASSETS_PRIOR", 0.5)])
    c.ratio("momentum", "MOMENTUM_RECENT", "MOMENTUM_OLD", -1)
    if c.get("MOMENTUM_RECENT") is not None and c.get("MOMENTUM_RECENT") <= 0:
        c.derived["momentum"] = None
        c.reasons["momentum"] = "FAILED_QUALITY_CHECK"
    if profile == "GENERAL":
        c.combine("ebit", [("PRETAX_INCOME_TTM", 1), ("INTEREST_EXPENSE_TTM", 1)])
        c.combine("fcf", [("OPERATING_CASH_FLOW_TTM", 1), ("CAPEX_TTM", -1)])
        if c.get("CAPEX_TTM") is not None and c.get("CAPEX_TTM") < 0:
            c.derived["fcf"] = None
            c.reasons["fcf"] = "FAILED_QUALITY_CHECK"
        c.combine("gross_profit", [("REVENUE_TTM", 1), ("COST_OF_REVENUE_TTM", -1)])
        for suffix in ("", "_PRIOR"):
            c.combine(
                "invested" + suffix,
                [
                    (key + suffix, sign)
                    for key, sign in (
                        ("COMMON_EQUITY", 1),
                        ("PREFERRED_EQUITY", 1),
                        ("DEBT_CURRENT", 1),
                        ("DEBT_NONCURRENT", 1),
                        ("MINORITY_INTEREST", 1),
                        ("CASH", -1),
                    )
                ],
            )
        c.combine("avg_invested", [("invested", 0.5), ("invested_PRIOR", 0.5)])
        tax = _tax(c, model, warnings)
        c.derived["normalized_tax_rate"] = tax
        c.derived["nopat"] = (
            c.get("ebit") * (1 - tax) if tax is not None and c.get("ebit") is not None else None
        )
        c.combine("cash_earnings_top", [("OPERATING_CASH_FLOW_TTM", 1), ("NET_INCOME_TTM", -1)])
        for name, top, bottom in (
            ("ebit_ev", "ebit", "ev"),
            ("fcf_yield", "fcf", "market_cap"),
            ("book_market", "COMMON_EQUITY", "market_cap"),
            ("gross_profitability", "gross_profit", "avg_assets"),
            ("roic", "nopat", "avg_invested"),
            ("cash_earnings", "cash_earnings_top", "avg_assets"),
        ):
            c.ratio(name, top, bottom)
        if c.get("COMMON_EQUITY") is not None and c.get("COMMON_EQUITY") <= 0:
            c.derived["book_market"] = None
            c.reasons["book_market"] = "INVALID_DENOMINATOR"
    elif profile in ("BANK", "PC_INSURER", "LIFE_INSURER"):
        for suffix in ("", "_PRIOR"):
            c.combine(
                "tce" + suffix,
                [
                    (key + suffix, sign)
                    for key, sign in (("COMMON_EQUITY", 1), ("GOODWILL", -1), ("INTANGIBLES", -1))
                ],
            )
        c.combine("avg_tce", [("tce", 0.5), ("tce_PRIOR", 0.5)])
        c.combine(
            "payout",
            [
                ("COMMON_DIVIDENDS_TTM", 1),
                ("COMMON_REPURCHASES_TTM", 1),
                ("STOCK_ISSUANCE_TTM", -1),
            ],
        )
        c.ratio("earnings_yield", "NET_INCOME_COMMON_TTM", "market_cap")
        c.ratio("tangible_book", "tce", "market_cap")
        c.ratio("net_payout", "payout", "market_cap")
        if c.get("tce") is None or c.get("tce") <= 0:
            errors.append("NON_POSITIVE_TANGIBLE_COMMON_EQUITY")
        if profile == "BANK":
            c.ratio("roa", "NET_INCOME_TTM", "avg_assets")
            c.ratio("cet1", "CET1_CAPITAL", "RISK_WEIGHTED_ASSETS")
            c.combine("bad_loans", [("NONACCRUAL_LOANS", 1), ("PAST_DUE_90_LOANS", 1)])
            c.ratio("npl_ratio", "bad_loans", "GROSS_LOANS")
            c.combine("credit_quality", [("npl_ratio", -1)])
            components = row.get("capital_requirement_components", [])
            if (
                not components
                or len(components) != len(set(components))
                or any(not key.startswith("CET1_REQUIREMENT_") for key in components)
            ):
                errors.append("MISSING_CET1_REQUIREMENT")
            else:
                c.combine("cet1_requirement", [(key, 1) for key in components])
                c.combine("cet1_surplus", [("cet1", 1), ("cet1_requirement", -1)])
                surplus = c.get("cet1_surplus")
                if surplus is None or any(
                    c.get(key) is None or c.get(key) < 0 for key in components
                ):
                    errors.append("MISSING_CET1_REQUIREMENT")
                elif (
                    c.get("cet1")
                    < c.get("cet1_requirement") + model["risk"]["cet1_surplus_exclude"]
                ):
                    errors.append("CET1_REQUIREMENT_BREACH")
                elif c.get("cet1") < c.get("cet1_requirement") + model["risk"]["cet1_surplus_warn"]:
                    warnings.append("LOW_CET1_SURPLUS")
        else:
            c.ratio("operating_roe", "OPERATING_INCOME_COMMON_TTM", "avg_tce")
            c.ratio("operating_roa", "OPERATING_INCOME_TTM", "avg_assets")
            c.combine("underwriting_cost", [("LOSSES_LAE_TTM", 1), ("UNDERWRITING_EXPENSE_TTM", 1)])
            c.ratio("combined_ratio", "underwriting_cost", "NET_PREMIUMS_TTM")
            c.derived["underwriting_margin"] = (
                1 - c.get("combined_ratio") if c.get("combined_ratio") is not None else None
            )
            quarters = [c.get(f"OPERATING_ROA_Q{q}") for q in range(1, 13)]
            evidence = row.get("provenance", {})
            contiguous = all(value is not None for value in quarters)
            boundary = row.get("period_end")
            for q in range(1, 13):
                item = evidence.get(f"OPERATING_ROA_Q{q}", {})
                try:
                    end, start = (
                        date.fromisoformat(item["period_end"]),
                        date.fromisoformat(item["period_start"]),
                    )
                    contiguous &= (
                        end.isoformat() == boundary and 70 <= (end - start).days + 1 <= 110
                    )
                    boundary = (start - timedelta(days=1)).isoformat()
                except (KeyError, ValueError):
                    contiguous = False
            c.derived["earnings_stability"] = -statistics.pstdev(quarters) if contiguous else None
            if not contiguous:
                c.reasons["earnings_stability"] = "INSUFFICIENT_HISTORY"
            c.ratio("rbc", "TOTAL_ADJUSTED_CAPITAL", "AUTHORIZED_CONTROL_LEVEL_RBC")
            rbc = c.get("rbc")
            if rbc is None:
                errors.append("MODEL_NOT_SUPPORTED")
            elif rbc < model["risk"]["rbc_exclude"]:
                errors.append("RBC_REGULATORY_RISK")
            elif rbc < model["risk"]["rbc_warn"]:
                warnings.append("RBC_TREND_WARNING")
            if (
                profile == "PC_INSURER"
                and (c.get("combined_ratio") or 0) > model["risk"]["combined_ratio_warn"]
            ):
                warnings.append("HIGH_COMBINED_RATIO")
    elif profile == "EQUITY_REIT":
        c.combine(
            "ffo",
            [
                (key, sign)
                for key, sign in (
                    ("NET_INCOME_COMMON_TTM", 1),
                    ("REAL_ESTATE_DA_TTM", 1),
                    ("REAL_ESTATE_GAINS_TTM", -1),
                    ("REAL_ESTATE_LOSSES_TTM", 1),
                    ("REAL_ESTATE_IMPAIRMENTS_TTM", 1),
                    ("FFO_JV_NCI_TTM", 1),
                )
            ],
        )
        c.combine(
            "affo",
            [
                ("ffo", 1),
                ("MAINTENANCE_CAPEX_TTM", -1),
                ("TENANT_IMPROVEMENTS_TTM", -1),
                ("LEASING_COMMISSIONS_TTM", -1),
                ("NONCASH_RENT_TTM", -1),
            ],
        )
        c.combine(
            "ebitdare",
            [
                (key, sign)
                for key, sign in (
                    ("NET_INCOME_TTM", 1),
                    ("INTEREST_EXPENSE_TTM", 1),
                    ("TAX_EXPENSE_TTM", 1),
                    ("DA_TTM", 1),
                    ("PROPERTY_GAINS_TTM", -1),
                    ("PROPERTY_LOSSES_TTM", 1),
                    ("REAL_ESTATE_IMPAIRMENTS_TTM", 1),
                    ("EBITDARE_AFFILIATES_TTM", 1),
                )
            ],
        )
        c.combine("net_debt", [("debt", 1), ("CASH", -1)])
        for name, top, bottom in (
            ("ffo_yield", "ffo", "market_cap"),
            ("affo_yield", "affo", "market_cap"),
            ("ebitdare_yield", "ebitdare", "ev"),
            ("interest_coverage", "ebitdare", "CASH_INTEREST_TTM"),
            ("leverage", "net_debt", "ebitdare"),
            ("affo_payout", "DIVIDENDS_DECLARED_TTM", "affo"),
            ("affo_payout_prior", "DIVIDENDS_DECLARED_PRIOR_TTM", "AFFO_PRIOR_TTM"),
            ("occupancy", "OCCUPIED_AREA", "AVAILABLE_AREA"),
        ):
            c.ratio(name, top, bottom)
        c.ratio("noi_growth", "SAME_STORE_NOI_TTM", "SAME_STORE_NOI_PRIOR_TTM", -1)
        c.combine("leverage_quality", [("leverage", -1)])
        if not row.get("reit_reconciled", False) or not row.get(
            "same_store_pool_consistent", False
        ):
            errors.append("MODEL_NOT_SUPPORTED")
        if c.get("ebitdare") is None or c.get("ebitdare") <= 0:
            errors.append("NON_POSITIVE_EBITDARE")
        for key, low, hard, warning in (
            (
                "interest_coverage",
                True,
                model["risk"]["reit_coverage_exclude"],
                model["risk"]["reit_coverage_warn"],
            ),
            (
                "leverage",
                False,
                model["risk"]["reit_leverage_exclude"],
                model["risk"]["reit_leverage_warn"],
            ),
        ):
            value = c.get(key)
            if value is None:
                errors.append("MISSING_RISK_INPUT:" + key)
            elif value < hard if low else value > hard:
                errors.append("REIT_" + key.upper() + "_BREACH")
            elif value < warning if low else value > warning:
                warnings.append("REIT_" + key.upper() + "_WARNING")
        evidence = row.get("provenance", {})
        if evidence.get("AFFO_PRIOR_TTM", {}).get("period_end") != evidence.get(
            "DIVIDENDS_DECLARED_PRIOR_TTM", {}
        ).get("period_end"):
            c.derived["affo_payout_prior"] = None
        payout, prior = c.get("affo_payout"), c.get("affo_payout_prior")
        if payout is None or prior is None:
            errors.append("MISSING_RISK_INPUT:affo_payout")
        elif min(payout, prior) > model["risk"]["reit_payout_exclude"]:
            errors.append("REIT_PAYOUT_BREACH")
        elif payout > model["risk"]["reit_payout_warn"]:
            warnings.append("REIT_PAYOUT_WARNING")
    metrics = {}
    if contract:
        for family in ("value", "quality"):
            for name in contract[family]:
                value = c.get(name)
                metrics[name] = {
                    "family": family,
                    "raw": value,
                    "directed": value,
                    "reason": c.reasons.get(name) if value is None else None,
                }
        metrics["momentum"] = {
            "family": "momentum",
            "raw": c.get("momentum"),
            "directed": c.get("momentum"),
            "reason": c.reasons.get("momentum"),
        }
        for family in ("value", "quality"):
            if sum(metrics[key]["raw"] is not None for key in contract[family]) < 2:
                errors.append("INSUFFICIENT_" + family.upper() + "_COVERAGE")
        if c.get("momentum") is None:
            errors.append("MISSING_MOMENTUM_BOUNDARY")
        if contract["anchor"] and c.get(contract["anchor"]) is None:
            errors.append("MISSING_PROFILE_ANCHOR")
    for metric in metrics.values():
        metric.update(
            {
                key: None
                for key in (
                    "winsorized",
                    "cohort",
                    "lower",
                    "upper",
                    "mean",
                    "sigma",
                    "z",
                    "clipped_z",
                )
            }
        )
        metric["cohort_count"] = 0
    return {
        "instrument_id": str(UUID(row["instrument_id"])),
        "profile": profile,
        "peer_group": row.get("peer_group"),
        "metrics": metrics,
        "derived": c.derived,
        "input_reasons": c.reasons,
        "provenance": row.get("provenance", {}),
        "reasons": sorted(set(errors)),
        "warnings": sorted(set(warnings)),
        "eligible": False,
        "rank": None,
        "percentile": None,
        "composite": None,
        "families": None,
        "contributions": None,
    }


def score(rows: list[dict], *, as_of: str, candidate: str = "primary") -> dict:
    model = load_manifest()
    cutoff = timestamp(as_of)
    if candidate not in model["weights"]:
        raise ValueError("Unknown predeclared model candidate")
    for row in rows:
        if not set(model["input_contract"]["required_metadata"]) <= row.keys():
            raise ValueError("Missing prepared-input metadata")
        if not all(
            isinstance(row[key], bool)
            for key in ("profile_supported", "in_universe", "active", "primary_class")
        ):
            raise ValueError("Eligibility flags must be booleans")
        if not isinstance(row["values"], dict) or not isinstance(row["provenance"], dict):
            raise ValueError("Values and provenance must be mappings")
        if not all(isinstance(value, dict) for value in row["provenance"].values()):
            raise ValueError("Each input requires structured provenance")
    ids = [str(UUID(row["instrument_id"])) for row in rows]
    if len(ids) != len(set(ids)):
        raise ValueError("Duplicate instrument in the cross-section")
    results = [
        _raw(row, cutoff, model) for row in sorted(rows, key=lambda r: UUID(r["instrument_id"]))
    ]
    base = [row for row in results if not row["reasons"]]
    rules = model["preprocessing"]
    for row in base:
        for name, metric in row["metrics"].items():
            metric.update(
                {
                    "winsorized": None,
                    "cohort": None,
                    "cohort_count": 0,
                    "lower": None,
                    "upper": None,
                    "mean": None,
                    "sigma": None,
                    "z": None,
                    "clipped_z": None,
                }
            )
            if metric["raw"] is None:
                continue
            compatible = [
                other
                for other in base
                if other["profile"] == row["profile"] and other["metrics"][name]["raw"] is not None
            ]
            primary = [other for other in compatible if other["peer_group"] == row["peer_group"]]
            if row["peer_group"] and len(primary) >= rules["primary_minimum"]:
                cohort, label = primary, "PRIMARY:" + row["peer_group"]
            elif len(compatible) >= rules["fallback_minimum"]:
                cohort, label = compatible, "PROFILE:" + row["profile"]
                row["warnings"].append("PEER_FALLBACK:" + name)
            else:
                metric["reason"] = "NO_COMPATIBLE_COHORT"
                continue
            values = [other["metrics"][name]["directed"] for other in cohort]
            lower, upper = (percentile(values, p) for p in rules["percentiles"])
            winsorized = [min(upper, max(lower, value)) for value in values]
            mean, sigma = statistics.mean(winsorized), statistics.pstdev(winsorized)
            value = min(upper, max(lower, metric["directed"]))
            z = 0.0 if sigma < rules["dispersion_epsilon"] else (value - mean) / sigma
            if sigma < rules["dispersion_epsilon"]:
                metric["reason"] = "NO_DISPERSION"
            metric.update(
                {
                    "winsorized": value,
                    "cohort": label,
                    "cohort_count": len(cohort),
                    "lower": lower,
                    "upper": upper,
                    "mean": mean,
                    "sigma": sigma,
                    "z": z,
                    "clipped_z": min(rules["z_clip"], max(-rules["z_clip"], z)),
                }
            )
        families = {}
        for family in ("value", "quality", "momentum"):
            parts = [m.get("clipped_z") for m in row["metrics"].values() if m["family"] == family]
            required = 1 if family == "momentum" else rules["minimum_family_metrics"]
            if sum(value is not None for value in parts) < required:
                row["reasons"].append("INSUFFICIENT_NORMALIZED_" + family.upper())
            families[family] = sum(value or 0 for value in parts) / (
                1 if family == "momentum" else 3
            )
        row["families"] = families
        row["contributions"] = dict(
            zip(
                families,
                (
                    families[key] * weight
                    for key, weight in zip(families, model["weights"][candidate], strict=True)
                ),
                strict=True,
            )
        )
        if not row["reasons"]:
            row["eligible"] = True
            row["composite"] = sum(row["contributions"].values())
    eligible = sorted(
        (row for row in results if row["eligible"]),
        key=lambda r: (-r["composite"], UUID(r["instrument_id"])),
    )
    enough = len(eligible) >= model["arithmetic"]["minimum_eligible"]
    if enough:
        for rank, row in enumerate(eligible, 1):
            ties = [
                i for i, other in enumerate(eligible, 1) if other["composite"] == row["composite"]
            ]
            row["rank"] = rank
            row["percentile"] = 100 * (len(eligible) - statistics.mean(ties)) / (len(eligible) - 1)
    for row in results:
        row["warnings"] = sorted(set(row["warnings"]))
        row["reasons"] = sorted(set(row["reasons"]))
    return {
        "model_version": model["version"],
        "manifest_sha256": checksum(model),
        "candidate": candidate,
        "as_of": as_of,
        "expected_count": len(results),
        "eligible_count": len(eligible),
        "excluded_count": len(results) - len(eligible),
        "status": "COMPLETE" if enough else "FAILED",
        "reasons": [] if enough else ["INSUFFICIENT_UNIVERSE"],
        "rows": results,
    }


def serialize(report: object) -> object:
    """Round only the wire representation; ranking used full precision above."""
    if isinstance(report, float):
        if not math.isfinite(report):
            raise ValueError("Non-finite output")
        return float(Decimal(str(report)).quantize(Decimal("0.000001"), rounding=ROUND_HALF_EVEN))
    if isinstance(report, dict):
        return {key: serialize(value) for key, value in report.items()}
    if isinstance(report, list):
        return [serialize(value) for value in report]
    return report
