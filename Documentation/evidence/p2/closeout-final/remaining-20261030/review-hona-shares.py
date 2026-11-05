"""Bind the retained HONA cover-page share count to its exact common-stock class."""

import hashlib
import json
import re
import subprocess
from pathlib import Path
from uuid import NAMESPACE_URL, uuid5

import psycopg
from quant_research.input_review import apply, validate

ROOT = Path(__file__).resolve().parent
INSTRUMENT = "97e6932f-7dca-4a6f-b955-5bd469481ee6"
FACT = "7fc68f06-2661-4823-96b2-bbed12c03c78"
PUBLICATION = Path("build/p2-final-20261030/current-recovery-2/publication")


def main():
    evidence_file = ROOT / "source-files.json"
    sources = json.loads(evidence_file.read_text())
    for source in sources:
        if hashlib.sha256((ROOT / source["file"]).read_bytes()).hexdigest() != source["sourceSha256"]:
            raise ValueError("Retained source changed")
    source = next(s for s in sources if s["symbol"] == "HONA-10Q")
    manifest = {
        "reviewId": str(uuid5(NAMESPACE_URL, "p2-hona-shares:" + FACT)),
        "kind": "SHARE_CLASS", "instrumentId": INSTRUMENT, "factId": FACT,
        "issueId": None, "decision": "ACCEPT", "reviewer": "codex-p2-primary-source-share-review",
        "evidence": {
            "scope": "EXACT_INSTRUMENT_SHARES", "shareClass": "Common Stock, par value $0.01",
            "sourceUri": source["sourceUri"],
            "sourceSha256": hashlib.sha256(evidence_file.read_bytes()).hexdigest(),
            "sourceObservedAt": source["observedAt"],
            "captureFormat": "HASHED_MANIFEST_OF_ORIGINAL_HTTP_RESPONSES",
            "issuerCik": "0002089271", "shares": 316952725, "sharesAsOf": "2026-07-25",
            "rationale": "The August 5 HONA 10-Q cover identifies HONA common stock and "
                         "316,952,725 shares outstanding on July 25, 2026. The June 25 8-K "
                         "confirms common stock is the sole class of capital stock after "
                         "recapitalization. This is an instant outstanding count, not weighted "
                         "average EPS shares, authorized shares, or parent Honeywell shares. "
                         "Link only the retained fact of the same issuer; do not waive "
                         "freshness, history, accounting or market-price requirements.",
        },
    }
    validate(manifest, evidence_file)
    process = subprocess.run(
        ["docker", "inspect", "--format", "{{json .Config.Env}}", "quant-platform-scoring-service-1"],
        capture_output=True, text=True, check=True,
    )
    settings = dict(item.split("=", 1) for item in json.loads(process.stdout) if "=" in item)
    with psycopg.connect(host="127.0.0.1", port=6543, dbname="quant_platform",
                        user="quant_scoring", password=settings["SCORING_DB_PASSWORD"]) as connection:
        with connection.transaction(), connection.cursor() as cursor:
            cursor.execute("""
                SELECT f.numeric_value,f.period_end::text,f.source_concept,iss.cik,
                       fi.accession,f.quality_state,f.period_start,f.unit
                FROM fundamentals.fundamental_facts f
                JOIN fundamentals.filings fi USING(filing_id)
                JOIN reference.issuers iss ON iss.issuer_id=f.issuer_id
                JOIN reference.instruments i ON i.issuer_id=f.issuer_id
                WHERE f.fact_id=%s AND i.instrument_id=%s
            """, (FACT, INSTRUMENT))
            if cursor.fetchone() != (316952725, "2026-07-25", "EntityCommonStockSharesOutstanding",
                                     "0002089271", "0002089271-26-000021", "VALID", None, "shares"):
                raise ValueError("Canonical share fact differs from the reviewed cover page")
            recorded = apply(connection, manifest)
            if apply(connection, manifest) != recorded:
                raise ValueError("Review replay is not idempotent")
        # Reuse the production selection verbatim, including cutoff-aware review selection.
        sql = Path("scoring-service/src/main/resources/sql/scoring-inputs.sql").read_text()
        prefix = sql.split(",\nbars_ranked AS MATERIALIZED (")[0]
        issuer = "issuers AS MATERIALIZED (SELECT DISTINCT issuer_id FROM instruments)"
        if prefix.count(issuer) != 1:
            raise ValueError("Production SQL extraction requires review")
        prefix = prefix.replace(issuer, "issuers AS MATERIALIZED (SELECT DISTINCT issuer_id "
                                "FROM instruments WHERE instrument_id=CAST(:instrument AS uuid))")
        query = prefix + "\nSELECT fact_id::text,numeric_value FROM shares_ranked " \
                         "WHERE instrument_id=CAST(:instrument AS uuid) AND r=1"
        query = re.sub(r"(?<!:):([A-Za-z][A-Za-z0-9]*)", r"%(\1)s", query)
        request = json.loads((PUBLICATION / "published-input-document.json").read_text())["canonical"]["request"]
        params = dict(request, basis=request["adjustmentBasis"], sp500=request["sp500Snapshot"],
                      nasdaq=request["nasdaq100Snapshot"], classification=request["classificationVersion"],
                      mapping=request["mappingVersion"], instrument=INSTRUMENT)
        selected = {}
        with connection.transaction(), connection.cursor() as cursor:
            cursor.execute("SET TRANSACTION READ ONLY")
            cursor.execute("SET LOCAL statement_timeout=60000")
            for label, cutoff in (("publishedCutoff", request["knowledgeCutoff"]), ("reviewCutoff", recorded)):
                params["knowledgeCutoff"] = cutoff
                cursor.execute(query, params)
                selected[label] = [(fact, int(value)) for fact, value in cursor.fetchall()]
        if selected != {"publishedCutoff": [], "reviewCutoff": [(FACT, 316952725)]}:
            raise ValueError("Production share selection did not change exactly as expected")
    for name, document in (
        ("review-HONA-shares.json", manifest),
        ("shares-applied.json", {"reviewId": manifest["reviewId"], "recordedAt": recorded,
                                 "idempotent": True, "selection": selected,
                                 "scope": "SHARE_SELECTION_ONLY_NOT_A_NEW_SCORE"}),
    ):
        path = ROOT / name
        if path.exists():
            if json.loads(path.read_text()) != json.loads(json.dumps(document)):
                raise ValueError("Existing evidence differs")
        else:
            path.write_text(json.dumps(document, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"recordedAt": recorded, "selectionVerified": True, "published": False}))


if __name__ == "__main__":
    main()
