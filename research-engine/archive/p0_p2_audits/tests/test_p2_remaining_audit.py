import pytest
from quant_research_audits.p2_remaining_audit import classify, next_actions, reconcile


def test_dividends_require_identity_review_not_automatic_clearance():
    assert classify([{"action_type": "cash_dividends", "provider_content": {"symbol": "T"}}]) == (
        "VERIFY_CASH_DIVIDEND_IDENTITY_AND_TERMS"
    )


def test_mixed_actions_and_missing_sources_do_not_become_dividend_only_cases():
    actions = [{"action_type": kind, "provider_content": {"symbol": "BNY"}}
               for kind in ("cash_dividends", "stock_mergers")]
    assert classify(actions) == "REVIEW_IDENTITY_CONTINUITY_AND_CORPORATE_ACTION_TERMS"
    assert classify([{"provider_content": None}]) == "MISSING_RETAINED_ACTION"
    assert classify([]) == "NO_CORPORATE_ACTION_ISSUES"


def test_current_reviews_need_republication_and_exact_evidence():
    issue = {"provider_content": {"symbol": "ECHO"}, "action_type": "name_changes",
             "latest_review": {"decision": "ACCEPT", "matches_issue_evidence": True}}
    assert classify([issue]) == "RECOMPUTE_WITH_NEW_CUTOFF_AND_VERIFY_RECOVERY"
    for review in ({"decision": "REVOKE", "matches_issue_evidence": True},
                   {"decision": "ACCEPT", "matches_issue_evidence": False}, {}):
        other = dict(issue, latest_review=review)
        assert classify([issue, other]) == "REVIEW_IDENTITY_CONTINUITY_AND_CORPORATE_ACTION_TERMS"


@pytest.mark.parametrize(("reason", "action"), [
    ("SECURITY_IDENTITY_REVIEW:SPCX", "VERIFY_SECURITY_IDENTITY"),
    ("NON_PRIMARY_SHARE_CLASS", "VERSION_INSTRUMENT_SHARE_CLASS_POLICY"),
    ("INACTIVE_OR_UNSUPPORTED_SECURITY", "VERIFY_ACTIVE_STATUS_AND_VERSION_SECURITY_POLICY"),
    ("MISSING_SCORE_DATE_PRICE", "RECOVER_EXACT_INSTRUMENT_PRICES_OR_RETAIN_EXCLUSION"),
    ("NO_VALID_NORMALIZED_METRICS", "TRACE_METRIC_INPUTS_HISTORY_AND_NORMALIZATION"),
])
def test_non_corporate_blockers_have_specific_next_steps(reason, action):
    assert classify([], [reason]) == action


def test_zero_exclusions_requires_a_complete_published_universe():
    publication = dict(state="PUBLISHED", expected_count=3, scored_count=3,
                       excluded_count=0, actual_count=3, actual_scored=3)
    reconcile(publication, [])
    for invalid in (None, dict(publication, state="FAILED"),
                    dict(publication, actual_count=2), dict(publication, actual_scored=2),
                    dict(publication, expected_count=0), dict(publication, excluded_count=1)):
        with pytest.raises(ValueError):
            reconcile(invalid, [])


def test_duplicate_or_missing_exclusions_cannot_reconcile():
    publication = dict(state="PUBLISHED", expected_count=4, scored_count=2,
                       excluded_count=2, actual_count=4, actual_scored=2)
    reconcile(publication, [{"instrument_id": "one"}, {"instrument_id": "two"}])
    for rows in ([], [{"instrument_id": "one"}] * 2):
        with pytest.raises(ValueError):
            reconcile(publication, rows)


def test_reviewed_action_does_not_hide_price_or_identity_blockers():
    issue = {"affected_key": "event", "provider_content": {"symbol": "X"},
             "action_type": "name_changes",
             "latest_review": {"decision": "ACCEPT", "matches_issue_evidence": True}}
    assert next_actions([issue], ["CORPORATE_ACTION_REVIEW:event"]) == [
        "RECOMPUTE_WITH_NEW_CUTOFF_AND_VERIFY_RECOVERY"]
    assert next_actions([issue], ["CORPORATE_ACTION_REVIEW:event",
                                 "MISSING_SCORE_DATE_PRICE", "SECURITY_IDENTITY_REVIEW:X"]) == [
        "RECOVER_EXACT_INSTRUMENT_PRICES_OR_RETAIN_EXCLUSION", "VERIFY_SECURITY_IDENTITY"]
    assert next_actions([issue], ["CORPORATE_ACTION_REVIEW:event",
                                 "CORPORATE_ACTION_REVIEW:missing"]) == ["MISSING_RETAINED_ACTION"]


def test_all_blocker_groups_are_kept_and_unknown_reasons_require_investigation():
    assert next_actions([], ["NON_PRIMARY_SHARE_CLASS", "NO_VALID_NORMALIZED_METRICS"]) == [
        "TRACE_METRIC_INPUTS_HISTORY_AND_NORMALIZATION", "VERSION_INSTRUMENT_SHARE_CLASS_POLICY"]
    assert next_actions([], ["MISSING_SCORE_DATE_PRICE", "INCOMPLETE_LIQUIDITY_WINDOW"]) == [
        "RECOVER_EXACT_INSTRUMENT_PRICES_OR_RETAIN_EXCLUSION"]
    assert next_actions([], ["NEW_UNRECOGNIZED_FAILURE"]) == ["INVESTIGATE_PUBLISHED_REASON"]
    assert next_actions([], ["CORPORATE_ACTION_REVIEW:missing"]) == ["MISSING_RETAINED_ACTION"]
