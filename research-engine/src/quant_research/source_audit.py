"""Read-only Docker/PostgreSQL audit of retained SEC sources for a publication."""

from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
from datetime import UTC, datetime
from pathlib import Path
from uuid import UUID

from .exclusion_audit import index
from .model.engine import timestamp


def query(container: str, select: str) -> list[dict]:
    sql = (
        "BEGIN READ ONLY; SET LOCAL ROLE quant_research; "
        "SET LOCAL statement_timeout=120000; "
        f"SELECT coalesce(jsonb_agg(t),'[]'::jsonb) FROM ({select}) t; COMMIT;"
    )
    result = subprocess.run(
        [
            "docker",
            "exec",
            "-i",
            container,
            "psql",
            "-U",
            "postgres",
            "-d",
            "quant_platform",
            "-X",
            "-qAt",
            "-v",
            "ON_ERROR_STOP=1",
        ],
        input=sql,
        capture_output=True,
        text=True,
        encoding="utf-8",
        check=True,
    )
    return json.loads(result.stdout)


def inspect_source(bundle: dict, cutoff: str) -> dict:
    """Check the retained chain, then distinguish absent tags from absent current facts."""
    parsed = bundle["filing_content"]
    original = bundle["original_content"]
    if parsed["originalArtifactId"] != bundle["original_id"]:
        raise ValueError("Original artifact identity mismatch")
    if parsed["originalContentHash"] != bundle["original_hash"]:
        raise ValueError("Recorded original content hash mismatch")
    if timestamp(original["retrievedAt"]) > timestamp(cutoff):
        raise ValueError("Source observed after publication cutoff")
    documents = [
        (url, body)
        for url, body in original["documents"].items()
        if "/api/xbrl/companyfacts/" in url
    ]
    if len(documents) != 1:
        raise ValueError("Expected exactly one retained Company Facts response")
    url, body = documents[0]
    company = json.loads(body)
    if company != original["companyFacts"]:
        raise ValueError("Retained SEC response differs from parsed Company Facts")
    filing = parsed["filing"]
    if int(company["cik"]) != int(filing["cik"]):
        raise ValueError("Company Facts / filing CIK mismatch")
    concepts = company["facts"].get("us-gaap", {})
    selected = {
        "CommonStockholdersEquity",
        "StockholdersEquity",
        "PreferredStockValue",
        "PreferredStockSharesIssued",
        "PreferredStockSharesOutstanding",
        "MinorityInterest",
        "InterestExpense",
        "InterestExpenseOperating",
        "OperatingIncomeLoss",
    }
    selected.update(k for k in concepts if k.startswith("PreferredStock"))
    tags = {}
    for concept in sorted(selected):
        facts = [
            dict(f, unit=unit)
            for unit, rows in concepts.get(concept, {}).get("units", {}).items()
            for f in rows
            if f["filed"] <= cutoff[:10]
        ]
        current = [
            f
            for f in facts
            if f["accn"] == filing["accession"] and f["end"] == filing["fiscalPeriodEnd"]
        ]
        tags[concept] = {
            "state": "CURRENT_FILING_FACTS"
            if current
            else "NO_CURRENT_FILING_FACTS"
            if facts
            else "ABSENT_FROM_RETAINED_COMPANYFACTS",
            "latest_end": max((f["end"] for f in facts), default=None),
            "current_facts": current,
            "parsed_current_facts": [
                f
                for f in filing["facts"]
                if f["taxonomy"] == "us-gaap"
                and f["concept"] == concept
                and f["end"] == filing["fiscalPeriodEnd"]
            ],
        }
    return {
        "accession": filing["accession"],
        "cik": filing["cik"],
        "period_end": filing["fiscalPeriodEnd"],
        "filed_date": filing["filedDate"],
        "primary_document": filing["primaryDocument"],
        "original_id": bundle["original_id"],
        "original_recorded_hash": bundle["original_hash"],
        "source_url": url,
        "retrieved_at": original["retrievedAt"],
        "retained_response_sha256": hashlib.sha256(body.encode("utf-8")).hexdigest(),
        "raw_response_matches_parsed": True,
        "recorded_hash_chain_matches": True,
        "scope": "RETAINED_COMPANYFACTS_NOT_COMPLETE_FILING_DOCUMENT",
        "tags": tags,
    }


def audit(publication: Path, output: Path, container: str, symbols: list[str]) -> dict:
    def read(name):
        return json.loads((publication / name).read_text(encoding="utf-8-sig"))

    verification = read("publication-verification.json")
    run_id = str(UUID(verification["run"]["score_run_id"]))
    runs = query(
        container,
        "SELECT score_run_id,state,input_sha256,expected_count,scored_count,"
        "excluded_count,knowledge_cutoff FROM research.scoring_runs "
        f"WHERE score_run_id='{run_id}'",
    )
    if len(runs) != 1 or runs[0]["state"] != "PUBLISHED":
        raise ValueError("Published run not found")
    run = runs[0]
    actual_hash = hashlib.sha256((publication / "published-input-document.json").read_bytes())
    if actual_hash.hexdigest() != run["input_sha256"]:
        raise ValueError("Archive input hash differs from database")
    evidence = read("published-model-evidence.json")
    scores = query(
        container,
        f"SELECT instrument_id,output FROM research.stock_scores WHERE score_run_id='{run_id}'",
    )
    actual = {k: v["output"] for k, v in index(scores, "instrument_id").items()}
    if actual != index(evidence["rows"], "instrument_id"):
        raise ValueError("Archived model outputs differ from database")
    counts = (len(actual), sum(r["eligible"] for r in actual.values()))
    if counts != (run["expected_count"], run["scored_count"]) or (
        counts[0] - counts[1] != run["excluded_count"]
    ):
        raise ValueError("Database publication counts do not reconcile")
    doc = read("published-input-document.json")
    canonical = index(doc["canonical"]["inputs"], "symbol")
    unknown = set(symbols) - canonical.keys()
    if unknown:
        raise ValueError(f"Symbols absent from publication: {sorted(unknown)}")
    output.mkdir(parents=True, exist_ok=True)
    cases = []
    for symbol in symbols:
        c = canonical[symbol]
        assets = [f for f in c["facts"] if f["metric"] == "TOTAL_ASSETS" and not f.get("start")]
        if not assets:
            cases.append({"symbol": symbol, "status": "NO_CANONICAL_ASSET_FILING"})
            continue
        anchor = max(assets, key=lambda f: (f["end"], f["filedDate"], f["factId"]))
        artifact_id = str(UUID(anchor["sourceArtifactId"]))
        bundles = query(
            container,
            "SELECT a.source_artifact_id AS filing_artifact_id,a.inline_content AS filing_content,"
            "b.source_artifact_id AS original_id,b.content_hash AS original_hash,"
            "b.inline_content AS original_content FROM operations.source_artifacts a "
            "JOIN operations.source_artifacts b "
            "ON b.source_artifact_id=(a.inline_content->>'originalArtifactId')::uuid "
            f"WHERE a.source_artifact_id='{artifact_id}'",
        )
        if len(bundles) != 1:
            raise ValueError(f"Retained source chain missing for {symbol}")
        bundle = bundles[0]
        case = inspect_source(bundle, run["knowledge_cutoff"])
        case.update(
            symbol=symbol,
            instrument_id=c["instrumentId"],
            anchor_fact=anchor,
            filing_artifact_id=artifact_id,
        )
        mappings = query(
            container,
            "SELECT source_concept,metric_code,source_unit,notes "
            "FROM fundamentals.source_metric_mappings "
            "WHERE mapping_version=(SELECT request->'inputs'->>'mappingVersion' "
            f"FROM research.scoring_runs WHERE score_run_id='{run_id}') AND taxonomy='us-gaap'",
        )
        for concept, tag in case["tags"].items():
            tag["mappings"] = [m for m in mappings if m["source_concept"] == concept]
        # Use stable UUID filenames, never caller-supplied symbols as paths.
        (output / f"{artifact_id}.json").write_text(
            json.dumps(bundle, ensure_ascii=False), encoding="utf-8"
        )
        cases.append(case)
    report = {
        "schema_version": 1,
        "verified_at": datetime.now(UTC).isoformat(),
        "run": run,
        "all_published_outputs_match_archive": True,
        "cases": cases,
        "remaining": "Full filing documents, all-instrument root causes and verified repair impact",
    }
    (output / "source-summary.json").write_text(
        json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8"
    )
    return report


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--publication", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--container", default="quant-platform-timescaledb-1")
    parser.add_argument("--symbols", nargs="+", default=["AAPL", "LULU", "JPM", "O"])
    args = parser.parse_args()
    report = audit(args.publication, args.output, args.container, args.symbols)
    print(
        json.dumps(
            {
                "run_id": report["run"]["score_run_id"],
                "database_outputs_match": report["all_published_outputs_match_archive"],
                "symbols": [c["symbol"] for c in report["cases"]],
            }
        )
    )


if __name__ == "__main__":
    main()
