"""Read-only check: rebuild a published run's input document in Python.

Loads the run's stored request, runs the shared input and coverage queries in a READ ONLY
transaction and compares the rebuilt document byte for byte (and by SHA-256) with the stored
one. Connection: ``QUANT_DB_DSN`` (libpq string or URL). Nothing is written to the database.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from uuid import UUID

import psycopg

from . import canonical_json
from .document import build
from .inputs import ScoringRequest


def first_difference(stored: str, rebuilt: str) -> dict | None:
    index = next((i for i, (a, b) in enumerate(zip(stored, rebuilt, strict=False)) if a != b),
                 None if len(stored) == len(rebuilt) else min(len(stored), len(rebuilt)))
    if index is None:
        return None
    return {"offset": index, "stored": stored[max(0, index - 160):index + 160],
            "rebuilt": rebuilt[max(0, index - 160):index + 160]}


def reproduce_document(connection, run_id: UUID, session_time_zone: str = "UTC") -> dict:
    with connection.cursor() as cursor:
        cursor.execute("""
            SELECT request::text, input_document, input_sha256, state
            FROM research.scoring_runs WHERE score_run_id=%s
        """, (run_id,))
        row = cursor.fetchone()
    if row is None or row[3] != "PUBLISHED":
        raise ValueError("Expected one published run")
    request_json, stored, digest, _ = row
    if canonical_json.sha256(stored) != digest:
        raise ValueError("Stored input document does not match its hash")
    stored_request = json.loads(request_json)
    request = ScoringRequest.from_json(stored_request["inputs"])
    rebuilt, _ = build(connection, request, stored_request.get("jurisdictions", {}),
                       session_time_zone)
    return {"runId": str(run_id), "storedSha256": digest,
            "rebuiltSha256": canonical_json.sha256(rebuilt),
            "identical": rebuilt == stored, "bytes": len(rebuilt.encode()),
            "firstDifference": first_difference(stored, rebuilt)}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-id", type=UUID, required=True)
    parser.add_argument("--session-time-zone", default="UTC",
                        help="zone of the original publisher session (Java: JVM default zone)")
    args = parser.parse_args()
    dsn = os.environ.get("QUANT_DB_DSN")
    if not dsn:
        sys.exit("Set QUANT_DB_DSN")
    with psycopg.connect(dsn) as connection:
        connection.read_only = True
        with connection.transaction():
            print(json.dumps(reproduce_document(connection, args.run_id, args.session_time_zone),
                             indent=2))


if __name__ == "__main__":
    main()
