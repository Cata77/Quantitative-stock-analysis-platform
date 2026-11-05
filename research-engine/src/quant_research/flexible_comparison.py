"""Compare 1.2 and 1.3 on the exact same prepared inputs; never publish or rewrite evidence."""

import argparse
import hashlib
import json
from collections import Counter
from pathlib import Path

from .model.engine import score as legacy_score
from .model.engine import serialize
from .model.flexible import score
from .model.freeze import verify_freeze


def compare(prepared, output, version="1.3.0", before_version="1.2.0"):
    verify_freeze()
    if output.exists():
        raise ValueError("Output already exists")
    raw = prepared.read_bytes()
    document = json.loads(raw)
    before = (legacy_score(document["rows"], as_of=document["as_of"])
              if before_version == "1.2.0" else
              score(document["rows"], as_of=document["as_of"], version=before_version))
    after = score(document["rows"], as_of=document["as_of"], version=version)
    old = {r["instrument_id"]: r for r in before["rows"]}
    summary = {
        "prepared_sha256": hashlib.sha256(raw).hexdigest(),
        "as_of": document["as_of"], "expected_count": len(old),
        "before_model": before["model_version"], "after_model": after["model_version"],
        "before_eligible": before["eligible_count"], "after_eligible": after["eligible_count"],
        "score_status_counts": dict(Counter(r["score_status"] for r in after["rows"])),
        "all_instruments_scored": all(r["eligible"] for r in after["rows"]),
        "historical_acceptance": "NOT_EVALUATED",
        "rows": [{
            "instrument_id": r["instrument_id"], "profile": r["profile"],
            "before_eligible": old[r["instrument_id"]]["eligible"],
            "after_eligible": r["eligible"], "score_status": r["score_status"],
            "before_composite": old[r["instrument_id"]]["composite"],
            "after_composite": r["composite"], "before_rank": old[r["instrument_id"]]["rank"],
            "after_rank": r["rank"], "coverage": r["coverage"],
            "before_blockers": old[r["instrument_id"]]["reasons"],
            "effective_weights": r["effective_weights"], "remaining_blockers": r["reasons"],
        } for r in after["rows"]],
    }
    output.mkdir(parents=True)
    (output / "prepared.json").write_bytes(raw)
    for name, data in [("model", after), ("summary", summary)]:
        (output / f"{name}.json").write_text(
            json.dumps(data, indent=2, allow_nan=False) + "\n", encoding="utf-8"
        )
    (output / "model-serialized.json").write_text(
        json.dumps(serialize(after), indent=2) + "\n", encoding="utf-8"
    )
    return {k: v for k, v in summary.items() if k != "rows"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--prepared", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--version", choices=["1.3.0", "1.4.0", "1.5.0"], default="1.3.0")
    parser.add_argument("--before-version", choices=["1.2.0", "1.3.0"], default="1.2.0")
    args = parser.parse_args()
    result = compare(args.prepared, args.output, args.version, args.before_version)
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
