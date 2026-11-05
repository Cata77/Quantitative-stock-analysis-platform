"""Review six exact ONEOK events against retained issuer and exchange sources."""
import hashlib
import json
import subprocess
from decimal import Decimal
from pathlib import Path
from uuid import NAMESPACE_URL, uuid5

import psycopg
from quant_research.input_review import apply, validate

ROOT = Path(__file__).resolve().parent
TERMS = {
    "2025-08-01": ("2025-08-14", "1.03"),
    "2025-11-03": ("2025-11-14", "1.03"),
    "2026-02-02": ("2026-02-13", "1.07"),
    "2026-05-04": ("2026-05-15", "1.07"),
    "2026-08-03": ("2026-08-14", "1.07"),
}


def main():
    sources_file = ROOT / "source-files.json"
    sources = json.loads(sources_file.read_text())
    if {s["name"] for s in sources} != {"dividends", "identity", "exchange", "reorganization"}:
        raise ValueError("Missing retained source")
    for source in sources:
        if hashlib.sha256((ROOT / source["file"]).read_bytes()).hexdigest() != source["sourceSha256"]:
            raise ValueError("Retained source changed")
    audit = json.loads(Path("Documentation/evidence/p2/closeout-final/remaining-audit-20261031.json")
                       .read_text())
    row = next(r for r in audit["rows"] if r["symbol"] == "OKE")
    if len(row["issues"]) != 6:
        raise ValueError("Unexpected event set")
    # Previously authorized local service credential, held only in memory.
    result = subprocess.run(["docker", "inspect", "--format", "{{json .Config.Env}}",
                             "quant-platform-scoring-service-1"],
                            text=True, capture_output=True, check=True)
    settings = dict(s.split("=", 1) for s in json.loads(result.stdout) if "=" in s)
    report = []
    with psycopg.connect(host="127.0.0.1", port=6543, dbname="quant_platform",
                        user="quant_scoring", password=settings["SCORING_DB_PASSWORD"],
                        connect_timeout=10) as connection, connection.transaction():
        for issue in row["issues"]:
            action = issue["provider_content"]
            if action["type"] == "cash_dividends":
                payable, amount = TERMS[action["ex_date"]]
                if (action["record_date"] != action["ex_date"] or action["payable_date"] != payable
                        or Decimal(str(action["rate"])) != Decimal(amount)
                        or action["cusip"] != "682680103" or action["symbol"] != "OKE"
                        or action["foreign"] or action["special"]):
                    raise ValueError("Dividend terms differ from the individually checked issuer history")
                source = next(s for s in sources if s["name"] == "dividends")
                rationale = ("Issuer dividend history confirms USD amount, ex-date, record date and "
                             "payable date for this exact ordinary common-stock distribution. "
                             "The exchange notice identifies 682680103 as the predecessor OKE CUSIP. "
                             "All five reviewed dividends precede the September reorganization.")
            elif action["type"] == "name_changes":
                expected = {"old_cusip": "682680103", "new_cusip": "30609A109",
                            "old_symbol": "OKE", "new_symbol": "OKE", "process_date": "2026-09-11"}
                if any(action.get(k) != v for k, v in expected.items()):
                    raise ValueError("Identity event differs from reviewed terms")
                source = next(s for s in sources if s["name"] == "reorganization")
                rationale = ("The issuer 8-K and NYSE notice confirm the September 10 legal "
                             "reorganization, the old and new CUSIPs and a one-for-one conversion "
                             "with unchanged share counts and percentage ownership. The issuer FAQ "
                             "dates the new trading CUSIP to September 11, matching the provider "
                             "process date. Legal effectiveness and trading processing are distinct. "
                             "The same OKE listing continues; this is not an unrelated ticker reuse.")
            else:
                raise ValueError("Unreviewed action type")
            with connection.cursor() as cursor:
                cursor.execute("""
                    SELECT q.evidence::text,iss.cik,a.provider_content,a.source_artifact_id::text
                    FROM operations.data_quality_issues q
                    JOIN reference.instruments i USING(instrument_id)
                    JOIN reference.issuers iss USING(issuer_id)
                    JOIN market_data.corporate_actions a ON a.instrument_id=q.instrument_id
                      AND a.provider_action_id=q.affected_key
                      AND a.revision_hash=q.evidence->>'revision'
                    WHERE q.data_quality_issue_id=%s AND q.instrument_id=%s
                """, (issue["data_quality_issue_id"], row["instrument_id"]))
                matches = cursor.fetchall()
            if len(matches) != 1:
                raise ValueError("Expected one exact issue revision")
            exact, cik, actual, artifact = matches[0]
            if (cik != "0001039684" or actual != action or json.loads(exact) != issue["evidence"]
                    or artifact != issue["source_artifact_id"]):
                raise ValueError("Canonical identity or event changed")
            manifest = {
                "reviewId": str(uuid5(NAMESPACE_URL, "p2-oke-20261031:" + issue["data_quality_issue_id"])),
                "kind": "QUALITY_ISSUE", "instrumentId": row["instrument_id"], "factId": None,
                "issueId": issue["data_quality_issue_id"], "decision": "ACCEPT",
                "reviewer": "codex-p2-issuer-and-exchange-review",
                "evidence": {
                    "sourceUri": source["sourceUri"], "sourceObservedAt": source["observedAt"],
                    "sourceSha256": hashlib.sha256(sources_file.read_bytes()).hexdigest(),
                    "captureFormat": "HASHED_MANIFEST_OF_ORIGINAL_HTTP_RESPONSES",
                    "issueEvidence": exact, "providerAction": action, "issuerCik": cik,
                    "providerSourceArtifactId": artifact,
                    "rationale": rationale + " Accept only this exact quality issue. Preserve "
                                 "prices, event revisions, other blockers and observation timestamps.",
                },
            }
            validate(manifest, sources_file)
            path = ROOT / ("review-" + issue["data_quality_issue_id"] + ".json")
            if path.exists():
                if json.loads(path.read_text()) != manifest:
                    raise ValueError("Existing manifest differs")
            else:
                path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
            recorded = apply(connection, manifest)
            if apply(connection, manifest) != recorded:
                raise ValueError("Non-idempotent review")
            report.append(dict(reviewId=manifest["reviewId"], recordedAt=recorded, idempotent=True))
    path = ROOT / "reviews-applied.json"
    document = dict(symbol="OKE", reviews=report, applied=len(report), published=False)
    if path.exists() and json.loads(path.read_text()) != document:
        raise ValueError("Existing application evidence differs")
    path.write_text(json.dumps(document, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(document, indent=2))


if __name__ == "__main__":
    main()
