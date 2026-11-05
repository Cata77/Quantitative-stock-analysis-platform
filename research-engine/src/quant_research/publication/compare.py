"""Read-only check: Python scores and result rows of a published run versus the stored tables.

Scores the run's stored prepared rows with the Python model of the run's version, builds the
rows ScoringRunService.persist would write and compares every table exactly (numbers as
decimals, JSON columns by parsed value). Connection: ``QUANT_DB_DSN``. No writes.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from collections import Counter
from functools import partial
from uuid import UUID

import psycopg

from ..model.engine import score as frozen_score
from ..model.engine import serialize
from ..model.flexible import score as flexible_score
from . import canonical_json
from .persist import result_rows

TABLES = {
    "lineage": ("score_lineage", "instrument_id,issuer_id,symbol,canonical_input::text,"
                "prepared_input::text,maximum_input_availability_timestamp"),
    "artifacts": ("score_source_artifacts", "instrument_id,source_artifact_id"),
    "scores": ("stock_scores", "instrument_id,scoring_profile,peer_group,eligible,value_score,"
               "quality_score,momentum_score,value_contribution,quality_contribution,"
               "momentum_contribution,composite_z,composite_percentile,ordinal_rank,"
               "available_metric_count,missing_metric_count,warnings::text,output::text"),
    "factors": ("factor_observations", "instrument_id,metric,family,raw_value,transformed_value,"
                "winsorized_value,cohort,cohort_count,lower_bound,upper_bound,cohort_mean,"
                "cohort_deviation,z_score,clipped_score,weight,contribution,reason"),
    "exclusions": ("score_exclusions", "instrument_id,stage,reason_code,affected_metric,detail"),
}
JSON_COLUMNS = {"lineage": (3, 4), "scores": (15, 16)}


def scorer(version: str):
    if version == "1.2.0":
        return frozen_score
    if version in {"1.3.0", "1.4.0", "1.5.0"}:
        return partial(flexible_score, version=version)
    raise ValueError("Unsupported model version")


def _normalize(name: str, row: tuple) -> tuple:
    values = list(row)
    for index in JSON_COLUMNS.get(name, ()):
        if values[index] is not None:
            values[index] = canonical_json.write(canonical_json.read(values[index]))
    return tuple(values)


def stored_rows(connection, run_id: UUID, name: str) -> list[tuple]:
    table, columns = TABLES[name]
    with connection.cursor() as cursor:
        cursor.execute(f"SELECT {columns} FROM research.{table} WHERE score_run_id=%s", (run_id,))
        return [_normalize(name, r) for r in cursor.fetchall()]


def compare(connection, run_id: UUID) -> dict:
    with connection.cursor() as cursor:
        cursor.execute("""
            SELECT r.input_document, r.request::text, m.semantic_version, r.scored_count,
                   r.excluded_count FROM research.scoring_runs r
            JOIN research.model_versions m USING(model_version_id)
            WHERE r.score_run_id=%s AND r.state='PUBLISHED'
        """, (run_id,))
        raw, request, version, scored, excluded = cursor.fetchone()
    document = canonical_json.read(raw)
    floats = json.loads(raw)  # Java re-read the stored document and scored doubles
    prepared = floats["prepared"]
    report = scorer(version)(prepared["rows"], as_of=prepared["as_of"],
                             candidate=json.loads(request)["candidate"])
    rebuilt = result_rows(run_id, document, report, serialize)
    summary = {"runId": str(run_id), "modelVersion": version,
               "counts": {"scored": report["eligible_count"] == scored,
                          "excluded": report["excluded_count"] == excluded},
               "tables": {}}
    for name in TABLES:
        mine = Counter(_normalize(name, row[1:]) for row in getattr(rebuilt, name))
        theirs = Counter(stored_rows(connection, run_id, name))
        summary["tables"][name] = {"rows": sum(theirs.values()), "identical": mine == theirs,
                                   "onlyPython": sum((mine - theirs).values()),
                                   "onlyStored": sum((theirs - mine).values())}
        if mine != theirs:
            summary["tables"][name]["example"] = repr(next(iter(mine - theirs), None))[:600]
            summary["tables"][name]["storedExample"] = repr(next(iter(theirs - mine), None))[:600]
    summary["identical"] = all(summary["counts"].values()) and all(
        t["identical"] for t in summary["tables"].values())
    return summary


def _sections(raw: str) -> dict:
    document = canonical_json.read(raw)
    return {name: canonical_json.sha256(canonical_json.write(document[name]))
            for name in ("canonical", "prepared")}


def compare_runs(connection, reference: UUID, candidate: UUID) -> dict:
    """Every result table and the frozen input sections of two published runs."""
    runs = {}
    for name, run_id in (("reference", reference), ("candidate", candidate)):
        with connection.cursor() as cursor:
            cursor.execute("""
                SELECT r.state, r.input_document, r.expected_count, r.scored_count,
                       r.excluded_count, m.semantic_version, p.implementation_version
                FROM research.scoring_runs r JOIN research.model_versions m USING(model_version_id)
                JOIN research.model_parity_certifications p USING(certification_id)
                WHERE r.score_run_id=%s""", (run_id,))
            state, raw, expected, scored, excluded, version, implementation = cursor.fetchone()
        runs[name] = {"state": state, "counts": [expected, scored, excluded],
                      "modelVersion": version, "implementation": implementation,
                      "sections": _sections(raw)}
    summary = {"reference": str(reference), "candidate": str(candidate),
               "implementations": [runs["reference"]["implementation"],
                                   runs["candidate"]["implementation"]],
               "published": all(r["state"] == "PUBLISHED" for r in runs.values()),
               "sameModel": runs["reference"]["modelVersion"] == runs["candidate"]["modelVersion"],
               "sameCounts": runs["reference"]["counts"] == runs["candidate"]["counts"],
               "sameInputSections": runs["reference"]["sections"] == runs["candidate"]["sections"],
               "tables": {}}
    for name in TABLES:
        left = Counter(stored_rows(connection, reference, name))
        right = Counter(stored_rows(connection, candidate, name))
        summary["tables"][name] = {"rows": sum(left.values()), "identical": left == right,
                                   "onlyReference": sum((left - right).values()),
                                   "onlyCandidate": sum((right - left).values())}
    summary["identical"] = (summary["published"] and summary["sameModel"]
                            and summary["sameCounts"] and summary["sameInputSections"]
                            and all(t["identical"] for t in summary["tables"].values()))
    return summary


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-id", type=UUID, required=True)
    args = parser.parse_args()
    dsn = os.environ.get("QUANT_DB_DSN")
    if not dsn:
        sys.exit("Set QUANT_DB_DSN")
    with psycopg.connect(dsn) as connection:
        connection.read_only = True
        with connection.transaction():
            print(json.dumps(compare(connection, args.run_id), indent=2, default=str))


if __name__ == "__main__":
    main()
