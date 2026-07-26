from __future__ import annotations

from collections.abc import Mapping, Sequence
from dataclasses import dataclass

import numpy as np
import polars as pl
import statsmodels.api as sm


@dataclass(frozen=True)
class FactorDiversificationReport:
    factor_names: tuple[str, ...]
    correlation_matrix: np.ndarray
    singular_values: np.ndarray
    condition_number: float
    effective_rank: float
    maximum_pairwise_correlation: float
    passes_thresholds: bool


@dataclass(frozen=True)
class NeweyWestRegressionReport:
    coefficients: Mapping[str, float]
    standard_errors: Mapping[str, float]
    t_statistics: Mapping[str, float]
    p_values: Mapping[str, float]
    r_squared: float
    observations: int
    max_lags: int


def svd_factor_diagnostics(
    frame: pl.DataFrame,
    factor_columns: Sequence[str] = ("z_value", "z_momentum", "z_quality"),
    *,
    maximum_correlation: float = 0.80,
    maximum_condition_number: float = 10.0,
) -> FactorDiversificationReport:
    """Diagnose factor redundancy using SVD of the factor correlation matrix."""
    names = tuple(factor_columns)
    if len(names) < 2:
        raise ValueError("at least two factor columns are required")
    missing = sorted(set(names) - set(frame.columns))
    if missing:
        raise ValueError(f"missing factor columns: {', '.join(missing)}")
    matrix = frame.select(names).drop_nulls().to_numpy().astype(float)
    if matrix.shape[0] < len(names) + 1:
        raise ValueError("insufficient complete rows for factor diagnostics")
    standard_deviations = np.std(matrix, axis=0, ddof=0)
    if np.any(standard_deviations == 0):
        raise ValueError("factor diagnostics require non-zero variance")

    correlation = np.corrcoef(matrix, rowvar=False)
    _, singular_values, _ = np.linalg.svd(correlation, full_matrices=False)
    smallest = float(singular_values[-1])
    largest = float(singular_values[0])
    condition_number = float("inf") if smallest == 0 else largest / smallest
    weights = singular_values / singular_values.sum()
    effective_rank = float(np.exp(-np.sum(weights * np.log(weights))))
    off_diagonal = np.abs(correlation - np.eye(len(names)))
    maximum_pairwise = float(np.max(off_diagonal))
    return FactorDiversificationReport(
        factor_names=names,
        correlation_matrix=correlation,
        singular_values=singular_values,
        condition_number=condition_number,
        effective_rank=effective_rank,
        maximum_pairwise_correlation=maximum_pairwise,
        passes_thresholds=(
            maximum_pairwise <= maximum_correlation and condition_number <= maximum_condition_number
        ),
    )


def newey_west_regression(
    excess_returns: Sequence[float],
    factor_returns: Mapping[str, Sequence[float]],
    *,
    max_lags: int | None = None,
) -> NeweyWestRegressionReport:
    """Fit OLS and report heteroskedasticity/autocorrelation-consistent errors."""
    if not factor_returns:
        raise ValueError("at least one factor return series is required")
    names = tuple(factor_returns)
    y = np.asarray(excess_returns, dtype=float)
    columns = [np.asarray(factor_returns[name], dtype=float) for name in names]
    if any(len(column) != len(y) for column in columns):
        raise ValueError("all return series must have equal length")
    matrix = np.column_stack(columns)
    complete = np.isfinite(y) & np.all(np.isfinite(matrix), axis=1)
    y = y[complete]
    matrix = matrix[complete]
    if len(y) <= len(names) + 2:
        raise ValueError("insufficient complete observations for regression")

    lags = int(np.floor(4 * (len(y) / 100) ** (2 / 9))) if max_lags is None else max_lags
    if lags < 0 or lags >= len(y):
        raise ValueError("max_lags must be between zero and observations - 1")
    design = sm.add_constant(matrix, has_constant="add")
    result = sm.OLS(y, design).fit(cov_type="HAC", cov_kwds={"maxlags": lags})
    parameter_names = ("alpha", *names)
    return NeweyWestRegressionReport(
        coefficients=dict(zip(parameter_names, result.params, strict=True)),
        standard_errors=dict(zip(parameter_names, result.bse, strict=True)),
        t_statistics=dict(zip(parameter_names, result.tvalues, strict=True)),
        p_values=dict(zip(parameter_names, result.pvalues, strict=True)),
        r_squared=float(result.rsquared),
        observations=int(result.nobs),
        max_lags=lags,
    )
