"""Frozen input document of a scoring run: canonical section, coverage evidence and preparation.

Port of the input stage of scoring-service ScoringRunService. The document and its SHA-256 are
byte-compatible with the Java publication (verified on run bead056b). Numeric semantics follow
Java: fact values are summed as binary doubles during preparation, while market fields of the
canonical section stay decimals.
"""

from __future__ import annotations

import copy
from pathlib import Path

from ..model.periods import from_cross_section
from . import canonical_json
from .inputs import CURRENT, ScoringRequest, load, named_to_psycopg

PREPROCESSING_VERSION = "canonical-preparation-v4"
OUTCOMES_QUERY = Path(__file__).resolve().parent / "sql/current-collection-outcomes.sql"
COMPLETE_COVERAGE = """
SELECT NOT EXISTS (
    SELECT 1 FROM (VALUES(CAST(:raw AS uuid),'RAW'),
        (CAST(:adjusted AS uuid),'SPLIT_DIVIDEND')) required(dataset,mode)
    WHERE NOT EXISTS (
        SELECT 1 FROM operations.data_coverage c
        JOIN operations.ingestion_runs r USING(ingestion_run_id)
        JOIN operations.job_definitions j USING(job_definition_id)
        WHERE c.dataset_id=required.dataset AND c.boundary_date=:date AND c.valid
            AND r.status='COMPLETE'
            AND j.configuration->>'eventType'='DAILY_PRICE' AND j.configuration->>'feed'='sip'
            AND j.configuration->>'adjustment'=required.mode
            AND (required.mode='RAW' OR j.configuration->>'adjustmentAsOf'=CAST(:basis AS text))
            AND EXISTS(SELECT 1 FROM operations.ingestion_run_snapshots s
                WHERE s.ingestion_run_id=r.ingestion_run_id AND s.universe_snapshot_id=:sp)
            AND EXISTS(SELECT 1 FROM operations.ingestion_run_snapshots s
                WHERE s.ingestion_run_id=r.ingestion_run_id AND s.universe_snapshot_id=:nq)
    )
)
"""


def _coverage_parameters(request: ScoringRequest) -> dict:
    return {"raw": request.raw_dataset, "adjusted": request.adjusted_dataset,
            "date": request.score_date, "basis": request.adjustment_basis.isoformat(),
            "sp": request.sp500_snapshot, "nq": request.nasdaq100_snapshot,
            "cutoff": request.knowledge_cutoff}


def complete_coverage(connection, request: ScoringRequest) -> bool:
    with connection.cursor() as cursor:
        cursor.execute(named_to_psycopg(COMPLETE_COVERAGE), _coverage_parameters(request))
        return bool(cursor.fetchone()[0])


def collection_outcomes(connection, request: ScoringRequest,
                        query: Path = OUTCOMES_QUERY) -> str | None:
    if request.timing_policy != CURRENT:
        return None
    with connection.cursor() as cursor:
        cursor.execute(named_to_psycopg(query.read_text(encoding="utf-8")),
                       _coverage_parameters(request))
        row = cursor.fetchone()
    return None if row is None else row[0]


def inputs_ready(connection, request: ScoringRequest) -> bool:
    """Complete daily coverage, or explicit collection outcomes for a current snapshot."""
    return (complete_coverage(connection, request)
            or collection_outcomes(connection, request) is not None)


def coverage(connection, request: ScoringRequest) -> dict:
    """COMPLETE_DAILY_COVERAGE, or the explicit collection outcomes of a current snapshot."""
    if complete_coverage(connection, request):
        return {"policy": "COMPLETE_DAILY_COVERAGE"}
    outcomes = collection_outcomes(connection, request)
    if outcomes is None:
        raise RuntimeError("WAITING_FOR_DAILY_COVERAGE")
    return canonical_json.read(outcomes)


def prepare(section: dict, jurisdictions: dict[str, str],
            version: str = PREPROCESSING_VERSION) -> dict:
    """Prepared rows with the Java numeric semantics (fact values as doubles)."""
    working = copy.deepcopy(section)
    for item in working["inputs"]:
        for fact in item["facts"]:
            if fact["value"] is not None:
                fact["value"] = float(fact["value"])
    cutoff, rows = from_cross_section(working, jurisdictions=jurisdictions,
                                      preprocessing_version=version)
    return {"as_of": cutoff, "rows": rows, "preprocessing_version": version}


def build(connection, request: ScoringRequest, jurisdictions: dict[str, str],
          session_time_zone: str = "UTC") -> tuple[str, dict]:
    """Return the serialized input document and its parsed content.

    The coverage evidence renders timestamps in the session time zone. Java runs used the JVM
    default zone (``Europe/Bucharest`` on the publishing machine); Python publications pin UTC
    so the document no longer depends on the host. Reproductions pass the original zone.
    """
    with connection.cursor() as cursor:
        cursor.execute("SELECT set_config('TimeZone', %s, true)", (session_time_zone,))
    evidence = coverage(connection, request)
    section = load(connection, request)
    document = {"canonical": section, "prepared": prepare(section, jurisdictions),
                "collectionCoverage": evidence}
    return canonical_json.write(document), document
