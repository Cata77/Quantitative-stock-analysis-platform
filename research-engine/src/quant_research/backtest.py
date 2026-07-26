from __future__ import annotations

from dataclasses import dataclass
from typing import Any

import numpy as np
import pandas as pd
import polars as pl
import vectorbt as vbt


@dataclass(frozen=True)
class BacktestCosts:
    fees_bps: float = 2.5
    slippage_bps: float = 5.0

    def __post_init__(self) -> None:
        total = self.fees_bps + self.slippage_bps
        if not 5.0 <= total <= 10.0:
            raise ValueError("total transaction cost must be between 5 and 10 bps")

    @property
    def total_bps(self) -> float:
        return self.fees_bps + self.slippage_bps


@dataclass(frozen=True)
class BacktestResult:
    portfolio: Any
    target_orders: pd.DataFrame
    costs: BacktestCosts

    def summary(self) -> dict[str, float]:
        return {
            "total_return": _scalar(self.portfolio.total_return()),
            "sharpe_ratio": _scalar(self.portfolio.sharpe_ratio()),
            "maximum_drawdown": _scalar(self.portfolio.max_drawdown()),
            "total_cost_bps": self.costs.total_bps,
        }


def run_ranked_long_only_backtest(
    prices: pl.DataFrame,
    scores: pl.DataFrame,
    *,
    top_n: int = 10,
    costs: BacktestCosts | None = None,
    initial_cash: float = 100_000.0,
    frequency: str = "1D",
) -> BacktestResult:
    """Execute equal-weight score portfolios on the next available price bar."""
    if top_n < 1:
        raise ValueError("top_n must be positive")
    if initial_cash <= 0:
        raise ValueError("initial_cash must be positive")
    costs = costs or BacktestCosts()
    close = _pivot(prices, "close_price")
    composite = _pivot(scores, "composite_score")
    common_symbols = sorted(set(close.columns) & set(composite.columns))
    if not common_symbols:
        raise ValueError("prices and scores have no common symbols")
    close = close.loc[:, common_symbols]
    composite = composite.reindex(columns=common_symbols)
    orders = build_next_bar_target_orders(close, composite, top_n=top_n)
    portfolio = vbt.Portfolio.from_orders(
        close=close,
        size=orders,
        size_type="targetpercent",
        direction="longonly",
        init_cash=initial_cash,
        fees=costs.fees_bps / 10_000.0,
        slippage=costs.slippage_bps / 10_000.0,
        cash_sharing=True,
        group_by=True,
        call_seq="auto",
        freq=frequency,
    )
    return BacktestResult(portfolio=portfolio, target_orders=orders, costs=costs)


def build_next_bar_target_orders(
    close: pd.DataFrame,
    scores: pd.DataFrame,
    *,
    top_n: int,
) -> pd.DataFrame:
    """Map every score observation to the first strictly later price timestamp."""
    if top_n < 1:
        raise ValueError("top_n must be positive")
    if close.empty or scores.empty:
        raise ValueError("close and scores must not be empty")
    if not close.index.is_monotonic_increasing:
        close = close.sort_index()
    if not scores.index.is_monotonic_increasing:
        scores = scores.sort_index()
    target_orders = pd.DataFrame(np.nan, index=close.index, columns=close.columns)

    for score_time, row in scores.iterrows():
        execution_times = close.index[close.index > score_time]
        if execution_times.empty:
            continue
        eligible = row.reindex(close.columns).dropna().sort_values(ascending=False, kind="stable")
        selected = eligible.head(top_n)
        if selected.empty:
            continue
        execution_time = execution_times[0]
        target_orders.loc[execution_time, :] = 0.0
        target_orders.loc[execution_time, selected.index] = 1.0 / len(selected)
    return target_orders


def _pivot(frame: pl.DataFrame, value_column: str) -> pd.DataFrame:
    required = {"time", "symbol", value_column}
    missing = sorted(required - set(frame.columns))
    if missing:
        raise ValueError(f"missing required columns: {', '.join(missing)}")
    selected = frame.select("time", "symbol", value_column)
    has_duplicate_keys = selected.select(pl.struct("time", "symbol").is_duplicated().any()).item()
    if has_duplicate_keys:
        raise ValueError("time/symbol observations must be unique")
    pandas_frame = pd.DataFrame(selected.to_dicts())
    pandas_frame["time"] = pd.to_datetime(pandas_frame["time"], utc=True)
    return (
        pandas_frame.pivot(index="time", columns="symbol", values=value_column)
        .sort_index()
        .astype(float)
    )


def _scalar(value: Any) -> float:
    if hasattr(value, "item"):
        return float(value.item())
    return float(value)
