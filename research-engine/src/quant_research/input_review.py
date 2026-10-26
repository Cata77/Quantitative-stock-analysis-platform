"""Validate or append an explicit, immutable input review using a retained evidence file."""

import argparse
import hashlib
import json
import os
from pathlib import Path
from uuid import UUID

import psycopg
from psycopg.types.json import Jsonb


def validate(manifest: dict, evidence_file: Path) -> dict:
    fields = {
        "reviewId",
        "kind",
        "instrumentId",
        "factId",
        "issueId",
        "decision",
        "reviewer",
        "evidence",
    }
    if set(manifest) != fields:
        raise ValueError("Unexpected or missing manifest fields")
    for key in ("reviewId", "instrumentId", "factId", "issueId"):
        if key in ("reviewId", "instrumentId") and manifest[key] is None:
            raise ValueError("Review and instrument IDs are required")
        if manifest[key] is not None:
            UUID(manifest[key])
    if manifest["kind"] not in {"COMMON_EQUITY", "SHARE_CLASS", "QUALITY_ISSUE"}:
        raise ValueError("Unsupported review kind")
    if manifest["decision"] not in {"ACCEPT", "REVOKE"}:
        raise ValueError("Unsupported decision")
    quality = manifest["kind"] == "QUALITY_ISSUE"
    if (manifest["issueId"] is not None) != quality or (manifest["factId"] is not None) == quality:
        raise ValueError("Review target must match its kind")
    if not isinstance(manifest["reviewer"], str) or len(manifest["reviewer"].strip()) < 3:
        raise ValueError("Reviewer must contain at least three characters")
    evidence = manifest["evidence"]
    if not isinstance(evidence, dict):
        raise ValueError("Evidence must be an object")
    if manifest["kind"] == "COMMON_EQUITY" and evidence.get("scope") != (
        "TOTAL_EQUITY_IS_COMMON_ONLY"
    ):
        raise ValueError("Common equity review requires explicit common-only scope")
    if manifest["kind"] == "SHARE_CLASS" and (
        evidence.get("scope") != "EXACT_INSTRUMENT_SHARES"
        or not str(evidence.get("shareClass") or "").strip()
    ):
        raise ValueError("Share review requires explicit instrument class")
    if quality and not isinstance(evidence.get("issueEvidence"), str):
        raise ValueError("Quality review requires the exact issue evidence")
    digest = hashlib.sha256(evidence_file.read_bytes()).hexdigest()
    if digest != evidence.get("sourceSha256"):
        raise ValueError("Retained evidence SHA-256 mismatch")
    if (
        not evidence.get("sourceUri", "").startswith("https://")
        or len(evidence.get("rationale", "")) < 20
    ):
        raise ValueError("Review requires source URI and explicit rationale")
    return manifest


def apply(connection, manifest: dict) -> str:
    """One transaction; same UUID is idempotent only for byte-equivalent JSON content."""
    with connection.transaction(), connection.cursor() as cursor:
        cursor.execute(
            "SELECT pg_advisory_xact_lock(hashtextextended(%s,0))", (manifest["reviewId"],)
        )
        cursor.execute(
            """SELECT kind,instrument_id::text,fact_id::text,issue_id::text,
                decision,reviewer,evidence,recorded_at FROM operations.input_reviews
                WHERE review_id=%s""",
            (manifest["reviewId"],),
        )
        previous = cursor.fetchone()
        values = tuple(
            manifest[k]
            for k in (
                "kind",
                "instrumentId",
                "factId",
                "issueId",
                "decision",
                "reviewer",
                "evidence",
            )
        )
        if previous:
            if previous[:7] != values:
                raise ValueError("Review UUID already exists with different content")
            return previous[7].isoformat()
        cursor.execute(
            """INSERT INTO operations.input_reviews
                (review_id,kind,instrument_id,fact_id,issue_id,decision,reviewer,evidence)
                VALUES (%s,%s,%s,%s,%s,%s,%s,%s) RETURNING recorded_at""",
            (manifest["reviewId"], *values[:6], Jsonb(values[6])),
        )
        return cursor.fetchone()[0].isoformat()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--evidence-file", type=Path, required=True)
    parser.add_argument(
        "--apply", action="store_true", help="Append using SCORING_DB_DSN; default only validates"
    )
    args = parser.parse_args()
    manifest = validate(json.loads(args.manifest.read_text(encoding="utf-8")), args.evidence_file)
    if not args.apply:
        print(json.dumps({"reviewId": manifest["reviewId"], "valid": True, "applied": False}))
        return
    with psycopg.connect(os.environ["SCORING_DB_DSN"]) as connection:
        recorded = apply(connection, manifest)
    print(json.dumps({"reviewId": manifest["reviewId"], "recordedAt": recorded, "applied": True}))


if __name__ == "__main__":
    main()
