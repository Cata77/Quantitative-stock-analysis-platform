"""Continue the dated publication using explicitly audited replacement collections.

Only the three sessions with exhausted transport retries are replaced. Original
attempts, checkpoints and failures remain intact. Run from the repository root.
Collection itself is launched separately with the request key below.
"""

import runpy
from pathlib import Path

SESSIONS = {"2026-02-25", "2026-02-26", "2026-03-03"}
REQUEST_KEY = "force:p2-20261030-transport-recovery:exhausted-transport-retries"
SECOND_REQUEST_KEY = "force:p2-20261030-transport-recovery-2:exhausted-transport-retries"


def select_runs(rows):
    return [row for row in rows if row["request_key"] == (
        SECOND_REQUEST_KEY if row["session"] == "2026-02-26"
        else REQUEST_KEY if row["session"] in SESSIONS else "scheduled"
    )]


def main():
    base = runpy.run_path(str(Path(__file__).with_name("finish-20261030.py")))

    def collection():
        return select_runs(base["query"]("""
            SELECT r.window_start::text session,r.request_key,r.ingestion_run_id,
              r.expected_count,count(i.*) actual_count,
              count(*) FILTER(WHERE i.status='COMPLETE') complete,
              count(*) FILTER(WHERE i.status='FAILED'
                AND i.failure->>'code'='MISSING_SESSION') absent,
              count(*) FILTER(WHERE i.status='FAILED'
                AND (i.failure->>'code') IS DISTINCT FROM 'MISSING_SESSION') terminal_errors
            FROM operations.ingestion_runs r
            JOIN operations.job_definitions j USING(job_definition_id)
            LEFT JOIN operations.ingestion_run_items i USING(ingestion_run_id)
            WHERE j.configuration->>'adjustmentAsOf'='2026-10-30'
              AND j.configuration->>'symbolAsOf'='2026-10-30'
              AND j.configuration->>'adjustment'='SPLIT_DIVIDEND'
              AND r.window_start BETWEEN '2025-10-28' AND '2026-10-29'
            GROUP BY r.ingestion_run_id ORDER BY r.window_start
        """))

    base["main"].__globals__.update(
        OUT=Path.cwd() / "build/p2-final-20261030/current-recovery-2",
        collection=collection,
    )
    base["main"]()


if __name__ == "__main__":
    main()
