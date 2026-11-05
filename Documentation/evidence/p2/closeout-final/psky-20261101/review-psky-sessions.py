"""Review the stale MISSING_SESSION issues of PSKY/SKYD (instrument 8e26439e), 2026-11-01.

Authorized by the user's 2026-10-31 decision (individual source-backed reviews with the
Scoring credential held in memory only) and the 2026-11-01 request to finish P2.

Every open, unreviewed, blocking MISSING_SESSION issue of the instrument must fall in
exactly one of two verified categories, otherwise nothing is applied:

* SYMBOL_RETIRED: session >= 2026-10-06. Nasdaq PSKY was retired when Class B moved to
  NYSE as SKYD (retained 8-K). The same dataset holds a VALID XNYS bar of this instrument
  for the same session, collected under SKYD before this review; its observation key is
  recorded. The issue described a request for a retired symbol, not a session without
  trading.
* BEFORE_LISTING: session < 2025-08-07, the first effective date of the instrument's
  symbol history. The Class B shares were created at the closing of 2025-08-07 (retained
  8-K12B) and the session precedes the 253-session window starting 2025-10-29, so no
  price, history or liquidity input uses it.

Reviews never change prices, issues, symbols or timestamps; the server assigns
recorded_at, so only cutoffs after the review can see it. Same UUID + same content is
idempotent. Without --apply only the read-only checks run.

    research-engine/.venv/Scripts/python.exe \
        Documentation/evidence/p2/closeout-final/psky-20261101/review-psky-sessions.py [--apply]
"""

import argparse
import hashlib
import html
import json
import re
import subprocess
import sys
from pathlib import Path
from uuid import NAMESPACE_URL, uuid5

import psycopg

sys.path.insert(0, str(Path(__file__).resolve().parents[5] / "research-engine" / "src"))
from quant_research.input_review import apply, validate  # noqa: E402

ROOT = Path(__file__).resolve().parent
PRIOR = ROOT.parent / "reviews-20261031"
INSTRUMENT = "8e26439e-70a8-4c8f-a52d-efdc67e4567e"
REVIEWER = "claude-p2-primary-source-review-20261101"
WINDOW_START = "2025-10-29"
LISTED_FROM = "2025-08-07"
RENAMED_ON = "2026-10-06"
PASSAGES = {
    "PSKY-skydance-8k": "transferred the listing of the Class B Common Stock from The Nasdaq Stock Market LLC "
                        "to the New York Stock Exchange and changed the ticker symbol for the Class B Common "
                        "Stock from “PSKY” to “SKYD”",
    "PSKY-closing-8k12b": "shares of Class B Common Stock will trade on the Nasdaq Stock Market LLC "
                          "(“ Nasdaq ”) under the ticker symbol “PSKY”",
}
CLOSING = "On August 7, 2025, Paramount Skydance Corporation, a Delaware corporation"


def credential():
    process = subprocess.run(["docker", "inspect", "--format", "{{json .Config.Env}}",
                              "quant-platform-scoring-service-1"], capture_output=True, text=True, check=True)
    settings = dict(item.split("=", 1) for item in json.loads(process.stdout) if "=" in item)
    return settings["SCORING_DB_PASSWORD"]


def sources():
    index = {r["key"]: dict(r, base=PRIOR) for r in json.loads((PRIOR / "source-files.json").read_text())}
    index.update({r["key"]: dict(r, base=ROOT) for r in json.loads((ROOT / "source-files.json").read_text())})
    texts = {}
    for key, passage in PASSAGES.items():
        raw = (index[key]["base"] / index[key]["file"]).read_bytes()
        if hashlib.sha256(raw).hexdigest() != index[key]["sourceSha256"]:
            raise ValueError("Retained source changed: " + key)
        texts[key] = re.sub(r"\s+", " ", html.unescape(re.sub(r"<[^>]+>", " ", raw.decode("utf-8", "replace"))))
        if passage not in texts[key]:
            raise ValueError("Passage missing from " + key)
    if CLOSING not in texts["PSKY-closing-8k12b"]:
        raise ValueError("Closing passage missing")
    return index


def source_fields(entry, passage):
    return dict(sourceUri=entry["sourceUri"], sourceSha256=entry["sourceSha256"],
                sourceObservedAt=entry["observedAt"], captureFormat=entry["captureFormat"],
                sourcePassage=passage)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    index = sources()
    with psycopg.connect(host="127.0.0.1", port=6543, dbname="quant_platform", user="quant_scoring",
                         password=credential(), connect_timeout=10) as connection:
        with connection.cursor() as cursor:
            cursor.execute("""SELECT min(effective_from)::text, count(*) FILTER (WHERE symbol='SKYD'
                    AND exchange_mic='XNYS' AND effective_from=%s AND effective_to IS NULL)
                FROM reference.instrument_symbols WHERE instrument_id=%s""", (RENAMED_ON, INSTRUMENT))
            first, skyd = cursor.fetchone()
            if first != LISTED_FROM or skyd != 1:
                raise ValueError("Symbol history differs from the reviewed state")
            cursor.execute("""
                SELECT q.data_quality_issue_id::text, q.dataset_id::text, q.affected_key, q.evidence::text,
                    q.ingestion_run_id::text, j.configuration->>'adjustment', j.configuration->>'adjustmentAsOf',
                    (SELECT jsonb_agg(jsonb_build_object('observationKey',b.observation_key,
                         'adjustmentAsOf',b.adjustment_as_of,'ingestedAt',b.ingested_at)
                         ORDER BY b.adjustment_as_of NULLS FIRST, b.ingested_at)
                     FROM market_data.daily_bar_observations b
                     WHERE b.instrument_id=q.instrument_id AND b.dataset_id=q.dataset_id
                       AND b.session_date=CAST(q.affected_key AS date) AND b.exchange_mic='XNYS'
                       AND b.quality_state='VALID' AND b.ingested_at>q.detected_at)
                FROM operations.data_quality_issues q
                JOIN operations.ingestion_runs r USING(ingestion_run_id)
                JOIN operations.job_definitions j ON j.job_definition_id=r.job_definition_id
                WHERE q.instrument_id=%s AND q.issue_type='MISSING_SESSION' AND q.status='OPEN'
                  AND q.severity='BLOCKING'
                  AND NOT EXISTS (SELECT 1 FROM operations.input_reviews v
                      WHERE v.issue_id=q.data_quality_issue_id AND v.reviewer<>%s)
                ORDER BY q.affected_key, q.data_quality_issue_id""", (INSTRUMENT, REVIEWER))
            issues = cursor.fetchall()
            cursor.execute("""SELECT count(*) FROM operations.data_quality_issues q
                WHERE q.instrument_id=%s AND q.status='OPEN' AND q.severity='BLOCKING'
                  AND q.issue_type<>'MISSING_SESSION'
                  AND NOT EXISTS (SELECT 1 FROM operations.input_reviews v WHERE v.issue_id=q.data_quality_issue_id)""",
                           (INSTRUMENT,))
            if cursor.fetchone()[0]:
                raise ValueError("Other open blocking issues exist; they are not covered by this review")
        manifests = []
        for issue, dataset, session, exact, run, mode, vintage, bars in issues:
            if json.loads(exact) != {"reason": "provider pagination exhausted"}:
                raise ValueError("Unexpected issue evidence " + issue)
            common = dict(issueEvidence=exact, issueRunId=run, datasetId=dataset, adjustment=mode,
                          issueAdjustmentAsOf=vintage, session=session)
            if session >= RENAMED_ON:
                if not bars:
                    raise ValueError(f"No later SKYD bar for {session} in {dataset}")
                entry = index["PSKY-skydance-8k"]
                evidence = dict(scope="SYMBOL_RETIRED_SESSION_RECOVERED_UNDER_SUCCESSOR_SYMBOL",
                                **source_fields(entry, PASSAGES["PSKY-skydance-8k"]), **common,
                                recoveredBars=bars,
                                rationale="Class B moved from Nasdaq PSKY to NYSE SKYD on 2026-10-06. The issue "
                                          "records an exhausted provider response for the retired PSKY request, "
                                          "not a session without trading; the same dataset holds a VALID XNYS bar "
                                          "of this instrument for the session, collected under SKYD. No price is "
                                          "copied, re-dated or inferred.")
            elif session < LISTED_FROM and session < WINDOW_START:
                entry = index["PSKY-closing-8k12b"]
                evidence = dict(scope="SESSION_BEFORE_INSTRUMENT_LISTING",
                                **source_fields(entry, PASSAGES["PSKY-closing-8k12b"]), **common,
                                supportingPassage=CLOSING, instrumentListedFrom=LISTED_FROM,
                                windowStart=WINDOW_START,
                                rationale="Paramount Skydance Class B shares were created at the closing on "
                                          "2025-08-07 and first traded as PSKY. A session before that date "
                                          "cannot have a bar for this instrument, and it precedes the 253-session "
                                          "window, so no history, momentum or liquidity input uses it.")
            else:
                raise ValueError("Issue outside both reviewed categories: " + session)
            manifests.append(dict(reviewId=str(uuid5(NAMESPACE_URL, "p2-20261101-psky-review:" + issue)),
                                  kind="QUALITY_ISSUE", instrumentId=INSTRUMENT, factId=None, issueId=issue,
                                  decision="ACCEPT", reviewer=REVIEWER, evidence=evidence))
        files = {m["reviewId"]: (index["PSKY-skydance-8k"] if m["evidence"]["session"] >= RENAMED_ON
                                 else index["PSKY-closing-8k12b"]) for m in manifests}
        for manifest in manifests:
            entry = files[manifest["reviewId"]]
            validate(manifest, entry["base"] / entry["file"])
        summary = {"instrumentId": INSTRUMENT, "issues": len(manifests),
                   "byScope": {s: sum(m["evidence"]["scope"] == s for m in manifests)
                               for s in sorted({m["evidence"]["scope"] for m in manifests})}}
        if not args.apply:
            print(json.dumps(dict(summary, applied=False), indent=2))
            return
        (ROOT / "manifests").mkdir(exist_ok=True)
        applied = []
        for manifest in manifests:
            path = ROOT / "manifests" / (manifest["reviewId"] + ".json")
            content = json.dumps(manifest, indent=2, ensure_ascii=False) + "\n"
            if path.exists() and path.read_text(encoding="utf-8") != content:
                raise ValueError("Stored manifest differs: " + path.name)
            path.write_text(content, encoding="utf-8")
            recorded = apply(connection, manifest)
            again = apply(connection, manifest)
            if again != recorded:
                raise ValueError("Review is not idempotent")
            applied.append(dict(reviewId=manifest["reviewId"], issueId=manifest["issueId"],
                                session=manifest["evidence"]["session"],
                                scope=manifest["evidence"]["scope"], recordedAt=recorded))
        (ROOT / "reviews-applied.json").write_text(json.dumps(dict(summary, applied=applied), indent=2) + "\n",
                                                   encoding="utf-8")
        print(json.dumps(dict(summary, applied=True,
                              lastRecordedAt=max(a["recordedAt"] for a in applied)), indent=2))


if __name__ == "__main__":
    main()
