import hashlib
from uuid import uuid4

import pytest

from quant_research.input_review import validate


def test_review_binds_retained_evidence_and_rejects_tampering(tmp_path):
    evidence = tmp_path / "filing.html"
    evidence.write_bytes(b"Retained original filing with explicit share class reconciliation")
    manifest = dict(
        reviewId=str(uuid4()),
        kind="COMMON_EQUITY",
        instrumentId=str(uuid4()),
        factId=str(uuid4()),
        issueId=None,
        decision="ACCEPT",
        reviewer="reviewer",
        evidence=dict(
            scope="TOTAL_EQUITY_IS_COMMON_ONLY",
            sourceUri="https://example.org/filing",
            rationale="Common equity reconciliation checked against original balance sheet",
            sourceSha256=hashlib.sha256(evidence.read_bytes()).hexdigest(),
        ),
    )
    assert validate(manifest, evidence) == manifest
    evidence.write_bytes(b"Different filing")
    with pytest.raises(ValueError, match="SHA-256"):
        validate(manifest, evidence)


def test_review_rejects_client_timestamp_before_any_database_write(tmp_path):
    with pytest.raises(ValueError, match="fields"):
        validate({"recordedAt": "2020-01-01"}, tmp_path / "absent")


@pytest.mark.parametrize(
    "patch, message",
    [
        ({"reviewId": None}, "IDs are required"),
        ({"factId": None}, "target"),
        ({"issueId": str(uuid4())}, "target"),
        ({"reviewer": "  "}, "Reviewer"),
        ({"evidence": []}, "object"),
        ({"evidence": {}}, "common-only"),
        ({"kind": "SHARE_CLASS", "evidence": {}}, "instrument class"),
        (
            {"kind": "QUALITY_ISSUE", "factId": None, "issueId": str(uuid4()), "evidence": {}},
            "exact issue evidence",
        ),
    ],
)
def test_review_rejects_invalid_target_and_scope_before_reading_evidence(tmp_path, patch, message):
    manifest = dict(
        reviewId=str(uuid4()),
        kind="COMMON_EQUITY",
        instrumentId=str(uuid4()),
        factId=str(uuid4()),
        issueId=None,
        decision="ACCEPT",
        reviewer="reviewer",
        evidence={"scope": "TOTAL_EQUITY_IS_COMMON_ONLY"},
    )
    manifest.update(patch)
    with pytest.raises(ValueError, match=message):
        validate(manifest, tmp_path / "absent")
