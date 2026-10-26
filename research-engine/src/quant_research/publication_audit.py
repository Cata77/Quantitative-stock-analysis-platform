"""Verify an explicit published run against its frozen inputs and Python replay."""

import argparse
import hashlib
import json
import math
from pathlib import Path
from uuid import UUID

from .model.engine import score, serialize
from .model.freeze import verify_freeze
from .model.periods import from_cross_section
from .source_audit import query


def equivalent(expected, actual, path="$"):
    if isinstance(expected, dict):
        if not isinstance(actual, dict) or expected.keys() != actual.keys():
            raise ValueError(f"{path}: keys differ")
        for key in expected:
            equivalent(expected[key], actual[key], f"{path}.{key}")
    elif isinstance(expected, list):
        if not isinstance(actual, list) or len(expected) != len(actual):
            raise ValueError(f"{path}: lengths differ")
        for index, (left, right) in enumerate(zip(expected, actual, strict=True)):
            equivalent(left, right, f"{path}[{index}]")
    elif isinstance(expected, (int, float)) and not isinstance(expected, bool):
        if (isinstance(actual, bool) or not isinstance(actual, (int, float))
                or not math.isfinite(actual) or abs(expected - actual) > 1e-9):
            raise ValueError(f"{path}: numbers differ")
    elif type(expected) is not type(actual) or expected != actual:
        raise ValueError(f"{path}: values differ")


def verify(run_id: UUID, output: Path, container: str):
    verify_freeze()
    if output.exists():
        raise ValueError("Output already exists")
    records = query(container, f"""
        SELECT score_run_id,state,as_of_date,knowledge_cutoff,preprocessing_version,
            expected_count,scored_count,excluded_count,input_sha256,input_document,request
        FROM research.scoring_runs WHERE score_run_id='{run_id}'
    """)
    if len(records) != 1 or records[0]["state"] != "PUBLISHED":
        raise ValueError("Expected one immutable published run")
    run = records[0]
    raw = run.pop("input_document")
    if hashlib.sha256(raw.encode()).hexdigest() != run["input_sha256"]:
        raise ValueError("Published input hash mismatch")
    doc = json.loads(raw)
    version = run["preprocessing_version"]
    cutoff, rows = from_cross_section(
        doc["canonical"], jurisdictions=run["request"]["jurisdictions"],
        preprocessing_version=version,
    )
    prepared = {"as_of": cutoff, "rows": rows}
    if "preprocessing_version" in doc["prepared"]:
        prepared["preprocessing_version"] = version
    equivalent(prepared, doc["prepared"])
    model = serialize(score(rows, as_of=cutoff, candidate=run["request"]["candidate"]))
    stored = query(container, f"""
        SELECT instrument_id,output FROM research.stock_scores WHERE score_run_id='{run_id}'
    """)
    expected = {r["instrument_id"]: r for r in model["rows"]}
    actual = {r["instrument_id"]: r["output"] for r in stored}
    if len(actual) != len(stored) or len(expected) != len(rows):
        raise ValueError("Duplicate instrument")
    equivalent(expected, actual)
    if (len(rows) != run["expected_count"] or model["eligible_count"] != run["scored_count"]
            or len(rows) - model["eligible_count"] != run["excluded_count"]):
        raise ValueError("Publication counts differ")
    lineage = query(container, f"""
        SELECT s.instrument_id,s.eligible,count(DISTINCT a.source_artifact_id) sources,
            bool_and(f.inline_content IS NOT NULL OR f.storage_uri IS NOT NULL) retained
        FROM research.stock_scores s
        LEFT JOIN research.score_source_artifacts a USING(score_run_id,instrument_id)
        LEFT JOIN operations.source_artifacts f USING(source_artifact_id)
        WHERE s.score_run_id='{run_id}' GROUP BY s.instrument_id,s.eligible
    """)
    if any(r["eligible"] and (not r["sources"] or not r["retained"]) for r in lineage):
        raise ValueError("Eligible result lacks retained sources")
    late = query(container, f"""
        SELECT count(*) n FROM research.score_lineage l
        CROSS JOIN LATERAL jsonb_array_elements(l.canonical_input->'priceLineage') p
        JOIN market_data.daily_bar_observations b ON b.observation_key=p->>'observationKey'
        JOIN research.scoring_runs r USING(score_run_id)
        WHERE l.score_run_id='{run_id}' AND (b.available_at>r.knowledge_cutoff
            OR b.observed_at>r.knowledge_cutoff OR b.ingested_at>r.knowledge_cutoff
            OR b.session_date>r.as_of_date)
    """)[0]["n"]
    if late:
        raise ValueError("Price lineage crosses publication cutoff")
    report = {"run": run, "pythonJavaPreparedParity": True, "pythonJavaScoreParity": True,
              "absoluteTolerance": 1e-9, "latePriceLineageRows": late, "retainedLineage": lineage}
    output.mkdir(parents=True)
    (output / "published-input-document.json").write_text(raw, encoding="utf-8", newline="")
    for name, data in [("publication-verification", report), ("published-model-evidence", model)]:
        (output / f"{name}.json").write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")
    return run


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-id", type=UUID, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--container", default="quant-platform-timescaledb-1")
    args = parser.parse_args()
    print(json.dumps(verify(args.run_id, args.output, args.container), indent=2))


if __name__ == "__main__":
    main()
