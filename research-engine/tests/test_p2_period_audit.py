"""Independent expectations for fiscal calendars, ambiguity and cutoff boundaries."""

import copy

import pytest
from test_model_periods import fact, section

from quant_research.model.periods import from_cross_section, ttm
from quant_research.model.preparation import CURRENT_VERSION


def period_cases():
    quarters = [
        fact("2025-06-29", "2025-09-27", 10),
        fact("2025-09-28", "2025-12-27", 20),
        fact("2025-12-28", "2026-03-28", 30),
        fact("2026-03-29", "2026-07-04", 40),
    ]
    ytd = [
        fact("2024-12-29", "2026-01-03", 100),
        fact("2026-01-04", "2026-07-04", 70),
        fact("2024-12-29", "2025-06-28", 40),
    ]
    overlapping = copy.deepcopy(quarters)
    overlapping[1]["start"] = "2025-09-27"
    ambiguous = ytd + [fact("2024-12-29", "2025-07-05", 42)]
    return [
        ("53_week_quarters", quarters, "2026-07-04", 100),
        ("53_week_ytd", ytd, "2026-07-04", 130),
        ("overlapping_quarters", overlapping, "2026-07-04", None),
        ("ambiguous_prior_ytd", ambiguous, "2026-07-04", None),
        ("missing_prior_ytd", ytd[:2], "2026-07-04", None),
        ("non_annual_duration", [fact("2025-07-01", "2026-07-31", 100)], "2026-07-31", None),
        ("negative_cash_flow", [fact("2025-07-01", "2026-06-30", -100)], "2026-06-30", -100),
    ]


@pytest.mark.parametrize("name,facts,end,expected", period_cases())
def test_fiscal_period_assembly(name, facts, end, expected):
    result = ttm(facts, "REVENUE", end)
    assert (None if result is None else result["value"]) == expected, name


@pytest.mark.parametrize("version", ["profiles-v1", CURRENT_VERSION])
def test_ambiguous_prior_balance_is_not_arbitrarily_selected(version):
    document = section()
    document["inputs"][0]["facts"].append(fact(None, "2025-07-01", 19000, "TOTAL_ASSETS"))
    _, rows = from_cross_section(document, preprocessing_version=version)
    assert "TOTAL_ASSETS_PRIOR" not in rows[0]["values"]


@pytest.mark.parametrize("field", ["availableAt", "observedAt"])
def test_exact_cutoff_is_included_but_later_information_is_not(field):
    document = section()
    current = document["inputs"][0]["facts"][0]
    current[field] = document["request"]["knowledgeCutoff"]
    _, rows = from_cross_section(document, preprocessing_version=CURRENT_VERSION)
    assert rows[0]["values"]["TOTAL_ASSETS"] == 20000
    current[field] = "2026-09-30T20:00:01Z"
    _, rows = from_cross_section(document, preprocessing_version=CURRENT_VERSION)
    assert rows[0]["values"]["TOTAL_ASSETS"] == 18000
