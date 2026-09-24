from pathlib import Path

import pandas as pd
import pytest

from quant_research.validation import (
    chronological_partitions,
    evaluate,
    portfolio_series,
    validate_panel,
    walk_forward_windows,
)


def panel():
    dates = pd.date_range("2010-01-31", periods=120, freq="ME", tz="UTC")
    rows = []
    for i, date in enumerate(dates):
        for stock in range(25):
            rows.append({"signal_at": date, "knowledge_at": date,
                         "execution_at": date + pd.Timedelta(days=1),
                         "next_session_open": date + pd.Timedelta(days=1),
                         "exit_at": date + pd.offsets.MonthEnd(1) + pd.Timedelta(days=1),
                         "forward_end": date + pd.offsets.MonthEnd(6) + pd.Timedelta(days=1),
                         "instrument_id": f"id-{stock:02}", "sector": str(stock % 3),
                         "profile": "GENERAL", "score": stock, "market_cap": 100 + stock,
                         "expected_count": 25, "total_return": stock / 1000 + (i % 3) / 1000,
                         "forward_6m": stock / 100,})
    return pd.DataFrame(rows)


def evidence():
    sha = (Path(__file__).parents[1] / "src/quant_research/model/manifest.sha256").read_text()
    return {"model_manifest_sha256": sha.strip(), "candidate": "primary",
            "synthetic": True, "universe_mode": "CURRENT_CONSTITUENTS_BACKTEST"}


def test_reports_all_costs_views_and_never_certifies_synthetic_history():
    result = evaluate(panel(), evidence())
    assert set(result["performance_by_cost_bps_per_side"]) == {"5", "10", "25", "50"}
    assert not result["production_ready"]
    assert not result["final_test_opened"]
    assert result["rank_ic_6m"]["observations"] < 24  # six-month boundary purging
    assert any("synthetic" in gate for gate in result["unmet_evidence_gates"])


@pytest.mark.parametrize("column", ["knowledge_at", "execution_at", "next_session_open"])
def test_rejects_future_knowledge_and_same_bar_or_wrong_session_execution(column):
    data = panel()
    data.loc[0, column] = data.loc[0, "signal_at"] + pd.Timedelta(days=10)
    with pytest.raises(ValueError):
        validate_panel(data)


def test_final_test_requires_explicit_opening_and_frozen_manifest():
    with pytest.raises(ValueError, match="sealed"):
        evaluate(panel(), evidence(), partition="final")
    with pytest.raises(ValueError, match="frozen model"):
        evaluate(panel(), {**evidence(), "model_manifest_sha256": "wrong"})


def test_splits_and_walk_forward_keep_training_before_embargo():
    data = panel()
    splits = chronological_partitions(data.signal_at.tolist())
    assert [len(splits[k]) for k in ("development", "validation", "final")] == [72, 24, 24]
    for window in walk_forward_windows(splits["development"] + splits["validation"]):
        assert pd.Timestamp(window["train_end"]) < (
            pd.Timestamp(window["test_start"]) - pd.DateOffset(months=6))


def test_costs_penalize_trades_and_missing_months_and_duplicates_are_rejected():
    data = panel()
    assert sum(portfolio_series(data, "top20", 50)) < sum(portfolio_series(data, "top20", 5))
    with pytest.raises(ValueError, match="duplicate"):
        validate_panel(pd.concat([data, data.iloc[:1]]))
    with pytest.raises(ValueError, match="contiguous"):
        validate_panel(data[data.signal_at != data.signal_at.unique()[1]])


def test_validation_does_not_inspect_sealed_final_outcomes():
    data = panel()
    splits = chronological_partitions(data.signal_at.tolist())
    data.loc[data.signal_at.isin(splits["final"]), "total_return"] = float("nan")
    assert not evaluate(data, evidence())["final_test_opened"]


@pytest.mark.parametrize("column", ["signal_at", "knowledge_at", "execution_at",
                                    "next_session_open", "exit_at", "forward_end"])
def test_rejects_naive_timestamps(column):
    data = panel()
    data[column] = data[column].dt.tz_localize(None)
    with pytest.raises(ValueError, match="timezone-aware"):
        validate_panel(data)


def test_evaluation_does_not_normalize_naive_signals_before_validation():
    data = panel()
    data["signal_at"] = data.signal_at.dt.tz_localize(None)
    with pytest.raises(ValueError, match="timezone-aware"):
        evaluate(data, evidence())


def test_rejects_impossible_forward_returns_and_nonfinite_expected_membership():
    data = panel()
    data.loc[0, "forward_6m"] = -1.01
    with pytest.raises(ValueError, match="total return"):
        validate_panel(data)
    data = panel()
    data["expected_count"] = float("inf")
    with pytest.raises(ValueError, match="expected universe"):
        validate_panel(data)
