import numpy as np
import polars as pl
import pytest

from quant_research.diagnostics import (
    newey_west_regression,
    svd_factor_diagnostics,
)


def test_svd_report_accepts_diversified_factors() -> None:
    random = np.random.default_rng(7)
    frame = pl.DataFrame(
        {
            "z_value": random.normal(size=500),
            "z_momentum": random.normal(size=500),
            "z_quality": random.normal(size=500),
        }
    )

    report = svd_factor_diagnostics(frame)

    assert report.passes_thresholds
    assert report.effective_rank > 2.5
    assert report.maximum_pairwise_correlation < 0.2


def test_svd_report_rejects_redundant_factors() -> None:
    base = np.linspace(-1, 1, 200)
    frame = pl.DataFrame(
        {
            "z_value": base,
            "z_momentum": base * 2,
            "z_quality": np.sin(base),
        }
    )

    report = svd_factor_diagnostics(frame)

    assert not report.passes_thresholds
    assert report.maximum_pairwise_correlation > 0.99


def test_newey_west_regression_recovers_factor_exposure() -> None:
    random = np.random.default_rng(21)
    factor = random.normal(0, 0.01, 600)
    noise = random.normal(0, 0.002, 600)
    excess = 0.0005 + 1.5 * factor + noise

    report = newey_west_regression(excess, {"market": factor})

    assert report.coefficients["market"] == pytest.approx(1.5, abs=0.03)
    assert report.coefficients["alpha"] == pytest.approx(0.0005, abs=0.0003)
    assert report.standard_errors["market"] > 0
    assert report.observations == 600
