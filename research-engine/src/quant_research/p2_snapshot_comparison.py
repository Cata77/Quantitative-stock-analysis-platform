"""Describe every input/result change between two verified publication archives.

Different economic dates are explicitly a longitudinal comparison, not a causal
estimate of the preparation change. Use p2_comparison for the controlled replay.
"""

import argparse
import hashlib
import json
from pathlib import Path

from .exclusion_audit import index


def read_archive(path):
    raw = (path / "published-input-document.json").read_bytes()
    run = json.loads((path / "publication-verification.json").read_text())["run"]
    if hashlib.sha256(raw).hexdigest() != run["input_sha256"]:
        raise ValueError("Archive input hash mismatch")
    return json.loads(raw), json.loads((path / "published-model-evidence.json").read_text()), run


def compare(before, after):
    old, old_model, old_run = read_archive(before)
    new, new_model, new_run = read_archive(after)
    a = index(old["canonical"]["inputs"], "instrumentId")
    b = index(new["canonical"]["inputs"], "instrumentId")
    x = index(old_model["rows"], "instrument_id")
    y = index(new_model["rows"], "instrument_id")
    pa = index(old["prepared"]["rows"], "instrument_id")
    pb = index(new["prepared"]["rows"], "instrument_id")
    if not a.keys() == b.keys() == x.keys() == y.keys() == pa.keys() == pb.keys():
        raise ValueError("Cohort changed")
    if old_model["model_version"] != new_model["model_version"]:
        raise ValueError("Model changed")

    def share(row):
        fact = next((f for f in row["facts"] if f["factId"] == row["shareFactId"]), None)
        return {"value": row["sharesOutstanding"], "factId": row["shareFactId"], "fact": fact}

    changes = []
    for key in sorted(a, key=lambda k: b[k]["symbol"]):
        av, bv = pa[key]["values"], pb[key]["values"]
        changes.append({
            "instrumentId": key, "symbol": b[key]["symbol"],
            "beforeEligible": x[key]["eligible"], "afterEligible": y[key]["eligible"],
            "beforeReasons": x[key]["reasons"], "afterReasons": y[key]["reasons"],
            "beforeShares": share(a[key]), "afterShares": share(b[key]),
            "changedValues": {k: {"before": av.get(k), "after": bv.get(k)}
                              for k in sorted(av.keys() | bv.keys()) if av.get(k) != bv.get(k)},
        })
    return {
        "scope": "LONGITUDINAL_SNAPSHOT_COMPARISON_NOT_CAUSAL_PREPARATION_IMPACT",
        "beforeRun": old_run["score_run_id"], "afterRun": new_run["score_run_id"],
        "beforeRequest": old["canonical"]["request"], "afterRequest": new["canonical"]["request"],
        "instrumentCount": len(changes), "beforeEligible": old_model["eligible_count"],
        "afterEligible": new_model["eligible_count"],
        "recovered": [r["symbol"] for r in changes
                      if not r["beforeEligible"] and r["afterEligible"]],
        "lost": [r["symbol"] for r in changes if r["beforeEligible"] and not r["afterEligible"]],
        "missingSharesRecovered": [r["symbol"] for r in changes
                                   if r["beforeShares"]["value"] is None
                                   and r["afterShares"]["value"] is not None],
        "shareValuesChanged": sum(r["beforeShares"]["value"] != r["afterShares"]["value"]
                                  for r in changes),
        "instruments": changes,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--before", type=Path, required=True)
    parser.add_argument("--after", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        raise ValueError("Output already exists")
    report = compare(args.before, args.after)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({k: v for k, v in report.items() if k != "instruments"}, indent=2))


if __name__ == "__main__":
    main()
