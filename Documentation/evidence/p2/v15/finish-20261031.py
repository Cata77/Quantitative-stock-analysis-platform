"""Dated P2 closeout (model 1.5.0): wait, publish once, replay, and verify Gateway.

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
from zoneinfo import ZoneInfo

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
OUT = ROOT / "build/p2-v15-20261031/current"  # --out selects a fresh attempt directory
CONTAINER = "quant-platform-timescaledb-1"
NEW_YORK = ZoneInfo("America/New_York")
DEADLINE = datetime(2026, 11, 1, 23, 0, tzinfo=timezone.utc)


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
        WHERE j.configuration->>'adjustmentAsOf'='2026-10-31'
          AND j.configuration->>'symbolAsOf'='2026-10-31'
          AND j.configuration->>'adjustment'='SPLIT_DIVIDEND'
          AND r.window_start BETWEEN '2025-10-29' AND '2026-10-30'
        GROUP BY r.ingestion_run_id ORDER BY r.window_start
    """)


def raw_refresh():
    """The audited SKYD raw refresh must also be terminal before publication."""
    return query("""
        SELECT r.window_start::text session,r.expected_count,count(i.*) actual_count,
          count(*) FILTER(WHERE i.status='COMPLETE') complete,
          count(*) FILTER(WHERE i.status='FAILED'
            AND i.failure->>'code'='MISSING_SESSION') absent,
          count(*) FILTER(WHERE i.status='FAILED'
            AND (i.failure->>'code') IS DISTINCT FROM 'MISSING_SESSION') terminal_errors
        FROM operations.ingestion_runs r JOIN operations.job_definitions j USING(job_definition_id)
        LEFT JOIN operations.ingestion_run_items i USING(ingestion_run_id)
        WHERE r.request_key='force:p2-20261031-psky-skyd-symbol-correction:reference-symbol-correction-psky-to-skyd-2026-10-06'
          AND j.configuration->>'adjustment'='RAW'
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


def verify_publication(run_id):
    """Replay, audit, compare and cross-check Gateway for one existing publication."""
    # On resume, completed steps are kept as written; outputs are never overwritten.
    if not (OUT / "publication/publication-verification.json").exists():
        verify(UUID(run_id), OUT / "publication", CONTAINER)
    if not (OUT / "remaining-audit.json").exists():
        audit(run_id, CONTAINER, OUT / "remaining-audit.json")
    save("comparison.json", compare(ROOT / "build/p2-final-20261031/publication", OUT / "publication",
                                         allow_model_change=True))
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


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check-only", action="store_true")
    parser.add_argument("--out", type=Path, help="new attempt directory; never reused")
    parser.add_argument("--cutoff", help="explicit elapsed cutoff (UTC, Z); its New York date must "
                        "equal the 2026-10-31 adjustment vintage and follow every used observation")
    parser.add_argument("--resume-run-id", type=UUID, help="verify an existing publication in --out "
                        "after an interrupted audit; never publishes")
    args = parser.parse_args()
    global OUT
    if args.out:
        OUT = ROOT / args.out
    sessions = [r["session"] for r in query("""
        SELECT session_date::text session FROM reference.trading_sessions
        WHERE exchange_mic='XNYS' AND NOT holiday
          AND session_date BETWEEN '2025-10-29' AND '2026-10-30'
        ORDER BY session_date
    """)]
    refresh_sessions = [s for s in sessions if s >= '2026-10-06']
    if args.check_only:
        runs = collection()
        refresh = raw_refresh()
        print(json.dumps({"ready": ready(runs, sessions), "sessions": len(sessions),
                              "runs": len(runs), "complete": sum(r["complete"] for r in runs),
                              "rawRefreshReady": ready(refresh, refresh_sessions),
                              "rawRefreshRuns": len(refresh)}))
        return
    if args.resume_run_id:
        request = json.loads((OUT / "request.json").read_text(encoding="utf-8"))
        result = json.loads((OUT / "command-result.json").read_text(encoding="utf-8"))
        run_id = str(args.resume_run_id)
        published = query(f"""
            SELECT r.score_run_id FROM research.scoring_runs r
            JOIN research.model_versions m USING(model_version_id)
            WHERE r.state='PUBLISHED' AND r.as_of_date='{request["scoreDate"]}'
              AND r.knowledge_cutoff='{request["cutoff"]}' AND m.semantic_version='{request["modelVersion"]}'
        """)
        if result["exitCode"] or [str(UUID(r["score_run_id"])) for r in published] != [run_id]:
            raise SystemExit("Resume requires exactly this successful publication of the recorded request")
        try:
            status("RESUMED_AUDIT_PENDING", runId=run_id)
            verify_publication(run_id)
        except Exception as error:
            status("STOPPED_REQUIRES_INSPECTION", failureType=type(error).__name__)
            raise
        return
    OUT.mkdir(parents=True, exist_ok=not args.out)
    save("launch.json", {"pid": os.getpid(), "startedAt": datetime.now(timezone.utc).isoformat()})
    jar = ROOT / "scoring-service/build/libs/scoring-service-0.0.1-SNAPSHOT.jar"
    pinned = OUT / "scoring.jar"
    shutil.copyfile(jar, pinned)
    digest = hashlib.sha256(pinned.read_bytes()).hexdigest()
    try:
        while True:
            if not args.cutoff and datetime.now(timezone.utc) >= DEADLINE:
                raise TimeoutError("Collection missed the dated publication deadline")
            runs = collection()
            if any(r.get("terminal_errors", 0) for r in runs):
                save("collection-failures.json", runs)
                raise RuntimeError("Terminal collection errors require an audited recovery")
            refresh = raw_refresh()
            if any(r.get("terminal_errors", 0) for r in refresh):
                save("raw-refresh-failures.json", refresh)
                raise RuntimeError("Terminal raw refresh errors require an audited recovery")
            if ready(runs, sessions) and ready(refresh, refresh_sessions):
                break
            status("WAITING_FOR_COLLECTION", complete=sum(r["complete"] for r in runs),
                   expected=sum(r["expected_count"] for r in runs), sessions=len(sessions))
            time.sleep(60)
        save("collection-ready.json", {"adjusted": runs, "rawRefresh": refresh})
        cutoff = datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")
        explicit = None
        if args.cutoff:
            chosen = datetime.fromisoformat(args.cutoff.replace("Z", "+00:00"))
            if chosen > datetime.now(timezone.utc) or chosen.astimezone(NEW_YORK).date().isoformat() != "2026-10-31":
                raise ValueError("Explicit cutoff must be elapsed and fall on the 2026-10-31 New York vintage date")
            # Every input the run may read must already be observed/available at the chosen cutoff.
            late = query(f"""
                SELECT 'daily_bar_observations' source,count(*) n FROM market_data.daily_bar_observations
                  WHERE ingested_at>'{args.cutoff}' OR available_at>'{args.cutoff}'
                UNION ALL SELECT 'consolidated_raw_liquidity',count(*) FROM market_data.consolidated_raw_liquidity
                  WHERE ingested_at>'{args.cutoff}' OR available_at>'{args.cutoff}'
                UNION ALL SELECT 'corporate_actions',count(*) FROM market_data.corporate_actions
                  WHERE ingested_at>'{args.cutoff}' OR available_at>'{args.cutoff}'
                UNION ALL SELECT 'instrument_symbols',count(*) FROM reference.instrument_symbols WHERE available_at>'{args.cutoff}'
                UNION ALL SELECT 'filings',count(*) FROM fundamentals.filings WHERE available_at>'{args.cutoff}'
                UNION ALL SELECT 'fundamental_facts',count(*) FROM fundamentals.fundamental_facts WHERE available_at>'{args.cutoff}'
                UNION ALL SELECT 'instrument_share_facts',count(*) FROM fundamentals.instrument_share_facts WHERE available_at>'{args.cutoff}'
                UNION ALL SELECT 'ingestion_run_items_terminal_after_cutoff',count(*) FROM operations.ingestion_run_items i
                  JOIN operations.ingestion_runs r USING(ingestion_run_id) JOIN operations.job_definitions j USING(job_definition_id)
                  WHERE j.configuration->>'adjustmentAsOf'='2026-10-31' AND i.status IN ('COMPLETE','FAILED')
                    AND i.updated_at>'{args.cutoff}'
            """)
            if any(row["n"] for row in late):
                save("late-observations.json", late)
                raise ValueError("Inputs were observed after the explicit cutoff")
            cutoff = chosen.isoformat().replace("+00:00", "Z")
            explicit = {"reason": "Adjustment vintage 2026-10-31 equals the cutoff's New York date; "
                                  "all inputs were observed before it (user decision 2026-11-01)",
                        "decidedAt": datetime.now(timezone.utc).isoformat(), "lateObservations": late}
        save("request.json", {"scoreDate": "2026-10-30", "cutoff": cutoff, "explicitCutoff": explicit,
                                  "modelVersion": "1.5.0", "preprocessingVersion": "canonical-preparation-v4",
                                  "jarSha256": digest})
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
                   "--scoring.model.version=1.5.0", "--scoring.command.score-date=2026-10-30",
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
            WHERE r.state='PUBLISHED' AND r.as_of_date='2026-10-30'
              AND r.knowledge_cutoff='{cutoff}' AND m.semantic_version='1.5.0'
        """)
        if len(published) != 1:
            raise ValueError("Expected exactly one new publication")
        run_id = str(UUID(published[0]["score_run_id"]))
        status("PUBLISHED_AUDIT_PENDING", runId=run_id)
        verify_publication(run_id)
    except Exception as error:  # noqa: BLE001 -- report only the type, never credentials from errors
        status("STOPPED_REQUIRES_INSPECTION", failureType=type(error).__name__)
        raise SystemExit(1) from None


if __name__ == "__main__":
    main()
