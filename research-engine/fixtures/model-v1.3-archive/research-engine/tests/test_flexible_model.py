import copy
import json
from pathlib import Path
from uuid import UUID

import pytest
from model_fixtures import fixture_rows

from quant_research.model.flexible import score

AS_OF = "2026-09-30T20:00:00Z"


def rows():
    _, fixtures = fixture_rows()
    template = next(r for r in fixtures if r["profile"] == "GENERAL")
    result = []
    for i in range(30):
        row = copy.deepcopy(template)
        row["instrument_id"] = str(UUID(int=i + 1))
        row["values"]["MOMENTUM_RECENT"] *= 1 + i / 100
        row["values"]["OPERATING_CASH_FLOW_TTM"] *= 1 + i / 100
        result.append(row)
    return result


def test_missing_families_redistribute_without_zero_imputation():
    inputs = rows()
    inputs[0]["values"].pop("SHARES_OUTSTANDING")
    inputs[0]["blocking_reasons"] = ["MISSING_INSTRUMENT_SHARES"]
    result = score(inputs, as_of=AS_OF)["rows"][0]
    assert result["eligible"] and result["score_status"] == "PARTIAL"
    assert result["families"]["value"] is None
    assert result["contributions"]["value"] is None
    assert result["effective_weights"] == {"value": 0, "quality": .6, "momentum": .4}
    assert sum(m["effective_weight"] for m in result["metrics"].values()) == pytest.approx(1)
    contribution = sum(m["contribution"] or 0 for m in result["metrics"].values())
    assert contribution == pytest.approx(result["composite"])


def test_stale_shares_disable_value_but_not_quality():
    inputs = rows()
    inputs[0]["blocking_reasons"] = ["STALE_SHARES"]
    result = score(inputs, as_of=AS_OF)["rows"][0]
    assert result["eligible"]
    assert result["families"]["value"] is None
    assert result["families"]["quality"] is not None


@pytest.mark.parametrize("reason", ["CORPORATE_ACTION_REVIEW:123", "UNKNOWN_NEW_FAILURE"])
def test_identity_and_unrecognized_blockers_remain_blocking(reason):
    inputs = rows()
    inputs[0]["blocking_reasons"] = [reason]
    result = score(inputs, as_of=AS_OF)["rows"][0]
    assert result["score_status"] == "UNAVAILABLE"
    assert result["composite"] is None and result["rank"] is None
    assert result["reasons"] == [reason]


def test_price_only_profiles_are_explicit_and_never_use_industrial_formulas():
    inputs = rows()
    for row in inputs:
        row.update(profile="FINANCIAL", profile_supported=False)
    report = score(inputs, as_of=AS_OF)
    assert report["eligible_count"] == 30
    for result in report["rows"]:
        assert result["score_status"] == "PARTIAL"
        assert result["effective_weights"] == {"value": 0, "quality": 0, "momentum": 1}
        assert set(result["metrics"]) == {"momentum"}
        assert result["coverage"]["normalized_metrics"] == 1


def test_future_information_and_history_gaps_do_not_enter_momentum():
    inputs = rows()
    inputs[0]["provenance"]["MOMENTUM_RECENT"]["observed_at"] = "2026-10-01T00:00:00Z"
    inputs[1]["blocking_reasons"] = ["MISSING_SESSION:2026-01-02"]
    report = score(inputs, as_of=AS_OF)
    for result in report["rows"][:2]:
        assert result["families"]["momentum"] is None
        assert result["effective_weights"]["momentum"] == 0
        assert result["eligible"]


def test_no_valid_component_does_not_fabricate_a_score():
    inputs = rows()
    inputs[0]["values"] = {}
    result = score(inputs, as_of=AS_OF)["rows"][0]
    assert not result["eligible"] and result["composite"] is None


def test_golden_flexible_report_and_deterministic_order():
    folder = Path(__file__).parents[1] / "fixtures/model-v1.3"
    prepared = json.loads((folder / "prepared.json").read_text())
    expected = json.loads((folder / "expected.json").read_text())
    assert score(prepared["rows"], as_of=prepared["as_of"]) == expected
    assert score(list(reversed(prepared["rows"])), as_of=prepared["as_of"]) == expected
