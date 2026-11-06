from quant_research.sector_audit import audit, expected_cohort, markdown, representatives

RUN = {"id": "run", "model_version": "1.5.0", "as_of_date": "2026-10-30",
       "knowledge_cutoff": "2026-11-01T20:00:00+00:00"}


def row(i, sector, profile="GENERAL", *, issuer=None, warnings=(), reasons=(), sic="3571"):
    return {"instrument_id": f"i{i}", "issuer_id": issuer or f"e{i}", "symbol": f"S{i:03}",
            "profile": profile, "peer_group": sector if profile == "GENERAL" else None,
            "eligible": not reasons, "reasons": list(reasons), "warnings": list(warnings),
            "sector": sector, "source_classification": sector, "sic": sic}


def factor(instrument, metric, raw, cohort=None, count=0, stats=(None, None, None, None)):
    mean, deviation, lower, upper = stats
    return {"instrument_id": instrument, "metric": metric, "raw": raw, "cohort": cohort,
            "cohort_count": count, "cohort_mean": mean, "cohort_deviation": deviation,
            "lower_bound": lower, "upper_bound": upper,
            "reason": None if cohort else "NOT_REPORTED"}


def universe():
    """12 Technology rows (primary cohort) and 9 Energy rows (fallback to the profile, 21)."""
    rows = [row(i, "Technology") for i in range(12)] + [row(i, "Energy") for i in range(12, 21)]
    factors = []
    for r in rows:
        primary = r["sector"] == "Technology"
        warnings = [] if primary else ["PEER_FALLBACK:book_market"]
        r["warnings"] += warnings
        cohort, count = ("PRIMARY:Technology", 12) if primary else ("PROFILE:GENERAL", 21)
        factors.append(factor(r["instrument_id"], "book_market", 1.0, cohort, count, (1, 2, 0, 3)))
        factors.append(factor(r["instrument_id"], "roic", None))
    return rows, factors


def test_published_cohorts_reconcile_with_the_rule():
    rows, factors = universe()
    report = audit(RUN, rows, factors)
    assert report["reconciliation"] == {"checked_rows": 21, "issues": [], "status": "RECONCILED"}
    tech, energy = report["sectors"]["Technology"], report["sectors"]["Energy"]
    assert tech["metrics"]["book_market"]["normalized_primary"] == 12
    assert tech["metrics"]["book_market"]["primary_cohort_size"] == 12
    assert energy["metrics"]["book_market"]["normalized_fallback"] == 9
    assert energy["metrics"]["roic"]["missing_reasons"] == {"NOT_REPORTED": 9}
    assert report["totals"]["fallback_warnings"] == {"book_market": 9}
    assert "| Energy | 9 | 9 |" in markdown(report)


def test_wrong_size_label_statistics_and_warning_are_reported():
    rows, factors = universe()
    factors[0]["cohort_count"] = 13
    factors[2]["cohort_mean"] = 5
    rows[13]["warnings"] = []
    rows[1]["peer_group"] = "Energy"
    checks = {i["check"] for i in audit(RUN, rows, factors)["reconciliation"]["issues"]}
    assert {"COHORT_MISMATCH", "INCONSISTENT_COHORT_STATISTICS", "FALLBACK_WARNING_MISMATCH",
            "PEER_GROUP_IS_NOT_SECTOR"} <= checks


def test_one_observation_less_moves_the_whole_sector_to_the_fallback():
    rows = [row(i, "Technology") for i in range(10)] + [row(i, "Energy") for i in range(10, 21)]
    raw = {(r["instrument_id"], "book_market"): 1.0 for r in rows}
    assert expected_cohort(rows[1], "book_market", rows, raw) == ("PRIMARY:Technology", 10)
    raw[("i0", "book_market")] = None
    assert expected_cohort(rows[1], "book_market", rows, raw) == ("PROFILE:GENERAL", 20)
    raw[("i20", "book_market")] = None
    assert expected_cohort(rows[1], "book_market", rows, raw) == (None, 0)


def test_non_general_profiles_have_no_sector_peer_group_and_momentum_uses_the_price_universe():
    rows = [row(i, "Financials") for i in range(10)]
    rows += [row(i, "Financials", "FINANCIAL") for i in range(10, 25)]
    raw = {(r["instrument_id"], "momentum"): 0.1 for r in rows}
    assert expected_cohort(rows[0], "momentum", rows, raw) == ("PRIMARY:Financials", 10)
    assert expected_cohort(rows[12], "momentum", rows, raw) == ("PRICE_UNIVERSE", 25)
    assert expected_cohort(rows[12], "book_market", rows, raw) == (None, 0)


def test_one_representative_per_issuer_and_blocked_rows_stay_outside_the_cohort():
    rows = [row(1, "Media", issuer="x"), row(2, "Media", issuer="x",
            warnings=["LIMITATION:NON_PRIMARY_SHARE_CLASS"]),
            row(3, "Media", issuer="y", warnings=["LIMITATION:NON_PRIMARY_SHARE_CLASS"])]
    assert representatives(rows) == {"i1", "i3"}
    blocked = row(4, "Media", reasons=["MISSING_SCORE_DATE_PRICE"])
    report = audit(RUN, [*rows, blocked], [])
    assert report["totals"]["normalization_base"] == 3
    assert report["totals"]["representative_base"] == 2


def test_missing_sector_needs_an_explicit_reason():
    unclassified = row(1, None)
    report = audit(RUN, [unclassified], [])
    checks = [i["check"] for i in report["reconciliation"]["issues"]]
    assert checks == ["UNCLASSIFIED_WITHOUT_REASON"]
    assert "UNCLASSIFIED" in report["sectors"]
