#!/usr/bin/env python3
"""Prepare dated S&P 500 and Nasdaq-100 universe snapshots from public sources."""

from __future__ import annotations

import argparse
import csv
import hashlib
import io
import json
import re
import sys
import urllib.request
from urllib.error import HTTPError
from collections import defaultdict
from datetime import date, datetime, timezone
from pathlib import Path
from typing import Any, Iterable


SP500_URL = (
    "https://raw.githubusercontent.com/datasets/"
    "s-and-p-500-companies/main/data/constituents.csv"
)
SP500_WIKITEXT_URL = (
    "https://en.wikipedia.org/w/index.php?"
    "title=List_of_S%26P_500_companies&action=raw"
)
NASDAQ100_URL = "https://api.nasdaq.com/api/quote/list-type/nasdaq100"
SEC_TICKERS_URL = "https://www.sec.gov/files/company_tickers_exchange.json"
SEC_TICKERS_MIRROR_URL = (
    "https://raw.githubusercontent.com/lwowlwowl/company_name_to_ticker/"
    "main/company_tickers_exchange.json"
)

SEC_IDENTITY_OVERRIDES = {
    "HONA": {
        "cik": "0002089271",
        "name": "Honeywell Aerospace Inc.",
        "exchange": "Nasdaq",
        "source_uri": (
            "https://www.sec.gov/Archives/edgar/data/2089271/"
            "000208927126000021/0002089271-26-000021-index.htm"
        ),
    },
    "SPCX": {
        "cik": "0001181412",
        "name": "SPACE EXPLORATION TECHNOLOGIES CORP",
        "exchange": "Nasdaq",
        "source_uri": (
            "https://www.sec.gov/Archives/edgar/data/1181412/"
            "000162828026052535/0001628280-26-052535-index.htm"
        ),
    },
}

OUTPUT_COLUMNS = [
    "symbol",
    "legal_name",
    "cik",
    "figi",
    "exchange_mic",
    "security_type",
    "share_class",
    "currency",
    "domicile",
    "sic",
    "source_classification",
    "mapped_sector",
    "primary_liquid_class",
]

EXCHANGE_MICS = {
    "nasdaq": "XNAS",
    "nasdaq stock market": "XNAS",
    "nyse": "XNYS",
    "new york stock exchange": "XNYS",
    "nyse american": "XASE",
    "nyse arca": "ARCX",
    "cboe": "BATS",
    "cboe bzx": "BATS",
    "cboe bzx exchange": "BATS",
}


def download(url: str) -> bytes:
    request = urllib.request.Request(
        url,
        headers={
            "Accept": "application/json,text/csv,text/plain,*/*",
            "Accept-Language": "en-US,en;q=0.9",
            "User-Agent": (
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                "AppleWebKit/537.36 (KHTML, like Gecko) "
                "Chrome/139.0.0.0 Safari/537.36 QuantPlatform/0.1"
            ),
        },
    )
    with urllib.request.urlopen(request, timeout=60) as response:
        return response.read()


def download_sec_reference() -> tuple[bytes, str]:
    try:
        return download(SEC_TICKERS_URL), SEC_TICKERS_URL
    except HTTPError as exception:
        if exception.code != 403:
            raise
        print(
            "SEC returned HTTP 403; using a public mirror of the SEC ticker/exchange file",
            file=sys.stderr,
        )
        return download(SEC_TICKERS_MIRROR_URL), SEC_TICKERS_MIRROR_URL


def sha256(content: bytes) -> str:
    return hashlib.sha256(content).hexdigest()


def normalize_symbol(symbol: str) -> str:
    return re.sub(r"[^A-Z0-9]", "", symbol.strip().upper())


def normalize_cik(value: Any) -> str:
    text = str(value).strip()
    if not text.isdigit() or len(text) > 10:
        raise ValueError(f"Invalid SEC CIK: {value!r}")
    return text.zfill(10)


def clean_legal_name(value: str) -> str:
    return re.sub(r"\s+", " ", value).strip()


def infer_share_class(symbol: str, *names: str) -> str:
    joined = " ".join(names)
    class_match = re.search(r"\bClass[ (\-]*([A-Z])\b", joined, re.IGNORECASE)
    if class_match:
        return f"CLASS_{class_match.group(1).upper()}"
    series_match = re.search(r"\bSeries[ (\-]*([A-Z])\b", joined, re.IGNORECASE)
    if series_match:
        return f"SERIES_{series_match.group(1).upper()}"
    punctuation_class = re.search(r"[.\-/]([A-Z])$", symbol.upper())
    if punctuation_class:
        return f"CLASS_{punctuation_class.group(1)}"
    return "UNSPECIFIED"


def infer_security_type(*names: str) -> str:
    joined = " ".join(names).lower()
    if "depositary share" in joined or "depositary receipt" in joined:
        return "DEPOSITARY_RECEIPT"
    return "COMMON_STOCK"


def exchange_mic(exchange: str) -> str:
    normalized = re.sub(r"\s+", " ", exchange).strip().lower()
    try:
        return EXCHANGE_MICS[normalized]
    except KeyError as exception:
        raise ValueError(f"Unsupported SEC exchange value: {exchange!r}") from exception


class SecListings:
    def __init__(self, raw: bytes) -> None:
        payload = json.loads(raw)
        fields = payload["fields"]
        entries = [dict(zip(fields, values, strict=True)) for values in payload["data"]]
        self.by_cik: dict[str, list[dict[str, Any]]] = defaultdict(list)
        self.by_symbol: dict[str, list[dict[str, Any]]] = defaultdict(list)
        for entry in entries:
            cik = normalize_cik(entry["cik"])
            entry["normalized_cik"] = cik
            self.by_cik[cik].append(entry)
            self.by_symbol[normalize_symbol(entry["ticker"])].append(entry)

    def resolve(self, symbol: str, cik_hint: str | None = None) -> dict[str, Any]:
        normalized = normalize_symbol(symbol)
        candidates = self.by_symbol.get(normalized, [])
        if cik_hint:
            cik = normalize_cik(cik_hint)
            exact_cik = [entry for entry in candidates if entry["normalized_cik"] == cik]
            if exact_cik:
                candidates = exact_cik
            elif candidates:
                candidates = [{**entry, "normalized_cik": cik} for entry in candidates]
            else:
                candidates = self.by_cik.get(cik, [])
        if not candidates:
            suffix = f" with CIK {cik_hint}" if cik_hint else ""
            raise ValueError(f"No SEC ticker/exchange match for {symbol}{suffix}")
        if len(candidates) > 1:
            active = [entry for entry in candidates if str(entry["exchange"]).strip()]
            if len(active) == 1:
                return active[0]
            signatures = {
                (clean_legal_name(str(entry["name"])), str(entry["exchange"]).strip())
                for entry in active
            }
            if active and len(signatures) == 1:
                return active[0]
            choices = ", ".join(
                f"{entry['ticker']}@{entry['exchange']}:{entry['normalized_cik']}"
                for entry in candidates
            )
            raise ValueError(f"Ambiguous SEC match for {symbol}: {choices}")
        return candidates[0]


def member(
    *,
    symbol: str,
    source_name: str,
    source_classification: str,
    sec_entry: dict[str, Any],
) -> dict[str, str]:
    legal_name = clean_legal_name(str(sec_entry["name"]))
    return {
        "symbol": symbol.strip().upper(),
        "legal_name": legal_name,
        "cik": sec_entry["normalized_cik"],
        "figi": "",
        "exchange_mic": exchange_mic(str(sec_entry["exchange"])),
        "security_type": infer_security_type(source_name, legal_name),
        "share_class": infer_share_class(symbol, source_name, legal_name),
        "currency": "USD",
        "domicile": "",
        "sic": "",
        "source_classification": source_classification.strip(),
        "mapped_sector": "",
        "primary_liquid_class": "true",
    }


def assign_primary_liquid_classes(rows: list[dict[str, str]]) -> None:
    by_issuer: dict[str, list[dict[str, str]]] = defaultdict(list)
    for row in rows:
        by_issuer[row["cik"]].append(row)
    for issuer_rows in by_issuer.values():
        if len(issuer_rows) == 1:
            continue
        preferred = sorted(
            issuer_rows,
            key=lambda row: (
                {"CLASS_A": 0, "UNSPECIFIED": 1, "CLASS_B": 2, "CLASS_C": 3}.get(
                    row["share_class"], 4
                ),
                row["symbol"],
            ),
        )[0]
        for row in issuer_rows:
            row["primary_liquid_class"] = "true" if row is preferred else "false"


def sp500_exchange_map(raw: bytes) -> dict[str, str]:
    text = raw.decode("utf-8-sig")
    listings: dict[str, str] = {}
    for template, exchange in (
        ("NyseSymbol", "NYSE"),
        ("NasdaqSymbol", "Nasdaq"),
        ("BZX link", "CBOE"),
    ):
        for symbol in re.findall(r"\{\{" + re.escape(template) + r"\|([^}|]+)", text):
            listings[symbol.strip().upper()] = exchange
    return listings


def sp500_rows(raw: bytes, wikitext_raw: bytes, sec: SecListings) -> list[dict[str, str]]:
    exchanges = sp500_exchange_map(wikitext_raw)
    source_rows = csv.DictReader(io.StringIO(raw.decode("utf-8-sig")))
    rows = []
    for source_row in source_rows:
        symbol = source_row["Symbol"].strip().upper()
        if symbol not in exchanges:
            raise ValueError(f"No exchange template found in the S&P source for {symbol}")
        try:
            sec_entry = sec.resolve(symbol, source_row["CIK"])
        except ValueError:
            sec_entry = {
                "name": source_row["Security"],
                "normalized_cik": normalize_cik(source_row["CIK"]),
                "exchange": exchanges[symbol],
            }
        else:
            sec_entry = {
                **sec_entry,
                "normalized_cik": normalize_cik(source_row["CIK"]),
                "exchange": exchanges[symbol],
            }
        rows.append(member(
            symbol=source_row["Symbol"],
            source_name=source_row["Security"],
            source_classification=source_row["GICS Sector"],
            sec_entry=sec_entry,
        ))
    assign_primary_liquid_classes(rows)
    return sorted(rows, key=lambda row: row["symbol"])


def nasdaq100_rows(raw: bytes, sec: SecListings) -> tuple[list[dict[str, str]], str]:
    payload = json.loads(raw)
    source_date = payload["data"]["date"]
    source_rows = payload["data"]["data"]["rows"]
    rows = []
    for source_row in source_rows:
        symbol = source_row["symbol"].strip().upper()
        try:
            sec_entry = sec.resolve(symbol)
        except ValueError:
            override = SEC_IDENTITY_OVERRIDES.get(symbol)
            if override is None:
                raise
            sec_entry = {
                "name": override["name"],
                "normalized_cik": override["cik"],
                "exchange": override["exchange"],
            }
        else:
            sec_entry = {**sec_entry, "exchange": "Nasdaq"}
        rows.append(member(
            symbol=source_row["symbol"],
            source_name=source_row["companyName"],
            source_classification=source_row.get("sector") or "",
            sec_entry=sec_entry,
        ))
    assign_primary_liquid_classes(rows)
    return sorted(rows, key=lambda row: row["symbol"]), source_date


def reconcile_overlapping_instruments(
    sp500: list[dict[str, str]], nasdaq100: list[dict[str, str]]
) -> None:
    sp500_by_symbol = {row["symbol"]: row for row in sp500}
    for nasdaq_row in nasdaq100:
        sp500_row = sp500_by_symbol.get(nasdaq_row["symbol"])
        if sp500_row is None:
            continue
        for field in ("cik", "exchange_mic", "security_type"):
            if sp500_row[field] != nasdaq_row[field]:
                raise ValueError(
                    f"Conflicting {field} for overlapping symbol {nasdaq_row['symbol']}: "
                    f"{sp500_row[field]} != {nasdaq_row[field]}"
                )
        classes = {sp500_row["share_class"], nasdaq_row["share_class"]}
        specific_classes = classes - {"UNSPECIFIED"}
        if len(specific_classes) > 1:
            raise ValueError(
                f"Conflicting share classes for overlapping symbol {nasdaq_row['symbol']}: "
                f"{sorted(classes)}"
            )
        if specific_classes:
            canonical_class = specific_classes.pop()
            sp500_row["share_class"] = canonical_class
            nasdaq_row["share_class"] = canonical_class
    assign_primary_liquid_classes(sp500)
    assign_primary_liquid_classes(nasdaq100)


def validate(label: str, rows: list[dict[str, str]], expected_count: int) -> None:
    if len(rows) != expected_count:
        raise ValueError(f"{label} has {len(rows)} rows; expected {expected_count}")
    source_listings: set[tuple[str, str]] = set()
    by_issuer: dict[str, list[dict[str, str]]] = defaultdict(list)
    for line_number, row in enumerate(rows, start=2):
        missing = [column for column in ("symbol", "legal_name", "cik", "exchange_mic") if not row[column]]
        if missing:
            raise ValueError(f"{label} row {line_number} is missing {', '.join(missing)}")
        if len(row["cik"]) != 10 or not row["cik"].isdigit():
            raise ValueError(f"{label} row {line_number} has invalid CIK {row['cik']!r}")
        listing = (row["symbol"], row["exchange_mic"])
        if listing in source_listings:
            raise ValueError(f"{label} contains duplicate listing {listing[0]}@{listing[1]}")
        source_listings.add(listing)
        by_issuer[row["cik"]].append(row)
    invalid_primary = [
        cik
        for cik, issuer_rows in by_issuer.items()
        if sum(row["primary_liquid_class"] == "true" for row in issuer_rows) != 1
    ]
    if invalid_primary:
        raise ValueError(f"{label} has invalid primary liquid classes for CIKs {invalid_primary}")


def csv_bytes(rows: Iterable[dict[str, str]]) -> bytes:
    stream = io.StringIO(newline="")
    writer = csv.DictWriter(stream, fieldnames=OUTPUT_COLUMNS, lineterminator="\n")
    writer.writeheader()
    writer.writerows(rows)
    return stream.getvalue().encode("utf-8")


def write(path: Path, content: bytes) -> dict[str, Any]:
    path.write_bytes(content)
    return {"file": path.name, "sha256": sha256(content)}


def parse_arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--snapshot-date", required=True, type=date.fromisoformat)
    parser.add_argument("--output-dir", type=Path, default=Path(__file__).resolve().parent)
    parser.add_argument("--expected-sp500", type=int, default=503)
    parser.add_argument("--expected-nasdaq100", type=int, default=102)
    return parser.parse_args()


def main() -> int:
    arguments = parse_arguments()
    generated_at = datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")

    sp500_raw = download(SP500_URL)
    sp500_wikitext_raw = download(SP500_WIKITEXT_URL)
    nasdaq100_raw = download(NASDAQ100_URL)
    sec_raw, sec_retrieval_url = download_sec_reference()
    sec = SecListings(sec_raw)

    sp500 = sp500_rows(sp500_raw, sp500_wikitext_raw, sec)
    nasdaq100, nasdaq_source_date = nasdaq100_rows(nasdaq100_raw, sec)
    reconcile_overlapping_instruments(sp500, nasdaq100)
    validate("SP500", sp500, arguments.expected_sp500)
    validate("NASDAQ100", nasdaq100, arguments.expected_nasdaq100)

    arguments.output_dir.mkdir(parents=True, exist_ok=True)
    date_text = arguments.snapshot_date.isoformat()
    sp500_output = write(arguments.output_dir / f"sp500-{date_text}.csv", csv_bytes(sp500))
    sp500_output["members"] = len(sp500)
    nasdaq_output = write(
        arguments.output_dir / f"nasdaq100-{date_text}.csv", csv_bytes(nasdaq100)
    )
    nasdaq_output["members"] = len(nasdaq100)

    sp500_ids = {(row["cik"], row["security_type"], row["share_class"]) for row in sp500}
    nasdaq_ids = {(row["cik"], row["security_type"], row["share_class"]) for row in nasdaq100}
    manifest = {
        "snapshot_date": date_text,
        "generated_at": generated_at,
        "import_mode": "CURRENT_SNAPSHOT_FORWARD",
        "sources": {
            "sp500_membership": {
                "uri": SP500_URL,
                "upstream": "https://en.wikipedia.org/wiki/List_of_S%26P_500_companies",
                "exchange_uri": SP500_WIKITEXT_URL,
                "license": "ODC-PDDL-1.0",
                "retrieved_sha256": sha256(sp500_raw),
                "exchange_source_sha256": sha256(sp500_wikitext_raw),
            },
            "nasdaq100_membership": {
                "uri": NASDAQ100_URL,
                "source_date": nasdaq_source_date,
                "retrieved_sha256": sha256(nasdaq100_raw),
            },
            "identity_enrichment": {
                "official_uri": SEC_TICKERS_URL,
                "retrieval_uri": sec_retrieval_url,
                "retrieved_sha256": sha256(sec_raw),
                "official_filing_overrides": {
                    symbol: {
                        "cik": values["cik"],
                        "source_uri": values["source_uri"],
                    }
                    for symbol, values in SEC_IDENTITY_OVERRIDES.items()
                },
            },
        },
        "outputs": {"sp500": sp500_output, "nasdaq100": nasdaq_output},
        "deduplicated_union_instruments": len(sp500_ids | nasdaq_ids),
        "overlapping_instruments": len(sp500_ids & nasdaq_ids),
    }
    manifest_content = (json.dumps(manifest, indent=2, sort_keys=True) + "\n").encode("utf-8")
    manifest_path = arguments.output_dir / f"snapshot-provenance-{date_text}.json"
    write(manifest_path, manifest_content)

    print(json.dumps(manifest, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exception:
        print(f"snapshot preparation failed: {exception}", file=sys.stderr)
        raise
