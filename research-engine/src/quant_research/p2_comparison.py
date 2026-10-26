"""Read-only comparison of versioned preparation against an immutable publication."""

import argparse
import hashlib
import json
import re
import subprocess
from pathlib import Path

from .exclusion_audit import index
from .model.engine import score, serialize
from .model.periods import from_cross_section
from .model.preparation import CURRENT_VERSION


def capture(request: dict, container: str, sql_path: Path) -> dict:
    params = dict(
        request,
        basis=request["adjustmentBasis"],
        sp500=request["sp500Snapshot"],
        nasdaq=request["nasdaq100Snapshot"],
        classification=request["classificationVersion"],
        mapping=request["mappingVersion"],
    )
    params["requiredMetrics"] = "{" + ",".join(sorted(request["requiredMetrics"])) + "}"
    sql = sql_path.read_text(encoding="utf-8")

    def literal(match):
        value = params[match[1]]
        return "'" + str(value).replace("'", "''") + "'"

    sql = re.sub(r"(?<!:):([A-Za-z][A-Za-z0-9]*)", literal, sql)
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
        input="BEGIN READ ONLY; SET LOCAL ROLE quant_scoring; "
        "SET LOCAL statement_timeout=300000; SET LOCAL work_mem='4MB'; "
        "SET LOCAL max_parallel_workers_per_gather=0; " + sql + "; COMMIT;",
        capture_output=True,
        text=True,
        encoding="utf-8",
        check=True,
    )
    response = json.loads(result.stdout)
    if response["errors"]:
        raise ValueError(response["errors"])
    return {"request": request, "inputs": response["inputs"]}


def compare(
    publication: Path, output: Path, container: str, *, verify_legacy_database: bool = False
) -> dict:
    input_hash = hashlib.sha256(
        (publication / "published-input-document.json").read_bytes()
    ).hexdigest()
    verification = json.loads((publication / "publication-verification.json").read_text())
    if input_hash != verification["run"]["input_sha256"]:
        raise ValueError("Published input SHA-256 mismatch")
    document = json.loads((publication / "published-input-document.json").read_text())
    baseline = json.loads((publication / "published-model-evidence.json").read_text())
    # Verify the complete legacy adapter, not merely the frozen prepared output.
    cutoff, old_rows = from_cross_section(document["canonical"])
    if serialize(score(old_rows, as_of=cutoff)) != baseline:
        raise ValueError("Legacy adapter does not reproduce publication")
    if verify_legacy_database:
        legacy = capture(
            document["canonical"]["request"],
            container,
            Path("scoring-service/src/main/resources/sql/scoring-inputs-profiles-v1.sql"),
        )
        legacy_cutoff, legacy_rows = from_cross_section(legacy)
        if serialize(score(legacy_rows, as_of=legacy_cutoff)) != baseline:
            raise ValueError("Database legacy selection differs from the controlled publication")
    section = capture(
        document["canonical"]["request"],
        container,
        Path("scoring-service/src/main/resources/sql/scoring-inputs.sql"),
    )
    cutoff, rows = from_cross_section(section, preprocessing_version=CURRENT_VERSION)
    current = serialize(score(rows, as_of=cutoff))
    old = index(baseline["rows"], "instrument_id")
    new = index(current["rows"], "instrument_id")
    if old.keys() != new.keys():
        raise ValueError("Universe differs from publication")
    symbols = {k: r["symbol"] for k, r in index(section["inputs"], "instrumentId").items()}
    changes = []
    prepared = index(document["prepared"]["rows"], "instrument_id")
    for row in rows:
        prior = prepared[row["instrument_id"]]
        changed_values = {
            k: {"before": prior["values"].get(k), "after": row["values"].get(k)}
            for k in sorted(prior["values"].keys() | row["values"].keys())
            if prior["values"].get(k) != row["values"].get(k)
        }
        before, after = prior["blocking_reasons"], row["blocking_reasons"]
        if changed_values or set(before) != set(after):
            changes.append(
                {
                    "instrumentId": row["instrument_id"],
                    "symbol": symbols[row["instrument_id"]],
                    "values": changed_values,
                    "removedBlockers": sorted(set(before) - set(after)),
                    "addedBlockers": sorted(set(after) - set(before)),
                    "remainingReasons": new[row["instrument_id"]]["reasons"],
                }
            )
    report = {
        "preprocessingVersion": CURRENT_VERSION,
        "diagnosticOnly": True,
        "modelVersion": baseline["model_version"],
        "cutoff": cutoff,
        "expected": len(rows),
        "beforeEligible": baseline["eligible_count"],
        "afterEligible": current["eligible_count"],
        "recovered": sorted(
            symbols[k] for k in old if not old[k]["eligible"] and new[k]["eligible"]
        ),
        "lost": sorted(symbols[k] for k in old if old[k]["eligible"] and not new[k]["eligible"]),
        "changedInputs": changes,
        "legacyReplayExact": True,
        "legacyDatabaseReplayExact": True if verify_legacy_database else None,
        "selectionSqlSha256": hashlib.sha256(
            Path("scoring-service/src/main/resources/sql/scoring-inputs.sql")
            .read_text(encoding="utf-8")
            .replace("\r\n", "\n")
            .encode()
        ).hexdigest(),
        "inputDocumentSha256": input_hash,
    }
    output.mkdir(parents=True, exist_ok=False)
    for name, data in (
        ("summary.json", report),
        ("canonical.json", section),
        (
            "prepared.json",
            {"as_of": cutoff, "rows": rows, "preprocessing_version": CURRENT_VERSION},
        ),
        ("model.json", current),
    ):
        (output / name).write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--publication", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--container", default="quant-platform-timescaledb-1")
    parser.add_argument("--verify-legacy-database", action="store_true")
    args = parser.parse_args()
    report = compare(
        args.publication,
        args.output,
        args.container,
        verify_legacy_database=args.verify_legacy_database,
    )
    print(json.dumps({k: v for k, v in report.items() if k != "changedInputs"}, indent=2))


if __name__ == "__main__":
    main()
