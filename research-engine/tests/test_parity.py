from datetime import UTC, datetime

import polars as pl

from quant_research.parity import compare_formula_outputs


def test_accepts_production_rounding_to_six_decimal_places() -> None:
    time = datetime(2026, 7, 25, tzinfo=UTC)
    research = pl.DataFrame(
        {
            "time": [time],
            "symbol": ["AAPL"],
            "z_value": [1.12345649],
            "z_momentum": [0.5],
            "z_quality": [-0.25],
            "composite_score": [0.45781883],
        }
    )
    production = pl.DataFrame(
        {
            "time": [time],
            "symbol": ["AAPL"],
            "z_value": [1.123456],
            "z_momentum": [0.500000],
            "z_quality": [-0.250000],
            "composite_score": [0.457819],
        }
    )

    report = compare_formula_outputs(research, production)

    assert report.matched
    assert report.compared_rows == 1
    assert report.maximum_absolute_error <= report.tolerance


def test_detects_missing_or_divergent_production_rows() -> None:
    research = pl.DataFrame(
        {
            "time": [1],
            "symbol": ["AAPL"],
            "z_value": [1.0],
            "z_momentum": [1.0],
            "z_quality": [1.0],
            "composite_score": [1.0],
        }
    )
    production = research.with_columns(pl.lit(2.0).alias("composite_score"))

    assert not compare_formula_outputs(research, production).matched
