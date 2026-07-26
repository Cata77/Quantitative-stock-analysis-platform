from __future__ import annotations

from collections.abc import Sequence

import polars as pl

RAW_FACTOR_COLUMNS = (
    "pe_ratio",
    "latest_close",
    "reference_close",
    "return_on_equity_ttm",
)


def cross_sectional_z_score(
    frame: pl.DataFrame,
    metric: str,
    output: str,
    *,
    group_column: str = "time",
) -> pl.DataFrame:
    """Apply ``Z = (X - mean) / population sigma`` within each time slice."""
    _require_columns(frame, (group_column, metric))
    value = pl.col(metric).cast(pl.Float64)
    mean = value.mean().over(group_column)
    sigma = value.std(ddof=0).over(group_column)
    z_score = (
        pl.when(value.is_null())
        .then(None)
        .when(sigma.is_null() | (sigma == 0))
        .then(0.0)
        .otherwise((value - mean) / sigma)
        .alias(output)
    )
    return frame.with_columns(z_score)


def calculate_factor_scores(
    factor_inputs: pl.DataFrame,
    *,
    group_column: str = "time",
) -> pl.DataFrame:
    """Mirror the Java scoring formula without rounding intermediate values."""
    _require_columns(factor_inputs, (group_column, "symbol", *RAW_FACTOR_COLUMNS))
    eligible = factor_inputs.filter(
        pl.all_horizontal(
            pl.col("pe_ratio").is_not_null(),
            pl.col("latest_close").is_not_null(),
            pl.col("reference_close").is_not_null(),
            pl.col("return_on_equity_ttm").is_not_null(),
            pl.col("pe_ratio") > 0,
            pl.col("latest_close") > 0,
            pl.col("reference_close") > 0,
        )
    ).with_columns(
        (1.0 / pl.col("pe_ratio").cast(pl.Float64)).alias("raw_value"),
        (
            pl.col("latest_close").cast(pl.Float64) / pl.col("reference_close").cast(pl.Float64)
            - 1.0
        ).alias("raw_momentum"),
        pl.col("return_on_equity_ttm").cast(pl.Float64).alias("raw_quality"),
    )
    scored = cross_sectional_z_score(eligible, "raw_value", "z_value", group_column=group_column)
    scored = cross_sectional_z_score(
        scored, "raw_momentum", "z_momentum", group_column=group_column
    )
    scored = cross_sectional_z_score(scored, "raw_quality", "z_quality", group_column=group_column)
    return scored.with_columns(
        ((pl.col("z_value") + pl.col("z_momentum") + pl.col("z_quality")) / 3.0).alias(
            "composite_score"
        )
    ).sort([group_column, "symbol"])


def calculate_iv_rank(
    current_iv: float,
    minimum_52_week_iv: float,
    maximum_52_week_iv: float,
) -> float | None:
    """Apply the Phase 4 IVR equation; return None for an undefined range."""
    if min(current_iv, minimum_52_week_iv, maximum_52_week_iv) < 0:
        raise ValueError("implied volatility values must not be negative")
    if maximum_52_week_iv < minimum_52_week_iv:
        raise ValueError("maximum_52_week_iv must not be below minimum_52_week_iv")
    if maximum_52_week_iv == minimum_52_week_iv:
        return None
    return (current_iv - minimum_52_week_iv) / (maximum_52_week_iv - minimum_52_week_iv) * 100.0


def _require_columns(frame: pl.DataFrame, columns: Sequence[str]) -> None:
    missing = sorted(set(columns) - set(frame.columns))
    if missing:
        raise ValueError(f"missing required columns: {', '.join(missing)}")
