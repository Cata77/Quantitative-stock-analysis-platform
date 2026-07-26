from __future__ import annotations

from dataclasses import dataclass

import polars as pl

SCORE_COLUMNS = ("z_value", "z_momentum", "z_quality", "composite_score")


@dataclass(frozen=True)
class FormulaParityReport:
    matched: bool
    compared_rows: int
    missing_research_rows: int
    missing_production_rows: int
    maximum_absolute_error: float
    tolerance: float


def compare_formula_outputs(
    research: pl.DataFrame,
    production: pl.DataFrame,
    *,
    tolerance: float = 0.0000005,
) -> FormulaParityReport:
    """Compare unrounded research values with six-decimal production scores."""
    if tolerance <= 0:
        raise ValueError("tolerance must be positive")
    keys = ("time", "symbol")
    _require_columns(research, (*keys, *SCORE_COLUMNS))
    _require_columns(production, (*keys, *SCORE_COLUMNS))

    research_keys = research.select(keys)
    production_keys = production.select(keys)
    missing_research = production_keys.join(research_keys, on=list(keys), how="anti").height
    missing_production = research_keys.join(production_keys, on=list(keys), how="anti").height
    joined = research.select((*keys, *SCORE_COLUMNS)).join(
        production.select((*keys, *SCORE_COLUMNS)),
        on=list(keys),
        how="inner",
        suffix="_production",
    )
    if joined.is_empty():
        maximum_error = float("inf") if (research.height or production.height) else 0.0
    else:
        error_columns = [
            (pl.col(column).cast(pl.Float64) - pl.col(f"{column}_production").cast(pl.Float64))
            .abs()
            .alias(f"{column}_error")
            for column in SCORE_COLUMNS
        ]
        maximum_error = (
            joined.select(error_columns).select(pl.max_horizontal(pl.all()).max()).item()
        )
        maximum_error = float(maximum_error or 0.0)
    return FormulaParityReport(
        matched=(missing_research == 0 and missing_production == 0 and maximum_error <= tolerance),
        compared_rows=joined.height,
        missing_research_rows=missing_research,
        missing_production_rows=missing_production,
        maximum_absolute_error=maximum_error,
        tolerance=tolerance,
    )


def _require_columns(frame: pl.DataFrame, columns: tuple[str, ...]) -> None:
    missing = sorted(set(columns) - set(frame.columns))
    if missing:
        raise ValueError(f"missing required columns: {', '.join(missing)}")
