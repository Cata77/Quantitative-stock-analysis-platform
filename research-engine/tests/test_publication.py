from datetime import UTC, date, datetime
from decimal import Decimal
from uuid import UUID

import pytest

from quant_research.publication import canonical_json
from quant_research.publication.inputs import (
    CURRENT,
    ScoringRequest,
    instant,
    named_to_psycopg,
    project_input,
)
from quant_research.publication.persist import decimal, result_rows


@pytest.mark.parametrize(("value", "text"), [
    (7164000000.0, "7.164E9"), (0.0, "0.0"), (-0.0, "-0.0"), (3.2e7, "3.2E7"),
    (108.25, "108.25"), (1e-4, "1.0E-4"), (0.001, "0.001"), (9999999.0, "9999999.0"),
    (10000000.0, "1.0E7"), (-2.5, "-2.5"), (1.23456789012e-12, "1.23456789012E-12"),
    (100.0, "100.0"), (0.1 + 0.2, "0.30000000000000004"),
])
def test_doubles_are_written_like_java_double_to_string(value, text):
    assert canonical_json.java_double(value) == text


def test_canonical_writer_sorts_keys_and_writes_plain_decimals():
    value = {"b": [Decimal("1.500"), Decimal("1E+3"), Decimal("0.000")], "a": None, "é": "ș",
             "c": {"y": True, "x": 2}}
    assert canonical_json.write(value) == '{"a":null,"b":[1.5,1000,0],"c":{"x":2,"y":true},"é":"ș"}'
    assert canonical_json.read('{"a":1.50,"b":2}') == {"a": Decimal("1.50"), "b": 2}


def test_hash_fields_length_prefixes_each_field():
    import hashlib
    expected = hashlib.sha256(b"\x00\x00\x00\x01a\x00\x00\x00\x02bc").hexdigest()
    assert canonical_json.hash_fields("a", "bc") == expected


def test_instant_matches_java_iso_instant_groups_of_three():
    moment = datetime(2026, 9, 28, 13, 25, 19, tzinfo=UTC)
    assert instant(moment.replace(microsecond=461886)) == "2026-09-28T13:25:19.461886Z"
    assert instant(moment.replace(microsecond=500000)) == "2026-09-28T13:25:19.500Z"
    assert instant(moment) == "2026-09-28T13:25:19Z"


def test_spring_named_parameters_keep_casts():
    sql = "SELECT CAST(:a AS uuid), x::jsonb, :b_c WHERE y='%'"
    assert named_to_psycopg(sql) == "SELECT CAST(%(a)s AS uuid), x::jsonb, %(b_c)s WHERE y='%%'"


def request(**changes):
    values = dict(
        score_date=date(2026, 10, 30),
        market_cutoff=datetime(2026, 10, 30, 20, tzinfo=UTC),
        knowledge_cutoff=datetime(2026, 11, 1, 20, tzinfo=UTC),
        sp500_snapshot=UUID(int=1), nasdaq100_snapshot=UUID(int=2), raw_dataset=UUID(int=3),
        adjusted_dataset=UUID(int=4), adjustment_basis=date(2026, 11, 1),
        classification_version=UUID(int=5), mapping_version="sec-us-gaap-v2",
        required_metrics=("TOTAL_ASSETS",), timing_policy=CURRENT)
    values.update(changes)
    return ScoringRequest(**values)


def test_request_validates_cutoffs_vintage_and_round_trips():
    original = request()
    assert ScoringRequest.from_json(original.to_json()) == original
    assert original.to_json()["knowledgeCutoff"] == "2026-11-01T20:00:00Z"
    for bad in (dict(adjustment_basis=date(2026, 10, 31)),
                dict(knowledge_cutoff=datetime(2026, 11, 9, 20, tzinfo=UTC)),
                dict(nasdaq100_snapshot=UUID(int=1)), dict(mapping_version="other"),
                dict(required_metrics=("bad",))):
        with pytest.raises(ValueError):
            request(**bad)


def test_input_projection_keeps_record_components_and_java_defaults():
    row = project_input({"instrumentId": str(UUID(int=7)).upper(), "historyCount": None,
                         "rawClose": 12.50, "unknown": 1,
                         "facts": [{"factId": str(UUID(int=8)),
                                    "availableAt": "2026-09-28 13:25:19.4+00"}],
                         "priceLineage": [], "reasons": [{"code": "X"}]})
    assert row["instrumentId"] == str(UUID(int=7)) and "unknown" not in row
    assert row["historyCount"] == 0 and row["inSp500"] is False and row["symbol"] is None
    assert row["rawClose"] == Decimal("12.5")
    assert row["facts"][0]["availableAt"] == "2026-09-28T13:25:19.400Z"
    assert row["facts"][0]["value"] is None and row["reasons"] == [{"code": "X", "detail": None}]


def test_persisted_numbers_use_java_scale_six_half_even():
    assert decimal(0.0000005) == Decimal("0.000000")
    assert decimal(0.0000015) == Decimal("0.000002")
    assert decimal(7.164e9) == Decimal("7164000000.000000")
    assert decimal(Decimal("1.2345675")) == Decimal("1.234568") and decimal(None) is None


def test_result_rows_follow_java_persist_mapping():
    run = UUID(int=99)
    instrument, issuer, artifact = str(UUID(int=1)), str(UUID(int=2)), str(UUID(int=3))
    document = {
        "canonical": {"inputs": [{"instrumentId": instrument, "issuerId": issuer, "symbol": "T",
                                  "facts": [{"sourceArtifactId": artifact}],
                                  "priceLineage": [{"sourceArtifactId": artifact}]}]},
        "prepared": {"rows": [{"instrument_id": instrument, "provenance": {
            "A": {"available_at": "2026-10-01T00:00:00Z",
                  "observed_at": "2026-10-02T00:00:00+00:00"}}}]},
    }
    row = {"instrument_id": instrument, "profile": "GENERAL", "peer_group": "Tech",
           "eligible": False,
           "score_status": "UNAVAILABLE", "families": {"value": None}, "contributions": {},
           "metrics": {"m": {"family": "value", "raw": None, "reason": "NOT_REPORTED",
                             "effective_weight": 0.25, "contribution": None}},
           "reasons": ["NO_VALID_NORMALIZED_METRICS"], "warnings": []}
    rows = result_rows(run, document, {"rows": [row]}, lambda r: r)
    assert rows.lineage[0][6] == datetime(2026, 10, 2, tzinfo=UTC)
    assert rows.artifacts == [(run, UUID(instrument), UUID(artifact))]
    assert rows.scores[0][14:16] == (0, 7)
    assert rows.factors[0][15] == Decimal("0.250000") and rows.factors[0][16] is None
    assert rows.exclusions == [(run, UUID(instrument), "METRIC", "NOT_REPORTED", "m", None),
                               (run, UUID(instrument), "ELIGIBILITY", "NO_VALID_NORMALIZED_METRICS",
                                "", "NO_VALID_NORMALIZED_METRICS")]


def test_request_document_matches_java_identity_fields():
    from quant_research.publication.publish import request_document
    text = request_document(request(), datetime(2026, 11, 2, 14, 30, tzinfo=UTC), {"b": "US"})
    document = canonical_json.read(text)
    assert list(document) == ["candidate", "collectionPolicy", "effective_from", "inputs",
                              "jurisdictions", "preprocessingVersion"]
    assert document["effective_from"] == "2026-11-02T14:30:00Z"
    assert document["inputs"]["requiredMetrics"] == ["TOTAL_ASSETS"]
    assert document["collectionPolicy"] == "CURRENT_COLLECTION_OUTCOMES_V1"


def test_publish_requires_autocommit_and_supported_model():
    from quant_research.publication.publish import publish

    class Connection:
        autocommit = False

    effective = datetime(2026, 11, 2, tzinfo=UTC)
    with pytest.raises(ValueError, match="autocommit"):
        publish(Connection(), request(), effective_from=effective)
    with pytest.raises(ValueError, match="Unsupported"):
        publish(Connection(), request(), effective_from=effective, model_version="1.2.0")


def test_certification_scores_the_fixture_and_is_stable():
    from quant_research.publication.certification import certify
    first, second = certify("1.5.0"), certify("1.5.0")
    assert first == second and first.implementation_version == "python-flexible-1.5.0"
    assert first.manifest_sha256.startswith("58a1c2c2")
    with pytest.raises(ValueError):
        certify("1.2.0")
