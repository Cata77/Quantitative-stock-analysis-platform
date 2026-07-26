from datetime import UTC, datetime

import polars as pl
import pytest

from quant_research.factors import (
    calculate_factor_scores,
    calculate_iv_rank,
    cross_sectional_z_score,
)


def test_phase_four_scores_match_population_z_score_and_composite() -> None:
    time = datetime(2026, 7, 25, tzinfo=UTC)
    inputs = pl.DataFrame(
        {
            "time": [time, time, time],
            "symbol": ["AAA", "BBB", "CCC"],
            "pe_ratio": [10.0, 5.0, 10.0 / 3.0],
            "latest_close": [110.0, 120.0, 130.0],
            "reference_close": [100.0, 100.0, 100.0],
            "return_on_equity_ttm": [0.1, 0.2, 0.3],
        }
    )

    scores = calculate_factor_scores(inputs)

    assert scores.get_column("z_value").to_list() == pytest.approx([-1.224744871, 0.0, 1.224744871])
    assert scores.get_column("z_momentum").to_list() == pytest.approx(
        [-1.224744871, 0.0, 1.224744871]
    )
    assert scores.get_column("z_quality").to_list() == pytest.approx(
        [-1.224744871, 0.0, 1.224744871]
    )
    assert scores.get_column("composite_score").to_list() == pytest.approx(
        [-1.224744871, 0.0, 1.224744871]
    )


def test_zero_variance_has_the_same_deterministic_rule_as_java() -> None:
    frame = pl.DataFrame({"time": [1, 1], "raw": [5.0, 5.0]})

    result = cross_sectional_z_score(frame, "raw", "z")

    assert result.get_column("z").to_list() == [0.0, 0.0]


def test_iv_rank_uses_the_exact_phase_four_equation() -> None:
    assert calculate_iv_rank(0.25, 0.10, 0.30) == pytest.approx(75.0)
    assert calculate_iv_rank(0.20, 0.20, 0.20) is None
    with pytest.raises(ValueError):
        calculate_iv_rank(-0.01, 0.10, 0.30)
