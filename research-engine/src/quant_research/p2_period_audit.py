"""Audit selected fiscal periods and provenance for an entire canonical cohort."""

import argparse
import hashlib
import json
from collections import Counter
from datetime import date
from pathlib import Path

from .model.engine import timestamp
from .model.periods import ADDITIVE, BALANCES, annual, days, from_cross_section
from .model.preparation import CURRENT_VERSION


def audit(section: dict) -> dict:
    cutoff, rows = from_cross_section(section, preprocessing_version=CURRENT_VERSION)
    counts = Counter()
    instruments = []
    identities = [item["instrumentId"] for item in section["inputs"]]
    if len(set(identities)) != len(identities):
        raise ValueError("Duplicate instrument in canonical cohort")
    for item, row in zip(section["inputs"], rows, strict=True):
        facts = {f["factId"]: f for f in item["facts"]}
        metrics = {}
        for metric, evidence in row["provenance"].items():
            base = metric.removesuffix("_TTM").removesuffix("_PRIOR")
            if base not in ADDITIVE | BALANCES:
                continue
            selected = [facts[source] for source in evidence["source_ids"]]
            if any(
                f["unit"] != "USD"
                or timestamp(f["availableAt"]) > timestamp(cutoff)
                or timestamp(f["observedAt"]) > timestamp(cutoff)
                or f["end"] > section["request"]["scoreDate"]
                for f in selected
            ):
                raise ValueError("Selected evidence violates currency, period or cutoff")
            if evidence.get("derivation"):
                method = evidence["derivation"]
            elif base in BALANCES:
                method = "COMPARABLE_BALANCE" if metric.endswith("_PRIOR") else "CURRENT_BALANCE"
                if metric.endswith("_PRIOR"):
                    distance = (date.fromisoformat(row["period_end"])
                                - date.fromisoformat(evidence["period_end"])).days
                    if not 350 <= distance <= 380:
                        raise ValueError("Prior balance is outside comparable year")
            elif len(selected) == 1 and annual(selected[0]):
                method = "DIRECT_ANNUAL"
            elif len(selected) == 4 and all(70 <= days(f) <= 110 for f in selected):
                method = "FOUR_QUARTERS"
            elif len(selected) == 3 and sum(annual(f) for f in selected) == 1:
                method = "ANNUAL_PLUS_YTD_MINUS_PRIOR_YTD"
            else:
                raise ValueError("Unrecognized selected period assembly")
            counts[method] += 1
            metrics[metric] = {
                "method": method,
                "value": row["values"][metric],
                "periodEnd": evidence["period_end"],
                "parts": [
                    {k: f.get(k) for k in (
                        "factId", "filingId", "metric", "start", "end", "unit",
                        "value", "availableAt", "observedAt",
                    )}
                    for f in selected
                ],
            }
        instruments.append({
            "instrumentId": item["instrumentId"], "symbol": item["symbol"],
            "periodEnd": row["period_end"], "metrics": metrics,
            "missing": row["preparation_diagnostics"],
            "shareFactId": item.get("shareFactId"),
            "blockingReasons": row["blocking_reasons"],
        })
    return {
        "scope": "SELECTED_PERIODS_AND_PROVENANCE_NOT_SOURCE_COMPLETENESS",
        "preprocessingVersion": CURRENT_VERSION, "knowledgeCutoff": cutoff,
        "scoreDate": section["request"]["scoreDate"], "instrumentCount": len(rows),
        "assemblyCounts": dict(sorted(counts.items())), "instruments": instruments,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--canonical", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    if args.output.exists():
        raise ValueError("Audit output already exists")
    raw = args.canonical.read_bytes()
    report = audit(json.loads(raw))
    report["canonicalSha256"] = hashlib.sha256(raw).hexdigest()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({k: v for k, v in report.items() if k != "instruments"}, indent=2))


if __name__ == "__main__":
    main()
