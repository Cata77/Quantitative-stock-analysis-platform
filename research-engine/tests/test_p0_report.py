import copy

import pytest
from model_fixtures import fixture_rows

from quant_research.exclusion_audit import index
from quant_research.model.engine import score, serialize
from quant_research.p0_report import (
    diagnose,
    gross_profit_patch,
    metric_name,
    simulate,
    source_facts,
)


@pytest.fixture
def gross_inputs():
    facts = [
        {
            "factId": key,
            "start": "2025-07-01",
            "end": "2026-06-30",
            "filingId": "filing",
            "unit": "USD",
        }
        for key in ("revenue", "gross")
    ]
    evidence = {
        "period_end": "2026-06-30",
        "unit": "USD",
        "available_at": "2026-08-01T00:00:00Z",
        "observed_at": "2026-08-02T00:00:00Z",
    }
    c = {"profile": "GENERAL", "facts": facts}
    p = {
        "values": {"REVENUE_TTM": 100, "GROSS_PROFIT_TTM": 40},
        "provenance": {
            "REVENUE_TTM": dict(evidence, source_ids=["revenue"]),
            "GROSS_PROFIT_TTM": dict(evidence, source_ids=["gross"]),
        },
    }
    return c, p


def test_gross_identity_preserves_sources_and_observation(gross_inputs):
    c, p = gross_inputs
    patch = gross_profit_patch(c, p)
    assert patch["value"] == 60
    assert patch["provenance"]["source_ids"] == ["gross", "revenue"]
    assert patch["provenance"]["observed_at"] == "2026-08-02T00:00:00Z"


@pytest.mark.parametrize(
    "field,value", [("filingId", "different"), ("start", "2025-01-01"), ("unit", "EUR")]
)
def test_gross_identity_refuses_incompatible_legs(gross_inputs, field, value):
    c, p = gross_inputs
    c["facts"][1][field] = value
    assert gross_profit_patch(c, p) is None


def test_gross_identity_never_overwrites_reported_cost(gross_inputs):
    c, p = gross_inputs
    p["values"]["COST_OF_REVENUE_TTM"] = 70
    assert gross_profit_patch(c, p) is None


def test_tax_and_prior_keys_resolve_to_source_metric():
    assert metric_name("ANNUAL_TAX_2") == "TAX_EXPENSE"
    assert metric_name("ANNUAL_PRETAX_3") == "PRETAX_INCOME"
    assert metric_name("COMMON_EQUITY_PRIOR") == "COMMON_EQUITY"
    assert metric_name("SAME_STORE_NOI_PRIOR_TTM") == "SAME_STORE_NOI"


def test_no_mapped_tag_is_not_relabelled_as_unreported_company_data():
    result = diagnose(
        "CAPEX_TTM",
        {"facts": [], "profile": "GENERAL"},
        {"period_end": "2026-06-30"},
        {"facts": {"us-gaap": None}},
        [],
    )
    assert result["cause"] == "MAPPED_CONCEPT_ABSENT_FROM_SOURCE"
    assert source_facts({"facts": {"us-gaap": None}}, [("us-gaap", "Missing")]) == []


def test_candidate_concept_does_not_create_a_value():
    source = {
        "facts": {
            "us-gaap": {
                "PaymentsToAcquireProductiveAssets": {
                    "units": {
                        "USD": [
                            {"end": "2026-06-30", "form": "10-Q", "val": 10},
                        ]
                    }
                }
            }
        }
    }
    result = diagnose(
        "CAPEX_TTM", {"facts": [], "profile": "GENERAL"}, {"period_end": "2026-06-30"}, source, []
    )
    assert result["cause"] == "UNMAPPED_CONCEPT_SCOPE_REVIEW"
    assert "value" not in result


def test_simulation_retains_other_blockers_and_does_not_mutate_input():
    _, rows = fixture_rows()
    as_of = "2026-09-30T20:00:00Z"
    rows[0]["blocking_reasons"] = [
        "MODEL_NOT_SUPPORTED:NO_MAPPED_FACTS",
        "CORPORATE_ACTION_REVIEW:x",
    ]
    original = copy.deepcopy(rows)
    baseline = index(serialize(score(rows, as_of=as_of))["rows"], "instrument_id")
    identity = rows[0]["instrument_id"]
    result = simulate(
        rows,
        {identity: [{"remove_blocker": "MODEL_NOT_SUPPORTED:NO_MAPPED_FACTS"}]},
        as_of,
        baseline,
        {r["instrument_id"]: r["instrument_id"] for r in rows},
    )
    assert rows == original
    assert "CORPORATE_ACTION_REVIEW:x" in result["remaining_blockers"][identity]
    assert identity not in result["recovered"]
