"""Scoring requests for a current snapshot (port of MonthEndScoringCoordinator.calculateCurrent).

The explicit, already elapsed knowledge cutoff is part of the run identity and must be reused
on retry. Datasets, classification and mapping are explicit configuration, as in Java.
"""

from __future__ import annotations

from datetime import UTC, date, datetime, timedelta
from uuid import UUID

from .inputs import CURRENT, NEW_YORK, PRE_OPEN, ScoringRequest


def _one(cursor, sql: str, parameters: dict):
    cursor.execute(sql, parameters)
    row = cursor.fetchone()
    return None if row is None else row[0]


def snapshot(cursor, code: str, score_date: date, cutoff: datetime) -> UUID | None:
    return _one(cursor, """
        SELECT universe_snapshot_id FROM (
            SELECT s.universe_snapshot_id,s.completeness_status FROM reference.universe_snapshots s
            JOIN reference.universes u USING(universe_id)
            WHERE u.code=%(code)s AND s.effective_date<=%(date)s AND s.observed_at<=%(cutoff)s
            ORDER BY s.effective_date DESC,s.observed_at DESC,s.universe_snapshot_id LIMIT 1
        ) latest WHERE completeness_status='COMPLETE'
        """, {"code": code, "date": score_date, "cutoff": cutoff})


def due_dates(connection, now: datetime) -> list[date]:
    """Completed, fully calendared XNYS month ends (MonthEndScoringCoordinator.dueDates)."""
    with connection.cursor() as cursor:
        cursor.execute("""
            SELECT MAX(session_date) FILTER(WHERE NOT holiday) AS month_end
            FROM reference.trading_sessions WHERE exchange_mic='XNYS'
            GROUP BY date_trunc('month',session_date)
            HAVING COUNT(*)=EXTRACT(day FROM date_trunc('month',MIN(session_date))
                    +INTERVAL '1 month - 1 day')
                AND MAX(closes_at)<=%(now)s
            ORDER BY month_end""", {"now": now})
        return [row[0] for row in cursor.fetchall()]


def month_end_request(connection, score_date: date, *, raw_dataset: UUID, adjusted_dataset: UUID,
                      classification: UUID, mapping_version: str, now: datetime | None = None
                      ) -> tuple[ScoringRequest, datetime] | None:
    """Pre-open request of a month end (MonthEndScoringCoordinator.calculate), or None if early.

    The decision is fixed at 30 minutes before the next known opening, so a later restart
    produces the same request and therefore the same run.
    """
    now = now or datetime.now(UTC)
    if score_date is None or score_date not in due_dates(connection, now):
        raise ValueError("Score date must be a completed, fully calendared month-end")
    with connection.cursor() as cursor:
        cutoff = _one(cursor, "SELECT closes_at FROM reference.trading_sessions "
                              "WHERE exchange_mic='XNYS' AND session_date=%(date)s",
                      {"date": score_date})
        opening = _one(cursor, """
            SELECT s.opens_at FROM reference.trading_sessions s
            WHERE s.exchange_mic='XNYS' AND s.session_date>%(date)s AND NOT s.holiday
              AND s.session_date=(SELECT min(session_date) FROM reference.trading_sessions
                  WHERE exchange_mic='XNYS' AND session_date>%(date)s AND NOT holiday)
              AND s.available_at<=%(cutoff)s AND s.observed_at<=%(cutoff)s
              AND (SELECT count(*) FROM reference.trading_sessions c WHERE c.exchange_mic='XNYS'
                  AND c.session_date>%(date)s AND c.session_date<=s.session_date
                  AND c.available_at<=%(cutoff)s AND c.observed_at<=%(cutoff)s)
                  =s.session_date-CAST(%(date)s AS date)
            ORDER BY s.session_date LIMIT 1""", {"date": score_date, "cutoff": cutoff})
        if opening is None:
            return None
        decision = opening - timedelta(minutes=30)
        if now < decision:
            return None
        sp = snapshot(cursor, "SP500", score_date, decision)
        nq = snapshot(cursor, "NASDAQ100", score_date, decision)
    if sp is None or nq is None:
        return None
    request = ScoringRequest(
        score_date=score_date, market_cutoff=cutoff, knowledge_cutoff=decision,
        sp500_snapshot=sp, nasdaq100_snapshot=nq, raw_dataset=raw_dataset,
        adjusted_dataset=adjusted_dataset, adjustment_basis=decision.astimezone(NEW_YORK).date(),
        classification_version=classification, mapping_version=mapping_version,
        required_metrics=("TOTAL_ASSETS",), timing_policy=PRE_OPEN)
    return request, opening


def current_request(connection, score_date: date, decision: datetime, *, raw_dataset: UUID,
                    adjusted_dataset: UUID, classification: UUID, mapping_version: str,
                    now: datetime | None = None) -> tuple[ScoringRequest, datetime] | None:
    """Request and effective time of a current snapshot, or None when not yet possible."""
    now = now or datetime.now(UTC)
    if decision.tzinfo is None or decision > now:
        raise ValueError("An elapsed explicit knowledge cutoff is required")
    with connection.cursor() as cursor:
        latest = _one(cursor, """
            SELECT max(session_date) FROM reference.trading_sessions
            WHERE exchange_mic='XNYS' AND NOT holiday AND closes_at<=%(cutoff)s
              AND available_at<=%(cutoff)s AND observed_at<=%(cutoff)s
            """, {"cutoff": decision})
        if score_date != latest:
            raise ValueError("Current snapshot requires the latest completed session at its cutoff")
        market = _one(cursor, "SELECT closes_at FROM reference.trading_sessions "
                              "WHERE exchange_mic='XNYS' AND session_date=%(date)s",
                      {"date": score_date})
        opening = _one(cursor, """
            SELECT opens_at FROM reference.trading_sessions
            WHERE exchange_mic='XNYS' AND NOT holiday
              AND opens_at>=%(earliest)s AND available_at<=%(cutoff)s AND observed_at<=%(cutoff)s
            ORDER BY opens_at LIMIT 1
            """, {"earliest": decision + timedelta(seconds=1800), "cutoff": decision})
        if opening is None:
            return None
        sp = snapshot(cursor, "SP500", score_date, decision)
        nq = snapshot(cursor, "NASDAQ100", score_date, decision)
    if sp is None or nq is None:
        return None
    request = ScoringRequest(
        score_date=score_date, market_cutoff=market, knowledge_cutoff=decision,
        sp500_snapshot=sp, nasdaq100_snapshot=nq, raw_dataset=raw_dataset,
        adjusted_dataset=adjusted_dataset,
        adjustment_basis=decision.astimezone(NEW_YORK).date(),
        classification_version=classification, mapping_version=mapping_version,
        required_metrics=("TOTAL_ASSETS",), timing_policy=CURRENT)
    return request, opening
