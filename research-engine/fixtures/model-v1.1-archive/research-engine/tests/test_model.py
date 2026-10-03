import copy
import json
from pathlib import Path

import pytest
from model_fixtures import fixture_rows

from quant_research.model.engine import Calculation, _raw, percentile, score, serialize, timestamp
from quant_research.model.manifest import ROOT, checksum, load_manifest

AS_OF = "2026-09-30T20:00:00Z"


def raw(profile="GENERAL"):
    _, rows = fixture_rows()
    return copy.deepcopy(next(row for row in rows if row["profile"] == profile))


def evaluate(row):
    return _raw(row, timestamp(AS_OF), load_manifest())


def test_manifest_is_closed_checksummed_and_cannot_evaluate_expressions(tmp_path):
    model = load_manifest()
    assert checksum(model) == (ROOT / "manifest.sha256").read_text().strip()
    model["weights"]["primary"] = [1, 0, 0]
    path = tmp_path / "bad.json"
    path.write_text(json.dumps(model))
    with pytest.raises(ValueError, match="allow-listed"):
        load_manifest(path)


def test_all_profiles_reproduce_hand_calculated_shared_values():
    fixture, rows = fixture_rows()
    for profile, expected in fixture["expected_derived"].items():
        result = evaluate(next(row for row in rows if row["profile"] == profile))
        assert result["reasons"] == []
        for key, value in expected.items():
            assert result["derived"][key] == pytest.approx(value), (profile, key)


def test_golden_all_stages_ties_and_accounting():
    _, rows = fixture_rows()
    report = score(rows, as_of=AS_OF)
    assert report["status"] == "COMPLETE"
    assert report["expected_count"] == report["eligible_count"] == 50
    assert report["excluded_count"] == 0
    for rank, row in enumerate(report["rows"], 1):
        assert row["rank"] == rank
        assert row["percentile"] == 50
        assert row["composite"] == 0
        assert row["families"] == {"value": 0, "quality": 0, "momentum": 0}
        for metric in row["metrics"].values():
            assert metric["raw"] == metric["directed"] == metric["winsorized"]
            assert metric["mean"] == metric["raw"]
            assert metric["cohort_count"] == 10
            assert metric["sigma"] == metric["z"] == metric["clipped_z"] == 0
            assert metric["reason"] == "NO_DISPERSION"
    assert score(list(reversed(rows)), as_of=AS_OF) == report
    expected = Path(__file__).parents[1] / "fixtures/model-v1/expected.json"
    assert report == json.loads(expected.read_text())


def test_winsorization_matches_independent_population_calculation():
    import math

    rows = []
    for n in range(20):
        row = raw()
        row["instrument_id"] = f"00000000-0000-0000-0000-{n + 1:012d}"
        row["values"]["PRETAX_INCOME_TTM"] = n * 100 - 200
        rows.append(row)
    result = score(rows, as_of=AS_OF)
    values = [n * 100 / 12500 for n in range(20)]
    lower, upper = 0.475 * 100 / 12500, 18.525 * 100 / 12500
    clipped = [min(upper, max(lower, x)) for x in values]
    mean = sum(clipped) / 20
    sigma = math.sqrt(sum((x - mean) ** 2 for x in clipped) / 20)
    for row, value in zip(result["rows"], clipped, strict=True):
        metric = row["metrics"]["ebit_ev"]
        assert metric["lower"] == pytest.approx(lower)
        assert metric["upper"] == pytest.approx(upper)
        assert metric["sigma"] == pytest.approx(sigma)
        assert metric["z"] == pytest.approx((value - mean) / sigma)
        assert -3 <= metric["clipped_z"] <= 3


def test_missing_component_is_neutral_only_after_two_survive():
    _, rows = fixture_rows()
    row = rows[0]
    row["values"].pop("COMMON_EQUITY")
    # Removing equity also removes ROIC; the other two metrics remain usable.
    result = score(
        rows + [dict(copy.deepcopy(rows[1]), instrument_id="00000000-0000-0000-0000-999999999999")],
        as_of=AS_OF,
    )["rows"][0]
    assert result["eligible"]
    assert result["metrics"]["book_market"]["raw"] is None
    assert result["families"]["value"] == 0
    row["values"].pop("CAPEX_TTM")
    assert "INSUFFICIENT_VALUE_COVERAGE" in evaluate(row)["reasons"]


def test_negative_earnings_and_cash_flow_are_retained():
    row = raw()
    row["values"]["PRETAX_INCOME_TTM"] = -1000
    row["values"]["OPERATING_CASH_FLOW_TTM"] = -100
    result = evaluate(row)
    assert not result["reasons"]
    assert result["metrics"]["ebit_ev"]["raw"] < 0
    assert result["metrics"]["fcf_yield"]["raw"] < 0


@pytest.mark.parametrize(
    ("profile", "key", "value", "code", "warning"),
    [
        ("BANK", "CET1_CAPITAL", 600, "CET1_REQUIREMENT_BREACH", False),
        ("BANK", "CET1_CAPITAL", 750, "LOW_CET1_SURPLUS", True),
        ("PC_INSURER", "TOTAL_ADJUSTED_CAPITAL", 199, "RBC_REGULATORY_RISK", False),
        ("LIFE_INSURER", "TOTAL_ADJUSTED_CAPITAL", 250, "RBC_TREND_WARNING", True),
        ("PC_INSURER", "LOSSES_LAE_TTM", 800, "HIGH_COMBINED_RATIO", True),
        ("EQUITY_REIT", "NET_INCOME_TTM", -2000, "NON_POSITIVE_EBITDARE", False),
        ("EQUITY_REIT", "CASH_INTEREST_TTM", 1000, "REIT_INTEREST_COVERAGE_BREACH", False),
        ("EQUITY_REIT", "CASH_INTEREST_TTM", 700, "REIT_INTEREST_COVERAGE_WARNING", True),
        ("EQUITY_REIT", "DEBT_NONCURRENT", 20000, "REIT_LEVERAGE_BREACH", False),
        ("EQUITY_REIT", "DEBT_NONCURRENT", 11000, "REIT_LEVERAGE_WARNING", True),
        ("EQUITY_REIT", "DIVIDENDS_DECLARED_TTM", 800, "REIT_PAYOUT_WARNING", True),
    ],
)
def test_every_profile_risk_gate(profile, key, value, code, warning):
    row = raw(profile)
    row["values"][key] = value
    assert code in evaluate(row)["warnings" if warning else "reasons"]


def test_reit_two_period_payout_gate_and_reconciliation():
    row = raw("EQUITY_REIT")
    row["values"]["DIVIDENDS_DECLARED_TTM"] = 1000
    row["values"]["DIVIDENDS_DECLARED_PRIOR_TTM"] = 1000
    assert "REIT_PAYOUT_BREACH" in evaluate(row)["reasons"]
    row["reit_reconciled"] = False
    assert "MODEL_NOT_SUPPORTED" in evaluate(row)["reasons"]


@pytest.mark.parametrize(
    ("key", "value", "code"),
    [
        ("active", False, "INACTIVE_SECURITY"),
        ("in_universe", False, "NOT_IN_UNIVERSE"),
        ("primary_class", False, "NON_PRIMARY_SHARE_CLASS"),
        ("security_type", "ETF", "UNSUPPORTED_SECURITY"),
        ("history_count", 251, "INSUFFICIENT_HISTORY"),
        ("liquidity_count", 19, "INCOMPLETE_LIQUIDITY_WINDOW"),
        ("filing_date", "2025-01-01", "STALE"),
        ("profile_supported", False, "MODEL_NOT_SUPPORTED"),
    ],
)
def test_universal_gates(key, value, code):
    row = raw()
    row[key] = value
    assert code in evaluate(row)["reasons"]


def test_cutoffs_units_and_missing_provenance_cannot_leak():
    row = raw()
    row["provenance"]["REVENUE_TTM"]["available_at"] = "2026-10-01T00:00:00Z"
    result = evaluate(row)
    assert result["metrics"]["gross_profitability"]["raw"] is None
    assert result["input_reasons"]["REVENUE_TTM"] == "FUTURE_INFORMATION"
    row["provenance"]["RAW_CLOSE"]["unit"] = "EUR"
    assert "INVALID_MARKET_CAP" in evaluate(row)["reasons"]
    row["provenance"].pop("SHARES_OUTSTANDING")
    assert evaluate(row)["input_reasons"]["SHARES_OUTSTANDING"] == "FAILED_QUALITY_CHECK"


def test_insurer_requires_contiguous_twelve_quarters():
    row = raw("PC_INSURER")
    row["provenance"]["OPERATING_ROA_Q5"]["period_end"] = "2020-01-01"
    assert evaluate(row)["metrics"]["earnings_stability"]["raw"] is None


def test_peer_fallback_and_incompatible_profiles():
    _, all_rows = fixture_rows()
    rows = all_rows[:10]
    assert score(rows[:9], as_of=AS_OF)["status"] == "FAILED"
    for n in range(10):
        row = copy.deepcopy(rows[n])
        row["instrument_id"] = f"00000000-0000-0000-0000-{n + 50:012d}"
        rows.append(row)
    for n, row in enumerate(rows):
        row["peer_group"] = "GROUP" + str(n % 4)
    result = score(rows, as_of=AS_OF)
    assert result["eligible_count"] == 20
    assert all("PEER_FALLBACK:ebit_ev" in r["warnings"] for r in result["rows"])
    assert score(rows[:9] + all_rows[10:20], as_of=AS_OF)["eligible_count"] == 10


def test_duplicates_unknown_candidates_nan_and_output_rounding():
    row = raw()
    with pytest.raises(ValueError, match="Duplicate"):
        score([row, row], as_of=AS_OF)
    with pytest.raises(ValueError, match="candidate"):
        score([row], as_of=AS_OF, candidate="adaptive")
    row["values"]["RAW_CLOSE"] = float("nan")
    assert "INVALID_MARKET_CAP" in evaluate(row)["reasons"]
    assert serialize(1.2345665) == 1.234566
    assert percentile([0, 100], 0.025) == 2.5


def test_statutory_fallback_has_time_and_jurisdiction_bounds():
    row = raw()
    row["jurisdiction"] = "UNKNOWN"
    assert evaluate(row)["derived"]["normalized_tax_rate"] is None
    assert "STATUTORY_TAX_FALLBACK" in evaluate(raw())["warnings"][0]
    calculation = Calculation(raw(), timestamp(AS_OF))
    assert calculation.get("RAW_CLOSE") == 100


def test_one_percentage_point_cet1_boundary_has_no_warning():
    row = raw("BANK")
    row["values"]["CET1_CAPITAL"] = 800
    assert "LOW_CET1_SURPLUS" not in evaluate(row)["warnings"]


def test_clipping_and_nonzero_missing_component_fixed_denominator():
    rows = []
    for n in range(20):
        row = raw()
        row["instrument_id"] = f"00000000-0000-0000-0000-{n + 1:012d}"
        row["values"]["PRETAX_INCOME_TTM"] = 800 if n else 100000
        rows.append(row)
    rows[0]["values"].pop("COMMON_EQUITY")
    result = score(rows, as_of=AS_OF)
    first = result["rows"][0]
    assert first["eligible"]
    assert first["metrics"]["ebit_ev"]["z"] > 3
    assert first["metrics"]["ebit_ev"]["clipped_z"] == 3
    assert first["families"]["value"] == 1  # (3 + 0 + missing-as-zero) / 3, not / 2
    assert first["composite"] == 0.5
    for candidate, expected in [("pure_value", 1), ("value_quality", 0.6), ("balanced", 0.4)]:
        assert score(rows, as_of=AS_OF, candidate=candidate)["rows"][0]["composite"] == expected


def test_frozen_source_and_golden_artifacts_match():
    from quant_research.model.freeze import verify_freeze

    record = verify_freeze()
    assert record["final_historical_test_opened"] is False
    assert record["model_version"] == "1.1.0"


def test_regulatory_requirements_need_effective_dates_and_parent_scope():
    row = raw("BANK")
    row["provenance"]["CET1_REQUIREMENT_BASE"]["effective_to"] = "2026-09-01"
    assert "MISSING_CET1_REQUIREMENT" in evaluate(row)["reasons"]
    row["regulatory_scope"] = "SUBSIDIARY_ONLY"
    assert "MODEL_NOT_SUPPORTED" in evaluate(row)["reasons"]


def test_freeze_rejects_changed_implementation(tmp_path):
    import shutil

    from quant_research.model.freeze import verify_freeze

    root = Path(__file__).parents[2]
    record = verify_freeze()
    for name in [*record["files"], "research-engine/fixtures/model-v1/freeze.json"]:
        destination = tmp_path / name
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(root / name, destination)
    source = tmp_path / "research-engine/src/quant_research/model/engine.py"
    source.write_text(source.read_text() + "\n# changed\n")
    with pytest.raises(ValueError, match="Frozen artifact changed"):
        verify_freeze(tmp_path)


@pytest.mark.parametrize(
    "case",
    json.loads((Path(__file__).parents[1] / "fixtures/model-v1/edge-cases.json").read_text()),
)
def test_shared_edge_case_contract(case):
    row = raw(case["profile"])
    row.update(case.get("fields", {}))
    row["values"].update(case.get("values", {}))
    for key in case.get("remove_values", []):
        row["values"].pop(key, None)
    result = evaluate(row)
    assert set(case.get("expected_exclusions", [])) <= set(result["reasons"])
    assert set(case.get("expected_warnings", [])) <= set(result["warnings"])
    for key, expected in case.get("expected_derived", {}).items():
        assert result["derived"][key] == (pytest.approx(expected) if expected is not None else None)


def test_misaligned_current_balances_and_invalid_boolean_metadata():
    row = raw()
    row["provenance"]["TOTAL_ASSETS"]["period_end"] = "2025-06-30"
    assert evaluate(row)["metrics"]["gross_profitability"]["raw"] is None
    row["active"] = "false"
    with pytest.raises(ValueError, match="booleans"):
        score([row], as_of=AS_OF)


@pytest.mark.parametrize("key", ["RAW_CLOSE", "ADJUSTED_CLOSE"])
def test_prepared_inputs_require_a_score_date_bar(key):
    row = raw()
    row["provenance"][key]["period_end"] = "2026-09-29"
    result = evaluate(row)
    assert result["input_reasons"][key] == "MISSING_SCORE_DATE_BAR"
    assert result["reasons"]


def test_source_ids_must_be_a_list_not_a_string():
    row = raw()
    row["provenance"]["RAW_CLOSE"]["source_ids"] = "unstructured-source"
    assert evaluate(row)["input_reasons"]["RAW_CLOSE"] == "FAILED_QUALITY_CHECK"


def test_varied_golden_scores_and_exclusions():
    root = Path(__file__).parents[1] / "fixtures/model-v1"
    document = json.loads((root / "varied-prepared.json").read_text())
    expected = json.loads((root / "varied-expected.json").read_text())
    report = score(document["rows"], as_of=document["as_of"])
    assert serialize(report) == expected
    assert report["eligible_count"] >= 20
    assert report["excluded_count"] > 0
    assert len({r["composite"] for r in report["rows"] if r["eligible"]}) > 10
    assert score(list(reversed(document["rows"])), as_of=document["as_of"]) == report
    assert any("PEER_FALLBACK:ebit_ev" in r["warnings"] for r in report["rows"])
    assert any(
        m["reason"] == "NO_COMPATIBLE_COHORT" for r in report["rows"] for m in r["metrics"].values()
    )
