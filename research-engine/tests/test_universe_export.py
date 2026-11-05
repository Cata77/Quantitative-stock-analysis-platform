import copy
import csv
from decimal import Decimal

import pytest

from quant_research.universe_export import (
    Gateway,
    collect,
    collect_rsi,
    render_html,
    write_export,
)

RUN = "50000000-0000-0000-0000-000000000001"


def test_flexible_status_and_weights_survive_export(tmp_path):
    run, rows = fixture()
    rows[0].update(scoreStatus="PARTIAL", effectiveWeights={"momentum": 1},
                   scoreCoverage={"normalized_metrics": 1, "expected_metrics": 7})
    write_export(tmp_path / "flexible", run, rows, [], rsi=rsi_for(rows))
    with (tmp_path / "flexible/universe.csv").open(encoding="utf-8-sig", newline="") as stream:
        exported = list(csv.DictReader(stream))
    assert exported[0]["scoreStatus"] == "PARTIAL"
    assert '"momentum"' in exported[0]["effectiveWeights"]
    assert "Scor parțial" in (tmp_path / "flexible/universe.html").read_text(encoding="utf-8")


def fixture():
    run = dict(
        id=RUN,
        complete=True,
        expectedCount=3,
        scoredCount=2,
        excludedCount=1,
        asOfDate="2026-09-30",
        modelVersion="1.2.0",
        modelVersionId=RUN,
        publishedAt="2026-10-01T12:00:00Z",
    )
    rows = []
    for index in range(3):
        rows.append(
            dict(
                instrumentId=f"30000000-0000-0000-0000-{index:012}",
                symbol=f"SYM{index}",
                profile="GENERAL",
                sector="Technology",
                eligible=index < 2,
                rank=index + 1 if index < 2 else None,
                percentile=100 - index * 100 if index < 2 else None,
                compositeScore=Decimal("0.1234567890123456789") if index < 2 else None,
                valueScore=Decimal("0"),
                qualityScore=None,
                momentumScore=None,
                availableMetricCount=2,
                missingMetricCount=5,
                warnings=[],
                factors=[
                    dict(metric="fcf_yield", raw_value=Decimal("12345678901234567890.123456789"))
                ],
                exclusions=[]
                if index < 2
                else [dict(stage="ELIGIBILITY", reason_code="MISSING_DATA")],
            )
        )
    return run, rows


def rsi_for(rows, values=(Decimal("71.2345"), Decimal("28.5"), None)):
    return {
        row["instrumentId"]: {
            "value": value,
            "reason": None if value is not None else "INSUFFICIENT_PRICE_HISTORY",
            "period": "2026-09-30" if value is not None else None,
        }
        for row, value in zip(rows, values, strict=True)
    }


def financials_source(run, rows, mutate=None):
    calls = []

    def fetch(instrument_id, run_id):
        calls.append((instrument_id, run_id))
        index = [r["instrumentId"] for r in rows].index(instrument_id)
        figure = (
            {"unit": "INDEX", "reason": "MISSING_SCORE_DATE_PRICE"}
            if index == 2
            else {"value": Decimal("40") + index, "unit": "INDEX", "period": "2026-09-30"}
        )
        result = {
            "company": {"instrumentId": instrument_id, "runId": run_id},
            "market": {"rsi14": figure},
        }
        if mutate:
            mutate(result)
        return result

    return fetch, calls


def source(run, rows, mutate=None):
    requests = []

    def fetch(params):
        requests.append(params)
        page, size = params["page"], params["size"]
        result = dict(
            run=copy.deepcopy(run),
            requestedAsOf="2026-10-22",
            page=page,
            size=size,
            totalElements=len(rows),
            totalPages=(len(rows) + size - 1) // size,
            content=copy.deepcopy(rows[page * size : (page + 1) * size]),
        )
        if mutate:
            mutate(result)
        return result

    return fetch, requests


def test_pagination_pins_first_run_and_date_and_explicitly_requests_entire_union():
    run, rows = fixture()
    fetch, requests = source(run, rows)
    actual_run, actual_rows, pages = collect(fetch, size=2)
    assert actual_run == run and actual_rows == rows and len(pages) == 2
    assert "runId" not in requests[0]
    assert requests[1]["runId"] == RUN
    assert requests[1]["asOf"] == "2026-10-22"
    assert all(p["eligibility"] == "ALL" and p["universe"] == "UNION" for p in requests)


@pytest.mark.parametrize(
    "failure",
    [
        "duplicate",
        "missing",
        "different_run",
        "metadata",
        "page_total",
        "rank",
        "excluded_score",
        "reason",
    ],
)
def test_rejects_incomplete_or_inconsistent_publication(failure):
    run, rows = fixture()
    if failure == "duplicate":
        rows[2]["instrumentId"] = rows[0]["instrumentId"]
    if failure == "rank":
        rows[1]["rank"] = 1
    if failure == "excluded_score":
        rows[2]["compositeScore"] = 0
    if failure == "reason":
        rows[2]["exclusions"] = [dict(stage="RAW", reason_code="NOT_REPORTED")]

    def mutate(page):
        if page["page"] == 1:
            if failure == "missing":
                page["content"] = []
            if failure == "different_run":
                page["run"]["id"] = "50000000-0000-0000-0000-000000000002"
            if failure == "metadata":
                page["run"]["modelVersion"] = "2.0.0"
            if failure == "page_total":
                page["totalElements"] = 2

    fetch, _ = source(run, rows, mutate)
    with pytest.raises(ValueError):
        collect(fetch, size=2)


def test_unavailable_run_does_not_become_successful_empty_export():
    with pytest.raises(ValueError, match="No complete publication"):
        collect(lambda _: {"run": None})


def test_csv_preserves_nulls_zero_precision_multiline_and_profile(tmp_path):
    run, rows = fixture()
    rows[0].update(symbol="LULU", sector='Text, with "quotes"\nand newline')
    fetch, _ = source(run, rows)
    run, rows, pages = collect(fetch)
    output = tmp_path / "export"
    report = write_export(output, run, rows, pages, rsi=rsi_for(rows))
    with (output / "universe.csv").open(encoding="utf-8-sig", newline="") as stream:
        result = list(csv.DictReader(stream))
    assert len(result) == 3 and report["uniqueInstruments"] == 3
    assert result[0]["profile"] == "GENERAL"
    assert result[0]["sector"] == rows[0]["sector"]
    assert result[0]["compositeScore"] == "0.1234567890123456789"
    assert "12345678901234567890.123456789" in result[0]["factors"]
    assert result[2]["rank"] == result[2]["compositeScore"] == ""
    assert result[2]["valueScore"] == "0"
    assert result[2]["qualityScore"] == ""
    assert '"MISSING_DATA"' in result[2]["eligibilityReasons"]
    assert (output / "verification.json").exists()
    with pytest.raises(FileExistsError):
        write_export(output, run, rows, pages, rsi=rsi_for(rows))


def test_eligible_csv_keeps_complete_evidence_and_html(tmp_path):
    run, rows = fixture()
    fetch, _ = source(run, rows)
    run, rows, pages = collect(fetch)
    report = write_export(tmp_path / "eligible", run, rows, pages, "ELIGIBLE", rsi=rsi_for(rows))
    assert report["exportedRows"] == 2 and report["uniqueInstruments"] == 3
    assert "SYM2" in (tmp_path / "eligible" / "universe.html").read_text(encoding="utf-8")


def test_html_escapes_source_text_including_details():
    run, rows = fixture()
    rows[0]["symbol"] = "<script>alert('bad')</script>"
    rows[0]["warnings"] = ["</pre><img src=x onerror=alert(1)>"]
    rendered = render_html(run, rows, rsi_for(rows))
    assert "<script>alert" not in rendered
    assert "<img src=x" not in rendered
    assert "&lt;script&gt;" in rendered and "&lt;img" in rendered


def test_rsi_is_collected_from_the_same_publication_for_every_instrument():
    run, rows = fixture()
    fetch, calls = financials_source(run, rows)
    rsi = collect_rsi(fetch, run, rows, max_per_minute=60000)
    assert sorted(calls) == sorted((row["instrumentId"], RUN) for row in rows)
    assert rsi[rows[0]["instrumentId"]]["value"] == Decimal("40")
    assert rsi[rows[2]["instrumentId"]] == {
        "value": None, "reason": "MISSING_SCORE_DATE_PRICE", "period": None
    }


@pytest.mark.parametrize("failure", ["other_run", "other_instrument", "missing", "range", "both"])
def test_rsi_rejects_responses_not_pinned_or_inconsistent(failure):
    run, rows = fixture()

    def mutate(result):
        figure = result["market"]["rsi14"]
        if failure == "other_run":
            result["company"]["runId"] = "50000000-0000-0000-0000-000000000002"
        if failure == "other_instrument":
            result["company"]["instrumentId"] = "30000000-0000-0000-0000-999999999999"
        if failure == "missing":
            del result["market"]["rsi14"]
        if failure == "range":
            figure.update(value=Decimal("100.01"), reason=None)
        if failure == "both":
            figure.update(value=Decimal("50"), reason="INVALID_PRICE")

    fetch, _ = financials_source(run, rows, mutate)
    with pytest.raises(ValueError):
        collect_rsi(fetch, run, rows, max_per_minute=60000)


def test_rsi_column_csv_and_sortable_html(tmp_path):
    run, rows = fixture()
    report = write_export(tmp_path / "rsi", run, rows, [], rsi=rsi_for(rows))
    with (tmp_path / "rsi/universe.csv").open(encoding="utf-8-sig", newline="") as stream:
        exported = list(csv.DictReader(stream))
    assert [r["rsi14"] for r in exported] == ["71.2345", "28.5", ""]
    assert exported[2]["rsi14Reason"] == "INSUFFICIENT_PRICE_HISTORY"
    assert report["rsiAvailable"] == 2 and "rsi.json" in report["sha256"]
    rendered = (tmp_path / "rsi/universe.html").read_text(encoding="utf-8")
    assert 'id="rsi-sort"' in rendered and 'aria-sort="none"' in rendered
    assert 'data-rsi="71.2345"' in rendered and "<td>71.23</td>" in rendered
    assert 'data-rsi=""' in rendered and "INSUFFICIENT_PRICE_HISTORY" in rendered


def test_export_rejects_rsi_that_does_not_cover_universe(tmp_path):
    run, rows = fixture()
    rsi = rsi_for(rows)
    rsi.pop(rows[1]["instrumentId"])
    with pytest.raises(ValueError, match="RSI"):
        write_export(tmp_path / "partial", run, rows, [], rsi=rsi)
    assert not (tmp_path / "partial").exists()


@pytest.mark.parametrize(
    "url",
    [
        "http://example.com",
        "https://user:secret@example.com",
        "https://example.com?token=secret",
        "file:///tmp/test",
    ],
)
def test_gateway_rejects_unsafe_credential_transport(url):
    with pytest.raises(ValueError):
        Gateway(url)
