"""Verified, publication-pinned Screener export through Gateway (no DB access)."""

from __future__ import annotations

import argparse
import csv
import getpass
import hashlib
import html
import json
import os
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from datetime import UTC, datetime
from decimal import Decimal
from pathlib import Path
from uuid import UUID

RSI_NOTE = (
    "RSI(14) Wilder (RSI_14_WILDER_V1) provine din aceeași publicație, din închiderile "
    "ajustate până la data scorului. Este informativ: nu intră în scor sau în rang."
)
RANKING_NOTE = (
    "Rangul și percentila compară toate instrumentele eligibile din reuniunea indicilor "
    "aceleiași publicații. Filtrarea nu recalculează rangul. Rangul folosește scorul "
    "nerotunjit, cu UUID pentru egalități; percentila folosește rangul mediu al "
    "egalităților: 100 × (N − rang mediu) / (N − 1). Valorile afișate sunt rotunjite."
)
FIELDS = (
    "instrumentId",
    "symbol",
    "profile",
    "sector",
    "peerGroup",
    "eligible",
    "scoreStatus",
    "scoreCoverage",
    "effectiveWeights",
    "rank",
    "percentile",
    "compositeScore",
    "valueScore",
    "qualityScore",
    "momentumScore",
    "valueContribution",
    "qualityContribution",
    "momentumContribution",
    "rsi14",
    "rsi14Reason",
    "availableMetricCount",
    "missingMetricCount",
    "metricCoverage",
    "eligibilityReasons",
    "warnings",
    "exclusions",
    "factors",
    "inSp500",
    "inNasdaq100",
    "runId",
    "asOfDate",
    "publishedAt",
    "modelVersion",
    "modelVersionId",
)


def json_text(value: object) -> str:
    # Decimal strings retain API precision in the evidence JSON and nested CSV cells.
    return json.dumps(value, ensure_ascii=False, indent=2, default=str, allow_nan=False)


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise ValueError("HTTP redirects are not accepted for authenticated requests")


class Gateway:
    def __init__(self, base_url: str, token: str = ""):
        url = urllib.parse.urlsplit(base_url)
        require(url.scheme in ("http", "https") and bool(url.hostname), "Invalid Gateway URL")
        require(
            not url.username and not url.password and not url.query and not url.fragment,
            "Gateway URL must not contain credentials, query or fragment",
        )
        require(
            url.scheme == "https" or url.hostname in ("localhost", "127.0.0.1", "::1"),
            "Use HTTPS except for local development",
        )
        self.base_url = base_url.rstrip("/")
        self.token = token
        self.opener = urllib.request.build_opener(NoRedirect())

    def request(self, path: str, payload: dict | None = None) -> dict:
        headers = {"Accept": "application/json"}
        if self.token:
            headers["Authorization"] = "Bearer " + self.token
        if payload is not None:
            headers["Content-Type"] = "application/json"
        request = urllib.request.Request(
            self.base_url + path,
            headers=headers,
            data=json.dumps(payload).encode() if payload is not None else None,
        )
        try:
            with self.opener.open(request, timeout=60) as response:
                return json.load(response, parse_float=Decimal)
        except urllib.error.HTTPError as error:
            # Never echo response bodies, credentials or request headers.
            raise ValueError(f"Gateway HTTP {error.code}; export not completed") from None
        except urllib.error.URLError:
            raise ValueError("Gateway unavailable; export not completed") from None

    def page(self, params: dict) -> dict:
        return self.request("/screener/rankings?" + urllib.parse.urlencode(params))

    def financials(self, instrument_id: str, run_id: str) -> dict:
        path = f"/screener/instruments/{UUID(instrument_id)}/financials?"
        return self.request(path + urllib.parse.urlencode({"runId": str(UUID(run_id))}))


def collect(
    fetch_page, *, run_id: str | None = None, as_of: str | None = None, size: int = 200
) -> tuple[dict, list[dict], list[dict]]:
    """Always reconcile the whole UNION, even when the requested output is filtered."""
    require(1 <= size <= 200, "Page size must be 1..200")
    params = {
        "eligibility": "ALL",
        "universe": "UNION",
        "sort": "COMPOSITE",
        "direction": "DESC",
        "size": size,
        "page": 0,
    }
    if run_id:
        params["runId"] = str(UUID(run_id))
    if as_of:
        params["asOf"] = as_of
    first = fetch_page(dict(params))
    run = first.get("run")
    require(bool(run) and run.get("complete") is True, "No complete publication available")
    require(not run_id or run["id"] == str(UUID(run_id)), "Requested run changed")
    params["runId"] = str(UUID(run["id"]))
    params["asOf"] = first["requestedAsOf"]
    expected, scored, excluded = (run[k] for k in ("expectedCount", "scoredCount", "excludedCount"))
    require(
        all(type(n) is int and n >= 0 for n in (expected, scored, excluded)),
        "Invalid publication counts",
    )
    require(expected == scored + excluded and scored >= 2, "Publication counts do not reconcile")
    pages = (expected + size - 1) // size
    rows, evidence = [], []
    for page_number in range(pages):
        params["page"] = page_number
        page = first if page_number == 0 else fetch_page(dict(params))
        require(page.get("run") == run, "Publication metadata changed during pagination")
        require(page.get("requestedAsOf") == params["asOf"], "Requested date changed")
        require(
            page.get("page") == page_number and page.get("size") == size,
            "Unexpected page coordinates",
        )
        require(
            page.get("totalElements") == expected and page.get("totalPages") == pages,
            "Page counts differ from publication",
        )
        require(
            len(page["content"]) == min(size, expected - page_number * size),
            "Missing or extra page rows",
        )
        rows.extend(page["content"])
        evidence.append(page)
    validate_rows(run, rows)
    return run, rows, evidence


def validate_rows(run: dict, rows: list[dict]) -> None:
    require(len(rows) == run["expectedCount"], "Wrong universe row count")
    ids = [str(UUID(row["instrumentId"])) for row in rows]
    require(len(set(ids)) == len(ids), "Duplicate instrument across pages")
    ranks = []
    for row in rows:
        require(type(row["eligible"]) is bool, "Invalid eligibility")
        counts = [row[k] for k in ("availableMetricCount", "missingMetricCount")]
        require(all(type(n) is int and n >= 0 for n in counts), "Invalid metric coverage")
        reasons = eligibility_reasons(row)
        if row["eligible"]:
            require(
                type(row["rank"]) is int
                and row["compositeScore"] is not None
                and row["percentile"] is not None,
                "Eligible instrument lacks score or rank",
            )
            require(not reasons, "Eligible instrument has eligibility blockers")
            ranks.append(row["rank"])
        else:
            require(
                all(row[k] is None for k in ("rank", "percentile", "compositeScore")),
                "Excluded instrument has a composite score, rank or percentile",
            )
            require(bool(reasons), "Excluded instrument lacks explicit eligibility reasons")
    require(
        sorted(ranks) == list(range(1, run["scoredCount"] + 1)),
        "Ranks or eligible count differ from publication",
    )
    require(len(rows) - len(ranks) == run["excludedCount"], "Excluded count mismatch")


def collect_rsi(
    fetch_financials,
    run: dict,
    rows: list[dict],
    workers: int = 4,
    max_per_minute: int = 600,
) -> dict[str, dict]:
    """RSI per instrument from the financials summary of the same pinned publication.

    Requests run concurrently so the universe completes well within one 15-minute JWT.
    Starts are paced to bound the load on the database; since P2s the web backend has
    no per-client limit (the former Gateway allowed 100 requests/minute).
    """
    require(1 <= workers <= 16, "RSI workers must be 1..16")
    require(max_per_minute >= 1, "RSI rate must be positive")
    ids = [str(UUID(row["instrumentId"])) for row in rows]
    interval, lock, next_start = 60 / max_per_minute, threading.Lock(), [time.monotonic()]

    def paced(instrument_id: str) -> dict:
        with lock:
            start = next_start[0] = max(next_start[0], time.monotonic())
            next_start[0] += interval
        time.sleep(max(0.0, start - time.monotonic()))
        return fetch_financials(instrument_id, run["id"])

    with ThreadPoolExecutor(max_workers=workers) as pool:
        summaries = list(pool.map(paced, ids))
    result = {}
    for instrument_id, summary in zip(ids, summaries, strict=True):
        company = summary.get("company") or {}
        require(
            company.get("runId") == run["id"] and company.get("instrumentId") == instrument_id,
            "Financials response belongs to another publication or instrument",
        )
        figure = (summary.get("market") or {}).get("rsi14")
        require(isinstance(figure, dict), "Financials response lacks rsi14")
        value, reason = figure.get("value"), figure.get("reason")
        require(
            (value is None) != (reason is None),
            "rsi14 must have exactly one of value or reason",
        )
        if value is not None:
            require(
                isinstance(value, (Decimal, int)) and not isinstance(value, bool)
                and 0 <= value <= 100,
                "Invalid rsi14 value",
            )
        result[instrument_id] = {"value": value, "reason": reason, "period": figure.get("period")}
    return result


def validate_rsi(rows: list[dict], rsi: dict[str, dict]) -> None:
    require(
        set(rsi) == {str(UUID(row["instrumentId"])) for row in rows},
        "RSI does not cover exactly the publication universe",
    )


def eligibility_reasons(row: dict) -> list[str]:
    return sorted(
        {
            e["reason_code"]
            for e in row["exclusions"]
            if e["stage"] == "ELIGIBILITY" and e.get("reason_code")
        }
    )


def export_row(row: dict, run: dict, rsi: dict) -> dict:
    result = {field: row.get(field) for field in FIELDS}
    result["rsi14"] = rsi["value"]
    result["rsi14Reason"] = rsi["reason"]
    available, missing = row["availableMetricCount"], row["missingMetricCount"]
    result["metricCoverage"] = (
        Decimal(available) / Decimal(available + missing) if available + missing else None
    )
    result["eligibilityReasons"] = eligibility_reasons(row)
    result.update(
        {k: run[k] for k in ("asOfDate", "publishedAt", "modelVersion", "modelVersionId")}
    )
    result["runId"] = run["id"]
    return result


def cell(value: object) -> str:
    if value is None:
        return ""
    if isinstance(value, (list, dict)):
        return json.dumps(value, ensure_ascii=False, default=str, allow_nan=False)
    if isinstance(value, bool):
        return str(value).lower()
    return str(value)


def render_html(run: dict, rows: list[dict], rsi: dict[str, dict]) -> str:
    def escaped(value):
        return html.escape(cell(value) if value is not None else "—")

    body = []
    for order, row in enumerate(rows):
        values = [row[k] for k in ("symbol", "profile", "sector")]
        status = {"COMPLETE": "Scor complet", "PARTIAL": "Scor parțial",
                  "UNAVAILABLE": "Exclus"}.get(row.get("scoreStatus"))
        values += [status or ("Eligibil" if row["eligible"] else "Exclus")]
        values += [
            row[k]
            for k in (
                "rank",
                "percentile",
                "compositeScore",
                "valueScore",
                "qualityScore",
                "momentumScore",
            )
        ]
        metric_total = row["availableMetricCount"] + row["missingMetricCount"]
        row_rsi = rsi[str(UUID(row["instrumentId"]))]
        if row_rsi["value"] is not None:
            rsi_cell = html.escape(f"{Decimal(row_rsi['value']):.2f}")
            rsi_attr = html.escape(str(row_rsi["value"]))
        else:
            rsi_cell = f'— <small>{html.escape(row_rsi["reason"])}</small>'
            rsi_attr = ""
        values += [
            f"{row['availableMetricCount']}/{metric_total}",
            "; ".join(eligibility_reasons(row)),
        ]
        detail = html.escape(
            json_text({k: row.get(k) for k in (
                "instrumentId", "factors", "exclusions", "warnings",
                "scoreStatus", "scoreCoverage", "effectiveWeights",
            )})
        )
        body.append(
            f'<tr data-eligible="{str(row["eligible"]).lower()}" data-order="{order}" '
            f'data-rsi="{rsi_attr}">'
            + "".join(f"<td>{escaped(v)}</td>" for v in values[:10])
            + f"<td>{rsi_cell}</td>"
            + "".join(f"<td>{escaped(v)}</td>" for v in values[10:])
            + "<td><details><summary>Date disponibile</summary>"
            + f"<pre>{detail}</pre></details></td></tr>"
        )
    headers = [
        "Simbol",
        "Profil",
        "Sector",
        "Stare",
        "Rang",
        "Percentilă",
        "Compozit",
        "Value",
        "Quality",
        "Momentum",
        "RSI 14",
        "Metrici disponibile",
        "Motive excludere",
        "Detalii",
    ]
    return """<!doctype html><html lang="ro"><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Universul publicației</title><style>
body{font:15px system-ui;margin:24px;color:#17263b;background:#f7f9fc}
table{border-collapse:collapse;background:white;width:100%;font-size:13px}
th,td{padding:9px;border-bottom:1px solid #dce3ed;text-align:left;vertical-align:top}
th{background:#17263b;color:white;position:sticky;top:0}pre{white-space:pre-wrap;max-width:600px}
label{display:inline-block;margin:12px 20px 12px 0}select,input{padding:7px}
.table{overflow:auto}p{max-width:1100px}tr[hidden]{display:none}
th button{font:inherit;color:inherit;background:none;border:0;padding:0;cursor:pointer}
td small{color:#5b6b80}
</style><h1>Universul publicației</h1>""" + (
        f"<p>Univers: <b>{run['expectedCount']}</b> · Eligibile: <b>{run['scoredCount']}</b> "
        f"· Excluse: <b>{run['excludedCount']}</b> · Acoperire: "
        f"<b>{100 * Decimal(run['scoredCount']) / Decimal(run['expectedCount']):.2f}%</b></p>"
        f"<p>Data scorului: {escaped(run['asOfDate'])} · Model: {escaped(run['modelVersion'])} "
        f"· Publicat: {escaped(run['publishedAt'])}<br>Run: {escaped(run['id'])}</p>"
        f"<p>{html.escape(RANKING_NOTE)}</p>"
        f"<p>{html.escape(RSI_NOTE)} Apasă pe antetul RSI pentru sortare descrescătoare, "
        "crescătoare și revenire la ordinea clasamentului; valorile lipsă rămân la final.</p>"
        "<p>— înseamnă valoare neatribuită. Scorurile parțiale și factorii sunt afișați numai "
        "când există în publicație. Acoperirea metricilor nu este încredere predictivă.</p>"
        "<p>În modelul 1.3, scorurile parțiale folosesc ponderi redistribuite; un scor bazat "
        "numai pe Momentum nu evaluează Value sau Quality. Ponderile sunt în detalii.</p>"
        f"<p>Statut model: {escaped(run.get('modelStatus'))} · "
        f"Tip publicație: {escaped(run.get('publicationType'))}</p>"
        '<label>Vizualizare <select id="eligibility"><option value="ALL">Întregul univers</option>'
        '<option value="true">Numai eligibile</option><option value="false">Numai excluse</option>'
        '</select></label><label>Simbol <input id="symbol" type="search"></label>'
        '<p id="visible" aria-live="polite"></p><div class="table"><table><thead><tr>'
        + "".join(
            '<th scope="col" aria-sort="none" id="rsi-header"><button id="rsi-sort" '
            'type="button">RSI 14 <span id="rsi-arrow">↕</span></button></th>'
            if h == "RSI 14"
            else f'<th scope="col">{h}</th>'
            for h in headers
        )
        + "</tr></thead><tbody>"
        + "".join(body)
        + "</tbody></table></div>"
        + """<script>
const eligibility=document.getElementById('eligibility'), symbol=document.getElementById('symbol');
function filter(){let count=0;document.querySelectorAll('tbody tr').forEach(row=>{
row.hidden=!(eligibility.value==='ALL'||row.dataset.eligible===eligibility.value)
||!row.cells[0].textContent.toUpperCase().includes(symbol.value.toUpperCase());
if(!row.hidden) count++;});
document.getElementById('visible').textContent='Rânduri afișate: '+count;}
eligibility.addEventListener('change',filter);symbol.addEventListener('input',filter);filter();
let rsiState=0;const states=[['none','↕'],['descending','↓'],['ascending','↑']];
document.getElementById('rsi-sort').addEventListener('click',()=>{
rsiState=(rsiState+1)%3;const tbody=document.querySelector('tbody');
const rows=[...tbody.rows];rows.sort((a,b)=>{
const order=a.dataset.order-b.dataset.order;if(rsiState===0) return order;
const x=a.dataset.rsi, y=b.dataset.rsi;
if(x===''||y==='') return (x==='')-(y==='')||order;
return (rsiState===1?y-x:x-y)||order;});
rows.forEach(row=>tbody.appendChild(row));
document.getElementById('rsi-header').setAttribute('aria-sort',states[rsiState][0]);
document.getElementById('rsi-arrow').textContent=states[rsiState][1];});
</script></html>"""
    )


def write_export(
    output: Path,
    run: dict,
    rows: list[dict],
    pages: list[dict],
    eligibility: str = "ALL",
    *,
    rsi: dict[str, dict],
) -> dict:
    validate_rows(run, rows)
    validate_rsi(rows, rsi)
    require(eligibility in ("ALL", "ELIGIBLE", "EXCLUDED"), "Invalid output eligibility")
    selected = [
        row
        for row in rows
        if eligibility == "ALL" or row["eligible"] == (eligibility == "ELIGIBLE")
    ]
    # A dedicated new directory prevents a failed run being mistaken for an older export.
    output.mkdir(parents=True, exist_ok=False)
    records = [export_row(row, run, rsi[str(UUID(row["instrumentId"]))]) for row in selected]
    with (output / "universe.csv").open("w", encoding="utf-8-sig", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=FIELDS)
        writer.writeheader()
        writer.writerows({k: cell(v) for k, v in row.items()} for row in records)
    with (output / "universe.csv").open(encoding="utf-8-sig", newline="") as stream:
        reread = list(csv.DictReader(stream))
    require(
        reread == [{k: cell(v) for k, v in row.items()} for row in records],
        "CSV round-trip differs from publication",
    )
    (output / "pages.json").write_text(json_text(pages) + "\n", encoding="utf-8")
    (output / "rsi.json").write_text(json_text(rsi) + "\n", encoding="utf-8")
    # HTML always offers the full universe, independently of the CSV selection.
    (output / "universe.html").write_text(render_html(run, rows, rsi), encoding="utf-8")
    summary = {
        "verifiedAt": datetime.now(UTC).isoformat(),
        "run": run,
        "selection": eligibility,
        "exportedRows": len(records),
        "uniqueInstruments": len(rows),
        "eligible": run["scoredCount"],
        "excluded": run["excludedCount"],
        "coverage": Decimal(run["scoredCount"]) / Decimal(run["expectedCount"]),
        "pagesFetched": len(pages),
        "rankingPopulation": RANKING_NOTE,
        "rsi": RSI_NOTE,
        "rsiAvailable": sum(1 for item in rsi.values() if item["value"] is not None),
        "numericEncoding": "Decimal API values are lossless strings in JSON; CSV nulls are empty",
        "checks": [
            "publication pinned",
            "unique instruments",
            "counts reconciled",
            "complete rank sequence",
            "excluded scores null",
            "explicit exclusion reasons",
            "CSV round-trip matches API",
            "RSI pinned to the same publication for every instrument",
        ],
        "sha256": {
            name: hashlib.sha256((output / name).read_bytes()).hexdigest()
            for name in ("universe.csv", "universe.html", "pages.json", "rsi.json")
        },
    }
    # Written last: its presence signals a completed export.
    (output / "verification.json").write_text(json_text(summary) + "\n", encoding="utf-8")
    return summary


def main() -> None:
    # Windows redirected consoles may default to a code page without Romanian letters.
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__)
    # 127.0.0.1, not localhost: on Windows the client first tries ::1 and waits ~2 s per request.
    parser.add_argument("--gateway", default="http://127.0.0.1:18080")
    parser.add_argument("--username", help="Login with a password prompt (or QUANT_PASSWORD)")
    parser.add_argument("--run-id")
    parser.add_argument("--as-of")
    parser.add_argument("--page-size", type=int, default=200)
    parser.add_argument("--rsi-workers", type=int, default=4, help="Parallel RSI calls, 1..16")
    parser.add_argument(
        "--rsi-per-minute", type=int, default=600, help="Pacing of financials requests"
    )
    parser.add_argument("--eligibility", choices=("ALL", "ELIGIBLE", "EXCLUDED"), default="ALL")
    parser.add_argument("--output", type=Path, required=True, help="New output directory")
    args = parser.parse_args()
    try:
        require(not args.output.exists(), "Output directory already exists; choose a new path")
        gateway = Gateway(args.gateway, os.environ.get("QUANT_ACCESS_TOKEN", ""))
        if args.username:
            password = os.environ.get("QUANT_PASSWORD") or getpass.getpass("Parolă: ")
            login = gateway.request(
                "/auth/login", {"username": args.username, "password": password}
            )
            gateway.token = login["accessToken"]
        require(bool(gateway.token), "Use --username or set QUANT_ACCESS_TOKEN")
        run, rows, pages = collect(
            gateway.page, run_id=args.run_id, as_of=args.as_of, size=args.page_size
        )
        rsi = collect_rsi(
            gateway.financials, run, rows, args.rsi_workers, args.rsi_per_minute
        )
        report = write_export(args.output, run, rows, pages, args.eligibility, rsi=rsi)
        print(
            f"Univers: {len(rows)} | Eligibile: {report['eligible']} | "
            f"Excluse: {report['excluded']} | Acoperire: {100 * report['coverage']:.2f}%"
        )
        print(f"Run: {run['id']} | CSV ({args.eligibility}): {report['exportedRows']} rânduri")
        print(RANKING_NOTE)
        print(f"Listă: {args.output / 'universe.html'}")
    except (ValueError, KeyError, TypeError, OSError) as error:
        print(f"Export failed: {error}", file=sys.stderr)
        raise SystemExit(1) from None


if __name__ == "__main__":
    main()
