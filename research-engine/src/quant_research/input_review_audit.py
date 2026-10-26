"""Read-only common-equity selection audit; never calculates or publishes a score."""

import argparse
import hashlib
import json
import os
import re
from pathlib import Path
from uuid import UUID

import psycopg

QUERY_PATH = Path("scoring-service/src/main/resources/sql/scoring-inputs.sql")


def selection_query(sql: str) -> str:
    """Reuse production CTEs, restricting issuer work without changing fact selection."""
    issuer = "issuers AS MATERIALIZED (SELECT DISTINCT issuer_id FROM instruments)"
    boundary = ",\nfact_bundles AS ("
    if sql.count(issuer) != 1 or sql.count(boundary) != 1:
        raise ValueError("Production SQL structure changed; review audit extraction")
    prefix = sql.split(boundary)[0].replace(
        issuer,
        "issuers AS MATERIALIZED (SELECT DISTINCT issuer_id FROM instruments "
        "WHERE instrument_id=CAST(:instrument AS uuid))",
    )
    query = (
        prefix
        + """
SELECT jsonb_build_object(
    'validationErrors',to_jsonb(validation.errors),
    'facts',coalesce((SELECT jsonb_agg(to_jsonb(f) ORDER BY f.period_end,f.metric_code)
        FROM input_facts f WHERE f.fact_id=ANY(CAST(:factIds AS uuid[]))), '[]'::jsonb))
FROM validation
"""
    )
    return re.sub(r"(?<!:):([A-Za-z][A-Za-z0-9]*)", r"%(\1)s", query)


def audit(connection, publication: Path, instrument_id: str) -> dict:
    instrument_id = str(UUID(instrument_id))
    raw = (publication / "published-input-document.json").read_bytes()
    run = json.loads((publication / "publication-verification.json").read_text())["run"]
    digest = hashlib.sha256(raw).hexdigest()
    if digest != run["input_sha256"]:
        raise ValueError("Published input SHA-256 mismatch")
    document = json.loads(raw)
    members = [
        row for row in document["canonical"]["inputs"] if row["instrumentId"] == instrument_id
    ]
    if len(members) != 1:
        raise ValueError("Instrument must occur exactly once in the archived universe")
    request = document["canonical"]["request"]
    sql = QUERY_PATH.read_text(encoding="utf-8")
    query = selection_query(sql)
    with connection.transaction(), connection.cursor() as cursor:
        cursor.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ, READ ONLY")
        cursor.execute("SET LOCAL ROLE quant_scoring")
        cursor.execute("SET LOCAL statement_timeout=60000")
        cursor.execute("SET LOCAL work_mem='4MB'")
        cursor.execute("SET LOCAL max_parallel_workers_per_gather=0")
        cursor.execute("SELECT transaction_timestamp()")
        now = cursor.fetchone()[0].isoformat()
        cursor.execute(
            """SELECT coalesce(jsonb_agg(to_jsonb(r) ORDER BY recorded_at,review_id),'[]'::jsonb)
            FROM operations.input_reviews r WHERE instrument_id=%s AND kind='COMMON_EQUITY'
            AND recorded_at<=%s""",
            (instrument_id, now),
        )
        reviews = cursor.fetchone()[0]
        if not reviews:
            raise ValueError("No recorded common-equity review for instrument")
        params = dict(
            request,
            basis=request["adjustmentBasis"],
            sp500=request["sp500Snapshot"],
            nasdaq=request["nasdaq100Snapshot"],
            classification=request["classificationVersion"],
            mapping=request["mappingVersion"],
            instrument=instrument_id,
            factIds=sorted({r["fact_id"] for r in reviews}),
            requiredMetrics=request["requiredMetrics"],
        )
        selections = {}
        for name, cutoff in (
            ("publicationCutoff", request["knowledgeCutoff"]),
            ("reviewCutoff", now),
        ):
            params["knowledgeCutoff"] = cutoff
            cursor.execute(query, params)
            selections[name] = {"knowledgeCutoff": cutoff, **cursor.fetchone()[0]}
        cursor.execute(
            """SELECT adjustment_mode,max(session_date)::text
            FROM market_data.daily_bar_observations
            WHERE instrument_id=%s AND available_at<=%s AND observed_at<=%s
            GROUP BY adjustment_mode ORDER BY adjustment_mode""",
            (instrument_id, now, now),
        )
        latest_prices = dict(cursor.fetchall())
    return {
        "scope": "DATA_SELECTION_ONLY_NOT_A_SCORING_RUN",
        "published": False,
        "symbol": members[0]["symbol"],
        "instrumentId": instrument_id,
        "economicDate": request["scoreDate"],
        "auditedAt": now,
        "inputDocumentSha256": digest,
        "productionSqlSha256": hashlib.sha256(sql.encode()).hexdigest(),
        "reviews": reviews,
        "selections": selections,
        "latestRetainedPrices": latest_prices,
        "limitation": "Review-time selection of historical financial facts does not authorize "
        "a score using an expired market cutoff. Validation errors are preserved.",
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--publication", type=Path, required=True)
    parser.add_argument("--instrument-id", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        raise ValueError("Audit output already exists")
    with psycopg.connect(os.environ["SCORING_DB_DSN"], autocommit=True) as connection:
        report = audit(connection, args.publication, args.instrument_id)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(
        json.dumps(
            {"symbol": report["symbol"], "scope": report["scope"], "output": str(args.output)}
        )
    )


if __name__ == "__main__":
    main()
