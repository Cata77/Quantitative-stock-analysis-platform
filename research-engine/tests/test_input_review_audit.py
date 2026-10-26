import pytest

from quant_research.input_review_audit import QUERY_PATH, selection_query


def test_audit_reuses_production_selection_and_keeps_validation_errors():
    sql = QUERY_PATH.read_text(encoding="utf-8")
    query = selection_query(sql)
    assert "FROM input_facts" in query
    assert "validation.errors" in query
    assert "WHERE instrument_id=CAST(%(instrument)s AS uuid)" in query
    assert "r.recorded_at<=p.knowledge_cutoff" in query
    assert "greatest(f.available_at,r.recorded_at)" in query
    assert "jsonb_agg(to_jsonb(f)" in query
    assert "fact_bundles AS" not in query


@pytest.mark.parametrize("change", ["issuers AS MATERIALIZED", "fact_bundles AS ("])
def test_audit_fails_closed_if_production_query_boundary_changes(change):
    sql = QUERY_PATH.read_text(encoding="utf-8").replace(change, "changed_boundary")
    with pytest.raises(ValueError, match="structure changed"):
        selection_query(sql)
