"""Port of scoring-service ScoringRunIntegrationTest to the Python publication (model 1.5.0).

The run lifecycle, the database publication guards, timing policies, coverage readiness and
idempotency are exercised against a freshly migrated database. Where Java mocked the input
repository, the loader of ``document`` is replaced and its calls are counted.
"""

from __future__ import annotations

import hashlib
import json
import threading
import uuid
from dataclasses import replace
from datetime import UTC, date, datetime, timedelta
from decimal import Decimal
from pathlib import Path

import psycopg
import pytest
from test_scoring_inputs import calendar, one, snapshot

from quant_research.model.flexible import score as flexible_score
from quant_research.publication import canonical_json, document
from quant_research.publication.document import inputs_ready
from quant_research.publication.inputs import CURRENT, PRE_OPEN, ScoringRequest, project_input
from quant_research.publication.publish import PREPROCESSING_VERSION, publish
from quant_research.publication.requests import current_request, month_end_request

pytestmark = pytest.mark.integration
DAY = date(2026, 9, 30)
CLOSE = datetime(2026, 9, 30, 20, tzinfo=UTC)
OPEN = datetime(2026, 10, 1, 13, 30, tzinfo=UTC)
GOLDEN = Path(__file__).resolve().parents[2] / "fixtures/model-v1/prepared.json"
MARKET = {"RAW_CLOSE", "ADJUSTED_CLOSE", "MOMENTUM_OLD", "MOMENTUM_RECENT", "MEDIAN_DOLLAR_VOLUME",
          "SHARES_OUTSTANDING"}


def sha(text: str) -> str:
    return hashlib.sha256(text.encode()).hexdigest()


class Loader:
    """Stands in for the input query; counts calls like Mockito verify(times)."""

    def __init__(self, section):
        self.section, self.calls, self.failures = section, 0, []
        self.lock = threading.Lock()

    def __call__(self, connection, request):
        with self.lock:
            self.calls += 1
            if self.failures:
                raise self.failures.pop(0)
        return self.section


class Runs:
    def __init__(self, db, database, dataset, artifact, sp500, nasdaq, classification, rows,
                 jurisdictions, monkeypatch):
        self.db, self.database, self.dataset, self.artifact = db, database, dataset, artifact
        self.sp500, self.nasdaq, self.classification = sp500, nasdaq, classification
        self.rows, self.jurisdictions = rows, jurisdictions
        self.request = ScoringRequest(
            score_date=DAY, market_cutoff=CLOSE, knowledge_cutoff=CLOSE, sp500_snapshot=sp500,
            nasdaq100_snapshot=nasdaq, raw_dataset=dataset, adjusted_dataset=dataset,
            adjustment_basis=DAY, classification_version=classification,
            mapping_version="sec-us-gaap-v1", required_metrics=("TOTAL_ASSETS",))
        self.loader = Loader(self.section(self.request, rows))
        monkeypatch.setattr(document, "load", self.loader)

    @staticmethod
    def section(request, rows):
        return canonical_json.read(canonical_json.write({"request": request.to_json(),
                                                         "inputs": rows}))

    def publish(self, request=None, effective=OPEN, jurisdictions=None, **options):
        with psycopg.connect(self.database, autocommit=True) as connection:
            return publish(connection, request or self.request, effective_from=effective,
                           jurisdictions=self.jurisdictions if jurisdictions is None else jurisdictions,
                           **options)

    def scalar(self, sql, params=None):
        return one(self.db, sql, params)

    def count(self, table):
        return self.scalar(f"SELECT count(*) FROM {table}")


@pytest.fixture
def runs(db, database, monkeypatch) -> Runs:
    provider = one(db, "INSERT INTO operations.data_providers (code,name,license_notes) VALUES ('fixture','Fixture','test') RETURNING provider_id")
    dataset = one(db, "INSERT INTO operations.datasets (provider_id,code,version,license_notes) VALUES (%s,'bars','1','test') RETURNING dataset_id", (provider,))
    issuer = one(db, "INSERT INTO reference.issuers (legal_name) VALUES ('Fixture') RETURNING issuer_id")
    fix = one(db, "INSERT INTO reference.instruments (issuer_id,security_type,share_class,currency,primary_exchange_mic,valid_from) VALUES (%s,'COMMON_STOCK','A','USD','XNAS','2026-08-01') RETURNING instrument_id", (issuer,))
    sp500, nasdaq = snapshot(db, "SP500", fix), snapshot(db, "NASDAQ100", fix)
    classification = one(db, "INSERT INTO reference.classification_versions(source,version,mapping_checksum) VALUES('scoring','1',repeat('f',64)) RETURNING classification_version_id")
    artifact = one(db, "INSERT INTO operations.source_artifacts(dataset_id,request_key,source_uri,retrieved_at,content_hash,media_type,parser_version,inline_content) VALUES(%s,'scoring-fixture','test://golden','2026-08-01',repeat('e',64),'application/json','fixture','{}') RETURNING source_artifact_id", (dataset,))
    template = next(r for r in json.loads(GOLDEN.read_text())["rows"] if r["profile"] == "GENERAL")
    values, provenance = template["values"], template["provenance"]
    db.execute("DELETE FROM reference.universe_memberships WHERE universe_snapshot_id IN (%s,%s)", (sp500, nasdaq))
    rows, jurisdictions = [], {}
    for i in range(12):
        issuer = one(db, "INSERT INTO reference.issuers(legal_name) VALUES(%s) RETURNING issuer_id", (f"Golden {i}",))
        stock = one(db, "INSERT INTO reference.instruments(issuer_id,security_type,share_class,currency,primary_exchange_mic,valid_from) VALUES(%s,'COMMON_STOCK','A','USD','XNYS','2025-01-01') RETURNING instrument_id", (issuer,))
        jurisdictions[str(issuer)] = "US"
        db.execute("INSERT INTO reference.universe_memberships VALUES(%s,%s,%s,'XNYS',true,clock_timestamp())", (sp500, stock, f"G{i}"))
        facts = []
        for key, value in values.items():
            if key.startswith("ANNUAL_") or key in MARKET:
                continue
            end = date.fromisoformat(provenance[key]["period_end"])
            metric, start = key, None
            if key.endswith("_TTM"):
                metric, start = key[:-4], (end.replace(year=end.year - 1) + timedelta(days=1)).isoformat()
            elif key.endswith("_PRIOR"):
                metric = key[:-6]
            facts.append({"factId": str(uuid.uuid4()), "filingId": str(uuid.uuid4()), "metric": metric,
                          "start": start, "end": end.isoformat(), "value": Decimal(str(value)),
                          "unit": "USD", "availableAt": "2026-08-01T00:00:00Z",
                          "observedAt": "2026-08-01T00:00:00Z", "filedDate": "2026-08-01",
                          "sourceArtifactId": str(artifact), "mappingVersion": "sec-us-gaap-v1",
                          "parserVersion": "fixture"})
        rows.append(project_input({
            "instrumentId": str(stock), "issuerId": str(issuer), "symbol": f"G{i}", "inSp500": True,
            "inNasdaq100": False, "primaryClass": True,
            "profile": "UNSUPPORTED" if i == 11 else "GENERAL", "sector": "Technology",
            "peerGroup": "Technology", "rawClose": None if i == 10 else values["RAW_CLOSE"],
            "adjustedClose": values["ADJUSTED_CLOSE"], "momentumRecent": values["MOMENTUM_RECENT"],
            "momentumOld": values["MOMENTUM_OLD"], "historyCount": 252, "liquidityCount": 20,
            "medianDollarVolume": values["MEDIAN_DOLLAR_VOLUME"],
            "sharesOutstanding": values["SHARES_OUTSTANDING"], "shareFactId": str(uuid.uuid4()),
            "facts": facts, "reasons": [],
            "priceLineage": [{"date": DAY.isoformat(), "mode": "RAW", "observationKey": "a" * 64,
                              "sourceArtifactId": str(artifact)}]}))
    db.execute("UPDATE reference.universe_snapshots SET expected_member_count=12,imported_member_count=12 WHERE universe_snapshot_id=%s", (sp500,))
    db.execute("UPDATE reference.universe_snapshots SET expected_member_count=0,imported_member_count=0 WHERE universe_snapshot_id=%s", (nasdaq,))
    for mode in ("RAW", "SPLIT_DIVIDEND"):
        config = canonical_json.write({"eventType": "DAILY_PRICE", "feed": "sip", "adjustment": mode,
                                       "adjustmentAsOf": DAY.isoformat()})
        definition = one(db, "INSERT INTO operations.job_definitions(dataset_id,code,version,configuration,max_attempts) VALUES(%s,%s,'1',CAST(%s AS jsonb),3) RETURNING job_definition_id", (dataset, f"scoring-{mode}", config))
        run = one(db, """INSERT INTO operations.ingestion_runs(logical_key,job_definition_id,dataset_id,run_mode,request_key,window_start,window_end,expected_count,application_version)
            VALUES(%s,%s,%s,'backfill','scoring-fixture',%s,%s,12,'test') RETURNING ingestion_run_id""", (sha(mode), definition, dataset, DAY, DAY))
        db.execute("INSERT INTO operations.ingestion_run_snapshots VALUES (%s,%s),(%s,%s)", (run, sp500, run, nasdaq))
        db.execute("INSERT INTO operations.ingestion_run_items (ingestion_run_id,instrument_id,item_key) SELECT %s,instrument_id,instrument_id::text FROM reference.universe_memberships WHERE universe_snapshot_id IN (%s,%s) GROUP BY instrument_id", (run, sp500, nasdaq))
        db.execute("UPDATE operations.ingestion_runs SET status='COMPLETE',staged_count=expected_count,accepted_count=expected_count,completed_at=clock_timestamp() WHERE ingestion_run_id=%s", (run,))
        db.execute("INSERT INTO operations.data_coverage(dataset_id,partition_key,boundary_date,ingestion_run_id,expected_count,accepted_count,validated_at) VALUES(%s,%s,%s,%s,12,12,clock_timestamp())", (dataset, mode, DAY, run))
    return Runs(db, database, dataset, artifact, sp500, nasdaq, classification, rows, jurisdictions,
                monkeypatch)


def model_counts(runs: Runs) -> tuple[int, int]:
    prepared = json.loads(canonical_json.write(document.prepare(runs.loader.section,
                                                                runs.jurisdictions)))
    report = flexible_score(prepared["rows"], as_of=prepared["as_of"], version="1.5.0")
    return report["eligible_count"], report["excluded_count"]


def test_publishes_exact_union_with_factors_exclusions_and_source_lineage(runs):
    run_id = runs.publish()
    assert runs.scalar("SELECT state FROM research.scoring_runs") == "PUBLISHED"
    assert runs.scalar("SELECT preprocessing_version FROM research.scoring_runs") == PREPROCESSING_VERSION
    assert runs.scalar("SELECT p.implementation_version FROM research.scoring_runs r JOIN research.model_parity_certifications p USING(certification_id)") == "python-flexible-1.5.0"
    assert runs.count("research.stock_scores") == 12
    scored, excluded = model_counts(runs)
    assert (runs.scalar("SELECT scored_count FROM research.scoring_runs"),
            runs.scalar("SELECT excluded_count FROM research.scoring_runs")) == (scored, excluded)
    assert scored + excluded == 12 and scored > 0
    assert runs.scalar("""
        SELECT count(*) FROM research.stock_scores s JOIN research.score_lineage l USING(score_run_id,instrument_id)
        JOIN research.score_source_artifacts a USING(score_run_id,instrument_id)
        JOIN operations.source_artifacts f USING(source_artifact_id)
        WHERE s.eligible AND f.source_uri='test://golden' AND l.prepared_input->'provenance' IS NOT NULL""") == scored
    assert runs.scalar("""
        SELECT count(*) FROM research.stock_scores s WHERE eligible AND abs(composite_z-(SELECT sum(contribution)
            FROM research.factor_observations f WHERE f.score_run_id=s.score_run_id AND f.instrument_id=s.instrument_id))>0.00001""") == 0
    assert runs.publish() == run_id and runs.loader.calls == 1
    with pytest.raises(psycopg.Error, match="immutable"):
        runs.db.execute("DELETE FROM research.stock_scores WHERE score_run_id=%s", (run_id,))
    with pytest.raises(psycopg.Error, match="immutable"):
        runs.db.execute("UPDATE research.scoring_runs SET expected_count=1 WHERE score_run_id=%s", (run_id,))


def crash(_run_id):
    raise RuntimeError("injected crash")


def test_rollback_after_batches_resumes_frozen_inputs_without_reload(runs):
    with pytest.raises(RuntimeError, match="injected crash"):
        runs.publish(before_publication=crash)
    assert runs.count("research.stock_scores") == 0 and runs.count("research.score_lineage") == 0
    assert runs.scalar("SELECT state FROM research.scoring_runs") == "FAILED"
    frozen = runs.scalar("SELECT input_sha256 FROM research.scoring_runs")
    runs.loader.failures.append(RuntimeError("inputs changed after capture"))
    runs.publish()
    assert runs.scalar("SELECT input_sha256 FROM research.scoring_runs") == frozen
    assert runs.scalar("SELECT attempts FROM research.scoring_runs") == 2
    assert runs.loader.calls == 1


def test_simultaneous_workers_produce_one_logical_run(runs):
    results = []
    workers = [threading.Thread(target=lambda: results.append(runs.publish())) for _ in range(2)]
    for worker in workers:
        worker.start()
    for worker in workers:
        worker.join(120)
    assert len(results) == 2 and results[0] == results[1]
    assert runs.count("research.scoring_runs") == 1 and runs.count("research.stock_scores") == 12
    assert runs.loader.calls == 1


def test_database_rejects_missing_factor_despite_matching_counts(runs):
    with pytest.raises(psycopg.Error, match="Missing factors"):
        _publish_with_statement(runs, "DELETE FROM research.factor_observations WHERE score_run_id=%s AND metric='momentum'")
    assert runs.scalar("SELECT state FROM research.scoring_runs") == "FAILED"
    assert runs.count("research.stock_scores") == 0


def _publish_with_statement(runs, sql):
    """Run a statement inside the publication transaction, just before PUBLISHED (Java seam)."""
    from quant_research.publication import publish as module
    original = module.write_results

    def write_then(cursor, rows):
        original(cursor, rows)
        cursor.execute(sql, (rows.scores[0][0],))
    module.write_results = write_then
    try:
        return runs.publish()
    finally:
        module.write_results = original


def test_flexible_publication_rejects_inconsistent_factor_weights(runs):
    with pytest.raises(psycopg.Error, match="Missing factors"):
        _publish_with_statement(runs, "UPDATE research.factor_observations SET weight=2 WHERE score_run_id=%s AND clipped_score IS NOT NULL")
    assert runs.count("research.stock_scores") == 0
    assert runs.scalar("SELECT state FROM research.scoring_runs") == "FAILED"


def test_database_rejects_missing_expected_instrument(runs):
    runs.loader.section = Runs.section(runs.request, runs.rows[:11])
    with pytest.raises(psycopg.Error, match="exact snapshot union"):
        runs.publish()
    assert runs.scalar("SELECT state FROM research.scoring_runs") == "FAILED"


def test_failed_query_retries_and_uncertified_model_cannot_be_approved(runs):
    with pytest.raises(psycopg.Error, match="without matching parity"):
        runs.db.execute("UPDATE research.model_versions SET approval_state='APPROVED'")
    runs.loader.failures.append(ValueError("incomplete snapshots"))
    with pytest.raises(ValueError, match="incomplete snapshots"):
        runs.publish()
    assert runs.scalar("SELECT state FROM research.scoring_runs") == "FAILED"
    runs.publish()
    assert runs.loader.calls == 2
    assert runs.scalar("SELECT state FROM research.scoring_runs") == "PUBLISHED"


def test_insertion_cannot_bypass_publication_and_captured_inputs_cannot_change(runs):
    with pytest.raises(RuntimeError):
        runs.publish(before_publication=crash)
    with pytest.raises(psycopg.Error, match="Captured"):
        runs.db.execute("UPDATE research.scoring_runs SET input_document='{}',input_sha256=encode(sha256(convert_to('{}','UTF8')),'hex')")
    with pytest.raises(psycopg.Error, match="must start pending"):
        runs.db.execute("""
            INSERT INTO research.scoring_runs(logical_key,as_of_date,market_cutoff,knowledge_cutoff,effective_from,
                sp500_snapshot_id,nasdaq100_snapshot_id,model_version_id,certification_id,candidate,input_version,request,state)
            SELECT repeat('a',64),as_of_date,market_cutoff,knowledge_cutoff,effective_from,sp500_snapshot_id,nasdaq100_snapshot_id,
                model_version_id,certification_id,candidate,input_version,request,'PUBLISHED' FROM research.scoring_runs""")


def test_publication_rejects_changed_lineage_and_missing_exclusions(runs):
    # Model 1.5 scores every golden row; one blocking reason gives an eligibility exclusion.
    rows = [dict(r) for r in runs.rows]
    rows[11]["reasons"] = [{"code": "PRICE_BELOW_MINIMUM", "detail": None}]
    runs.loader.section = Runs.section(runs.request, rows)
    assert model_counts(runs)[1] >= 1
    with pytest.raises(psycopg.Error, match="lineage differs"):
        _publish_with_statement(runs, "UPDATE research.score_lineage SET prepared_input='{}' WHERE score_run_id=%s")
    with pytest.raises(psycopg.Error, match="Missing factors"):
        _publish_with_statement(runs, "DELETE FROM research.score_exclusions WHERE score_run_id=%s AND stage='ELIGIBILITY'")
    assert runs.count("research.stock_scores") == 0


def test_explicit_new_input_configuration_supersedes_without_mutating_published_run(runs):
    first = runs.publish()
    second = runs.publish(jurisdictions={})
    assert second != first
    assert runs.scalar("SELECT supersedes FROM research.scoring_runs WHERE score_run_id=%s", (second,)) == first
    assert runs.scalar("SELECT count(*) FROM research.scoring_runs WHERE state='PUBLISHED'") == 2


def test_incomplete_daily_collection_does_not_freeze_partial_inputs(runs):
    runs.db.execute("UPDATE operations.data_coverage SET valid=false WHERE partition_key='SPLIT_DIVIDEND'")
    with psycopg.connect(runs.database) as c:
        assert not inputs_ready(c, runs.request)
    with pytest.raises(RuntimeError, match="WAITING_FOR_DAILY_COVERAGE"):
        runs.publish()
    assert runs.loader.calls == 0
    assert runs.scalar("SELECT input_document IS NULL FROM research.scoring_runs")
    runs.db.execute("UPDATE operations.data_coverage SET valid=true")
    runs.publish()
    assert runs.scalar("SELECT state FROM research.scoring_runs") == "PUBLISHED"


def ready(runs, request) -> bool:
    with psycopg.connect(runs.database) as c:
        return inputs_ready(c, request)


def test_current_snapshot_requires_durable_missing_session_evidence_for_every_member(runs):
    current = replace(runs.request, knowledge_cutoff=CLOSE + timedelta(seconds=60), timing_policy=CURRENT)
    db = runs.db
    db.execute("DELETE FROM operations.data_coverage")
    db.execute("UPDATE operations.ingestion_runs SET status='FAILED'")
    db.execute("UPDATE operations.ingestion_run_items SET status='COMPLETE'")
    item = one(db, "SELECT ingestion_run_item_id FROM operations.ingestion_run_items LIMIT 1")
    db.execute("UPDATE operations.ingestion_run_items SET status='FAILED',failure='{\"code\":\"MISSING_SESSION\"}',updated_at=%s WHERE ingestion_run_item_id=%s", (CLOSE, item))
    assert not ready(runs, current)
    attempt = uuid.uuid4()
    db.execute("INSERT INTO operations.ingestion_attempts(attempt_id,ingestion_run_item_id,attempt_number,worker_id,status,finished_at,failure) VALUES(%s,%s,1,'fixture','FAILED',%s,'{\"code\":\"MISSING_SESSION\"}')", (attempt, item, CLOSE))
    db.execute("INSERT INTO operations.ingestion_checkpoints(ingestion_run_item_id,attempt_id,checkpoint,updated_at) VALUES(%s,%s,'{\"nextPageToken\":\"\",\"barsSeen\":0}',%s)", (item, attempt, CLOSE))
    db.execute("INSERT INTO operations.data_quality_issues(dataset_id,ingestion_run_id,instrument_id,severity,issue_type,affected_key,evidence) SELECT %s,ingestion_run_id,instrument_id,'BLOCKING','MISSING_SESSION','2026-09-30','{}' FROM operations.ingestion_run_items WHERE ingestion_run_item_id=%s", (runs.dataset, item))
    assert not ready(runs, current)
    db.execute("UPDATE operations.source_artifacts SET parser_version='alpaca-daily-v2' WHERE source_artifact_id=%s", (runs.artifact,))
    db.execute("INSERT INTO operations.ingestion_item_artifacts VALUES(%s,%s)", (item, runs.artifact))
    assert ready(runs, current)
    assert not ready(runs, runs.request)  # Monthly/legacy policy stays strict.
    db.execute("UPDATE operations.ingestion_checkpoints SET checkpoint='{\"nextPageToken\":\"more\",\"barsSeen\":0}' WHERE ingestion_run_item_id=%s", (item,))
    assert not ready(runs, current)
    db.execute("UPDATE operations.ingestion_checkpoints SET checkpoint='{\"nextPageToken\":\"\",\"barsSeen\":0}',updated_at=%s WHERE ingestion_run_item_id=%s", (CLOSE + timedelta(seconds=61), item))
    assert not ready(runs, current)
    db.execute("UPDATE operations.ingestion_checkpoints SET updated_at=%s WHERE ingestion_run_item_id=%s", (CLOSE, item))
    db.execute("UPDATE operations.ingestion_run_items SET failure='{\"code\":\"HTTP_ERROR\"}' WHERE ingestion_run_item_id=%s", (item,))
    assert not ready(runs, current)


def known_calendar(runs, through: date) -> None:
    calendar(runs.db, date(2026, 9, 1), through)
    runs.db.execute("UPDATE reference.trading_sessions SET available_at='2026-01-01',observed_at='2026-01-01'")


def requests_at(runs, now):
    return dict(raw_dataset=runs.dataset, adjusted_dataset=runs.dataset,
                classification=runs.classification, mapping_version="sec-us-gaap-v1", now=now)


def test_current_snapshot_rejects_wrong_opening_late_calendar_and_future_cutoff(runs):
    known_calendar(runs, date(2026, 10, 2))
    cutoff = datetime(2026, 10, 1, 13, 5, tzinfo=UTC)
    current = replace(runs.request, knowledge_cutoff=cutoff, adjustment_basis=DAY + timedelta(days=1),
                      timing_policy=CURRENT)
    with pytest.raises(psycopg.Error, match="Invalid current-snapshot"):
        runs.publish(current, OPEN)
    runs.db.execute("UPDATE reference.trading_sessions SET observed_at='2026-10-01T13:06:00Z' WHERE session_date='2026-10-02'")
    with pytest.raises(psycopg.Error, match="Invalid current-snapshot"):
        runs.publish(current, OPEN + timedelta(days=1))
    with psycopg.connect(runs.database) as c:
        with pytest.raises(ValueError, match="elapsed explicit"):
            current_request(c, DAY, cutoff, **requests_at(runs, datetime(2026, 10, 1, 13, tzinfo=UTC)))
        with pytest.raises(ValueError, match="latest completed"):
            current_request(c, DAY - timedelta(days=1), cutoff - timedelta(minutes=10),
                            **requests_at(runs, datetime(2026, 10, 1, 13, tzinfo=UTC)))
    assert runs.count("research.scoring_runs") == 0


def test_delayed_daily_run_waits_until_the_fixed_pre_open_decision(runs):
    known_calendar(runs, date(2026, 10, 1))
    with psycopg.connect(runs.database) as c:
        assert month_end_request(c, DAY, **requests_at(runs, datetime(2026, 9, 30, 21, tzinfo=UTC))) is None
    assert runs.count("research.scoring_runs") == 0


def test_late_next_opening_cannot_be_skipped_for_a_later_known_session(runs):
    known_calendar(runs, date(2026, 10, 2))
    runs.db.execute("UPDATE reference.trading_sessions SET observed_at='2026-10-01T12:00:00Z' WHERE session_date='2026-10-01'")
    with psycopg.connect(runs.database) as c:
        assert month_end_request(c, DAY, **requests_at(runs, datetime(2026, 10, 2, 12, tzinfo=UTC))) is None
    assert runs.count("research.scoring_runs") == 0 and runs.loader.calls == 0


def test_database_rejects_incorrect_pre_open_execution_and_late_calendar(runs):
    known_calendar(runs, date(2026, 10, 1))
    delayed = replace(runs.request, knowledge_cutoff=OPEN - timedelta(minutes=30),
                      adjustment_basis=DAY + timedelta(days=1), timing_policy=PRE_OPEN)
    with pytest.raises(psycopg.Error, match="Invalid pre-open scoring timing or calendar"):
        runs.publish(delayed, OPEN + timedelta(seconds=60))
    runs.db.execute("UPDATE reference.trading_sessions SET observed_at='2026-10-01T12:00:00Z' WHERE session_date='2026-10-01'")
    with pytest.raises(psycopg.Error, match="Invalid pre-open scoring timing or calendar"):
        runs.publish(delayed, OPEN)
    assert runs.count("research.scoring_runs") == 0 and runs.loader.calls == 0


def test_new_startup_clock_does_not_change_month_end_identity(runs):
    known_calendar(runs, date(2026, 10, 1))
    runs.db.execute("UPDATE operations.job_definitions SET configuration=jsonb_set(configuration,'{adjustmentAsOf}','\"2026-10-01\"') WHERE code='scoring-SPLIT_DIVIDEND'")
    identities = []
    for now in (datetime(2026, 10, 2, tzinfo=UTC), datetime(2026, 10, 15, tzinfo=UTC)):
        with psycopg.connect(runs.database) as c:
            request, opening = month_end_request(c, DAY, **requests_at(runs, now))
        runs.loader.section = Runs.section(request, runs.rows)
        # No tax jurisdiction is inferred for scheduled runs; other quality metrics still count.
        identities.append(runs.publish(request, opening, jurisdictions={}))
    assert identities[0] == identities[1]
    assert runs.count("research.scoring_runs") == 1 and runs.loader.calls == 1


def seed_real_inputs(runs) -> None:
    db = runs.db
    db.execute("""
        INSERT INTO reference.trading_sessions(exchange_mic,session_date,opens_at,closes_at,timezone,holiday,early_close,source,available_at,observed_at)
        SELECT 'XNYS',d::date,CASE WHEN extract(isodow FROM d)<6 THEN (d+interval '13 hours 30 minutes') AT TIME ZONE 'UTC' END,
            CASE WHEN extract(isodow FROM d)<6 THEN (d+interval '20 hours') AT TIME ZONE 'UTC' END,
            'America/New_York',extract(isodow FROM d)>5,false,'fixture','2025-01-01','2025-01-01'
        FROM generate_series('2025-06-01'::timestamp,'2026-10-02',interval '1 day') d""")
    for row in runs.rows:
        db.execute("INSERT INTO reference.instrument_symbols(instrument_id,symbol,exchange_mic,effective_from,source,available_at,observed_at) VALUES(%s,%s,'XNYS','2025-01-01','fixture','2025-01-01','2025-01-01')", (row["instrumentId"], row["symbol"]))
        db.execute("INSERT INTO reference.issuer_classifications(issuer_id,classification_version_id,mapped_sector,effective_from,available_at,observed_at) VALUES(%s,%s,'Technology','2025-01-01','2025-01-01','2025-01-01')", (row["issuerId"], runs.classification))
        observation = sha(row["instrumentId"])
        db.execute("INSERT INTO market_data.observations(observation_key,dataset_id,instrument_id,event_type,economic_time,adjustment_mode,payload,source_artifact_id,observed_at) VALUES(%s,%s,%s,'FILING_FACTS','2026-06-30','NONE','{}',%s,'2026-08-01')", (observation, runs.dataset, row["instrumentId"], runs.artifact))
        filing = one(db, """INSERT INTO fundamentals.filings(issuer_id,accession,revision_hash,form,fiscal_period_end,filed_date,accepted_at,published_at,available_at,observed_at,ingested_at,primary_document,parser_version,mapping_version,source_artifact_id,observation_key)
            VALUES(%s,%s,repeat('a',64),'10-Q','2026-06-30','2026-08-01','2026-08-01','2026-08-01','2026-08-01','2026-08-01','2026-08-01','fixture','fixture','sec-us-gaap-v1',%s,%s) RETURNING filing_id""", (row["issuerId"], row["instrumentId"], runs.artifact, observation))
        facts = list(row["facts"]) + [{"factId": row["shareFactId"], "metric": "SHARES_OUTSTANDING", "start": None, "end": "2026-06-30", "value": row["sharesOutstanding"], "unit": "shares"}]
        for fact in facts:
            db.execute("""INSERT INTO fundamentals.fundamental_facts(fact_id,issuer_id,filing_id,metric_code,mapping_version,taxonomy,source_concept,period_start,period_end,source_value,numeric_value,unit,dimensions,source_context,source_fact_hash,priority,quality_state,available_at,observed_at,source_artifact_id)
                VALUES(%s,%s,%s,%s,'sec-us-gaap-v1','us-gaap',%s,%s,%s,%s,%s,%s,'{}','{}',%s,1,'VALID','2026-08-01','2026-08-01',%s)""",
                       (fact["factId"], row["issuerId"], filing, fact["metric"], fact["metric"], fact["start"], fact["end"], fact["value"], fact["value"], fact["unit"], sha(fact["factId"]), runs.artifact))
        db.execute("INSERT INTO fundamentals.instrument_share_facts VALUES(%s,%s,'SINGLE_SHARE_CLASS','2026-08-01')", (row["instrumentId"], row["shareFactId"]))
        db.execute("INSERT INTO fundamentals.profile_observations VALUES(%s,%s,'GENERAL','FACTS_AVAILABLE','fixture','2026-08-01',%s)", (row["issuerId"], observation, runs.artifact))
    db.execute("""
        INSERT INTO market_data.observations(observation_key,dataset_id,instrument_id,event_type,economic_time,adjustment_mode,payload,source_artifact_id,observed_at,ingested_at)
        SELECT md5(i.instrument_id::text||s.session_date||m.mode)||md5(i.instrument_id::text||s.session_date||m.mode),%s,i.instrument_id,'DAILY_PRICE',s.session_date,m.mode,'{}',%s,'2026-10-01T12:00:00Z','2026-10-01T12:01:00Z'
        FROM reference.instruments i CROSS JOIN reference.trading_sessions s CROSS JOIN (VALUES('RAW'),('SPLIT_DIVIDEND')) m(mode)
        WHERE s.exchange_mic='XNYS' AND NOT s.holiday AND s.session_date<='2026-09-30'""", (runs.dataset, runs.artifact))
    db.execute("""
        INSERT INTO market_data.daily_bar_observations(session_date,observation_key,instrument_id,provider_id,dataset_id,exchange_mic,feed,adjustment_mode,adjustment_as_of,currency,bar_time,open,high,low,close,volume,trade_count,source_revision,source_artifact_id,available_at,observed_at,ingested_at)
        SELECT o.economic_time::date,o.observation_key,o.instrument_id,d.provider_id,o.dataset_id,'XNYS','sip',o.adjustment_mode,CASE WHEN o.adjustment_mode='SPLIT_DIVIDEND' THEN '2026-10-01'::date END,'USD',o.economic_time,100,110,90,100,1000000,1000,repeat('d',64),%s,'2026-10-01T12:00:00Z','2026-10-01T12:00:00Z','2026-10-01T12:01:00Z'
        FROM market_data.observations o JOIN operations.datasets d USING(dataset_id) WHERE o.event_type='DAILY_PRICE'""", (runs.artifact,))
    db.execute("UPDATE operations.job_definitions SET configuration=jsonb_set(configuration,'{adjustmentAsOf}','\"2026-10-01\"') WHERE code='scoring-SPLIT_DIVIDEND'")


@pytest.mark.parametrize("current", [False, True], ids=["pre-open", "current"])
@pytest.mark.parametrize("too_late", [False, True], ids=["on-time", "too-late"])
def test_real_timed_publication_retries_without_future_leakage(runs, monkeypatch, current, too_late):
    from quant_research.publication import inputs as real_inputs
    monkeypatch.setattr(document, "load", real_inputs.load)  # the real input query from here on
    seed_real_inputs(runs)
    now = datetime(2026, 10, 1, 13, 5, tzinfo=UTC)

    def calculate():
        with psycopg.connect(runs.database) as c:
            built = (current_request(c, DAY, now, **requests_at(runs, now)) if current
                     else month_end_request(c, DAY, **requests_at(runs, now)))
            if built is None or not inputs_ready(c, built[0]):
                return False
        runs.publish(built[0], built[1], jurisdictions={})
        return True

    runs.db.execute("UPDATE operations.data_coverage SET valid=false")
    assert calculate() is False and runs.count("research.scoring_runs") == 0
    runs.db.execute("UPDATE operations.data_coverage SET valid=true")
    if too_late:
        runs.db.execute("UPDATE market_data.daily_bar_observations SET observed_at='2026-10-01T13:06:00Z'")
        with pytest.raises(RuntimeError, match="INSUFFICIENT_UNIVERSE"):
            calculate()
        assert runs.scalar("SELECT state FROM research.scoring_runs") == "FAILED"
        assert runs.count("research.stock_scores") == 0
        return
    assert calculate() is True
    assert runs.scalar("SELECT state FROM research.scoring_runs") == "PUBLISHED"
    assert runs.scalar("SELECT scored_count FROM research.scoring_runs") == 12
    assert runs.scalar("SELECT request->'inputs'->>'timingPolicy' FROM research.scoring_runs") == (CURRENT if current else PRE_OPEN)
    assert runs.scalar("SELECT knowledge_cutoff<effective_from FROM research.scoring_runs")
    if current:
        assert runs.scalar("SELECT effective_from FROM research.scoring_runs") == datetime(2026, 10, 2, 13, 30, tzinfo=UTC)
    captured = runs.scalar("SELECT input_sha256 FROM research.scoring_runs")
    # A later provider correction cannot change a captured run on restart.
    runs.db.execute("UPDATE market_data.daily_bar_observations SET close=105,observed_at='2026-10-01T13:06:00Z'")
    assert calculate() is True
    assert runs.count("research.scoring_runs") == 1
    assert runs.scalar("SELECT input_sha256 FROM research.scoring_runs") == captured
