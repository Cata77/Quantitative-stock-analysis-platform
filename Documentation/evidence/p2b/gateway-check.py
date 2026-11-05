"""Criterion check through Gateway: rankings row -> financials of the same publication."""
import json, secrets, time, urllib.request, urllib.error
from uuid import uuid4
from pathlib import Path
G = "http://localhost:18080"
def http(path, body=None, token=None):
    req = urllib.request.Request(G + path, data=json.dumps(body).encode() if body else None,
        headers={"Content-Type": "application/json", **({"Authorization": "Bearer " + token} if token else {})})
    try:
        with urllib.request.urlopen(req, timeout=60) as r: return r.status, json.loads(r.read() or b"null")
    except urllib.error.HTTPError as e: return e.code, None
cred = {"username": "p2b_" + uuid4().hex[:12], "password": secrets.token_urlsafe(24)}
http("/auth/register", cred); token = http("/auth/login", cred)[1]["accessToken"]
for _ in range(30):
    code, page = http("/screener/rankings?eligibility=ALL&size=200", token=token)
    if code == 200: break
    time.sleep(2)
run = page["run"]["id"]; rows = page["content"]
checks = []
excluded = http(f"/screener/rankings?eligibility=EXCLUDED&runId={run}&size=10", token=token)[1]["content"]
for row in [rows[0], excluded[0]]:
    code, body = http(f"/screener/instruments/{row['instrumentId']}/financials?runId={run}", token=token)
    checks.append({"symbol": row["symbol"], "status": code, "sameRun": body["run"]["id"] == run,
                   "sameRank": body["score"]["rank"] == row["rank"], "sameEligibility": body["score"]["eligible"] == row["eligible"],
                   "rsi": body["technical"]["rsi"]["value"], "rsiReason": body["technical"]["rsi"]["reason"]})
unauth = http(f"/screener/instruments/{rows[0]['instrumentId']}/financials?runId={run}")[0]
result = {"runId": run, "checks": checks, "withoutToken": unauth,
          "passed": all(c["status"] == 200 and c["sameRun"] and c["sameRank"] and c["sameEligibility"] for c in checks) and unauth == 401}
Path("build/p2b-20261102/live/gateway-check.json").write_text(json.dumps(result, indent=2) + "\n"); print(json.dumps(result, indent=1))
