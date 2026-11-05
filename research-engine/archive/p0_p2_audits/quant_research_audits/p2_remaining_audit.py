"""Read-only triage of remaining P2 exclusions; no implicit review approvals."""

import argparse
import json
import subprocess
from collections import Counter
from datetime import UTC, datetime
from pathlib import Path
from uuid import UUID


def classify(issues, reasons=()):
    """A missing historical identity is evidence to investigate, never permission to clear it."""
    if not issues:
        if any(r.startswith("SECURITY_IDENTITY_REVIEW:") for r in reasons):
            return "VERIFY_SECURITY_IDENTITY"
        if "INACTIVE_OR_UNSUPPORTED_SECURITY" in reasons:
            return "VERIFY_ACTIVE_STATUS_AND_VERSION_SECURITY_POLICY"
        if "NON_PRIMARY_SHARE_CLASS" in reasons:
            return "VERSION_INSTRUMENT_SHARE_CLASS_POLICY"
        if any(r in {"MISSING_SCORE_DATE_PRICE", "INCOMPLETE_LIQUIDITY_WINDOW",
                     "NOT_REPORTED:ADJUSTED_CLOSE"} for r in reasons):
            return "RECOVER_EXACT_INSTRUMENT_PRICES_OR_RETAIN_EXCLUSION"
        if "NO_VALID_NORMALIZED_METRICS" in reasons:
            return "TRACE_METRIC_INPUTS_HISTORY_AND_NORMALIZATION"
        return "NO_CORPORATE_ACTION_ISSUES"
    if all(i.get("latest_review", {}).get("decision") == "ACCEPT"
           and i["latest_review"].get("matches_issue_evidence") is True for i in issues):
        return "RECOMPUTE_WITH_NEW_CUTOFF_AND_VERIFY_RECOVERY"
    if any(not issue.get("provider_content") for issue in issues):
        return "MISSING_RETAINED_ACTION"
    if all(issue["action_type"] == "cash_dividends" for issue in issues):
        return "VERIFY_CASH_DIVIDEND_IDENTITY_AND_TERMS"
    return "REVIEW_IDENTITY_CONTINUITY_AND_CORPORATE_ACTION_TERMS"


def reconcile(publication, rows):
    """An empty exclusion list is success only for a reconciled published universe."""
    if publication is None or publication["state"] != "PUBLISHED":
        raise ValueError("Expected one immutable published run")
    expected = publication["expected_count"]
    scored = publication["scored_count"]
    excluded = publication["excluded_count"]
    if (expected < 1 or scored + excluded != expected or excluded != len(rows)
            or publication["actual_count"] != expected
            or publication["actual_scored"] != scored
            or len({r["instrument_id"] for r in rows}) != len(rows)):
        raise ValueError("Publication counts or excluded instruments do not reconcile")


def next_actions(issues, reasons):
    """Inventory every blocker; a corporate review cannot clear unrelated failures."""
    corporate = {r.split(":", 1)[1] for r in reasons
                 if r.startswith("CORPORATE_ACTION_REVIEW:")}
    retained = {i.get("affected_key") for i in issues}
    actions = set()
    if corporate - retained:
        actions.add("MISSING_RETAINED_ACTION")
    if issues:
        actions.add(classify(issues))
    for reason in reasons:
        if reason.startswith("CORPORATE_ACTION_REVIEW:"):
            continue
        action = classify([], [reason])
        actions.add("INVESTIGATE_PUBLISHED_REASON" if action == "NO_CORPORATE_ACTION_ISSUES"
                    else action)
    # Recompute alone is appropriate only after every published blocker is addressed.
    if len(actions) > 1:
        actions.discard("RECOMPUTE_WITH_NEW_CUTOFF_AND_VERIFY_RECOVERY")
    return sorted(actions) or ["INVESTIGATE_PUBLISHED_REASON"]


def audit(run_id, container, output):
    run_id = str(UUID(str(run_id)))
    if output.exists():
        raise ValueError("Output already exists")
    sql = f"""
        SELECT s.instrument_id,l.symbol,s.scoring_profile,s.output->'reasons' reasons,
          (SELECT coalesce(jsonb_agg(t),'[]'::jsonb) FROM (
            SELECT q.data_quality_issue_id,q.affected_key,q.evidence,q.detected_at,
              a.action_type,a.provider_content,a.source_artifact_id,
              coalesce((SELECT jsonb_build_object('review_id',v.review_id,
                  'decision',v.decision,'recorded_at',v.recorded_at,
                  'matches_issue_evidence',v.evidence->>'issueEvidence'=q.evidence::text,
                  'after_publication_cutoff',v.recorded_at>r.knowledge_cutoff)
                FROM operations.input_reviews v
                WHERE v.kind='QUALITY_ISSUE' AND v.instrument_id=s.instrument_id
                  AND v.issue_id=q.data_quality_issue_id
                ORDER BY v.recorded_at DESC,v.review_id DESC LIMIT 1),
                '{{}}'::jsonb) latest_review,
              (SELECT min(sym.effective_from) FROM reference.instrument_symbols sym
               WHERE sym.instrument_id=s.instrument_id AND sym.symbol=coalesce(
                 a.provider_content->>'symbol',a.provider_content->>'old_symbol',
                 a.provider_content->>'source_symbol',a.provider_content->>'acquiree_symbol'))
                earliest_matching_symbol_date,
              coalesce(a.effective_date,a.ex_date,a.process_date) action_boundary
            FROM operations.data_quality_issues q
            LEFT JOIN market_data.corporate_actions a ON a.instrument_id=q.instrument_id
              AND a.provider_action_id=q.affected_key
              AND a.revision_hash=(q.evidence->>'revision')
            WHERE q.instrument_id=s.instrument_id AND q.issue_type='CORPORATE_ACTION_REVIEW'
              AND (s.output->'reasons') ? ('CORPORATE_ACTION_REVIEW:'||q.affected_key)
            ORDER BY q.data_quality_issue_id
          ) t) issues
        FROM research.stock_scores s JOIN research.score_lineage l USING(score_run_id,instrument_id)
        JOIN research.scoring_runs r USING(score_run_id)
        WHERE s.score_run_id='{run_id}' AND r.state='PUBLISHED' AND NOT s.eligible
        ORDER BY l.symbol,s.instrument_id
    """
    result = subprocess.run(
        ["docker", "exec", "-i", container, "psql", "-U", "postgres", "-d",
         "quant_platform", "-X", "-qAt", "-v", "ON_ERROR_STOP=1"],
        input="BEGIN READ ONLY; SET LOCAL ROLE quant_scoring; "
              "SET LOCAL statement_timeout=120000; SELECT jsonb_build_object("
              "'publication',(SELECT to_jsonb(p) FROM ("
              "SELECT state,expected_count,scored_count,excluded_count,knowledge_cutoff,"
              "(SELECT count(*) FROM research.stock_scores s WHERE s.score_run_id=r.score_run_id)"
              " actual_count,(SELECT count(*) FROM research.stock_scores s "
              "WHERE s.score_run_id=r.score_run_id AND s.eligible) actual_scored "
              f"FROM research.scoring_runs r WHERE score_run_id='{run_id}') p),"
              f"'rows',(SELECT coalesce(jsonb_agg(t),'[]'::jsonb) FROM ({sql}) t)); COMMIT;",
        capture_output=True, text=True, encoding="utf-8", check=True,
    )
    snapshot = json.loads(result.stdout)
    rows = snapshot["rows"]
    reconcile(snapshot["publication"], rows)
    for row in rows:
        row["next_actions"] = next_actions(row["issues"], row["reasons"])
        primary = classify(row["issues"], row["reasons"])
        row["next_action"] = (primary if primary in row["next_actions"]
                              else row["next_actions"][0])
        for issue in row["issues"]:
            start, boundary = issue["earliest_matching_symbol_date"], issue["action_boundary"]
            issue["symbol_history_starts_after_action"] = (
                None if start is None or boundary is None else start > boundary
            )
    report = {
        "runId": run_id, "excludedInstruments": len(rows),
        "auditedAt": datetime.now(UTC).isoformat(),
        "publication": snapshot["publication"],
        "allInstrumentsScored": not rows,
        "reviewInterpretation": "LIVE_REVIEW_STATUS_DOES_NOT_CHANGE_PUBLISHED_RESULTS",
        "corporateActionIssueCount": sum(len(r["issues"]) for r in rows),
        "nextActions": dict(Counter(r["next_action"] for r in rows)),
        "allNextActions": dict(Counter(a for r in rows for a in r["next_actions"])),
        "remainingReasonsByInstrument": dict(Counter(
            reason for r in rows for reason in {s.split(":")[0] for s in r["reasons"]}
        )),
        "policy": "READ_ONLY_TRIAGE; NO_REVIEWS_APPLIED; NO_HISTORICAL_TIMESTAMPS_CHANGED",
        "rows": rows,
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("x", encoding="utf-8") as stream:
        json.dump(report, stream, indent=2)
        stream.write("\n")
    return {k: v for k, v in report.items() if k != "rows"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-id", type=UUID, required=True)
    parser.add_argument("--container", default="quant-platform-timescaledb-1")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(audit(args.run_id, args.container, args.output), indent=2))


if __name__ == "__main__":
    main()
