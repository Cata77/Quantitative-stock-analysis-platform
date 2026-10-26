import json

import pytest

from quant_research.source_audit import inspect_source


@pytest.fixture
def bundle():
    company = {
        "cik": 1,
        "facts": {
            "us-gaap": {
                "StockholdersEquity": {
                    "units": {
                        "USD": [
                            {
                                "accn": "filing-1",
                                "end": "2026-06-30",
                                "filed": "2026-07-31",
                                "val": 100,
                            },
                            {
                                "accn": "other-filing",
                                "end": "2026-06-30",
                                "filed": "2026-07-31",
                                "val": 999,
                            },
                        ]
                    }
                },
                "InterestExpense": {
                    "units": {
                        "USD": [
                            {"accn": "old", "end": "2023-12-31", "filed": "2024-02-01", "val": 5},
                        ]
                    }
                },
            }
        },
    }
    return {
        "original_id": "original",
        "original_hash": "recorded-hash",
        "filing_content": {
            "originalArtifactId": "original",
            "originalContentHash": "recorded-hash",
            "filing": {
                "cik": "0000000001",
                "accession": "filing-1",
                "fiscalPeriodEnd": "2026-06-30",
                "filedDate": "2026-07-31",
                "primaryDocument": "report.htm",
                "facts": [],
            },
        },
        "original_content": {
            "retrievedAt": "2026-09-01T00:00:00Z",
            "companyFacts": company,
            "documents": {
                "https://data.sec.gov/api/xbrl/companyfacts/CIK0000000001.json": json.dumps(company)
            },
        },
    }


def test_source_distinguishes_absent_tag_old_period_and_exact_filing(bundle):
    result = inspect_source(bundle, "2026-10-01T00:00:00Z")
    tags = result["tags"]
    assert tags["CommonStockholdersEquity"]["state"] == "ABSENT_FROM_RETAINED_COMPANYFACTS"
    assert tags["InterestExpense"]["state"] == "NO_CURRENT_FILING_FACTS"
    assert tags["InterestExpense"]["latest_end"] == "2023-12-31"
    assert [f["val"] for f in tags["StockholdersEquity"]["current_facts"]] == [100]
    assert result["raw_response_matches_parsed"]


def test_reject_source_observed_after_cutoff(bundle):
    with pytest.raises(ValueError, match="after publication cutoff"):
        inspect_source(bundle, "2026-08-01T00:00:00Z")


def test_reject_altered_raw_response(bundle):
    original = bundle["original_content"]
    original["documents"][next(iter(original["documents"]))] = "{}"
    with pytest.raises(ValueError, match="differs from parsed"):
        inspect_source(bundle, "2026-10-01T00:00:00Z")


@pytest.mark.parametrize(
    "field,value,error",
    [
        ("original_id", "wrong", "identity mismatch"),
        ("original_hash", "wrong", "hash mismatch"),
    ],
)
def test_reject_broken_artifact_chain(bundle, field, value, error):
    bundle[field] = value
    with pytest.raises(ValueError, match=error):
        inspect_source(bundle, "2026-10-01T00:00:00Z")


def test_reject_wrong_issuer(bundle):
    bundle["filing_content"]["filing"]["cik"] = "0000000002"
    with pytest.raises(ValueError, match="CIK mismatch"):
        inspect_source(bundle, "2026-10-01T00:00:00Z")
