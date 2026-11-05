"""Live P2b check against publication bead056b: endpoint vs stored lineage, RSI vs an independent calc."""
import json, secrets, subprocess, urllib.request, urllib.error
from uuid import uuid4
from pathlib import Path
RUN = "bead056b-eb58-4e18-aa5b-6e7815d6f8e9"; OUT = Path("build/p2b-20261102/live")
def http(url, body=None, token=None):
    req = urllib.request.Request(url, data=json.dumps(body).encode() if body else None,
        headers={"Content-Type": "application/json", **({"Authorization": "Bearer " + token} if token else {})})
    try:
        with urllib.request.urlopen(req, timeout=60) as r: return r.status, json.loads(r.read() or b"null")
    except urllib.error.HTTPError as e: return e.code, json.loads(e.read() or b"null")
def sql(q):
    r = subprocess.run(["docker","exec","-i","quant-platform-timescaledb-1","psql","-U","postgres","-d","quant_platform","-X","-qAt"],
        input=f"BEGIN READ ONLY; SELECT coalesce(jsonb_agg(t),'[]') FROM ({q}) t; COMMIT;", capture_output=True, text=True, check=True)
    return json.loads(r.stdout)
cred = {"username": "p2b_" + uuid4().hex[:12], "password": secrets.token_urlsafe(24)}
http("http://localhost:18080/auth/register", cred)
token = http("http://localhost:18080/auth/login", cred)[1]["accessToken"]
def rsi(closes):
    ch=[b-a for a,b in zip(closes,closes[1:])]; g=sum(max(c,0) for c in ch[:14])/14; l=sum(max(-c,0) for c in ch[:14])/14
    for c in ch[14:]: g=(g*13+max(c,0))/14; l=(l*13+max(-c,0))/14
    return None if g==l==0 else 100.0 if l==0 else 100-100/(1+g/l)
report = {"runId": RUN, "instruments": {}}
rows = sql(f"SELECT l.symbol, l.instrument_id, l.prepared_input->'values' v FROM research.score_lineage l WHERE score_run_id='{RUN}' AND symbol IN ('AAPL','MSFT','JPM','O','SKYD','HONA','SPCX','WBD')")
for row in rows:
    code, body = http(f"http://localhost:18182/screener/instruments/{row['instrument_id']}/financials?runId={RUN}", token=token)
    assert code == 200, (row["symbol"], code, body)
    inputs = {i["code"]: i for i in body["inputs"]}
    mismatched = [k for k, v in (row["v"] or {}).items() if inputs.get(k, {}).get("value") != v]
    # Independent selection by the postgres role (same rules as scoring-inputs.sql), then Python RSI.
    bars = sql(f"""WITH r AS (SELECT request->'inputs' i, as_of_date d, market_cutoff m, knowledge_cutoff k FROM research.scoring_runs WHERE score_run_id='{RUN}'),
      s AS (SELECT session_date FROM reference.trading_sessions, r WHERE exchange_mic='XNYS' AND NOT holiday AND session_date<=r.d
            AND closes_at<=r.m AND available_at<=r.k AND observed_at<=r.k ORDER BY session_date DESC LIMIT 253),
      b AS (SELECT DISTINCT ON (b.session_date) b.session_date, b.close, b.quality_state, b.feed, b.currency
            FROM market_data.daily_bar_observations b JOIN s USING(session_date), r
            WHERE b.instrument_id='{row['instrument_id']}' AND b.dataset_id=(r.i->>'adjustedDataset')::uuid
              AND b.adjustment_mode='SPLIT_DIVIDEND' AND b.adjustment_as_of=(r.i->>'adjustmentBasis')::date
              AND b.available_at<=r.k AND b.observed_at<=r.k AND b.ingested_at<=r.k
            ORDER BY b.session_date, b.available_at DESC, b.observed_at DESC, b.ingested_at DESC, b.observation_key)
      SELECT s.session_date, b.close FROM s LEFT JOIN b ON b.session_date=s.session_date AND b.quality_state='VALID' AND b.feed='sip'
        AND b.currency=(SELECT currency FROM reference.instruments WHERE instrument_id='{row['instrument_id']}')
        AND b.session_date>=(SELECT valid_from FROM reference.instruments WHERE instrument_id='{row['instrument_id']}')
        AND b.session_date<coalesce((SELECT valid_to FROM reference.instruments WHERE instrument_id='{row['instrument_id']}'),'infinity')
      ORDER BY 1""")
    run_closes = []
    for b in reversed(bars):
        if b["close"] is None: break
        run_closes.insert(0, float(b["close"]))
    last_ok = bool(bars) and bars[-1]["close"] is not None
    expected = round(rsi(run_closes), 4) if last_ok and len(run_closes) >= 15 else None
    api = body["technical"]["rsi"]
    report["instruments"][row["symbol"]] = {
        "eligible": body["score"]["eligible"], "inputs": len(body["inputs"]), "derived": len(body["derived"]),
        "metrics": len(body["metrics"]), "inputValueMismatches": mismatched,
        "adjustedCloseMatchesRsiLastClose": (not last_ok) or row["v"].get("ADJUSTED_CLOSE") == run_closes[-1],
        "rsi": api["value"], "rsiIndependent": expected, "rsiSessions": api["sessionsUsed"], "rsiReason": api["reason"],
        "rsiAgrees": (api["value"] is None and expected is None) or (expected is not None and abs(api["value"] - expected) < 1e-4),
        "marketCap": next((d["value"] for d in body["derived"] if d["code"] == "market_cap"), None)}
code404, _ = http(f"http://localhost:18182/screener/instruments/{uuid4()}/financials?runId={RUN}", token=token)
code400, _ = http(f"http://localhost:18182/screener/instruments/{uuid4()}/financials", token=token)
report["errors"] = {"unknownInstrument": code404, "missingRunId": code400}
report["passed"] = all(v["rsiAgrees"] and not v["inputValueMismatches"] and v["adjustedCloseMatchesRsiLastClose"]
                       for v in report["instruments"].values()) and code404 == 404 and code400 == 400
(OUT / "verification.json").write_text(json.dumps(report, indent=2) + "\n"); print(json.dumps(report, indent=1))
