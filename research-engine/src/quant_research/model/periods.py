"""Pure period assembly: no database calls and no summing cumulative YTD as quarters."""

from __future__ import annotations

from datetime import date, timedelta

from .engine import timestamp

ADDITIVE = {
    "REVENUE",
    "NET_INCOME",
    "NET_INCOME_COMMON",
    "OPERATING_INCOME",
    "PRETAX_INCOME",
    "INTEREST_EXPENSE",
    "TAX_EXPENSE",
    "GROSS_PROFIT",
    "COST_OF_REVENUE",
    "OPERATING_CASH_FLOW",
    "CAPEX",
    "COMMON_DIVIDENDS",
    "COMMON_REPURCHASES",
    "STOCK_ISSUANCE",
}
BALANCES = {
    "TOTAL_ASSETS",
    "COMMON_EQUITY",
    "CASH",
    "DEBT_CURRENT",
    "DEBT_NONCURRENT",
    "PREFERRED_EQUITY",
    "MINORITY_INTEREST",
    "GOODWILL",
    "INTANGIBLES",
}


def days(fact: dict) -> int:
    return (date.fromisoformat(fact["end"]) - date.fromisoformat(fact["start"])).days + 1


def annual(fact: dict) -> bool:
    return fact.get("start") is not None and 350 <= days(fact) <= 380


def _result(parts: list[tuple[dict, int]]) -> dict:
    return {
        "value": sum(fact["value"] * sign for fact, sign in parts),
        "source_ids": sorted({fact["factId"] for fact, _ in parts}),
        "available_at": max((fact["availableAt"] for fact, _ in parts), key=timestamp),
        "observed_at": max((fact["observedAt"] for fact, _ in parts), key=timestamp),
        "unit": "USD",
        "filed_date": max(fact["filedDate"] for fact, _ in parts),
        "period_end": max(fact["end"] for fact, _ in parts),
    }


def ttm(facts: list[dict], metric: str, end: str) -> dict | None:
    if metric not in ADDITIVE:
        return None
    selected = [f for f in facts if f["metric"] == metric and f.get("start") and f["end"] <= end]
    direct = [f for f in selected if f["end"] == end and annual(f)]
    if len(direct) == 1:
        return _result([(direct[0], 1)])
    if len(direct) > 1:
        return None
    quarters, boundary = [], end
    for _ in range(4):
        candidates = [f for f in selected if f["end"] == boundary and 70 <= days(f) <= 110]
        if len(candidates) != 1:
            break
        fact = candidates[0]
        quarters.append(fact)
        boundary = (date.fromisoformat(fact["start"]) - timedelta(days=1)).isoformat()
    if (
        len(quarters) == 4
        and 350 <= (date.fromisoformat(end) - date.fromisoformat(boundary)).days <= 380
    ):
        return _result([(f, 1) for f in quarters])
    results = []
    for ytd in selected:
        if ytd["end"] != end or not 70 <= days(ytd) < 350:
            continue
        for year in selected:
            if not annual(year) or date.fromisoformat(year["end"]) + timedelta(
                days=1
            ) != date.fromisoformat(ytd["start"]):
                continue
            for prior in selected:
                span = (date.fromisoformat(end) - date.fromisoformat(prior["end"])).days
                if (
                    prior["start"] == year["start"]
                    and abs(days(prior) - days(ytd)) <= 7
                    and 350 <= span <= 380
                ):
                    results.append(_result([(year, 1), (ytd, 1), (prior, -1)]))
    return results[0] if len(results) == 1 else None


def from_cross_section(
    section: dict, *, jurisdictions: dict[str, str] | None = None
) -> tuple[str, list[dict]]:
    """Consume the immutable Phase 6 CrossSection JSON contract.

    The SQL already ranks source revisions before this boundary. No profile unsupported
    by that query is enabled here. Jurisdiction must be supplied explicitly by issuer ID.
    """
    request = section["request"]
    cutoff = timestamp(request["knowledgeCutoff"])
    if cutoff > timestamp(request["marketCutoff"]):
        raise ValueError("Knowledge cutoff exceeds the market cutoff")
    score_date = date.fromisoformat(request["scoreDate"])
    rows = []
    for item in section["inputs"]:
        facts = []
        for fact in item["facts"]:
            if (
                timestamp(fact["availableAt"]) <= cutoff
                and timestamp(fact["observedAt"]) <= cutoff
                and date.fromisoformat(fact["end"]) <= score_date
                and fact["unit"] == "USD"
            ):
                facts.append(fact)
        # Only already selected revisions are accepted, never pick an arbitrary duplicate.
        periods = [(f["metric"], f.get("start"), f["end"]) for f in facts]
        if len(periods) != len(set(periods)):
            raise ValueError("Ambiguous fact period in canonical cross-section")
        latest = max(
            (f["end"] for f in facts if f["metric"] == "TOTAL_ASSETS" and not f.get("start")),
            default=None,
        )
        values, provenance = {}, {}

        def put(key: str, evidence: dict | None, values=values, provenance=provenance) -> None:
            if evidence:
                values[key] = evidence["value"]
                provenance[key] = {k: v for k, v in evidence.items() if k != "value"}

        def balance(metric: str, end: str, facts=facts) -> dict | None:
            matches = [
                f for f in facts if f["metric"] == metric and not f.get("start") and f["end"] == end
            ]
            return _result([(matches[0], 1)]) if len(matches) == 1 else None

        if latest:
            prior_candidates = sorted(
                {
                    f["end"]
                    for f in facts
                    if f["metric"] == "TOTAL_ASSETS"
                    and not f.get("start")
                    and 350
                    <= (date.fromisoformat(latest) - date.fromisoformat(f["end"])).days
                    <= 380
                }
            )
            prior = prior_candidates[0] if len(prior_candidates) == 1 else None
            for metric in BALANCES:
                put(metric, balance(metric, latest))
                if prior:
                    put(metric + "_PRIOR", balance(metric, prior))
            for metric in ADDITIVE:
                put(metric + "_TTM", ttm(facts, metric, latest))
            annual_ends = sorted(
                {f["end"] for f in facts if f["metric"] == "PRETAX_INCOME" and annual(f)},
                reverse=True,
            )[:3]
            for number, end in enumerate(annual_ends, 1):
                for key, metric in (("TAX", "TAX_EXPENSE"), ("PRETAX", "PRETAX_INCOME")):
                    matches = [
                        f for f in facts if f["metric"] == metric and f["end"] == end and annual(f)
                    ]
                    if len(matches) == 1:
                        put(f"ANNUAL_{key}_{number}", _result([(matches[0], 1)]))
        price_sources = [p["observationKey"] for p in item["priceLineage"]]
        for key, field, unit in (
            ("RAW_CLOSE", "rawClose", "USD"),
            ("ADJUSTED_CLOSE", "adjustedClose", "USD"),
            ("MOMENTUM_RECENT", "momentumRecent", "USD"),
            ("MOMENTUM_OLD", "momentumOld", "USD"),
            ("MEDIAN_DOLLAR_VOLUME", "medianDollarVolume", "USD"),
            ("SHARES_OUTSTANDING", "sharesOutstanding", "shares"),
        ):
            if item.get(field) is not None:
                put(
                    key,
                    {
                        "value": item[field],
                        "unit": unit,
                        "source_ids": [item["shareFactId"]]
                        if key == "SHARES_OUTSTANDING"
                        else price_sources,
                        "available_at": cutoff.isoformat(),
                        "observed_at": cutoff.isoformat(),
                        "availability_basis": "PHASE6_CUTOFF_UPPER_BOUND",
                        "period_end": score_date.isoformat(),
                    },
                )
        rows.append(
            {
                "instrument_id": item["instrumentId"],
                "issuer_id": item["issuerId"],
                "profile": item["profile"],
                "profile_supported": item["profile"] == "GENERAL",
                "peer_group": item["peerGroup"],
                "jurisdiction": (jurisdictions or {}).get(item["issuerId"]),
                "in_universe": item["inSp500"] or item["inNasdaq100"],
                "active": True,
                "primary_class": item["primaryClass"],
                "security_type": "COMMON_STOCK",
                "history_count": item["historyCount"],
                "liquidity_count": item["liquidityCount"],
                "filing_date": max((f["filedDate"] for f in facts), default="1900-01-01"),
                "period_end": latest,
                "values": values,
                "provenance": provenance,
                "blocking_reasons": [
                    r["code"] + (":" + r["detail"] if r.get("detail") else "")
                    for r in item["reasons"]
                ],
            }
        )
    return request["knowledgeCutoff"], rows
