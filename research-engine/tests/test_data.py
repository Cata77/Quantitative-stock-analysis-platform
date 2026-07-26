from datetime import UTC, datetime, timedelta
from decimal import Decimal
from typing import Any

import pytest

from quant_research.data import LookAheadViolation, PointInTimeRepository


class RecordingExecutor:
    def __init__(self, rows: list[dict[str, Any]] | None = None) -> None:
        self.rows = rows or []
        self.query = ""
        self.parameters: dict[str, Any] = {}

    def __call__(self, query: str, parameters: dict[str, Any]) -> list[dict[str, Any]]:
        self.query = query
        self.parameters = parameters
        return self.rows


def test_factor_input_query_uses_publication_time_and_as_of_cutoffs() -> None:
    cutoff = datetime(2026, 7, 25, tzinfo=UTC)
    executor = RecordingExecutor(
        [
            {
                "time": cutoff,
                "symbol": "AAPL",
                "publication_time": cutoff - timedelta(days=10),
                "latest_price_time": cutoff - timedelta(hours=1),
                "reference_price_time": cutoff - timedelta(days=366),
                "pe_ratio": Decimal("20"),
                "return_on_equity_ttm": Decimal("0.25"),
                "latest_close": Decimal("210"),
                "reference_close": Decimal("175"),
            }
        ]
    )
    repository = PointInTimeRepository(executor)

    result = repository.load_factor_inputs_as_of(
        cutoff,
        lookback_days=365,
        symbols=["msft", "AAPL", "aapl"],
    )

    assert result.height == 1
    assert "f.time <= cutoff.as_of_date" in executor.query
    assert "bar.time <= cutoff.as_of_date" in executor.query
    assert "latest_quarter" not in executor.query
    assert executor.parameters["as_of_date"] == cutoff
    assert executor.parameters["symbols"] == ["AAPL", "MSFT"]


def test_historical_windows_cannot_extend_beyond_as_of_date() -> None:
    repository = PointInTimeRepository(RecordingExecutor())
    cutoff = datetime(2026, 7, 25, tzinfo=UTC)

    with pytest.raises(LookAheadViolation):
        repository.load_market_bars(
            cutoff - timedelta(days=10),
            cutoff + timedelta(seconds=1),
            as_of_date=cutoff,
        )


def test_all_query_timestamps_must_be_timezone_aware() -> None:
    repository = PointInTimeRepository(RecordingExecutor())

    with pytest.raises(ValueError, match="timezone-aware"):
        repository.load_factor_inputs_as_of(datetime(2026, 7, 25))


def test_factor_score_query_keeps_an_explicit_research_cutoff() -> None:
    executor = RecordingExecutor()
    repository = PointInTimeRepository(executor)
    cutoff = datetime(2026, 7, 25, tzinfo=UTC)

    repository.load_factor_scores(
        cutoff - timedelta(days=30),
        cutoff,
        as_of_date=cutoff,
    )

    assert "score.time <= cutoff.as_of_date" in executor.query
    assert "%(as_of_date)s::timestamptz" in executor.query
