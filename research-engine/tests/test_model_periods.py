import copy
from datetime import date

import pytest
from model_fixtures import fixture_rows

from quant_research.model.engine import _raw, timestamp
from quant_research.model.manifest import load_manifest
from quant_research.model.periods import from_cross_section, ttm


def fact(start, end, value, metric="REVENUE", available="2026-08-01T00:00:00Z"):
    return {
        "factId": metric + ":" + str(start) + ":" + end,
        "metric": metric,
        "start": start,
        "end": end,
        "value": value,
        "unit": "USD",
        "availableAt": available,
        "observedAt": available,
        "filedDate": "2026-08-01",
    }


def test_ttm_prefers_direct_annual_and_retains_sources():
    annual = fact("2025-07-01", "2026-06-30", 123)
    result = ttm([annual], "REVENUE", "2026-06-30")
    assert result["value"] == 123
    assert result["source_ids"] == [annual["factId"]]
    assert ttm([annual, annual], "REVENUE", "2026-06-30") is None


def test_four_contiguous_quarters_and_a_gap():
    facts = [
        fact("2025-07-01", "2025-09-30", 10),
        fact("2025-10-01", "2025-12-31", 20),
        fact("2026-01-01", "2026-03-31", 30),
        fact("2026-04-01", "2026-06-30", 40),
    ]
    assert ttm(facts, "REVENUE", "2026-06-30")["value"] == 100
    assert ttm(facts[1:], "REVENUE", "2026-06-30") is None
    assert ttm(facts, "SHARES_OUTSTANDING", "2026-06-30") is None


def test_ytd_is_not_double_counted():
    facts = [
        fact("2025-01-01", "2025-12-31", 100),
        fact("2026-01-01", "2026-06-30", 70),
        fact("2025-01-01", "2025-06-30", 40),
    ]
    result = ttm(facts, "REVENUE", "2026-06-30")
    assert result["value"] == 130
    assert len(result["source_ids"]) == 3
    assert ttm(facts[:2], "REVENUE", "2026-06-30") is None


def section():
    return {
        "request": {
            "scoreDate": "2026-09-30",
            "marketCutoff": "2026-09-30T20:00:00Z",
            "knowledgeCutoff": "2026-09-30T20:00:00Z",
        },
        "inputs": [
            {
                "instrumentId": "00000000-0000-0000-0000-000000000001",
                "issuerId": "00000000-0000-0000-0000-000000000002",
                "profile": "GENERAL",
                "peerGroup": "Technology",
                "inSp500": True,
                "inNasdaq100": True,
                "primaryClass": True,
                "historyCount": 252,
                "liquidityCount": 20,
                "rawClose": 100,
                "adjustedClose": 100,
                "momentumRecent": 110,
                "momentumOld": 100,
                "medianDollarVolume": 10000000,
                "sharesOutstanding": 100,
                "shareFactId": "shares:1",
                "priceLineage": [{"observationKey": "price:1"}],
                "reasons": [],
                "facts": [
                    fact(None, "2026-06-30", 20000, "TOTAL_ASSETS"),
                    fact(None, "2025-06-30", 18000, "TOTAL_ASSETS"),
                    fact("2025-07-01", "2026-06-30", 1000),
                    fact("2026-01-01", "2026-09-30", 9000, available="2026-10-01T00:00:00Z"),
                ],
            }
        ],
    }


def test_adapter_aligns_periods_filters_future_and_preserves_exclusions():
    document = section()
    cutoff, rows = from_cross_section(document)
    assert cutoff == document["request"]["knowledgeCutoff"]
    row = rows[0]
    assert row["values"]["REVENUE_TTM"] == 1000
    assert row["values"]["TOTAL_ASSETS_PRIOR"] == 18000
    assert row["provenance"]["REVENUE_TTM"]["source_ids"] == ["REVENUE:2025-07-01:2026-06-30"]
    assert row["jurisdiction"] is None  # No inference from USD or listing country.
    document["inputs"][0]["reasons"] = [{"code": "BLOCKING_ISSUE", "detail": "split"}]
    document["inputs"][0]["profile"] = "BANK"
    _, rows = from_cross_section(document)
    assert not rows[0]["profile_supported"]
    assert rows[0]["blocking_reasons"] == ["BLOCKING_ISSUE:split"]


def test_adapter_rejects_duplicates_and_bad_cutoff():
    document = section()
    document["inputs"][0]["facts"].append(copy.deepcopy(document["inputs"][0]["facts"][0]))
    with pytest.raises(ValueError, match="Ambiguous"):
        from_cross_section(document)
    document = section()
    document["request"]["knowledgeCutoff"] = "2026-10-01T00:00:00Z"
    with pytest.raises(ValueError, match="cutoff"):
        from_cross_section(document)


def test_aligned_annual_tax_median_clipping_and_invalid_year_fallback():
    _, rows = fixture_rows()
    row = rows[0]
    for n, (year, tax) in enumerate([(2025, 10), (2024, 20), (2023, 90)], 1):
        for name, amount in (("TAX", tax), ("PRETAX", 100)):
            key = f"ANNUAL_{name}_{n}"
            row["values"][key] = amount
            row["provenance"][key] = {
                "period_end": date(year, 12, 31).isoformat(),
                "unit": "USD",
                "source_ids": ["annual:" + key],
                "available_at": "2026-08-01T00:00:00Z",
                "observed_at": "2026-08-01T00:00:00Z",
            }
    cutoff = timestamp("2026-09-30T20:00:00Z")
    assert _raw(row, cutoff, load_manifest())["derived"]["normalized_tax_rate"] == 0.2
    row["values"]["ANNUAL_TAX_1"] = 100
    row["values"]["ANNUAL_TAX_2"] = 100
    assert _raw(row, cutoff, load_manifest())["derived"]["normalized_tax_rate"] == 0.4
    row["values"]["ANNUAL_PRETAX_1"] = -1
    assert _raw(row, cutoff, load_manifest())["derived"]["normalized_tax_rate"] == 0.21


def test_prior_balances_and_reit_payout_require_aligned_periods():
    _, rows = fixture_rows()
    row = rows[0]
    row["provenance"]["TOTAL_ASSETS_PRIOR"]["period_end"] = "2024-06-30"
    result = _raw(row, timestamp("2026-09-30T20:00:00Z"), load_manifest())
    assert result["metrics"]["gross_profitability"]["raw"] is None
    row = next(r for r in rows if r["profile"] == "EQUITY_REIT")
    row["provenance"]["AFFO_PRIOR_TTM"]["period_end"] = "2024-06-30"
    assert (
        "MISSING_RISK_INPUT:affo_payout"
        in _raw(row, timestamp("2026-09-30T20:00:00Z"), load_manifest())["reasons"]
    )
