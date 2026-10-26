import copy

import pytest
from test_model_periods import section

from quant_research.p2_period_audit import audit


def test_report_links_values_to_selected_facts_and_preserves_missing_diagnostics():
    document = section()
    document["inputs"][0]["symbol"] = "FIXTURE"
    report = audit(document)
    assert report["instrumentCount"] == 1
    metrics = report["instruments"][0]["metrics"]
    assert metrics["REVENUE_TTM"]["method"] == "DIRECT_ANNUAL"
    assert metrics["REVENUE_TTM"]["value"] == 1000
    assert metrics["REVENUE_TTM"]["parts"][0]["factId"] == "REVENUE:2025-07-01:2026-06-30"
    assert report["instruments"][0]["missing"]["CAPEX_TTM"] == "NO_SELECTED_CANONICAL_FACT"


def test_duplicate_members_cannot_inflate_audit_counts():
    document = section()
    document["inputs"][0]["symbol"] = "FIXTURE"
    document["inputs"].append(copy.deepcopy(document["inputs"][0]))
    with pytest.raises(ValueError, match="Duplicate instrument"):
        audit(document)
