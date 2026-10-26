"""Offline publication audit. Never changes inputs or estimates recovered scores."""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
from collections import Counter, defaultdict
from itertools import combinations
from pathlib import Path

from .model.engine import score, serialize


def index(rows: list[dict], key: str) -> dict[str, dict]:
    result = {}
    for row in rows:
        identity = row[key]
        if identity in result:
            raise ValueError(f"Duplicate {key}: {identity}")
        result[identity] = row
    return result


def category(reason: str) -> str:
    code = reason.split(":", 1)[0]
    if code == "MODEL_NOT_SUPPORTED":
        return "UNSUPPORTED_PROFILE_OR_INPUT_SCOPE"
    if code.startswith("STALE"):
        return "STALE_DATA"
    if code in {
        "CORPORATE_ACTION_REVIEW",
        "SECURITY_IDENTITY_REVIEW",
        "NON_PRIMARY_SHARE_CLASS",
        "INACTIVE_OR_UNSUPPORTED_SECURITY",
    }:
        return "IDENTITY_OR_CORPORATE_ACTION"
    if code in {"MISSING_PROFILE_ANCHOR", "MISSING_RISK_INPUT"}:
        return "PROFILE_SUPPLEMENT_NOT_IMPLEMENTED"
    if code == "MISSING_REQUIRED_METRIC":
        return "MISSING_CANONICAL_INPUT"
    if code in {
        "MISSING_INSTRUMENT_SHARES",
        "MISSING_SESSION",
        "INSUFFICIENT_HISTORY",
        "INSUFFICIENT_PRICE_HISTORY",
        "MISSING_MOMENTUM_BOUNDARY",
    }:
        return "MISSING_CANONICAL_INPUT"
    if code.startswith("INSUFFICIENT_"):
        return "COVERAGE_OR_NORMALIZATION"
    if code.startswith(("INVALID_", "NON_POSITIVE_")):
        return "ECONOMIC_OR_INVALID_INPUT"
    return "UNRESOLVED"


def input_diagnostics(canonical: dict, prepared: dict, output: dict) -> dict:
    """Expose evidence without interpreting absent canonical facts as absent SEC data."""
    facts = canonical["facts"]
    by_id = {f["factId"]: f for f in facts}
    values = prepared["values"]
    keys = set(values) | {k for k in output["derived"] if k.isupper()}
    result = {}
    for key in sorted(keys):
        provenance = prepared["provenance"].get(key, {})
        candidates = [
            f for f in facts if f["metric"] == key.removesuffix("_PRIOR").removesuffix("_TTM")
        ]
        present = values.get(key) is not None
        if present:
            status = "PREPARED"
        elif not candidates:
            status = "ABSENT_CANONICAL_SOURCE_OR_MAPPING_UNRESOLVED"
        else:
            status = "CANONICAL_PRESENT_PERIOD_UNIT_OR_CUTOFF_REVIEW"
        result[key] = {
            "value": values.get(key),
            "status": status,
            "model_reason": output["input_reasons"].get(key),
            "provenance": provenance,
            "selected_facts": [by_id[s] for s in provenance.get("source_ids", []) if s in by_id],
            "candidate_facts": candidates if not present else [],
        }
    return result


def overlap(rows: list[dict]) -> dict:
    counts, sole, pairs = Counter(), Counter(), Counter()
    for row in rows:
        if row["eligible"]:
            continue
        reasons = sorted({r.split(":", 1)[0] for r in row["reasons"]})
        counts.update(reasons)
        if len(reasons) == 1:
            sole.update(reasons)
        pairs.update(combinations(reasons, 2))
    return {
        "reasons": [
            {"reason": k, "instruments": n, "sole_recorded_blocker": sole[k]}
            for k, n in counts.most_common()
        ],
        "pairs": [{"reasons": list(k), "instruments": n} for k, n in pairs.most_common()],
        "interpretation": "Sole recorded blocker is not verified recoverability. "
        "Repairs require source evidence and full-cohort rescoring including normalization.",
        "verified_recovered_instruments": None,
    }


def audit_export(path: Path, canonical: dict, outputs: dict) -> dict:
    with path.open(encoding="utf-8-sig", newline="") as stream:
        rows = list(csv.DictReader(stream))
    exported = index(rows, "instrument_id")
    eligible = {k for k, v in outputs.items() if v["eligible"]}
    differences = []
    for identity in sorted(eligible & exported.keys()):
        expected = {
            "symbol": canonical[identity]["symbol"],
            "scoring_profile": outputs[identity]["profile"],
            "ordinal_rank": str(outputs[identity]["rank"]),
        }
        for field, value in expected.items():
            if exported[identity][field] != value:
                differences.append(
                    {
                        "instrument_id": identity,
                        "field": field,
                        "export": exported[identity][field],
                        "publication": value,
                    }
                )
    return {
        "checked_fields": ["symbol", "scoring_profile", "ordinal_rank"],
        "rows": len(rows),
        "missing": sorted(eligible - exported.keys()),
        "unexpected": sorted(exported.keys() - eligible),
        "differences": differences,
    }


def build_report(source: Path, destination: Path) -> dict:
    names = [
        "published-input-document.json",
        "published-model-evidence.json",
        "publication-verification.json",
        "exclusions.json",
        "rankings.csv",
    ]
    if source.resolve() == destination.resolve():
        raise ValueError("Output must differ from the publication directory")
    hashes = {name: hashlib.sha256((source / name).read_bytes()).hexdigest() for name in names}

    def read(name):
        return json.loads((source / name).read_text(encoding="utf-8-sig"))

    verification = read("publication-verification.json")
    run = verification["run"]
    if hashes[names[0]] != run["input_sha256"]:
        raise ValueError("Published input SHA-256 mismatch")
    document, evidence = read(names[0]), read(names[1])
    canonical = index(document["canonical"]["inputs"], "instrumentId")
    prepared = index(document["prepared"]["rows"], "instrument_id")
    outputs = index(evidence["rows"], "instrument_id")
    if canonical.keys() != prepared.keys() or canonical.keys() != outputs.keys():
        raise ValueError("Canonical/prepared/output instrument sets differ")
    expected = len(outputs)
    eligible = sum(r["eligible"] for r in outputs.values())
    excluded = expected - eligible
    if (expected, eligible, excluded) != (
        run["expected_count"],
        run["scored_count"],
        run["excluded_count"],
    ) or (expected, eligible, excluded) != (
        evidence["expected_count"],
        evidence["eligible_count"],
        evidence["excluded_count"],
    ):
        raise ValueError("Publication counts do not reconcile")
    if document["prepared"]["as_of"] != evidence["as_of"]:
        raise ValueError("Prepared and model evidence cutoff differ")
    reproduced = serialize(
        score(
            document["prepared"]["rows"],
            as_of=document["prepared"]["as_of"],
            candidate=evidence["candidate"],
        )
    )
    if reproduced != evidence:
        raise ValueError("Model evidence does not reproduce from the hashed prepared inputs")
    exclusions = defaultdict(list)
    for entry in read("exclusions.json"):
        identity = entry["instrument_id"]
        if identity not in outputs or entry["symbol"] != canonical[identity]["symbol"]:
            raise ValueError("Exclusion identity/symbol mismatch")
        exclusions[identity].append(entry)
    for identity, row in outputs.items():
        actual = {x["reason_code"] for x in exclusions[identity] if x["stage"] == "ELIGIBILITY"}
        if actual != set(row["reasons"]) or (not row["eligible"] and not actual):
            raise ValueError(f"Eligibility reasons differ: {identity}")
    summary = {
        "schema_version": 1,
        "run_id": run["score_run_id"],
        "score_date": run["as_of_date"],
        "knowledge_cutoff": run["knowledge_cutoff"],
        "model_version": evidence["model_version"],
        "source_sha256": hashes,
        "expected": expected,
        "eligible": eligible,
        "excluded": excluded,
        "scope": "ARCHIVED_PUBLICATION_NOT_LIVE_DATABASE",
        "model_evidence_reproduced": True,
        "root_cause_audit_complete": False,
        "overlap": overlap(list(outputs.values())),
        "export_integrity": audit_export(source / "rankings.csv", canonical, outputs),
    }
    destination.mkdir(parents=True, exist_ok=True)
    with (destination / "instruments.jsonl").open("w", encoding="utf-8") as stream:
        for identity in sorted(outputs, key=lambda k: (canonical[k]["symbol"], k)):
            c, p, row = canonical[identity], prepared[identity], outputs[identity]
            report = {
                "run_id": run["score_run_id"],
                "instrument_id": identity,
                "issuer_id": c["issuerId"],
                "symbol": c["symbol"],
                "sector": c["sector"],
                "profile": row["profile"],
                "peer_group": row["peer_group"],
                "eligible": row["eligible"],
                "rank": row["rank"],
                "reasons": row["reasons"],
                "warnings": row["warnings"],
                "categories": sorted({category(r) for r in row["reasons"]}),
                "exclusions": exclusions[identity],
                "metrics": row["metrics"],
                "derived": row["derived"],
                "input_reasons": row["input_reasons"],
                "inputs": input_diagnostics(c, p, row),
                "canonical_reasons": c["reasons"],
                "period_end": p["period_end"],
                "filing_date": p["filing_date"],
                "canonical_pointer": f"instrumentId={identity}",
                "source_review": "PENDING_RETAINED_SOURCE_ARTIFACT_AUDIT",
            }
            stream.write(json.dumps(report, sort_keys=True, ensure_ascii=False) + "\n")
    (destination / "summary.json").write_text(
        json.dumps(summary, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )
    return summary


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--publication", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    summary = build_report(args.publication, args.output)
    print(
        json.dumps(
            {
                k: summary[k]
                for k in ("run_id", "expected", "eligible", "excluded", "export_integrity")
            },
            indent=2,
        )
    )


if __name__ == "__main__":
    main()
