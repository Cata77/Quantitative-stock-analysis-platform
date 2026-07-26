from datetime import UTC, datetime, timedelta

import pandas as pd
import polars as pl
import pytest

from quant_research.backtest import (
    BacktestCosts,
    build_next_bar_target_orders,
    run_ranked_long_only_backtest,
)


def test_score_is_executed_only_on_the_next_available_bar() -> None:
    score_time = pd.Timestamp("2026-07-25T00:05:00Z")
    close = pd.DataFrame(
        {"AAPL": [100.0, 101.0], "MSFT": [200.0, 202.0]},
        index=pd.to_datetime(["2026-07-25T20:00:00Z", "2026-07-26T20:00:00Z"], utc=True),
    )
    scores = pd.DataFrame(
        {"AAPL": [2.0], "MSFT": [1.0]},
        index=pd.DatetimeIndex([score_time]),
    )

    orders = build_next_bar_target_orders(close, scores, top_n=1)

    assert orders.loc[pd.Timestamp("2026-07-25T20:00:00Z"), "AAPL"] == 1.0
    assert orders.loc[pd.Timestamp("2026-07-25T20:00:00Z"), "MSFT"] == 0.0
    assert orders.loc[:score_time].dropna(how="all").empty


def test_vectorbt_backtest_applies_plan_compliant_costs() -> None:
    start = datetime(2026, 7, 21, 20, tzinfo=UTC)
    times = [start + timedelta(days=day) for day in range(5)]
    prices = pl.DataFrame(
        {
            "time": [time for time in times for _ in range(2)],
            "symbol": ["AAPL", "MSFT"] * len(times),
            "close_price": [value for day in range(5) for value in (100.0 + day * 2, 200.0 - day)],
        }
    )
    scores = pl.DataFrame(
        {
            "time": [times[0] - timedelta(hours=1), times[2] - timedelta(hours=1)],
            "symbol": ["AAPL", "MSFT"],
            "composite_score": [2.0, 2.0],
        }
    )

    result = run_ranked_long_only_backtest(
        prices,
        scores,
        top_n=1,
        costs=BacktestCosts(fees_bps=2.5, slippage_bps=5.0),
    )

    assert result.costs.total_bps == 7.5
    assert result.portfolio.orders.count() >= 1
    assert result.summary()["total_cost_bps"] == 7.5


def test_rejects_backtests_without_the_required_cost_penalty() -> None:
    with pytest.raises(ValueError, match="between 5 and 10"):
        BacktestCosts(fees_bps=0, slippage_bps=0)
