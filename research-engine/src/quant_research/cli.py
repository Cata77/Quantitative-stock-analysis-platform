from __future__ import annotations

import argparse
import json
from collections.abc import Sequence
from dataclasses import asdict, is_dataclass
from datetime import datetime
from typing import Any

import numpy as np
import polars as pl

from quant_research.backtest import BacktestCosts, run_ranked_long_only_backtest
from quant_research.config import ResearchSettings
from quant_research.data import PointInTimeRepository
from quant_research.diagnostics import newey_west_regression, svd_factor_diagnostics
from quant_research.factors import calculate_factor_scores
from quant_research.parity import compare_formula_outputs
from quant_research.regimes import fit_market_regimes


def main(argv: Sequence[str] | None = None) -> int:
    parser = _parser()
    arguments = parser.parse_args(argv)
    if arguments.command == "regression":
        report = _regression(arguments)
    else:
        settings = ResearchSettings.from_environment()
        repository = PointInTimeRepository.from_dsn(settings.database_dsn)
        report = _database_command(arguments, settings, repository)
    print(json.dumps(report, default=_json_default, indent=2, sort_keys=True))
    return 0


def _database_command(
    arguments: argparse.Namespace,
    settings: ResearchSettings,
    repository: PointInTimeRepository,
) -> Any:
    as_of = _timestamp(arguments.as_of)
    if arguments.command == "formula-check":
        production = repository.load_latest_factor_scores_as_of(as_of)
        if production.is_empty():
            raise RuntimeError("no production score batch exists at or before as_of")
        score_time = production.get_column("time").first()
        inputs = repository.load_factor_inputs_as_of(
            score_time,
            lookback_days=arguments.lookback_days,
        )
        research = calculate_factor_scores(inputs)
        return compare_formula_outputs(research, production)

    start = _timestamp(arguments.start)
    end = _timestamp(arguments.end)
    if arguments.command == "diagnostics":
        scores = repository.load_factor_scores(
            start,
            end,
            as_of_date=as_of,
            symbols=arguments.symbols,
        )
        return svd_factor_diagnostics(scores)
    if arguments.command == "backtest":
        scores = repository.load_factor_scores(
            start,
            end,
            as_of_date=as_of,
            symbols=arguments.symbols,
        )
        bars = repository.load_market_bars(
            start,
            end,
            as_of_date=as_of,
            symbols=arguments.symbols,
        )
        result = run_ranked_long_only_backtest(
            bars,
            scores,
            top_n=arguments.top_n,
            costs=BacktestCosts(settings.fees_bps, settings.slippage_bps),
            initial_cash=arguments.initial_cash,
        )
        return result.summary()
    if arguments.command == "regimes":
        symbol = arguments.symbol.upper()
        symbol_bars = repository.load_market_bars(
            start,
            end,
            as_of_date=as_of,
            symbols=[symbol],
        ).sort("time")
        returns = (
            symbol_bars.select(pl.col("close_price").cast(pl.Float64).pct_change().alias("returns"))
            .drop_nulls()
            .get_column("returns")
            .to_list()
        )
        return fit_market_regimes(
            returns,
            regime_count=arguments.regime_count,
        )
    raise AssertionError(f"unsupported command: {arguments.command}")


def _regression(arguments: argparse.Namespace) -> Any:
    frame = pl.read_csv(arguments.input)
    factor_columns = tuple(
        column.strip() for column in arguments.factors.split(",") if column.strip()
    )
    missing = sorted({arguments.excess_return, *factor_columns} - set(frame.columns))
    if missing:
        raise ValueError(f"input is missing columns: {', '.join(missing)}")
    return newey_west_regression(
        frame.get_column(arguments.excess_return).to_list(),
        {column: frame.get_column(column).to_list() for column in factor_columns},
        max_lags=arguments.max_lags,
    )


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="quant-research",
        description="Point-in-time quantitative research validation",
    )
    subparsers = parser.add_subparsers(dest="command", required=True)

    formula = subparsers.add_parser(
        "formula-check",
        help="recompute the latest production score batch and verify parity",
    )
    formula.add_argument("--as-of", required=True)
    formula.add_argument("--lookback-days", type=int, default=365)

    diagnostics = subparsers.add_parser(
        "diagnostics",
        help="run SVD factor-diversification diagnostics",
    )
    _add_window(diagnostics)

    backtest = subparsers.add_parser(
        "backtest",
        help="run a next-bar, transaction-cost-aware vectorbt simulation",
    )
    _add_window(backtest)
    backtest.add_argument("--top-n", type=int, default=10)
    backtest.add_argument("--initial-cash", type=float, default=100_000.0)

    regimes = subparsers.add_parser(
        "regimes",
        help="fit Gaussian hidden-Markov regimes to one symbol",
    )
    _add_window(regimes)
    regimes.add_argument("--symbol", required=True)
    regimes.add_argument("--regime-count", type=int, default=2)

    regression = subparsers.add_parser(
        "regression",
        help="run an OLS regression with Newey-West HAC errors from CSV",
    )
    regression.add_argument("--input", required=True)
    regression.add_argument("--excess-return", default="excess_return")
    regression.add_argument(
        "--factors",
        default="value_return,momentum_return,quality_return",
    )
    regression.add_argument("--max-lags", type=int)
    return parser


def _add_window(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--start", required=True)
    parser.add_argument("--end", required=True)
    parser.add_argument("--as-of", required=True)
    parser.add_argument("--symbols", nargs="*")


def _timestamp(raw: str) -> datetime:
    value = raw[:-1] + "+00:00" if raw.endswith("Z") else raw
    parsed = datetime.fromisoformat(value)
    if parsed.tzinfo is None or parsed.utcoffset() is None:
        raise ValueError("timestamps must include a timezone")
    return parsed


def _json_default(value: Any) -> Any:
    if is_dataclass(value):
        return asdict(value)
    if isinstance(value, np.ndarray):
        return value.tolist()
    if isinstance(value, np.generic):
        return value.item()
    if isinstance(value, datetime):
        return value.isoformat()
    raise TypeError(f"{type(value).__name__} is not JSON serializable")


if __name__ == "__main__":
    raise SystemExit(main())
