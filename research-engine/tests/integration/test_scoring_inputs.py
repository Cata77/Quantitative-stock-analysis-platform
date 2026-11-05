"""Port of scoring-service ScoringInputIntegrationTest: the shared input query at a cutoff.

The Python loader runs ``scoring-inputs.sql`` against a freshly migrated database. Every test
checks one point-in-time, identity or quality rule of the canonical cross-section.
"""

from __future__ import annotations

import json
import uuid
from dataclasses import dataclass, replace
from datetime import UTC, date, datetime, timedelta
from decimal import Decimal

import psycopg
import pytest

from quant_research.publication.inputs import INPUT_QUERY, PRE_OPEN, ScoringRequest, load

pytestmark = pytest.mark.integration
DAY = date(2026, 9, 1)
CLOSE = datetime(2026, 9, 1, 20, tzinfo=UTC)


def one(db, sql, params=None):
    return db.execute(sql, params or {}).fetchone()[0]


@dataclass
class Fixture:
    db: psycopg.Connection
    dataset: uuid.UUID
    instrument: uuid.UUID
    sp500: uuid.UUID
    nasdaq: uuid.UUID
    classification: uuid.UUID
    artifact: uuid.UUID

    def request(self, **changes) -> ScoringRequest:
        values = dict(score_date=DAY, market_cutoff=CLOSE, knowledge_cutoff=CLOSE,
                      sp500_snapshot=self.sp500, nasdaq100_snapshot=self.nasdaq,
                      raw_dataset=self.dataset, adjusted_dataset=self.dataset, adjustment_basis=DAY,
                      classification_version=self.classification, mapping_version="sec-us-gaap-v1",
                      required_metrics=("TOTAL_ASSETS",))
        values.update(changes)
        return ScoringRequest(**values)

    def load(self, request: ScoringRequest | None = None) -> list[dict]:
        with self.db.transaction():
            return load(self.db, request or self.request())["inputs"]

    def row(self, request: ScoringRequest | None = None) -> dict:
        return self.load(request)[0]


def reasons(row: dict) -> set[str]:
    return {r["code"] for r in row["reasons"]}


def snapshot(db, code: str, instrument) -> uuid.UUID:
    sid = one(db, """
        INSERT INTO reference.universe_snapshots (universe_id,effective_date,observed_at,source,
            source_checksum,import_mode,completeness_status,expected_member_count,imported_member_count)
        SELECT universe_id,'2026-08-01','2026-08-01T00:00:00Z','fixture',repeat('f',64),
            'CURRENT_SNAPSHOT_FORWARD','COMPLETE',1,1 FROM reference.universes WHERE code=%(code)s
        RETURNING universe_snapshot_id""", {"code": code})
    db.execute("""
        INSERT INTO reference.universe_memberships (universe_snapshot_id,instrument_id,source_symbol,
            source_exchange_mic,primary_liquid_class) VALUES (%s,%s,'FIX','XNAS',true)""",
               (sid, instrument))
    return sid


def calendar(db, start: date, through: date) -> None:
    day = start
    while day <= through:
        holiday = day.isoweekday() >= 6
        for exchange in ("XNYS", "XNAS", "ARCX", "BATS"):
            opens = None if holiday else datetime(day.year, day.month, day.day, 13, 30, tzinfo=UTC)
            closes = None if holiday else datetime(day.year, day.month, day.day, 20, tzinfo=UTC)
            db.execute("""
                INSERT INTO reference.trading_sessions (exchange_mic,session_date,opens_at,closes_at,
                    timezone,holiday,early_close,source,available_at,observed_at)
                VALUES (%s,%s,%s,%s,'America/New_York',%s,false,'fixture','2026-01-01T00:00:00Z',
                    '2026-01-01T00:00:00Z') ON CONFLICT DO NOTHING""",
                       (exchange, day, opens, closes, holiday))
        day += timedelta(days=1)


def seed(db, dataset, classification, artifact) -> None:
    db.execute("""
        INSERT INTO reference.issuer_classifications(issuer_id,classification_version_id,mapped_sector,
            effective_from,available_at,observed_at)
        SELECT issuer_id,%(c)s,'Technology','2025-01-01','2025-01-01','2025-01-01'
        FROM reference.issuers ON CONFLICT DO NOTHING""", {"c": classification})
    db.execute("""
        INSERT INTO market_data.observations(observation_key,dataset_id,instrument_id,event_type,
            economic_time,adjustment_mode,payload,source_artifact_id,observed_at,ingested_at)
        SELECT md5(instrument_id::text)||md5(instrument_id::text),%(d)s,instrument_id,'FILING_FACTS',
            '2026-06-30','NONE','{}',%(a)s,'2026-08-01','2026-08-01'
        FROM reference.instruments ON CONFLICT DO NOTHING""", {"d": dataset, "a": artifact})
    db.execute("""
        INSERT INTO fundamentals.filings(issuer_id,accession,revision_hash,form,fiscal_period_end,
            filed_date,accepted_at,published_at,available_at,observed_at,ingested_at,primary_document,
            parser_version,mapping_version,source_artifact_id,observation_key)
        SELECT issuer_id,'fixture-'||instrument_id,repeat('c',64),'10-Q','2026-06-30','2026-08-01',
            '2026-08-01','2026-08-01','2026-08-01','2026-08-01','2026-08-01','fixture.htm','fixture',
            'sec-us-gaap-v1',%(a)s,md5(instrument_id::text)||md5(instrument_id::text)
        FROM reference.instruments ON CONFLICT DO NOTHING""", {"a": artifact})
    db.execute("""
        INSERT INTO fundamentals.fundamental_facts(issuer_id,filing_id,metric_code,mapping_version,
            taxonomy,source_concept,period_end,source_value,numeric_value,unit,dimensions,
            source_context,source_fact_hash,priority,quality_state,available_at,observed_at,
            source_artifact_id)
        SELECT l.issuer_id,l.filing_id,m.code,'sec-us-gaap-v1','us-gaap',m.concept,'2026-06-30',1000,
            1000,m.unit,'{}','{}',md5(m.code)||md5(m.code),1,'VALID','2026-08-01','2026-08-01',%(a)s
        FROM fundamentals.filings l CROSS JOIN (VALUES ('TOTAL_ASSETS','Assets','USD'),
            ('SHARES_OUTSTANDING','CommonStockSharesOutstanding','shares')) m(code,concept,unit)
        ON CONFLICT DO NOTHING""", {"a": artifact})
    db.execute("""
        INSERT INTO fundamentals.instrument_share_facts
        SELECT i.instrument_id,f.fact_id,'SINGLE_SHARE_CLASS','2026-08-01'
        FROM fundamentals.fundamental_facts f JOIN reference.instruments i USING(issuer_id)
        WHERE f.metric_code='SHARES_OUTSTANDING' ON CONFLICT DO NOTHING""")
    db.execute("""
        INSERT INTO fundamentals.profile_observations
        SELECT issuer_id,md5(instrument_id::text)||md5(instrument_id::text),'GENERAL',
            'FACTS_AVAILABLE','fixture','2026-08-01',%(a)s
        FROM reference.instruments ON CONFLICT DO NOTHING""", {"a": artifact})
    db.execute("""
        INSERT INTO market_data.observations(observation_key,dataset_id,instrument_id,event_type,
            economic_time,adjustment_mode,payload,source_artifact_id,observed_at,ingested_at)
        SELECT md5(i.instrument_id::text||s.session_date||m.mode)
                ||md5(i.instrument_id::text||s.session_date||m.mode),
            %(d)s,i.instrument_id,'DAILY_PRICE',s.session_date,m.mode,'{}',%(a)s,
            '2026-08-31','2026-08-31'
        FROM reference.instruments i CROSS JOIN reference.trading_sessions s
        CROSS JOIN (VALUES('RAW'),('SPLIT_DIVIDEND')) m(mode)
        WHERE s.exchange_mic='XNAS' AND NOT s.holiday ON CONFLICT DO NOTHING""",
               {"d": dataset, "a": artifact})
    db.execute("""
        INSERT INTO market_data.daily_bar_observations(session_date,observation_key,instrument_id,
            provider_id,dataset_id,exchange_mic,feed,adjustment_mode,adjustment_as_of,currency,
            bar_time,open,high,low,close,volume,trade_count,source_revision,source_artifact_id,
            available_at,observed_at,ingested_at)
        SELECT o.economic_time::date,o.observation_key,o.instrument_id,d.provider_id,o.dataset_id,
            'XNAS','sip',o.adjustment_mode,
            CASE WHEN o.adjustment_mode='SPLIT_DIVIDEND' THEN '2026-09-01'::date END,
            'USD',o.economic_time,100,200,90,100+extract(doy FROM o.economic_time)/10,100000,1000,
            repeat('d',64),%(a)s,'2026-09-01T19:00:00Z','2026-09-01T19:00:00Z','2026-09-01T19:00:00Z'
        FROM market_data.observations o JOIN operations.datasets d USING(dataset_id)
        WHERE o.event_type='DAILY_PRICE' ON CONFLICT DO NOTHING""", {"a": artifact})


@pytest.fixture
def fx(db) -> Fixture:
    provider = one(db, "INSERT INTO operations.data_providers (code,name,license_notes) "
                       "VALUES ('fixture','Fixture','test') RETURNING provider_id")
    dataset = one(db, "INSERT INTO operations.datasets (provider_id,code,version,license_notes) "
                      "VALUES (%s,'bars','1','test') RETURNING dataset_id", (provider,))
    issuer = one(db, "INSERT INTO reference.issuers (legal_name) VALUES ('Fixture') "
                     "RETURNING issuer_id")
    instrument = one(db, """
        INSERT INTO reference.instruments (issuer_id,security_type,share_class,currency,
            primary_exchange_mic,valid_from)
        VALUES (%s,'COMMON_STOCK','A','USD','XNAS','2026-08-01') RETURNING instrument_id""",
                     (issuer,))
    db.execute("""
        INSERT INTO reference.instrument_symbols (instrument_id,symbol,exchange_mic,effective_from,
            source,available_at,observed_at)
        VALUES (%s,'FIX','XNAS','2026-08-01','fixture','2026-08-01T00:00:00Z','2026-08-01T00:00:00Z')
        """, (instrument,))
    sp500, nasdaq = snapshot(db, "SP500", instrument), snapshot(db, "NASDAQ100", instrument)
    db.execute("UPDATE reference.instruments SET valid_from='2025-01-01'")
    db.execute("""
        INSERT INTO reference.trading_sessions(exchange_mic,session_date,opens_at,closes_at,timezone,
            holiday,early_close,source,available_at,observed_at)
        SELECT e,d::date,
            CASE WHEN extract(isodow FROM d)<6 THEN (d+interval '13 hours 30 minutes') AT TIME ZONE 'UTC' END,
            CASE WHEN extract(isodow FROM d)<6 THEN (d+interval '20 hours') AT TIME ZONE 'UTC' END,
            'America/New_York',extract(isodow FROM d)>5,false,'fixture','2025-01-01','2025-01-01'
        FROM generate_series('2025-06-01'::timestamp,'2026-09-01',interval '1 day') d
        CROSS JOIN (VALUES('XNYS'),('XNAS')) exchanges(e)""")
    classification = one(db, "INSERT INTO reference.classification_versions(source,version,"
                             "mapping_checksum) VALUES('fixture','1',repeat('a',64)) "
                             "RETURNING classification_version_id")
    artifact = one(db, """
        INSERT INTO operations.source_artifacts(dataset_id,request_key,source_uri,retrieved_at,
            content_hash,media_type,parser_version,inline_content)
        VALUES(%s,'input-fixture','test://input','2026-08-01',repeat('b',64),'application/json',
            'fixture','{}') RETURNING source_artifact_id""", (dataset,))
    seed(db, dataset, classification, artifact)
    return Fixture(db, dataset, instrument, sp500, nasdaq, classification, artifact)


def delayed_request(fx: Fixture) -> ScoringRequest:
    calendar(fx.db, DAY + timedelta(days=1), DAY + timedelta(days=1))
    fx.db.execute("UPDATE reference.trading_sessions SET available_at='2025-01-01',"
                  "observed_at='2025-01-01' WHERE session_date=%s", (DAY + timedelta(days=1),))
    return fx.request(knowledge_cutoff=datetime(2026, 9, 2, 13, tzinfo=UTC),
                      adjustment_basis=DAY + timedelta(days=1), timing_policy=PRE_OPEN)


def test_delayed_bars_are_usable_only_within_the_versioned_decision_boundary(fx):
    delayed = delayed_request(fx)
    fx.db.execute("""
        UPDATE market_data.daily_bar_observations SET available_at='2026-09-02T12:00:00Z',
            observed_at='2026-09-02T12:00:00Z',ingested_at='2026-09-02T12:01:00Z',
            adjustment_as_of=CASE WHEN adjustment_mode='SPLIT_DIVIDEND' THEN '2026-09-02'::date END""")
    assert fx.row()["rawClose"] is None
    assert sum(1 for r in fx.load(delayed) if not r["reasons"]) == 1
    fx.db.execute("UPDATE market_data.daily_bar_observations SET observed_at='2026-09-02T13:01:00Z'"
                  " WHERE session_date=%s", (DAY,))
    assert fx.row(delayed)["rawClose"] is None
    fx.db.execute("UPDATE market_data.daily_bar_observations SET observed_at='2026-09-02T12:00:00Z',"
                  "ingested_at='2026-09-02T13:01:00Z' WHERE session_date=%s", (DAY,))
    assert fx.row(delayed)["rawClose"] is None


def test_delayed_policy_rejects_wrong_decision_and_late_calendar_knowledge(fx):
    delayed = delayed_request(fx)
    wrong = replace(delayed, knowledge_cutoff=delayed.knowledge_cutoff + timedelta(seconds=60))
    with pytest.raises(ValueError, match="INVALID_PRE_OPEN_CUTOFF"):
        fx.load(wrong)
    fx.db.execute("UPDATE reference.trading_sessions SET observed_at='2026-09-02T12:00:00Z' "
                  "WHERE session_date=%s", (DAY + timedelta(days=1),))
    with pytest.raises(ValueError, match="INVALID_PRE_OPEN_CUTOFF"):
        fx.load(delayed)


def test_returns_one_complete_row_with_calendar_boundaries_and_lineage(fx):
    rows = fx.load()
    assert len(rows) == 1 and not rows[0]["reasons"]
    row = rows[0]
    assert row["historyCount"] == 252 and row["liquidityCount"] == 20
    assert row["inSp500"] and row["inNasdaq100"]
    assert len(row["facts"]) == 2
    for fact in row["facts"]:
        stored = one(fx.db, "SELECT observed_at FROM fundamentals.fundamental_facts WHERE fact_id=%s",
                     (uuid.UUID(fact["factId"]),))
        assert datetime.fromisoformat(fact["observedAt"].replace("Z", "+00:00")) == stored
    assert len(row["priceLineage"]) == 23
    assert row["sharesOutstanding"] == 1000
    boundary = one(fx.db, """
        SELECT 100+extract(doy FROM session_date)/10 FROM reference.trading_sessions
        WHERE exchange_mic='XNYS' AND NOT holiday AND session_date<='2026-09-01'
        ORDER BY session_date DESC OFFSET 21 LIMIT 1""")
    assert Decimal(str(row["momentumRecent"])) == boundary


def test_missing_member_is_retained_and_accounting_balances(fx):
    fx.db.execute("""
        INSERT INTO reference.instruments(issuer_id,security_type,share_class,currency,
            primary_exchange_mic,valid_from)
        SELECT issuer_id,'COMMON_STOCK','B','USD','XNAS','2025-01-01' FROM reference.instruments""")
    fx.db.execute("""
        INSERT INTO reference.universe_memberships
            (universe_snapshot_id,instrument_id,source_symbol,source_exchange_mic,primary_liquid_class)
        SELECT %s,instrument_id,'OTHER','XNAS',false FROM reference.instruments WHERE share_class='B'
        """, (fx.sp500,))
    fx.db.execute("UPDATE reference.universe_snapshots SET expected_member_count=2,"
                  "imported_member_count=2 WHERE universe_snapshot_id=%s", (fx.sp500,))
    rows = fx.load()
    assert len(rows) == 2
    missing = next(r for r in rows if r["instrumentId"] != str(fx.instrument))
    assert {"NON_PRIMARY_SHARE_CLASS", "MISSING_SCORE_DATE_PRICE",
            "MISSING_INSTRUMENT_SHARES"} <= reasons(missing)


def test_future_corrections_and_resolved_quality_issues_respect_knowledge_cutoff(fx):
    fx.db.execute("""
        INSERT INTO operations.data_quality_issues(dataset_id,instrument_id,severity,issue_type,
            affected_key,evidence,status,detected_at,resolved_at)
        VALUES(%s,%s,'BLOCKING','CORPORATE_ACTION_REVIEW','fixture','{}','RESOLVED',
            '2026-09-01T19:30:00Z','2026-09-02')""", (fx.dataset, fx.instrument))
    fx.db.execute("UPDATE fundamentals.fundamental_facts SET numeric_value=9000,"
                  "available_at='2026-09-02',observed_at='2026-09-02' WHERE metric_code='TOTAL_ASSETS'")
    row = fx.row()
    assert {"CORPORATE_ACTION_REVIEW", "MISSING_REQUIRED_METRIC"} <= reasons(row)
    assert "TOTAL_ASSETS" not in {f["metric"] for f in row["facts"]}


def test_gaps_cannot_shift_momentum_or_use_iex_volume(fx):
    fx.db.execute("""
        UPDATE market_data.daily_bar_observations SET feed='iex'
        WHERE session_date=(SELECT session_date FROM reference.trading_sessions
            WHERE exchange_mic='XNYS' AND NOT holiday AND session_date<='2026-09-01'
            ORDER BY session_date DESC OFFSET 21 LIMIT 1)""")
    fx.db.execute("UPDATE market_data.daily_bar_observations SET feed='iex' WHERE adjustment_mode='RAW'")
    row = fx.row()
    assert row["momentumRecent"] is None
    assert {"MISSING_MOMENTUM_BOUNDARY", "INSUFFICIENT_PRICE_HISTORY",
            "INCOMPLETE_LIQUIDITY_WINDOW"} <= reasons(row)


def test_empty_latest_filing_does_not_erase_known_general_coverage(fx):
    fx.db.execute("UPDATE fundamentals.profile_observations SET status='MODEL_NOT_SUPPORTED',"
                  "reason='NO_MAPPED_FACTS'")
    assert "MODEL_NOT_SUPPORTED" not in reasons(fx.row())
    fx.db.execute("UPDATE fundamentals.profile_observations SET profile='FINANCIAL'")
    assert "MODEL_NOT_SUPPORTED" in reasons(fx.row())


def test_foreign_unit_cannot_poison_valid_canonical_unit_but_invalid_usd_still_blocks(fx):
    fx.db.execute("""
        INSERT INTO fundamentals.fundamental_facts(issuer_id,filing_id,metric_code,mapping_version,
            taxonomy,source_concept,period_end,source_value,numeric_value,unit,dimensions,
            source_context,source_fact_hash,priority,quality_state,available_at,observed_at,
            source_artifact_id)
        SELECT issuer_id,filing_id,metric_code,mapping_version,taxonomy,source_concept,period_end,
            50,NULL,'EUR',dimensions,source_context,repeat('e',64),priority,'INVALID_UNIT',
            available_at,observed_at,source_artifact_id
        FROM fundamentals.fundamental_facts WHERE metric_code='TOTAL_ASSETS'""")
    assert "TOTAL_ASSETS" in {f["metric"] for f in fx.row()["facts"]}
    fx.db.execute("UPDATE fundamentals.fundamental_facts SET unit='USD',quality_state='INVALID_PERIOD'"
                  " WHERE unit='EUR'")
    assert "TOTAL_ASSETS" not in {f["metric"] for f in fx.row()["facts"]}


SHARE_EVIDENCE = json.dumps({"rationale": "Explicit single common share class verified",
                             "sourceUri": "https://example.org/filing", "sourceSha256": "a" * 64,
                             "scope": "EXACT_INSTRUMENT_SHARES", "shareClass": "Common"})


def test_review_is_immutable_and_cannot_be_backdated_or_use_another_metric(fx):
    shares = one(fx.db, "SELECT fact_id FROM fundamentals.fundamental_facts "
                        "WHERE metric_code='SHARES_OUTSTANDING'")
    review = uuid.uuid4()
    fx.db.execute("""
        INSERT INTO operations.input_reviews(review_id,kind,instrument_id,fact_id,decision,reviewer,
            evidence,recorded_at)
        VALUES(%s,'SHARE_CLASS',%s,%s,'ACCEPT','test-reviewer',CAST(%s AS jsonb),'2000-01-01')
        """, (review, fx.instrument, shares, SHARE_EVIDENCE))
    assert one(fx.db, "SELECT recorded_at > '2000-01-02'::timestamptz FROM operations.input_reviews "
                      "WHERE review_id=%s", (review,))
    with pytest.raises(psycopg.Error, match="immutable"):
        fx.db.execute("UPDATE operations.input_reviews SET decision='REVOKE' WHERE review_id=%s",
                      (review,))
    with pytest.raises(psycopg.Error, match="common-only"):
        fx.db.execute("""
            INSERT INTO operations.input_reviews(review_id,kind,instrument_id,fact_id,decision,
                reviewer,evidence)
            VALUES(%s,'COMMON_EQUITY',%s,%s,'ACCEPT','test-reviewer',CAST(%s AS jsonb))""",
                      (uuid.uuid4(), fx.instrument, shares, SHARE_EVIDENCE))
    # The historic query cannot see today's review and keeps the original known share fact.
    assert fx.row()["sharesOutstanding"] == 1000


def _missing_session(fx, session: str, detected: str) -> uuid.UUID:
    return one(fx.db, """
        INSERT INTO operations.data_quality_issues(dataset_id,instrument_id,severity,issue_type,
            affected_key,evidence,detected_at)
        VALUES(%s,%s,'BLOCKING','MISSING_SESSION',%s,'{"reason":"provider pagination exhausted"}',
            CAST(%s AS timestamptz)) RETURNING data_quality_issue_id""",
               (fx.dataset, fx.instrument, session, detected))


def _session_review(fx, issue: uuid.UUID, scope: str) -> None:
    exact = one(fx.db, "SELECT evidence::text FROM operations.data_quality_issues "
                       "WHERE data_quality_issue_id=%s", (issue,))
    evidence = json.dumps({"rationale": "Session explained by a verified instrument identity",
                           "sourceUri": "https://example.org/8-k", "sourceSha256": "a" * 64,
                           "scope": scope, "issueEvidence": exact})
    fx.db.execute("""
        INSERT INTO operations.input_reviews(review_id,kind,instrument_id,issue_id,decision,
            reviewer,evidence)
        VALUES(%s,'QUALITY_ISSUE',%s,%s,'ACCEPT','test-reviewer',CAST(%s AS jsonb))""",
                  (uuid.uuid4(), fx.instrument, issue, evidence))


def test_missing_session_review_requires_a_verified_identity_explanation(fx):
    # FIX trades until 2026-08-20, then the same instrument trades as FIX2 (V024).
    fx.db.execute("UPDATE reference.instrument_symbols SET effective_to='2026-08-20' "
                  "WHERE instrument_id=%s AND symbol='FIX'", (fx.instrument,))
    fx.db.execute("""
        INSERT INTO reference.instrument_symbols(instrument_id,symbol,exchange_mic,effective_from,
            source,available_at,observed_at)
        VALUES(%s,'FIX2','XNAS','2026-08-20','fixture','2026-08-20','2026-08-20')""",
                  (fx.instrument,))
    fx.db.execute("DELETE FROM market_data.daily_bar_observations WHERE instrument_id=%s "
                  "AND session_date='2026-07-01'", (fx.instrument,))
    recovered = _missing_session(fx, "2026-08-21", "2026-09-01T18:00:00Z")
    before_rename = _missing_session(fx, "2026-08-19", "2026-09-01T18:00:00Z")
    detected_after_bar = _missing_session(fx, "2026-08-24", "2026-09-01T19:30:00Z")
    before_listing = _missing_session(fx, "2026-07-01", "2026-09-01T18:00:00Z")
    listed_with_bar = _missing_session(fx, "2026-07-02", "2026-09-01T18:00:00Z")
    retired = "SYMBOL_RETIRED_SESSION_RECOVERED_UNDER_SUCCESSOR_SYMBOL"
    listing = "SESSION_BEFORE_INSTRUMENT_LISTING"
    _session_review(fx, recovered, retired)
    _session_review(fx, before_listing, listing)
    for issue, scope, message in ((before_rename, retired, "symbol change"),
                                  (detected_after_bar, retired, "later valid bar"),
                                  (listed_with_bar, listing, "first symbol date"),
                                  (recovered, "PROVIDER_GAP_ACCEPTED", "identity scope")):
        with pytest.raises(psycopg.Error, match=message):
            _session_review(fx, issue, scope)
    assert one(fx.db, "SELECT count(*) FROM operations.data_quality_issues "
                      "WHERE issue_type='MISSING_SESSION' AND status='OPEN'") == 5


EQUITY_EVIDENCE = json.dumps({"rationale": "Verified full statement common-only equity reconciliation",
                              "sourceUri": "https://example.org/filing", "sourceSha256": "b" * 64,
                              "scope": "TOTAL_EQUITY_IS_COMMON_ONLY"})


def _equity_review(fx, fact, decision) -> datetime:
    return one(fx.db, """
        INSERT INTO operations.input_reviews(review_id,kind,instrument_id,fact_id,decision,reviewer,
            evidence)
        VALUES(%s,'COMMON_EQUITY',%s,%s,%s,'test-reviewer',CAST(%s AS jsonb)) RETURNING recorded_at
        """, (uuid.uuid4(), fx.instrument, fact, decision, EQUITY_EVIDENCE))


def _equity_selection_at(fx, cutoff: datetime) -> list[Decimal]:
    """Financial-selection diagnostic only: no score is built at the later review cutoff."""
    sql = INPUT_QUERY.read_text(encoding="utf-8").replace("\r\n", "\n")
    prefix = sql[:sql.index(",\nfact_bundles AS (")]
    from quant_research.publication.inputs import named_to_psycopg
    parameters = fx.request().parameters()  # the request of the score date, at a later cutoff
    parameters["knowledgeCutoff"] = cutoff
    rows = fx.db.execute(named_to_psycopg(prefix + " SELECT numeric_value FROM input_facts "
                                                   "WHERE metric_code='COMMON_EQUITY'"),
                         parameters).fetchall()
    return [r[0] for r in rows]


def test_common_equity_review_lifecycle_preserves_cutoff_and_direct_facts(fx):
    fact = one(fx.db, """
        INSERT INTO fundamentals.fundamental_facts(issuer_id,filing_id,metric_code,mapping_version,
            taxonomy,source_concept,period_end,source_value,numeric_value,unit,dimensions,
            source_context,source_fact_hash,priority,quality_state,available_at,observed_at,
            source_artifact_id)
        SELECT issuer_id,filing_id,'STOCKHOLDERS_EQUITY',mapping_version,taxonomy,
            'StockholdersEquity',period_end,700,700,unit,dimensions,source_context,repeat('7',64),
            priority,quality_state,available_at,observed_at,source_artifact_id
        FROM fundamentals.fundamental_facts WHERE metric_code='TOTAL_ASSETS' RETURNING fact_id""")
    accepted = _equity_review(fx, fact, "ACCEPT")
    assert _equity_selection_at(fx, CLOSE) == []
    assert _equity_selection_at(fx, accepted - timedelta(microseconds=1)) == []
    assert _equity_selection_at(fx, accepted) == [Decimal(700)]
    revoked = _equity_review(fx, fact, "REVOKE")
    assert _equity_selection_at(fx, revoked) == []
    assert _equity_selection_at(fx, accepted) == [Decimal(700)]
    restored = _equity_review(fx, fact, "ACCEPT")
    fx.db.execute("""
        INSERT INTO fundamentals.fundamental_facts(issuer_id,filing_id,metric_code,mapping_version,
            taxonomy,source_concept,period_end,source_value,numeric_value,unit,dimensions,
            source_context,source_fact_hash,priority,quality_state,available_at,observed_at,
            source_artifact_id)
        SELECT issuer_id,filing_id,'COMMON_EQUITY',mapping_version,taxonomy,'CommonStockholdersEquity',
            period_end,650,650,unit,dimensions,source_context,repeat('6',64),priority,quality_state,
            available_at,observed_at,source_artifact_id
        FROM fundamentals.fundamental_facts WHERE fact_id=%s""", (fact,))
    assert _equity_selection_at(fx, restored) == [Decimal(650)]
    assert one(fx.db, "SELECT numeric_value FROM fundamentals.fundamental_facts WHERE fact_id=%s",
               (fact,)) == 700


def test_invalid_snapshot_pair_fails_the_whole_request(fx):
    fx.db.execute("UPDATE reference.universe_snapshots SET observed_at='2026-09-02' "
                  "WHERE universe_snapshot_id=%s", (fx.nasdaq,))
    with pytest.raises(ValueError, match="SNAPSHOT_NOT_POINT_IN_TIME_COMPLETE"):
        fx.load()


def before_close(fx) -> ScoringRequest:
    return fx.request(knowledge_cutoff=datetime(2026, 9, 1, 19, 15, tzinfo=UTC))


def test_appended_filing_revision_and_classification_are_selected_only_when_known(fx):
    fx.db.execute("""
        INSERT INTO fundamentals.filings(issuer_id,accession,revision_hash,form,fiscal_period_end,
            filed_date,accepted_at,published_at,available_at,observed_at,ingested_at,primary_document,
            parser_version,mapping_version,source_artifact_id,observation_key)
        SELECT issuer_id,accession,repeat('e',64),form,fiscal_period_end,filed_date,accepted_at,
            published_at,'2026-09-01T19:30:00Z','2026-09-01T19:30:00Z','2026-09-01T19:30:00Z',
            primary_document,parser_version,mapping_version,source_artifact_id,observation_key
        FROM fundamentals.filings""")
    fx.db.execute("""
        INSERT INTO fundamentals.fundamental_facts(issuer_id,filing_id,metric_code,mapping_version,
            taxonomy,source_concept,period_end,source_value,numeric_value,unit,dimensions,
            source_context,source_fact_hash,priority,quality_state,available_at,observed_at,
            source_artifact_id)
        SELECT issuer_id,filing_id,'TOTAL_ASSETS',mapping_version,'us-gaap','Assets',
            fiscal_period_end,9000,9000,'USD','{}','{}',repeat('e',64),1,'VALID',
            '2026-09-01T19:30:00Z','2026-09-01T19:30:00Z',source_artifact_id
        FROM fundamentals.filings WHERE revision_hash=repeat('e',64)""")
    old = fx.row(before_close(fx))
    assert next(f for f in old["facts"] if f["metric"] == "TOTAL_ASSETS")["value"] == 1000
    current = fx.row()
    assert len(current["facts"]) == 1 and current["facts"][0]["value"] == 9000
    assert current["sharesOutstanding"] is None
    fx.db.execute("UPDATE reference.issuer_classifications SET available_at='2026-09-02',"
                  "observed_at='2026-09-02'")
    assert fx.row()["sector"] is None


def test_rejected_latest_price_revision_cannot_resurrect_an_older_valid_bar(fx):
    fx.db.execute("""
        INSERT INTO market_data.observations(observation_key,dataset_id,instrument_id,event_type,
            economic_time,adjustment_mode,payload,source_artifact_id,observed_at,ingested_at)
        VALUES(repeat('e',64),%s,%s,'DAILY_PRICE','2026-09-01','RAW','{}',%s,
            '2026-09-01T19:30:00Z','2026-09-01T19:30:00Z')""",
                  (fx.dataset, fx.instrument, fx.artifact))
    fx.db.execute("""
        INSERT INTO market_data.daily_bar_observations(session_date,observation_key,instrument_id,
            provider_id,dataset_id,exchange_mic,feed,adjustment_mode,adjustment_as_of,currency,
            bar_time,open,high,low,close,volume,trade_count,source_revision,source_artifact_id,
            available_at,observed_at,ingested_at,quality_state)
        SELECT session_date,repeat('e',64),instrument_id,provider_id,dataset_id,exchange_mic,feed,
            adjustment_mode,adjustment_as_of,currency,bar_time,open,high,low,close,volume,
            trade_count,repeat('e',64),source_artifact_id,'2026-09-01T19:30:00Z',
            '2026-09-01T19:30:00Z','2026-09-01T19:30:00Z','REJECTED'
        FROM market_data.daily_bar_observations
        WHERE adjustment_mode='RAW' AND session_date='2026-09-01'""")
    assert fx.row(before_close(fx))["rawClose"] is not None
    assert fx.row()["rawClose"] is None


def test_a_newer_known_snapshot_supersedes_the_old_membership(fx):
    fx.db.execute("""
        INSERT INTO reference.universe_snapshots(universe_id,effective_date,observed_at,source,
            source_checksum,import_mode,completeness_status,expected_member_count,imported_member_count)
        SELECT universe_id,'2026-09-01','2026-09-01T19:30:00Z','fixture',repeat('e',64),
            import_mode,'PARTIAL',2,0 FROM reference.universe_snapshots WHERE universe_snapshot_id=%s
        """, (fx.sp500,))
    assert len(fx.load(before_close(fx))) == 1
    with pytest.raises(ValueError, match="SUPERSEDED_UNIVERSE_SNAPSHOT"):
        fx.load()


def test_calendar_holes_fail_instead_of_changing_trading_offsets(fx):
    fx.db.execute("DELETE FROM reference.trading_sessions WHERE exchange_mic='XNYS' "
                  "AND session_date='2026-08-03'")
    with pytest.raises(ValueError, match="INCOMPLETE_TRADING_CALENDAR"):
        fx.load()


def test_conflicting_primary_flags_and_unsupported_profiles_remain_visible(fx):
    fx.db.execute("UPDATE reference.universe_memberships SET primary_liquid_class=false "
                  "WHERE universe_snapshot_id=%s", (fx.nasdaq,))
    fx.db.execute("UPDATE fundamentals.profile_observations SET profile='BANK',"
                  "status='MODEL_NOT_SUPPORTED'")
    rows = fx.load()
    assert len(rows) == 1 and rows[0]["reasons"]
    assert {"NON_PRIMARY_SHARE_CLASS", "MODEL_NOT_SUPPORTED"} <= reasons(rows[0])


def test_stale_facts_and_shares_cannot_certify_readiness(fx):
    fx.db.execute("UPDATE fundamentals.filings SET fiscal_period_end='2025-12-31',"
                  "filed_date='2026-02-01',accepted_at='2026-02-01',published_at='2026-02-01'")
    fx.db.execute("UPDATE fundamentals.fundamental_facts SET period_end='2025-12-31'")
    assert {"STALE_SHARES", "STALE_OR_MISSING_FILING", "MISSING_REQUIRED_METRIC"} <= reasons(fx.row())
