"""Prepare the two individually reviewed renames; read-only DB access, no approvals applied.

Run from the repository root with research-engine's Python environment.
The retained issuer announcements were reviewed individually; this is not a
general rule for accepting name_changes or matching instruments by ticker.
"""

import hashlib
import json
import subprocess
from pathlib import Path
from uuid import NAMESPACE_URL, UUID, uuid5

from quant_research.input_review import validate


def query(select):
    result = subprocess.run(
        ["docker", "exec", "-i", "quant-platform-timescaledb-1", "psql", "-U", "postgres",
         "-d", "quant_platform", "-X", "-qAt", "-v", "ON_ERROR_STOP=1"],
        input="BEGIN READ ONLY; SET LOCAL ROLE quant_scoring; "
              "SET LOCAL statement_timeout=120000; "
              f"SELECT coalesce(jsonb_agg(t),'[]'::jsonb) FROM ({select}) t; COMMIT;",
        capture_output=True, text=True, encoding="utf-8", check=True,
    )
    return json.loads(result.stdout)


def main():
    target = Path(__file__).resolve().parent
    source = target / "identity-sources.json"
    digest = hashlib.sha256(source.read_bytes()).hexdigest()
    confirmed = {
        "ECHO": ("SATS", "2026-06-24", "278768106", "EchoStar",
                 ("https://echostar.gcs-web.com/news-releases/news-release-details/"
                 "echostar-changing-stocker-ticker-sats-echo-marking-companys-next")),
        "P": ("PSTG", "2026-04-17", "74624M102", "Everpure",
              ("https://www.everpuredata.com/company/newsroom/press-releases/"
              "everpure-to-change-ticker-symbol.html")),
    }
    proposals = json.loads((target / "proposed-reviews.json").read_text(encoding="utf-8"))
    if len(proposals) != 2 or {p["symbol"] for p in proposals} != set(confirmed):
        raise ValueError("Expected exactly the two individually reviewed renames")
    prepared = []
    for proposal in proposals:
        symbol = proposal["symbol"]
        old, date, cusip, issuer_name, uri = confirmed[symbol]
        issue_id = str(UUID(proposal["issueId"]))
        instrument_id = str(UUID(proposal["instrumentId"]))
        if proposal["sourceSha256"] != digest or uri not in source.read_text(encoding="utf-8"):
            raise ValueError("Retained source changed")
        records = query(f"""
            SELECT q.evidence::text AS exact_evidence, q.issue_type,
                   iss.legal_name, iss.cik, a.provider_content, a.source_artifact_id
            FROM operations.data_quality_issues q
            JOIN reference.instruments i USING(instrument_id)
            JOIN reference.issuers iss USING(issuer_id)
            JOIN market_data.corporate_actions a ON a.instrument_id=q.instrument_id
              AND a.provider_action_id=q.affected_key
              AND a.revision_hash=q.evidence->>'revision'
            WHERE q.data_quality_issue_id='{issue_id}' AND q.instrument_id='{instrument_id}'
        """)
        if len(records) != 1:
            raise ValueError("Expected exactly one retained issue and action revision")
        record = records[0]
        action = record["provider_content"]
        if (record["issue_type"] != "CORPORATE_ACTION_REVIEW"
                or issuer_name.lower() not in record["legal_name"].lower()
                or action != proposal["providerAction"]
                or action["type"] != "name_changes"
                or (action["old_symbol"], action["new_symbol"], action["process_date"])
                != (old, symbol, date)
                or action["old_cusip"] != cusip or action["new_cusip"] != cusip):
            raise ValueError("Canonical issue no longer matches the reviewed rename")
        manifest = {
            "reviewId": str(uuid5(NAMESPACE_URL, "p2-closeout-rename:" + issue_id)),
            "kind": "QUALITY_ISSUE", "instrumentId": instrument_id, "factId": None,
            "issueId": issue_id, "decision": "ACCEPT", "reviewer": "codex-p2-issuer-rename-review",
            "evidence": {
                "sourceUri": uri, "sourceSha256": digest,
                "captureFormat": "WEB_TOOL_RENDERED_RESPONSE_NOT_ORIGIN_BYTES",
                "scope": "EXACT_RENAME_ISSUE_ONLY", "issueEvidence": record["exact_evidence"],
                "providerAction": action, "providerSourceArtifactId": record["source_artifact_id"],
                "issuerName": record["legal_name"], "issuerCik": record["cik"],
                "rationale": "Issuer announcement confirms the dated symbol change and unchanged "
                "CUSIP for the same common stock. Provider old/new symbols, effective date "
                "and unchanged CUSIP agree. Accept only this rename issue; do not change "
                "price observations, historical symbols or any other corporate action.",
            },
        }
        validate(manifest, source)
        path = target / f"review-{symbol}.json"
        if path.exists():
            if json.loads(path.read_text(encoding="utf-8")) != manifest:
                raise ValueError("Existing manifest differs; evidence must not be overwritten")
        else:
            with path.open("x", encoding="utf-8") as stream:
                stream.write(json.dumps(manifest, indent=2) + "\n")
        prepared.append({"symbol": symbol, "reviewId": manifest["reviewId"],
                             "valid": True, "applied": False})
    print(json.dumps(prepared, indent=2))


if __name__ == "__main__":
    main()
