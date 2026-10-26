"""Versioned preparation improvements; historical profiles-v1 remains reproducible."""

from .engine import timestamp

CURRENT_VERSION = "canonical-preparation-v3"


def enrich(row: dict, item: dict, cutoff: str) -> None:
    from .periods import ADDITIVE, BALANCES, _result

    values, provenance = row["values"], row["provenance"]
    facts = [
        f
        for f in item["facts"]
        if timestamp(f["availableAt"]) <= timestamp(cutoff)
        and timestamp(f["observedAt"]) <= timestamp(cutoff)
        and f["end"] <= row["score_date"]
        and f["unit"] == "USD"
    ]
    if (
        row["profile"] == "GENERAL"
        and "COST_OF_REVENUE_TTM" not in values
        and "REVENUE_TTM" in values
        and "GROSS_PROFIT_TTM" in values
    ):

        def selected(key):
            ids = provenance[key]["source_ids"]
            selected_facts = [f for f in facts if f["factId"] in ids]
            return selected_facts if len(selected_facts) == len(ids) else []

        def legs(parts):
            return {(f["start"], f["end"], f.get("filingId"), f["unit"]) for f in parts}

        revenue, gross = selected("REVENUE_TTM"), selected("GROSS_PROFIT_TTM")
        if (
            revenue
            and legs(revenue) == legs(gross)
            and all(f.get("filingId") for f in revenue + gross)
        ):
            evidence = _result([(f, 1) for f in revenue + gross])
            evidence.pop("value")
            evidence["derivation"] = "REVENUE_MINUS_GROSS_PROFIT_SAME_FILING_PERIOD_V1"
            values["COST_OF_REVENUE_TTM"] = values["REVENUE_TTM"] - values["GROSS_PROFIT_TTM"]
            provenance["COST_OF_REVENUE_TTM"] = evidence
    diagnostics = {}
    for metric in ADDITIVE:
        key = metric + "_TTM"
        if key not in values:
            diagnostics[key] = (
                "TTM_NO_COMPATIBLE_PERIOD_COMBINATION"
                if any(f["metric"] == metric for f in facts)
                else "NO_SELECTED_CANONICAL_FACT"
            )
    for metric in BALANCES:
        for suffix in ("", "_PRIOR"):
            key = metric + suffix
            if key not in values:
                diagnostics[key] = (
                    "NO_UNIQUE_COMPARABLE_BALANCE"
                    if any(f["metric"] == metric for f in facts)
                    else "NO_SELECTED_CANONICAL_FACT"
                )
    row["preparation_diagnostics"] = diagnostics
