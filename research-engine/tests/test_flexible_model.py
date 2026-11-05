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


@pytest.mark.parametrize("version", ["1.3.0", "1.4.0"])
def test_future_information_and_history_gaps_do_not_enter_momentum(version):
    inputs = rows()
    inputs[0]["provenance"]["MOMENTUM_RECENT"]["observed_at"] = "2026-10-01T00:00:00Z"
    inputs[1]["blocking_reasons"] = ["MISSING_SESSION:2026-01-02"]
    report = score(inputs, as_of=AS_OF, version=version)
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


def test_v14_missing_filing_disables_even_present_accounting_values():
    inputs = rows()
    inputs[0]["blocking_reasons"] = ["STALE_OR_MISSING_FILING"]
    assert not score(inputs, as_of=AS_OF)["rows"][0]["eligible"]
    result = score(inputs, as_of=AS_OF, version="1.4.0")["rows"][0]
    assert result["eligible"] and result["score_status"] == "PARTIAL"
    assert result["effective_weights"] == {"value": 0, "quality": 0, "momentum": 1}
    assert all(m["raw"] is None for m in result["metrics"].values()
               if m["family"] != "momentum")
    assert "LIMITATION:STALE_OR_MISSING_FILING" in result["warnings"]


def test_v14_price_cohort_crosses_profiles_but_accounting_does_not():
    inputs = rows()
    inputs[0].update(profile="UNKNOWN", profile_supported=False, peer_group=None)
    assert not score(inputs, as_of=AS_OF)["rows"][0]["eligible"]
    result = score(inputs, as_of=AS_OF, version="1.4.0")["rows"][0]
    assert result["eligible"]
    assert set(result["metrics"]) == {"momentum"}
    assert result["metrics"]["momentum"]["cohort"] == "PRICE_UNIVERSE"
    assert result["metrics"]["momentum"]["cohort_count"] == 30
    inputs[1]["blocking_reasons"] = ["CORPORATE_ACTION_REVIEW:unresolved"]
    inputs[2]["history_count"] = 200
    result = score(inputs, as_of=AS_OF, version="1.4.0")["rows"][0]
    assert result["metrics"]["momentum"]["cohort_count"] == 28
    assert not score(inputs[:20], as_of=AS_OF, version="1.4.0")["rows"][0]["eligible"]


@pytest.mark.parametrize("reason", ["CORPORATE_ACTION_REVIEW:1", "NON_PRIMARY_SHARE_CLASS",
                                   "INACTIVE_OR_UNSUPPORTED_SECURITY", "UNKNOWN_FAILURE"])
def test_v14_missing_filing_does_not_override_other_blockers(reason):
    inputs = rows()
    inputs[0]["blocking_reasons"] = ["STALE_OR_MISSING_FILING", reason]
    result = score(inputs, as_of=AS_OF, version="1.4.0")["rows"][0]
    assert not result["eligible"] and result["reasons"] == [reason]


def test_v14_golden_and_unknown_version():
    folder = Path(__file__).parents[1] / "fixtures/model-v1.4"
    prepared = json.loads((folder / "prepared.json").read_text())
    expected = json.loads((folder / "expected.json").read_text())
    assert score(prepared["rows"], as_of=prepared["as_of"], version="1.4.0") == expected
    assert score(list(reversed(prepared["rows"])), as_of=prepared["as_of"],
                 version="1.4.0") == expected
    with pytest.raises(ValueError, match="Unsupported flexible model version"):
        score(prepared["rows"], as_of=prepared["as_of"], version="unknown")


def test_v15_secondary_classes_score_but_fit_one_row_per_issuer():
    inputs = rows()
    for i, row in enumerate(inputs):
        row["issuer_id"] = str(UUID(int=1000 + i))
    inputs[1].update(issuer_id=inputs[0]["issuer_id"], primary_class=False,
                     blocking_reasons=["NON_PRIMARY_SHARE_CLASS"])
    assert not score(inputs, as_of=AS_OF, version="1.4.0")["rows"][1]["eligible"]
    report = score(inputs, as_of=AS_OF, version="1.5.0")
    primary, secondary = report["rows"][0], report["rows"][1]
    assert secondary["eligible"] and "LIMITATION:NON_PRIMARY_SHARE_CLASS" in secondary["warnings"]
    assert secondary["metrics"]["momentum"]["cohort_count"] == 29
    assert primary["metrics"]["momentum"]["cohort_count"] == 29
    # Never a class-level capitalization for a multi-class issuer.
    for result in (primary, secondary):
        assert result["families"]["value"] is None
        assert "LIMITATION:MULTI_CLASS_ISSUER_CAPITALIZATION_UNAVAILABLE" in result["warnings"]
    assert report["rows"][2]["families"]["value"] is not None


def test_v15_depositary_receipts_score_but_lifecycle_and_other_types_block():
    inputs = rows()
    for i, row in enumerate(inputs):
        row["issuer_id"] = str(UUID(int=1000 + i))
    inputs[0].update(profile="UNKNOWN", profile_supported=False, peer_group=None,
                     blocking_reasons=["INACTIVE_OR_UNSUPPORTED_SECURITY:DEPOSITARY_RECEIPT"])
    result = score(inputs, as_of=AS_OF, version="1.5.0")["rows"][0]
    assert result["eligible"] and set(result["metrics"]) == {"momentum"}
    assert not score(inputs, as_of=AS_OF, version="1.4.0")["rows"][0]["eligible"]
    for reason in ("INACTIVE_OR_UNSUPPORTED_SECURITY:DELISTED:2026-10-06",
                   "INACTIVE_OR_UNSUPPORTED_SECURITY:NOT_YET_LISTED:2026-11-02",
                   "INACTIVE_OR_UNSUPPORTED_SECURITY:PREFERRED_STOCK",
                   "INACTIVE_OR_UNSUPPORTED_SECURITY"):
        inputs[0]["blocking_reasons"] = [reason]
        result = score(inputs, as_of=AS_OF, version="1.5.0")["rows"][0]
        assert not result["eligible"] and result["reasons"] == [reason]


def test_v15_golden():
    folder = Path(__file__).parents[1] / "fixtures/model-v1.5"
    prepared = json.loads((folder / "prepared.json").read_text())
    expected = json.loads((folder / "expected.json").read_text())
    assert score(prepared["rows"], as_of=prepared["as_of"], version="1.5.0") == expected
    assert score(list(reversed(prepared["rows"])), as_of=prepared["as_of"],
                 version="1.5.0") == expected
