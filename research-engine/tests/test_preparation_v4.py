import copy

import pytest
from test_model_periods import fact, section

from quant_research.model.periods import from_cross_section


def equity_cases():
    cases = []
    for change in ("valid", "filing", "missing_filing", "late", "unit", "period",
                   "missing_preferred", "negative_preferred", "negative_total", "direct",
                   "unsupported", "future", "explicit_zero"):
        doc = section()
        facts = doc["inputs"][0]["facts"]
        total = fact(None, "2026-06-30", 7261, "STOCKHOLDERS_EQUITY")
        pref = fact(None, "2026-06-30", 2, "PREFERRED_EQUITY")
        total["filingId"] = pref["filingId"] = "same-filing"
        facts.extend([total, pref])
        expected = None
        if change == "valid":
            expected = 7259
        elif change == "filing":
            pref["filingId"] = "other-filing"
        elif change == "missing_filing":
            total.pop("filingId")
        elif change == "late":
            pref["observedAt"] = "2026-10-01T00:00:00Z"
        elif change == "unit":
            pref["unit"] = "EUR"
        elif change == "period":
            pref["end"] = "2026-06-29"
        elif change == "missing_preferred":
            facts.remove(pref)
        elif change == "negative_preferred":
            pref["value"] = -2
        elif change == "negative_total":
            total["value"], expected = -3, -5
        elif change == "direct":
            facts.append(fact(None, "2026-06-30", 123, "COMMON_EQUITY"))
            expected = 123
        elif change == "unsupported":
            doc["inputs"][0]["profile"] = "BANK"
        elif change == "future":
            total["end"] = pref["end"] = "2027-06-30"
        elif change == "explicit_zero":
            pref["value"], expected = 0, 7261
        cases.append((change, doc, expected))
    return cases


@pytest.mark.parametrize(("name", "doc", "expected"), equity_cases())
def test_explicit_parent_equity_derivation_and_historical_policy(name, doc, expected):
    original = copy.deepcopy(doc)
    _, rows = from_cross_section(doc, preprocessing_version="canonical-preparation-v4")
    row = rows[0]
    assert row["values"].get("COMMON_EQUITY") == expected
    assert doc == original
    _, legacy = from_cross_section(doc, preprocessing_version="canonical-preparation-v3")
    assert legacy[0]["values"].get("COMMON_EQUITY") == (123 if name == "direct" else None)
    if expected is not None and name != "direct":
        evidence = row["provenance"]["COMMON_EQUITY"]
        assert evidence["derivation"] == "PARENT_EQUITY_MINUS_PREFERRED_SAME_FILING_PERIOD_V1"
        assert len(evidence["source_ids"]) == 2
        assert evidence["period_end"] == "2026-06-30"
        assert "COMMON_EQUITY" not in row["preparation_diagnostics"]


def test_comparable_prior_requires_its_own_pair_and_preserves_period():
    _, doc, _ = equity_cases()[0]
    facts = doc["inputs"][0]["facts"]
    # The existing fixture already has a comparable prior TOTAL_ASSETS anchor.
    for metric, value in (("STOCKHOLDERS_EQUITY", 7000), ("PREFERRED_EQUITY", 3)):
        f = fact(None, "2025-06-30", value, metric)
        f["filingId"] = "prior-filing"
        facts.append(f)
    _, rows = from_cross_section(doc, preprocessing_version="canonical-preparation-v4")
    assert rows[0]["values"]["COMMON_EQUITY_PRIOR"] == 6997
    assert rows[0]["provenance"]["COMMON_EQUITY_PRIOR"]["period_end"] == "2025-06-30"
