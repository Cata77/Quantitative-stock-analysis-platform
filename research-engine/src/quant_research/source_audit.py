"""Read-only SQL helper kept for publication_audit; the full P0 source audit is archived
in research-engine/archive/p0_p2_audits/quant_research_audits/source_audit.py (P2s)."""

from __future__ import annotations

import json
import subprocess


def query(container: str, select: str) -> list[dict]:
    sql = (
        "BEGIN READ ONLY; SET LOCAL ROLE quant_research; "
        "SET LOCAL statement_timeout=120000; "
        f"SELECT coalesce(jsonb_agg(t),'[]'::jsonb) FROM ({select}) t; COMMIT;"
    )
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
        input=sql,
        capture_output=True,
        text=True,
        encoding="utf-8",
        check=True,
    )
    return json.loads(result.stdout)
