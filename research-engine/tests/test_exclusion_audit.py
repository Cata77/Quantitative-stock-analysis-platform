import csv

import pytest

from quant_research.exclusion_audit import audit_export, index, input_diagnostics, overlap


def test_duplicate_identity_rejected():
    with pytest.raises(ValueError, match="Duplicate"):
        index([{"id": "same"}, {"id": "same"}], "id")


def test_overlap_counts_instruments_not_reason_details_or_metrics():
    report = overlap(
        [
            {"eligible": False, "reasons": ["REVIEW:a", "REVIEW:b", "COVERAGE"]},
            {"eligible": False, "reasons": ["REVIEW:c"]},
            {"eligible": True, "reasons": []},
        ]
    )
    counts = {r["reason"]: r for r in report["reasons"]}
    assert counts["REVIEW"] == {
        "reason": "REVIEW",
        "instruments": 2,
        "sole_recorded_blocker": 1,
    }
    assert report["pairs"] == [{"reasons": ["COVERAGE", "REVIEW"], "instruments": 1}]
    assert report["verified_recovered_instruments"] is None


def test_missing_canonical_fact_is_not_claimed_missing_in_source():
    result = input_diagnostics(
        {"facts": []},
        {"values": {}, "provenance": {}},
        {"derived": {"COMMON_EQUITY": None}, "input_reasons": {"COMMON_EQUITY": "NOT_REPORTED"}},
    )
    assert result["COMMON_EQUITY"]["status"] == "ABSENT_CANONICAL_SOURCE_OR_MAPPING_UNRESOLVED"
    assert result["COMMON_EQUITY"]["value"] is None


def test_candidate_fact_retains_evidence_for_period_review():
    fact = {
        "factId": "f1",
        "metric": "INTEREST_EXPENSE",
        "start": "2020-01-01",
        "end": "2020-03-31",
        "value": 10,
        "sourceArtifactId": "artifact",
    }
    result = input_diagnostics(
        {"facts": [fact]},
        {"values": {}, "provenance": {}},
        {"derived": {"INTEREST_EXPENSE_TTM": None}, "input_reasons": {}},
    )["INTEREST_EXPENSE_TTM"]
    assert result["status"] == "CANONICAL_PRESENT_PERIOD_UNIT_OR_CUTOFF_REVIEW"
    assert result["candidate_facts"] == [fact]


def test_zero_is_present_and_selected_fact_resolves():
    fact = {"factId": "f1", "metric": "PREFERRED_EQUITY", "value": 0}
    result = input_diagnostics(
        {"facts": [fact]},
        {
            "values": {"PREFERRED_EQUITY": 0},
            "provenance": {"PREFERRED_EQUITY": {"source_ids": ["f1"]}},
        },
        {"derived": {}, "input_reasons": {}},
    )["PREFERRED_EQUITY"]
    assert result["status"] == "PREPARED"
    assert result["selected_facts"] == [fact]


def test_export_detects_profile_corruption_and_missing_member(tmp_path):
    path = tmp_path / "rankings.csv"
    with path.open("w", newline="") as stream:
        writer = csv.DictWriter(
            stream,
            fieldnames=[
                "instrument_id",
                "symbol",
                "scoring_profile",
                "ordinal_rank",
            ],
        )
        writer.writeheader()
        writer.writerow(
            {
                "instrument_id": "i1",
                "symbol": "LULU",
                "scoring_profile": "GE/NERAL",
                "ordinal_rank": "1",
            }
        )
    report = audit_export(
        path,
        {"i1": {"symbol": "LULU"}},
        {
            "i1": {"eligible": True, "profile": "GENERAL", "rank": 1},
            "i2": {"eligible": True},
        },
    )
    assert report["missing"] == ["i2"]
    assert report["differences"] == [
        {
            "instrument_id": "i1",
            "field": "scoring_profile",
            "export": "GE/NERAL",
            "publication": "GENERAL",
        }
    ]
