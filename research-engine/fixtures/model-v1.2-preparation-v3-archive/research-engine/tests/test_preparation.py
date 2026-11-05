import copy

import pytest
from test_model_periods import fact, section

from quant_research.model.periods import from_cross_section
from quant_research.model.preparation import CURRENT_VERSION


def example():
    document = section()
    facts = document["inputs"][0]["facts"]
    facts.append(fact("2025-07-01", "2026-06-30", 400, "GROSS_PROFIT"))
    for f in facts:
        f["filingId"] = "filing-1"
    return document


def test_derived_cost_retains_all_sources_and_old_policy_is_unchanged():
    document = example()
    original = copy.deepcopy(document)
    _, old = from_cross_section(document)
    _, new = from_cross_section(document, preprocessing_version=CURRENT_VERSION)
    assert "COST_OF_REVENUE_TTM" not in old[0]["values"]
    assert new[0]["values"]["COST_OF_REVENUE_TTM"] == 600
    evidence = new[0]["provenance"]["COST_OF_REVENUE_TTM"]
    assert len(evidence["source_ids"]) == 2
    assert evidence["observed_at"] == "2026-08-01T00:00:00Z"
    assert evidence["derivation"] == "REVENUE_MINUS_GROSS_PROFIT_SAME_FILING_PERIOD_V1"
    assert document == original


@pytest.mark.parametrize("change", ["filing", "period", "late", "unit", "unsupported", "direct"])
def test_reconciliation_requires_compatible_evidence_and_never_overwrites(change):
    document = example()
    item = document["inputs"][0]
    gross = item["facts"][-1]
    if change == "filing":
        gross["filingId"] = "other-filing"
    if change == "period":
        gross["start"] = "2025-07-02"
    if change == "late":
        gross["observedAt"] = "2026-10-01T00:00:00Z"
    if change == "unit":
        gross["unit"] = "EUR"
    if change == "unsupported":
        item["profile"] = "EQUITY_REIT"
    if change == "direct":
        item["facts"].append(fact("2025-07-01", "2026-06-30", 650, "COST_OF_REVENUE"))
    _, rows = from_cross_section(document, preprocessing_version=CURRENT_VERSION)
    assert rows[0]["values"].get("COST_OF_REVENUE_TTM") == (650 if change == "direct" else None)


def test_period_diagnostics_do_not_claim_source_absence():
    document = section()
    document["inputs"][0]["facts"].append(fact("2026-01-01", "2026-03-31", 50, "CAPEX"))
    _, rows = from_cross_section(document, preprocessing_version=CURRENT_VERSION)
    diagnostics = rows[0]["preparation_diagnostics"]
    assert diagnostics["CAPEX_TTM"] == "TTM_NO_COMPATIBLE_PERIOD_COMBINATION"
    assert diagnostics["COMMON_EQUITY"] == "NO_SELECTED_CANONICAL_FACT"
