"""Offline, reproducible scoring; emits every stage without publishing scores."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from .engine import score, serialize
from .freeze import verify_freeze
from .periods import from_cross_section


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--format", choices=("prepared", "phase6"), default="prepared")
    parser.add_argument("--jurisdictions", type=Path)
    parser.add_argument("--candidate", default="primary")
    parser.add_argument(
        "--research-stage", choices=("development", "validation", "final"), default="development"
    )
    args = parser.parse_args(argv)
    if args.research_stage == "final":
        verify_freeze()
    document = json.loads(args.input.read_text(encoding="utf-8"))
    if args.format == "phase6":
        jurisdictions = (
            json.loads(args.jurisdictions.read_text(encoding="utf-8")) if args.jurisdictions else {}
        )
        as_of, rows = from_cross_section(document, jurisdictions=jurisdictions)
    else:
        as_of, rows = document["as_of"], document["rows"]
    report = score(rows, as_of=as_of, candidate=args.candidate)
    print(json.dumps(serialize(report), indent=2, sort_keys=True, allow_nan=False))
    return 0 if report["status"] == "COMPLETE" else 2


if __name__ == "__main__":
    raise SystemExit(main())
