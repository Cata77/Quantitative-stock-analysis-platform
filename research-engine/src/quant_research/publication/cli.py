"""Command line for Python publications (P2s). Connection: ``QUANT_DB_DSN`` (quant_scoring).

  current       publish the current snapshot for an explicit elapsed cutoff (production path)
  republish     publish the stored request of an existing run again with a new effective time;
                refused on the main database ``quant_platform`` (verification copies only)
  compare-runs  compare every result table of two runs (read only)
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from datetime import UTC, date, datetime
from uuid import UUID

import psycopg

from .compare import compare_runs
from .inputs import ScoringRequest, parse_instant
from .publish import publish
from .requests import current_request

MAIN_DATABASE = "quant_platform"


def _connect(autocommit: bool):
    dsn = os.environ.get("QUANT_DB_DSN")
    if not dsn:
        sys.exit("Set QUANT_DB_DSN")
    return psycopg.connect(dsn, autocommit=autocommit)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest="command", required=True)
    current = commands.add_parser("current")
    current.add_argument("--score-date", type=date.fromisoformat, required=True)
    current.add_argument("--cutoff", type=parse_instant, required=True)
    current.add_argument("--raw-dataset", type=UUID, required=True)
    current.add_argument("--adjusted-dataset", type=UUID, required=True)
    current.add_argument("--classification", type=UUID, required=True)
    current.add_argument("--mapping-version", required=True)
    current.add_argument("--model-version", default="1.5.0")
    again = commands.add_parser("republish")
    again.add_argument("--from-run", type=UUID, required=True)
    again.add_argument("--effective-from", type=parse_instant, default=None)
    again.add_argument("--model-version", default="1.5.0")
    both = commands.add_parser("compare-runs")
    both.add_argument("--reference", type=UUID, required=True)
    both.add_argument("--candidate", type=UUID, required=True)
    args = parser.parse_args()

    if args.command == "compare-runs":
        with _connect(False) as connection:
            connection.read_only = True
            with connection.transaction():
                print(json.dumps(compare_runs(connection, args.reference, args.candidate),
                                 indent=2, default=str))
        return
    with _connect(True) as connection:
        if args.command == "current":
            built = current_request(connection, args.score_date, args.cutoff,
                                    raw_dataset=args.raw_dataset,
                                    adjusted_dataset=args.adjusted_dataset,
                                    classification=args.classification,
                                    mapping_version=args.mapping_version)
            if built is None:
                print(json.dumps({"published": False, "reason": "INPUTS_NOT_READY"}))
                sys.exit(2)
            request, effective = built
            jurisdictions = {}
        else:
            name = connection.execute("SELECT current_database()").fetchone()[0]
            if name == MAIN_DATABASE:
                sys.exit("republish is only allowed on a verification copy")
            stored = connection.execute("SELECT request::text FROM research.scoring_runs "
                                        "WHERE score_run_id=%s", (args.from_run,)).fetchone()
            if stored is None:
                sys.exit("Unknown run")
            document = json.loads(stored[0])
            request = ScoringRequest.from_json(document["inputs"])
            jurisdictions = document.get("jurisdictions", {})
            effective = args.effective_from or datetime.now(UTC).replace(microsecond=0)
        run_id = publish(connection, request, effective_from=effective,
                         jurisdictions=jurisdictions, model_version=args.model_version)
        print(json.dumps({"published": True, "runId": str(run_id)}))


if __name__ == "__main__":
    main()
