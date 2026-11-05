"""Publish one scoring run from Python, with the lifecycle of ScoringRunService.run.

Separate transactions, as in Java: (1) register the certification and the run by logical key;
(2) mark the attempt RUNNING; (3) freeze the input document once; (4) score, write every result
table and publish atomically. A published run is never touched again; a failure marks the
attempt FAILED with its reason and leaves frozen inputs for an exact retry. The database
triggers re-check lineage, cutoffs, contributions and the certification at publication.
"""

from __future__ import annotations

import json
from collections.abc import Callable
from datetime import datetime
from uuid import UUID

from ..model.engine import serialize
from ..model.flexible import score as flexible_score
from . import canonical_json
from .certification import certify
from .document import PREPROCESSING_VERSION, build
from .inputs import ScoringRequest, instant
from .persist import ResultRows, result_rows

COLLECTION_POLICY = "CURRENT_COLLECTION_OUTCOMES_V1"
INPUT_VERSION = "prepared-canonical-v2"
SUPPORTED = {"1.5.0"}
BATCH = 100


def request_document(request: ScoringRequest, effective_from: datetime,
                     jurisdictions: dict[str, str]) -> str:
    """The run request column; with the manifest SHA it defines the logical key (as in Java)."""
    return canonical_json.write({
        "inputs": request.to_json(), "jurisdictions": dict(sorted(jurisdictions.items())),
        "candidate": "primary", "effective_from": instant(effective_from),
        "preprocessingVersion": PREPROCESSING_VERSION, "collectionPolicy": COLLECTION_POLICY})


def _lock(cursor, run_id: UUID) -> tuple[str, bool]:
    cursor.execute("SELECT state, input_document IS NOT NULL FROM research.scoring_runs "
                   "WHERE score_run_id=%s FOR UPDATE", (run_id,))
    return cursor.fetchone()


def _batches(cursor, sql: str, rows: list[tuple]) -> None:
    for start in range(0, len(rows), BATCH):
        cursor.executemany(sql, rows[start:start + BATCH])


def write_results(cursor, rows: ResultRows) -> None:
    _batches(cursor, "INSERT INTO research.score_lineage VALUES "
             "(%s,%s,%s,%s,CAST(%s AS jsonb),CAST(%s AS jsonb),%s)", rows.lineage)
    _batches(cursor, "INSERT INTO research.score_source_artifacts VALUES (%s,%s,%s)",
             rows.artifacts)
    _batches(cursor, """
        INSERT INTO research.stock_scores(score_run_id,instrument_id,scoring_profile,peer_group,
            eligible,value_score,quality_score,momentum_score,value_contribution,
            quality_contribution,momentum_contribution,composite_z,composite_percentile,
            ordinal_rank,available_metric_count,missing_metric_count,warnings,output,published_at)
        VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,CAST(%s AS jsonb),
            CAST(%s AS jsonb),clock_timestamp())
        """, rows.scores)
    _batches(cursor, "INSERT INTO research.factor_observations VALUES "
             "(%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)", rows.factors)
    _batches(cursor, "INSERT INTO research.score_exclusions VALUES (%s,%s,%s,%s,%s,%s)",
             rows.exclusions)


def publish(connection, request: ScoringRequest, *, effective_from: datetime,
            jurisdictions: dict[str, str] | None = None, model_version: str = "1.5.0",
            before_publication: Callable[[UUID], None] | None = None) -> UUID:
    """Publish (or resume) the run of this request; returns its id. Needs autocommit mode."""
    if model_version not in SUPPORTED:
        raise ValueError("Unsupported scoring model version")
    if not connection.autocommit:
        raise ValueError("publish() manages its own transactions; use an autocommit connection")
    jurisdictions = jurisdictions or {}
    certification = certify(model_version)
    request_json = request_document(request, effective_from, jurisdictions)
    key = canonical_json.hash_fields(certification.manifest_sha256, request_json)
    with connection.transaction(), connection.cursor() as cursor:
        cursor.execute("SELECT model_version_id FROM research.model_versions "
                       "WHERE manifest_sha256=%s AND approval_state <> 'RETIRED'",
                       (certification.manifest_sha256,))
        (model_id,) = cursor.fetchone()
        cursor.execute("""
            INSERT INTO research.model_parity_certifications(certification_id,model_version_id,
                manifest_sha256,implementation_version,absolute_tolerance)
            VALUES (%s,%s,%s,%s,0.000000001) ON CONFLICT DO NOTHING
            """, (certification.certification_id, model_id, certification.manifest_sha256,
                  certification.implementation_version))
        cursor.execute("""
            INSERT INTO research.scoring_runs(logical_key,as_of_date,market_cutoff,
                knowledge_cutoff,effective_from,sp500_snapshot_id,nasdaq100_snapshot_id,
                model_version_id,certification_id,candidate,input_version,preprocessing_version,
                request,supersedes)
            VALUES (%(key)s,%(date)s,%(market)s,%(knowledge)s,%(effective)s,%(sp)s,%(nq)s,
                %(model)s,%(cert)s,'primary',%(input)s,%(preprocessing)s,CAST(%(request)s AS jsonb),
                (SELECT score_run_id FROM research.scoring_runs WHERE as_of_date=%(date)s
                   AND model_version_id=%(model)s AND candidate='primary' AND state='PUBLISHED'
                 ORDER BY published_at DESC,score_run_id LIMIT 1))
            ON CONFLICT(logical_key) DO NOTHING
            """, {"key": key, "date": request.score_date, "market": request.market_cutoff,
                  "knowledge": request.knowledge_cutoff, "effective": effective_from,
                  "sp": request.sp500_snapshot, "nq": request.nasdaq100_snapshot,
                  "model": model_id, "cert": certification.certification_id,
                  "input": INPUT_VERSION, "preprocessing": PREPROCESSING_VERSION,
                  "request": request_json})
        cursor.execute("SELECT score_run_id FROM research.scoring_runs WHERE logical_key=%s",
                       (key,))
        (run_id,) = cursor.fetchone()
    try:
        with connection.transaction(), connection.cursor() as cursor:
            state, _ = _lock(cursor, run_id)
            if state == "PUBLISHED":
                return run_id
            cursor.execute("""
                UPDATE research.scoring_runs SET state='RUNNING',attempts=attempts+1,
                    started_at=clock_timestamp(),finished_at=NULL,failure_reason=NULL
                WHERE score_run_id=%s""", (run_id,))
        with connection.transaction(), connection.cursor() as cursor:
            state, frozen = _lock(cursor, run_id)
            if state != "PUBLISHED" and not frozen:
                document, content = build(connection, request, jurisdictions)
                cursor.execute("""
                    UPDATE research.scoring_runs SET input_document=%s,input_sha256=%s,
                        expected_count=%s WHERE score_run_id=%s
                    """, (document, canonical_json.sha256(document),
                          len(content["canonical"]["inputs"]), run_id))
        with connection.transaction(), connection.cursor() as cursor:
            state, _ = _lock(cursor, run_id)
            if state == "PUBLISHED":
                return run_id
            cursor.execute("SELECT input_document FROM research.scoring_runs "
                           "WHERE score_run_id=%s", (run_id,))
            (raw,) = cursor.fetchone()
            prepared = json.loads(raw)["prepared"]  # doubles, as Java re-read the document
            report = flexible_score(prepared["rows"], as_of=prepared["as_of"],
                                    candidate="primary", version=model_version)
            if report.get("status") != "COMPLETE":
                raise RuntimeError("INSUFFICIENT_UNIVERSE")
            write_results(cursor, result_rows(run_id, canonical_json.read(raw), report,
                                              serialize))
            if before_publication:
                before_publication(run_id)
            cursor.execute("""
                UPDATE research.scoring_runs SET state='PUBLISHED',certification_id=%s,
                    eligible_count=%s,scored_count=%s,excluded_count=%s,
                    finished_at=clock_timestamp(),published_at=clock_timestamp(),
                    failure_reason=NULL WHERE score_run_id=%s
                """, (certification.certification_id, report["eligible_count"],
                      report["eligible_count"], report["excluded_count"], run_id))
        return run_id
    except Exception as failure:
        reason = f"{type(failure).__name__}: {failure}"[:1500]
        with connection.transaction(), connection.cursor() as cursor:
            _lock(cursor, run_id)
            cursor.execute("""
                UPDATE research.scoring_runs SET state='FAILED',finished_at=clock_timestamp(),
                    failure_reason=%s WHERE score_run_id=%s AND state<>'PUBLISHED'
                """, (reason, run_id))
        raise
