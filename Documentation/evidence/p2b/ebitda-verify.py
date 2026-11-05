"""Live EBITDA_FROM_SOURCE_V1 check on publication bead056b (2026-11-05).

Every instrument of the run is requested from the new Screener jar (port 18182, role quant_screener).
The expected D&A is computed independently in SQL as the postgres role: the OPERATING_INCOME_TTM
source facts of the stored lineage, the original retained SEC response behind each fact, the same
accession/start/end, one concept (priority DDA > D&A) for every component, signs from the periods.
Run from the repository root; writes only build/p2b-ebitda-20261105/live/ebitda-verification.json.
"""
import json
import secrets
import subprocess
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from decimal import Decimal
from pathlib import Path
from uuid import uuid4

RUN = "bead056b-eb58-4e18-aa5b-6e7815d6f8e9"
OUT = Path("build/p2b-ebitda-20261105/live")
SCREENER = "http://localhost:18182"


def http(url, body=None, token=None):
    request = urllib.request.Request(url, data=json.dumps(body).encode() if body else None, headers={
        "Content-Type": "application/json", **({"Authorization": "Bearer " + token} if token else {})})
    try:
        with urllib.request.urlopen(request, timeout=120) as response:
            return response.status, json.loads(response.read() or b"null", parse_float=Decimal)
    except urllib.error.HTTPError as error:
        return error.code, None


def sql(query):
    result = subprocess.run(
        ["docker", "exec", "-i", "quant-platform-timescaledb-1", "psql", "-U", "postgres", "-d", "quant_platform",
         "-X", "-qAt", "-v", "ON_ERROR_STOP=1"],
        input=f"BEGIN READ ONLY; SET LOCAL statement_timeout=600000; "
              f"SELECT coalesce(jsonb_agg(t),'[]') FROM ({query}) t; COMMIT;",
        capture_output=True, text=True, encoding="utf-8", check=True)
    return json.loads(result.stdout, parse_float=Decimal)


EXPECTED = f"""
WITH r AS (SELECT knowledge_cutoff k FROM research.scoring_runs WHERE score_run_id='{RUN}'),
rows AS (
  SELECT l.instrument_id, l.symbol, (l.prepared_input->'values'->>'OPERATING_INCOME_TTM')::numeric oi,
         l.prepared_input->'provenance'->'OPERATING_INCOME_TTM'->'source_ids' ids
  FROM research.score_lineage l WHERE l.score_run_id='{RUN}'),
parts AS (
  SELECT x.instrument_id, f.fact_id, f.period_start s, f.period_end e, f.numeric_value v, g.accession,
         coalesce((a.inline_content->>'originalArtifactId')::uuid, a.source_artifact_id) raw
  FROM rows x CROSS JOIN LATERAL jsonb_array_elements_text(x.ids) i(id)
  JOIN fundamentals.fundamental_facts f ON f.fact_id=i.id::uuid
  JOIN fundamentals.filings g USING(filing_id)
  JOIN operations.source_artifacts a ON a.source_artifact_id=f.source_artifact_id),
shaped AS (
  SELECT p.*, count(*) OVER w n, max(e) OVER w last_end,
         (e-s+1) BETWEEN 350 AND 380 annual FROM parts p WINDOW w AS (PARTITION BY instrument_id)),
signed AS (
  SELECT *, CASE WHEN n IN (1,4) THEN 1 WHEN e=last_end AND NOT annual THEN 1 WHEN annual THEN 1 ELSE -1 END sign
  FROM shaped),
da AS (
  SELECT p.instrument_id, p.fact_id, k.concept, k.priority, count(DISTINCT (j->>'val')::numeric) c,
         min((j->>'val')::numeric) val
  FROM signed p JOIN operations.source_artifacts a ON a.source_artifact_id=p.raw, r
  CROSS JOIN (VALUES ('DepreciationDepletionAndAmortization',1),('DepreciationAndAmortization',2)) k(concept,priority)
  CROSS JOIN LATERAL jsonb_array_elements(coalesce(
      a.inline_content->'companyFacts'->'facts'->'us-gaap'->k.concept->'units'->'USD','[]'::jsonb)) j
  WHERE a.retrieved_at<=r.k AND j->>'accn'=p.accession AND j->>'start'=p.s::text AND j->>'end'=p.e::text
    AND j->>'form' IN ('10-K','10-Q','10-K/A','10-Q/A')
  GROUP BY 1,2,3,4),
complete AS (
  SELECT d.instrument_id, d.concept, d.priority, sum(d.val*p.sign) total, sum(p.v*p.sign) oi_check
  FROM da d JOIN signed p USING(instrument_id,fact_id) WHERE d.c=1
  GROUP BY 1,2,3 HAVING count(*)=(SELECT count(*) FROM signed q WHERE q.instrument_id=d.instrument_id))
SELECT x.symbol, x.instrument_id::text, x.oi,
  (SELECT c.total FROM complete c WHERE c.instrument_id=x.instrument_id ORDER BY c.priority LIMIT 1) da,
  (SELECT c.concept FROM complete c WHERE c.instrument_id=x.instrument_id ORDER BY c.priority LIMIT 1) concept,
  (SELECT c.oi_check FROM complete c WHERE c.instrument_id=x.instrument_id ORDER BY c.priority LIMIT 1) oi_check
FROM rows x ORDER BY x.symbol
"""


def main():
    expected = sql(EXPECTED)
    credentials = {"username": "ebitda_" + uuid4().hex[:12], "password": secrets.token_urlsafe(24)}
    http("http://localhost:18080/auth/register", credentials)
    token = http("http://localhost:18080/auth/login", credentials)[1]["accessToken"]

    def fetch(row):
        return row, http(f"{SCREENER}/screener/instruments/{row['instrument_id']}/financials?runId={RUN}&view=FULL",
                         token=token)

    with ThreadPoolExecutor(max_workers=4) as pool:
        results = list(pool.map(fetch, expected))
    mismatches, reasons, available = [], {}, 0
    for row, (code, body) in results:
        if code != 200:
            mismatches.append({"symbol": row["symbol"], "http": code})
            continue
        actual = body["supplemental"]["depreciationAmortization"]
        want = row["da"] if row["oi"] is not None and row["oi_check"] == row["oi"] else None
        same = (actual["value"] == want and (want is None or (
            actual["ebitda"] == row["oi"] + want and actual["concept"] == "us-gaap:" + row["concept"])))
        if actual["partOfScore"] is not False or not same:
            mismatches.append({"symbol": row["symbol"], "expected": want, "actual": actual["value"],
                               "reason": actual["reason"]})
        if actual["value"] is not None:
            available += 1
        else:
            reasons[actual["reason"]] = reasons.get(actual["reason"], 0) + 1
    aapl = next(b for r, (c, b) in results if r["symbol"] == "AAPL")["supplemental"]["depreciationAmortization"]
    report = {"runId": RUN, "instruments": len(results), "available": available, "gapReasons": reasons,
              "mismatches": mismatches, "aapl": aapl, "passed": not mismatches}
    (OUT / "ebitda-verification.json").write_text(json.dumps(report, indent=2, default=str) + "\n", encoding="utf-8")
    print(json.dumps({k: v for k, v in report.items() if k != "aapl"}, default=str))


if __name__ == "__main__":
    main()
