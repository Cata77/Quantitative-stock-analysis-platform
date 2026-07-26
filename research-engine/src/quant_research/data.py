from __future__ import annotations

import re
from collections.abc import Mapping, Sequence
from datetime import UTC, datetime
from typing import Any, Protocol

import polars as pl
import psycopg
from psycopg.rows import dict_row

SYMBOL_PATTERN = re.compile(r"^[A-Z0-9./-]{1,10}$")


class LookAheadViolation(ValueError):
    """Raised when a query could observe information after its research cutoff."""


class QueryExecutor(Protocol):
    def __call__(
        self,
        query: str,
        parameters: Mapping[str, Any],
    ) -> Sequence[Mapping[str, Any]]: ...


class PsycopgReadOnlyExecutor:
    def __init__(self, dsn: str) -> None:
        if not dsn.strip():
            raise ValueError("dsn must not be blank")
        self._dsn = dsn

    def __call__(
        self,
        query: str,
        parameters: Mapping[str, Any],
    ) -> Sequence[Mapping[str, Any]]:
        with psycopg.connect(self._dsn, row_factory=dict_row) as connection:
            connection.read_only = True
            with connection.cursor() as cursor:
                cursor.execute(query, dict(parameters))
                return cursor.fetchall()


class PointInTimeRepository:
    """The only database boundary exposed to research workflows.

    Every public read requires an explicit timezone-aware ``as_of_date`` and
    every SQL statement compares source observation time against that cutoff.
    """

    def __init__(self, executor: QueryExecutor) -> None:
        self._execute = executor

    @classmethod
    def from_dsn(cls, dsn: str) -> PointInTimeRepository:
        return cls(PsycopgReadOnlyExecutor(dsn))

    def load_factor_inputs_as_of(
        self,
        as_of_date: datetime,
        *,
        lookback_days: int = 365,
        symbols: Sequence[str] | None = None,
    ) -> pl.DataFrame:
        cutoff = _as_utc(as_of_date, "as_of_date")
        if lookback_days < 1:
            raise ValueError("lookback_days must be positive")
        normalized_symbols = _normalize_symbols(symbols)
        symbol_clause = ""
        parameters: dict[str, Any] = {
            "as_of_date": cutoff,
            "lookback_days": lookback_days,
        }
        if normalized_symbols:
            symbol_clause = "AND f.symbol = ANY(%(symbols)s)"
            parameters["symbols"] = normalized_symbols

        query = f"""
            WITH research_cutoff AS (
                SELECT %(as_of_date)s::timestamptz AS as_of_date
            ),
            latest_fundamentals AS (
                SELECT DISTINCT ON (f.symbol)
                    f.symbol,
                    f.time AS publication_time,
                    f.pe_ratio,
                    f.return_on_equity_ttm
                FROM fundamental_snapshots f
                CROSS JOIN research_cutoff cutoff
                WHERE f.time <= cutoff.as_of_date
                  {symbol_clause}
                ORDER BY f.symbol, f.time DESC
            )
            SELECT
                cutoff.as_of_date AS time,
                fundamentals.symbol,
                fundamentals.publication_time,
                latest_bar.time AS latest_price_time,
                reference_bar.time AS reference_price_time,
                fundamentals.pe_ratio,
                fundamentals.return_on_equity_ttm,
                latest_bar.close_price AS latest_close,
                reference_bar.close_price AS reference_close
            FROM latest_fundamentals fundamentals
            CROSS JOIN research_cutoff cutoff
            JOIN LATERAL (
                SELECT bar.time, bar.close_price
                FROM market_bars bar
                WHERE bar.symbol = fundamentals.symbol
                  AND bar.time <= cutoff.as_of_date
                ORDER BY bar.time DESC
                LIMIT 1
            ) latest_bar ON TRUE
            JOIN LATERAL (
                SELECT bar.time, bar.close_price
                FROM market_bars bar
                WHERE bar.symbol = fundamentals.symbol
                  AND bar.time <= cutoff.as_of_date
                      - make_interval(days => %(lookback_days)s)
                ORDER BY bar.time DESC
                LIMIT 1
            ) reference_bar ON TRUE
            WHERE fundamentals.pe_ratio > 0
              AND fundamentals.return_on_equity_ttm IS NOT NULL
              AND latest_bar.time > reference_bar.time
            ORDER BY fundamentals.symbol
            """
        return _frame(self._execute(query, parameters))

    def load_market_bars(
        self,
        start: datetime,
        end: datetime,
        *,
        as_of_date: datetime,
        symbols: Sequence[str] | None = None,
    ) -> pl.DataFrame:
        start_utc, end_utc, cutoff = _validate_window(start, end, as_of_date)
        normalized_symbols = _normalize_symbols(symbols)
        symbol_clause = ""
        parameters: dict[str, Any] = {
            "start": start_utc,
            "end": end_utc,
            "as_of_date": cutoff,
        }
        if normalized_symbols:
            symbol_clause = "AND bar.symbol = ANY(%(symbols)s)"
            parameters["symbols"] = normalized_symbols
        query = f"""
            WITH research_cutoff AS (
                SELECT %(as_of_date)s::timestamptz AS as_of_date
            )
            SELECT
                bar.time,
                bar.symbol,
                bar.open_price,
                bar.high_price,
                bar.low_price,
                bar.close_price,
                bar.volume
            FROM market_bars bar
            CROSS JOIN research_cutoff cutoff
            WHERE bar.time >= %(start)s
              AND bar.time <= %(end)s
              AND bar.time <= cutoff.as_of_date
              {symbol_clause}
            ORDER BY bar.time, bar.symbol
            """
        return _frame(self._execute(query, parameters))

    def load_factor_scores(
        self,
        start: datetime,
        end: datetime,
        *,
        as_of_date: datetime,
        symbols: Sequence[str] | None = None,
    ) -> pl.DataFrame:
        start_utc, end_utc, cutoff = _validate_window(start, end, as_of_date)
        normalized_symbols = _normalize_symbols(symbols)
        symbol_clause = ""
        parameters: dict[str, Any] = {
            "start": start_utc,
            "end": end_utc,
            "as_of_date": cutoff,
        }
        if normalized_symbols:
            symbol_clause = "AND score.symbol = ANY(%(symbols)s)"
            parameters["symbols"] = normalized_symbols
        query = f"""
            WITH research_cutoff AS (
                SELECT %(as_of_date)s::timestamptz AS as_of_date
            )
            SELECT
                score.time,
                score.symbol,
                score.composite_score,
                score.z_value,
                score.z_momentum,
                score.z_quality
            FROM factor_scores score
            CROSS JOIN research_cutoff cutoff
            WHERE score.time >= %(start)s
              AND score.time <= %(end)s
              AND score.time <= cutoff.as_of_date
              {symbol_clause}
            ORDER BY score.time, score.symbol
            """
        return _frame(self._execute(query, parameters))

    def load_latest_factor_scores_as_of(
        self,
        as_of_date: datetime,
        *,
        symbols: Sequence[str] | None = None,
    ) -> pl.DataFrame:
        cutoff = _as_utc(as_of_date, "as_of_date")
        normalized_symbols = _normalize_symbols(symbols)
        symbol_clause = ""
        parameters: dict[str, Any] = {"as_of_date": cutoff}
        if normalized_symbols:
            symbol_clause = "AND score.symbol = ANY(%(symbols)s)"
            parameters["symbols"] = normalized_symbols
        query = f"""
            WITH research_cutoff AS (
                SELECT %(as_of_date)s::timestamptz AS as_of_date
            ),
            latest_batch AS (
                SELECT MAX(score.time) AS score_time
                FROM factor_scores score
                CROSS JOIN research_cutoff cutoff
                WHERE score.time <= cutoff.as_of_date
            )
            SELECT
                score.time,
                score.symbol,
                score.composite_score,
                score.z_value,
                score.z_momentum,
                score.z_quality
            FROM factor_scores score
            CROSS JOIN latest_batch batch
            WHERE score.time = batch.score_time
              {symbol_clause}
            ORDER BY score.symbol
            """
        return _frame(self._execute(query, parameters))


def _validate_window(
    start: datetime,
    end: datetime,
    as_of_date: datetime,
) -> tuple[datetime, datetime, datetime]:
    start_utc = _as_utc(start, "start")
    end_utc = _as_utc(end, "end")
    cutoff = _as_utc(as_of_date, "as_of_date")
    if start_utc > end_utc:
        raise ValueError("start must not be after end")
    if end_utc > cutoff:
        raise LookAheadViolation("end must not be after as_of_date")
    return start_utc, end_utc, cutoff


def _as_utc(value: datetime, field: str) -> datetime:
    if value.tzinfo is None or value.utcoffset() is None:
        raise ValueError(f"{field} must be timezone-aware")
    return value.astimezone(UTC)


def _normalize_symbols(symbols: Sequence[str] | None) -> list[str]:
    if not symbols:
        return []
    normalized = sorted({symbol.strip().upper() for symbol in symbols})
    if any(not SYMBOL_PATTERN.fullmatch(symbol) for symbol in normalized):
        raise ValueError("symbols contain an invalid value")
    return normalized


def _frame(rows: Sequence[Mapping[str, Any]]) -> pl.DataFrame:
    return pl.DataFrame(list(rows), infer_schema_length=None)
