"""Dated local P2 follow-up: wait, publish once, replay, and verify Gateway.

Run from the repository root. No collection state is changed by this script.
The deadline prevents silently switching the economic date or adjustment vintage.
"""

import argparse
import hashlib
import json
import os
import secrets
import shutil
import subprocess
import time
from datetime import datetime, timezone
from decimal import Decimal
from pathlib import Path
from uuid import UUID, uuid4

from quant_research.p2_remaining_audit import audit
from quant_research.p2_snapshot_comparison import compare
from quant_research.publication_audit import verify
from quant_research.universe_export import (
    Gateway,
    collect,
    eligibility_reasons,
    write_export,
)

ROOT = Path.cwd()
OUT = ROOT / "build/p2-final-20261030/current"
CONTAINER = "quant-platform-timescaledb-1"
DEADLINE = datetime(2026, 10, 30, 19, 0, tzinfo=timezone.utc)


def query(select):
    result = subprocess.run(
        ["docker", "exec", "-i", CONTAINER, "psql", "-U", "postgres", "-d",
         "quant_platform", "-X", "-qAt", "-v", "ON_ERROR_STOP=1"],
        input="BEGIN READ ONLY; SET LOCAL ROLE quant_scoring; "
              "SET LOCAL statement_timeout=120000; "
              f"SELECT coalesce(jsonb_agg(t),'[]'::jsonb) FROM ({select}) t; COMMIT;",
        text=True, encoding="utf-8", capture_output=True, check=True, timeout=150,
    )
    return json.loads(result.stdout)


def collection():
    return query("""
        SELECT r.window_start::text session,r.expected_count,count(i.*) actual_count,
          count(*) FILTER(WHERE i.status='COMPLETE') complete,
          count(*) FILTER(WHERE i.status='FAILED'
            AND i.failure->>'code'='MISSING_SESSION') absent,
          count(*) FILTER(WHERE i.status='FAILED'
            AND (i.failure->>'code') IS DISTINCT FROM 'MISSING_SESSION') terminal_errors
        FROM operations.ingestion_runs r JOIN operations.job_definitions j USING(job_definition_id)
        LEFT JOIN operations.ingestion_run_items i USING(ingestion_run_id)
        WHERE j.configuration->>'adjustmentAsOf'='2026-10-30'
          AND j.configuration->>'symbolAsOf'='2026-10-30'
          AND j.configuration->>'adjustment'='SPLIT_DIVIDEND'
          AND r.window_start BETWEEN '2025-10-28' AND '2026-10-29'
        GROUP BY r.ingestion_run_id ORDER BY r.window_start
    """)


def ready(runs, sessions):
    return (bool(sessions) and len(runs) == len(sessions)
            and {r["session"] for r in runs} == set(sessions)
            and all(r["expected_count"] > 0
                    and r.get("terminal_errors", 0) == 0
                    and r["actual_count"] == r["expected_count"]
                    and r["complete"] + r["absent"] == r["expected_count"] for r in runs))


def save(name, value):
    with (OUT / name).open("x", encoding="utf-8") as stream:
        json.dump(value, stream, indent=2)
        stream.write("\n")


def status(state, **fields):
    document = dict(state=state, observedAt=datetime.now(timezone.utc).isoformat(), **fields)
    temporary = OUT / "status.tmp"
    temporary.write_text(json.dumps(document, indent=2) + "\n", encoding="utf-8")
    temporary.replace(OUT / "status.json")
    print(json.dumps(document), flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check-only", action="store_true")
    args = parser.parse_args()
    sessions = [r["session"] for r in query("""
        SELECT session_date::text session FROM reference.trading_sessions
        WHERE exchange_mic='XNYS' AND NOT holiday
          AND session_date BETWEEN '2025-10-28' AND '2026-10-29'
        ORDER BY session_date
    """)]
    if args.check_only:
        runs = collection()
        print(json.dumps({"ready": ready(runs, sessions), "sessions": len(sessions),
                              "runs": len(runs), "complete": sum(r["complete"] for r in runs)}))
        return
    OUT.mkdir(parents=True, exist_ok=True)
    save("launch.json", {"pid": os.getpid(), "startedAt": datetime.now(timezone.utc).isoformat()})
    jar = ROOT / "scoring-service/build/libs/scoring-service-0.0.1-SNAPSHOT.jar"
    pinned = OUT / "scoring.jar"
    shutil.copyfile(jar, pinned)
    digest = hashlib.sha256(pinned.read_bytes()).hexdigest()
    try:
        while True:
            if datetime.now(timezone.utc) >= DEADLINE:
                raise TimeoutError("Collection missed the dated publication deadline")
            runs = collection()
            if any(r.get("terminal_errors", 0) for r in runs):
                save("collection-failures.json", runs)
                raise RuntimeError("Terminal collection errors require an audited recovery")
            if ready(runs, sessions):
                break
            status("WAITING_FOR_COLLECTION", complete=sum(r["complete"] for r in runs),
                   expected=sum(r["expected_count"] for r in runs), sessions=len(sessions))
            time.sleep(60)
        save("collection-ready.json", runs)
        cutoff = datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")
        save("request.json", {"scoreDate": "2026-10-29", "cutoff": cutoff,
                                  "modelVersion": "1.4.0", "jarSha256": digest})
        # Only the child environment receives credentials; no CLI argument or evidence contains them.
        environment = dict(os.environ)
        for line in (ROOT / "infrastructure/.env").read_text(encoding="utf-8-sig").splitlines():
            if line.strip() and not line.lstrip().startswith("#") and "=" in line:
                key, value = line.split("=", 1)
                environment[key.strip()] = value.strip().strip('"').strip("'")
        process = subprocess.run(["docker", "inspect", "--format", "{{json .Config.Env}}",
                                  "quant-platform-scoring-service-1"],
                                 capture_output=True, text=True, check=True)
        container_env = dict(item.split("=", 1) for item in json.loads(process.stdout) if "=" in item)
        environment.update(SCORING_DB_PASSWORD=container_env["SCORING_DB_PASSWORD"],
                           SCORING_DB_URL="jdbc:postgresql://localhost:6543/quant_platform",
                           SPRING_PROFILES_ACTIVE="local", KAFKA_BOOTSTRAP_SERVERS="localhost:9092",
                           SCORING_ELASTICSEARCH_ENABLED="false", SCORING_PORT="18095")
        command = ["java", "-Xmx2g", "-jar", str(pinned),
                   "--scoring.command.operation=current-score-and-exit",
                   "--scoring.model.version=1.4.0", "--scoring.command.score-date=2026-10-29",
                   "--scoring.command.knowledge-cutoff=" + cutoff,
                   "--scoring.month-end-enabled=false",
                   "--spring.datasource.hikari.connection-init-sql=SET max_parallel_workers_per_gather=0"]
        status("PUBLISHING", cutoff=cutoff)
        with (OUT / "publish.log").open("x", encoding="utf-8") as log:
            result = subprocess.run(command, env=environment, stdout=log,
                                    stderr=subprocess.STDOUT, timeout=1800, check=False)
        save("command-result.json", {"exitCode": result.returncode})
        if result.returncode:
            raise RuntimeError("Scoring rejected publication; retained log and request identify the attempt")
        published = query(f"""
            SELECT r.score_run_id FROM research.scoring_runs r
            JOIN research.model_versions m USING(model_version_id)
            WHERE r.state='PUBLISHED' AND r.as_of_date='2026-10-29'
              AND r.knowledge_cutoff='{cutoff}' AND m.semantic_version='1.4.0'
        """)
        if len(published) != 1:
            raise ValueError("Expected exactly one new publication")
        run_id = str(UUID(published[0]["score_run_id"]))
        status("PUBLISHED_AUDIT_PENDING", runId=run_id)
        verify(UUID(run_id), OUT / "publication", CONTAINER)
        audit(run_id, CONTAINER, OUT / "remaining-audit.json")
        save("comparison.json", compare(ROOT / "build/p2-final-20261030/publication", OUT / "publication"))
        gateway = Gateway("http://localhost:18080")
        credentials = {"username": "p2_" + uuid4().hex[:16], "password": secrets.token_urlsafe(32)}
        gateway.request("/auth/register", credentials)
        gateway.token = gateway.request("/auth/login", credentials)["accessToken"]
        run, rows, pages = collect(gateway.page, run_id=run_id, size=100)
        write_export(OUT / "gateway", run, rows, pages, "ALL")
        _, second, _ = collect(gateway.page, run_id=run_id, size=137)
        if rows != second:
            raise ValueError("Gateway pagination changed results")
        model = json.loads((OUT / "publication/published-model-evidence.json").read_text(),
                           parse_float=Decimal)
        actual = {r["instrument_id"]: r for r in model["rows"]}
        if {r["instrumentId"] for r in rows} != actual.keys():
            raise ValueError("Gateway universe differs from publication")
        for row in rows:
            stored = actual[row["instrumentId"]]
            fields = {"eligible": "eligible", "rank": "rank", "percentile": "percentile",
                      "compositeScore": "composite", "profile": "profile",
                      "scoreStatus": "score_status", "scoreCoverage": "coverage",
                      "effectiveWeights": "effective_weights", "warnings": "warnings"}
            if any(row[a] != stored[b] for a, b in fields.items()):
                raise ValueError("Gateway result differs from publication")
            if eligibility_reasons(row) != sorted(stored["reasons"]):
                raise ValueError("Gateway exclusions differ from publication")
            for family in ("value", "quality", "momentum"):
                if (row[family + "Score"] != (stored["families"] or {}).get(family)
                        or row[family + "Contribution"] != (stored["contributions"] or {}).get(family)):
                    raise ValueError("Gateway components differ from publication")
        save("gateway-cross-check.json", {"runId": run_id, "pages": [100, 137],
                                               "instruments": len(rows), "passed": True})
        status("VERIFIED", runId=run_id, scored=model["eligible_count"],
               excluded=model["excluded_count"], allInstrumentsScored=model["excluded_count"] == 0)
    except Exception as error:  # noqa: BLE001 -- report only the type, never credentials from errors
        status("STOPPED_REQUIRES_INSPECTION", failureType=type(error).__name__)
        raise SystemExit(1) from None


if __name__ == "__main__":
    main()
