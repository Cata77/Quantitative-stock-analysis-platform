import hashlib
import json

import pytest
from test_preparation import example

from quant_research import p2_comparison
from quant_research.model.engine import score, serialize
from quant_research.model.periods import from_cross_section


def publication(tmp_path):
    source = tmp_path / "publication"
    source.mkdir()
    section = example()
    section["inputs"][0]["symbol"] = "TEST"
    cutoff, rows = from_cross_section(section)
    document = {"canonical": section, "prepared": {"as_of": cutoff, "rows": rows}}
    data = json.dumps(document).encode()
    (source / "published-input-document.json").write_bytes(data)
    (source / "published-model-evidence.json").write_text(
        json.dumps(serialize(score(rows, as_of=cutoff))), encoding="utf-8"
    )
    (source / "publication-verification.json").write_text(
        json.dumps({"run": {"input_sha256": hashlib.sha256(data).hexdigest()}}), encoding="utf-8"
    )
    return source, section


def test_comparison_detects_removed_values_and_preserves_publication(tmp_path, monkeypatch):
    source, section = publication(tmp_path)
    original = (source / "published-input-document.json").read_bytes()
    # Missing a previously selected source must appear in the before/after report.
    section["inputs"][0]["facts"] = [
        f for f in section["inputs"][0]["facts"] if f["metric"] != "REVENUE"
    ]
    monkeypatch.setattr(p2_comparison, "capture", lambda *args: section)
    report = p2_comparison.compare(source, tmp_path / "result", "unused")
    assert report["legacyReplayExact"] is True
    assert report["legacyDatabaseReplayExact"] is None
    assert report["changedInputs"][0]["values"]["REVENUE_TTM"] == {
        "before": 1000,
        "after": None,
    }
    assert (source / "published-input-document.json").read_bytes() == original


def test_tampered_publication_fails_before_database_access(tmp_path, monkeypatch):
    source, _ = publication(tmp_path)
    with (source / "published-input-document.json").open("ab") as stream:
        stream.write(b" ")
    monkeypatch.setattr(p2_comparison, "capture", lambda *args: pytest.fail("DB accessed"))
    with pytest.raises(ValueError, match="SHA-256"):
        p2_comparison.compare(source, tmp_path / "result", "unused")
