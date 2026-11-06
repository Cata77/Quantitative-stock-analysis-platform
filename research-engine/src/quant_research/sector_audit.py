"""Read-only audit of the sector peer groups of a published run (P2b.1).

Separates four concepts that the model keeps apart:

* ``sector``: ``reference.issuer_classifications.mapped_sector`` of the run's classification
  version, as known at the run's score date and knowledge cutoff;
* ``profile``: the accounting profile used by the model (``GENERAL``, ``FINANCIAL`` ...);
* ``peer_group``: the primary normalization group, the sector for ``GENERAL`` rows only;
* ``cohort``: the group a metric was actually normalized in (``PRIMARY:<sector>``,
  ``PROFILE:<profile>`` or ``PRICE_UNIVERSE``), stored per metric in
  ``research.factor_observations``.

The audit recomputes, from the published rows alone, the cohort the model 1.4/1.5 rule must
have chosen (at least 10 compatible observations in the peer group, otherwise at least 20 in
the profile or, for momentum, the price universe, with ``PEER_FALLBACK``) and reconciles it with
the stored labels, counts and statistics. SIC codes of SEC submissions are reported as an
industry-level candidate only; they are not a licensed classification and are not used by the
model. Connection: ``QUANT_DB_DSN``. Nothing is written to the database.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from collections import Counter, defaultdict
from pathlib import Path
from uuid import UUID

PRIMARY_MINIMUM = 10
FALLBACK_MINIMUM = 20
SMALL_SAMPLE = 20
METRICS = ("book_market", "cash_earnings", "ebit_ev", "fcf_yield", "gross_profitability",
           "momentum", "roic")
STATISTICS = ("cohort_count", "cohort_mean", "cohort_deviation", "lower_bound", "upper_bound")
NON_PRIMARY = "LIMITATION:NON_PRIMARY_SHARE_CLASS"

RUN_SQL = """
SELECT score_run_id::text, as_of_date, knowledge_cutoff, state,
       request->'inputs'->>'classificationVersion',
       (SELECT semantic_version FROM research.model_versions v
        WHERE v.model_version_id = r.model_version_id)
FROM research.scoring_runs r WHERE score_run_id=%s
"""
ROWS_SQL = """
WITH run AS (SELECT * FROM research.scoring_runs WHERE score_run_id=%(run)s),
classified AS (
    SELECT DISTINCT ON (c.issuer_id) c.issuer_id, c.mapped_sector, c.source_classification
    FROM reference.issuer_classifications c CROSS JOIN run
    WHERE c.classification_version_id
          = CAST(run.request->'inputs'->>'classificationVersion' AS uuid)
      AND c.effective_from <= run.as_of_date
      AND (c.effective_to IS NULL OR c.effective_to > run.as_of_date)
      AND c.available_at <= run.knowledge_cutoff AND c.observed_at <= run.knowledge_cutoff
    ORDER BY c.issuer_id, c.effective_from DESC, c.available_at DESC, c.issuer_classification_id
),
sic AS (
    SELECT DISTINCT ON (i.issuer_id) i.issuer_id, NULLIF(o.payload->>'sic', '') sic
    FROM market_data.observations o JOIN reference.instruments i USING (instrument_id)
    CROSS JOIN run
    WHERE o.event_type = 'FILING_FACTS' AND o.observed_at <= run.knowledge_cutoff
    ORDER BY i.issuer_id, o.observed_at DESC, o.observation_key
)
SELECT s.instrument_id::text, l.issuer_id::text, l.symbol, s.scoring_profile, s.peer_group,
       s.eligible, s.output->'reasons', s.warnings, c.mapped_sector, c.source_classification,
       sic.sic
FROM research.stock_scores s
JOIN research.score_lineage l USING (score_run_id, instrument_id)
LEFT JOIN classified c ON c.issuer_id = l.issuer_id
LEFT JOIN sic ON sic.issuer_id = l.issuer_id
WHERE s.score_run_id = %(run)s ORDER BY l.symbol, s.instrument_id
"""
FACTORS_SQL = """
SELECT instrument_id::text, metric, raw_value, cohort, cohort_count, cohort_mean, cohort_deviation,
       lower_bound, upper_bound, reason
FROM research.factor_observations WHERE score_run_id=%s ORDER BY instrument_id, metric
"""
ROW_KEYS = ("instrument_id", "issuer_id", "symbol", "profile", "peer_group", "eligible",
            "reasons", "warnings", "sector", "source_classification", "sic")
FACTOR_KEYS = ("instrument_id", "metric", "raw", "cohort", *STATISTICS, "reason")


def load(connection, run_id: UUID) -> tuple[dict, list[dict], list[dict]]:
    with connection.cursor() as cursor:
        found = cursor.execute(RUN_SQL, (str(run_id),)).fetchone()
        if found is None:
            raise ValueError("Unknown run")
        run = dict(zip(("id", "as_of_date", "knowledge_cutoff", "state", "classification_version",
                        "model_version"), found, strict=True))
        rows = [dict(zip(ROW_KEYS, r, strict=True))
                for r in cursor.execute(ROWS_SQL, {"run": str(run_id)}).fetchall()]
        factors = [dict(zip(FACTOR_KEYS, r, strict=True))
                   for r in cursor.execute(FACTORS_SQL, (str(run_id),)).fetchall()]
    run["as_of_date"] = str(run["as_of_date"])
    run["knowledge_cutoff"] = run["knowledge_cutoff"].isoformat()
    return run, rows, factors


def representatives(rows: list[dict]) -> set[str]:
    """One row per issuer, as model 1.5 normalizes: primary classes, or an issuer's only class."""
    with_primary = {r["issuer_id"] for r in rows if NON_PRIMARY not in r["warnings"]}
    return {r["instrument_id"] for r in rows
            if NON_PRIMARY not in r["warnings"] or r["issuer_id"] not in with_primary}


def expected_cohort(row: dict, metric: str, base: list[dict], raw: dict) -> tuple[str | None, int]:
    """The 1.4/1.5 rule, recomputed from published rows (``base`` holds representatives only)."""
    price = metric == "momentum"
    compatible = [r for r in base if (price or r["profile"] == row["profile"])
                  and raw.get((r["instrument_id"], metric)) is not None]
    primary = [r for r in compatible if r["peer_group"] == row["peer_group"]]
    if row["peer_group"] and len(primary) >= PRIMARY_MINIMUM:
        return "PRIMARY:" + row["peer_group"], len(primary)
    if len(compatible) >= FALLBACK_MINIMUM:
        return ("PRICE_UNIVERSE" if price else "PROFILE:" + row["profile"]), len(compatible)
    return None, 0


def audit(run: dict, rows: list[dict], factors: list[dict]) -> dict:
    by_id = {r["instrument_id"]: r for r in rows}
    factor = {(f["instrument_id"], f["metric"]): f for f in factors}
    raw = {key: f["raw"] for key, f in factor.items()}
    reps = representatives(rows)
    # Normalization runs over rows without blocking reasons; NO_VALID_NORMALIZED_METRICS is
    # assigned only after normalization, so such rows are still part of the cohorts.
    base_all = [r for r in rows if set(r["reasons"]) <= {"NO_VALID_NORMALIZED_METRICS"}]
    base = [r for r in base_all if r["instrument_id"] in reps]
    issues: list[dict] = []

    def issue(check: str, **detail):
        issues.append({"check": check, **detail})

    # 1. Provenance: the stored peer group is the sector of GENERAL rows, nothing else.
    for r in rows:
        expected = r["sector"] if r["profile"] == "GENERAL" else None
        if r["peer_group"] != expected:
            issue("PEER_GROUP_IS_NOT_SECTOR", symbol=r["symbol"], stored=r["peer_group"],
                  expected=expected)
        if r["sector"] is None and "MISSING_SECTOR" not in r["reasons"]:
            issue("UNCLASSIFIED_WITHOUT_REASON", symbol=r["symbol"])
    # 2. One label means one cohort: identical size and statistics for every member.
    groups = defaultdict(set)
    for f in factors:
        if f["cohort"] is not None:
            groups[(f["metric"], f["cohort"])].add(tuple(f[k] for k in STATISTICS))
    for (metric, label), variants in sorted(groups.items()):
        if len(variants) != 1:
            issue("INCONSISTENT_COHORT_STATISTICS", metric=metric, cohort=label,
                  variants=len(variants))
    # 3. The stored cohort is the one the rule selects, with the same size and fallback warning.
    for r in base_all:
        for metric in METRICS:
            f = factor.get((r["instrument_id"], metric))
            if f is None or f["raw"] is None:
                if f is not None and f["cohort"] is not None:
                    issue("COHORT_WITHOUT_RAW_VALUE", symbol=r["symbol"], metric=metric)
                continue
            label, size = expected_cohort(r, metric, base, raw)
            if (label, size) != (f["cohort"], f["cohort_count"] if label else 0):
                issue("COHORT_MISMATCH", symbol=r["symbol"], metric=metric, stored=f["cohort"],
                      stored_count=f["cohort_count"], expected=label, expected_count=size)
            fallback = "PEER_FALLBACK:" + metric in r["warnings"]
            if label and fallback != (not label.startswith("PRIMARY:")):
                issue("FALLBACK_WARNING_MISMATCH", symbol=r["symbol"], metric=metric)
            if label is None and f["reason"] != "NO_COMPATIBLE_COHORT":
                issue("MISSING_NO_COMPATIBLE_COHORT", symbol=r["symbol"], metric=metric)

    sectors = {}
    for sector in sorted({r["sector"] or "UNCLASSIFIED" for r in rows}):
        members = [r for r in rows if (r["sector"] or "UNCLASSIFIED") == sector]
        general = [r for r in members if r["profile"] == "GENERAL"]
        general_ids = {r["instrument_id"] for r in general}
        metrics = {}
        for metric in METRICS:
            observed = [factor[(i, metric)] for i in general_ids if (i, metric) in factor]
            cohorts = Counter(f["cohort"].split(":")[0] if f["cohort"] else "NONE"
                              for f in observed)
            primary = [f["cohort_count"] for f in observed
                       if (f["cohort"] or "").startswith("PRIMARY:")]
            metrics[metric] = {
                "general_rows": len(general),
                "raw_available": sum(f["raw"] is not None for f in observed),
                "normalized_primary": cohorts.get("PRIMARY", 0),
                "normalized_fallback": cohorts.get("PROFILE", 0) + cohorts.get("PRICE_UNIVERSE", 0),
                "not_normalized": cohorts.get("NONE", 0),
                "primary_cohort_size": max(primary) if primary else None,
                "missing_reasons": dict(sorted(Counter(
                    f["reason"] for f in observed if f["cohort"] is None and f["reason"]).items())),
            }
        sizes = [m["primary_cohort_size"] for m in metrics.values() if m["primary_cohort_size"]]
        sectors[sector] = {
            "members": len(members),
            "eligible": sum(r["eligible"] for r in members),
            "profiles": dict(sorted(Counter(r["profile"] for r in members).items())),
            "local_review_classifications": sum(
                (r["source_classification"] or "").startswith("LOCAL_REVIEW:") for r in members),
            "sic_major_groups": dict(sorted(Counter(
                (r["sic"] or "NONE")[:2] for r in members).items())),
            "smallest_primary_cohort": min(sizes) if sizes else None,
            "small_sample": bool(sizes) and min(sizes) < SMALL_SAMPLE,
            "metrics": metrics,
        }
    fallback = Counter(w.split(":", 1)[1] for r in rows for w in r["warnings"]
                       if w.startswith("PEER_FALLBACK:"))
    return {
        "run": run,
        "concepts": {
            "sector": "reference.issuer_classifications.mapped_sector "
                      "(classification version of the run)",
            "profile": "accounting profile of the model "
                       "(GENERAL, FINANCIAL, INSURER, EQUITY_REIT, UNKNOWN)",
            "peer_group": "sector for GENERAL rows; none for other profiles",
            "cohort": "group each metric was normalized in (factor_observations.cohort)",
            "industry": "not stored; SEC SIC major group shown as a candidate only",
        },
        "rule": {"primary_minimum": PRIMARY_MINIMUM, "fallback_minimum": FALLBACK_MINIMUM,
                 "small_sample_below": SMALL_SAMPLE, "representatives": "one row per issuer"},
        "totals": {
            "rows": len(rows), "eligible": sum(r["eligible"] for r in rows),
            "normalization_base": len(base_all), "representative_base": len(base),
            "profiles": dict(sorted(Counter(r["profile"] for r in rows).items())),
            "classified": sum(r["sector"] is not None for r in rows),
            "local_review_classifications": sum(
                (r["source_classification"] or "").startswith("LOCAL_REVIEW:") for r in rows),
            "with_sic": sum(r["sic"] is not None for r in rows),
            "distinct_sic": len({r["sic"] for r in rows if r["sic"]}),
            "fallback_warnings": dict(sorted(fallback.items())),
            "metric_cohorts": {m: dict(sorted(Counter(
                (factor[(r["instrument_id"], m)]["cohort"] or "NONE").split(":")[0]
                for r in rows if (r["instrument_id"], m) in factor).items())) for m in METRICS},
        },
        "sectors": sectors,
        "reconciliation": {"checked_rows": len(base_all), "issues": issues,
                           "status": "RECONCILED" if not issues else "DIFFERENCES"},
        "rows": [{k: by_id[i][k] for k in ("symbol", "profile", "sector", "peer_group", "eligible")}
                 for i in sorted(by_id, key=lambda i: by_id[i]["symbol"] or "")],
    }


def markdown(report: dict) -> str:
    run, totals = report["run"], report["totals"]
    lines = [f"# Sector peer groups of run `{run['id']}`", "",
             f"Model {run['model_version']}, score date {run['as_of_date']}, knowledge cutoff "
             f"{run['knowledge_cutoff']}. Reconciliation: **{report['reconciliation']['status']}** "
             f"({report['reconciliation']['checked_rows']} rows, "
             f"{len(report['reconciliation']['issues'])} differences).", "",
             "| Sector | Members | Eligible | Profiles | GENERAL | Smallest primary cohort |",
             "|---|---|---|---|---|---|"]
    for sector, s in report["sectors"].items():
        profiles = ", ".join(f"{k} {v}" for k, v in s["profiles"].items())
        lines.append(f"| {sector} | {s['members']} | {s['eligible']} | {profiles} | "
                     f"{s['profiles'].get('GENERAL', 0)} | {s['smallest_primary_cohort'] or '—'} |")
    lines += ["", "Per metric, GENERAL rows: primary / fallback / not normalized "
              "(primary cohort size).", "",
              "| Sector | " + " | ".join(METRICS) + " |", "|---|" + "---|" * len(METRICS)]
    for sector, s in report["sectors"].items():
        cells = []
        for metric in METRICS:
            m = s["metrics"][metric]
            size = f" ({m['primary_cohort_size']})" if m["primary_cohort_size"] else ""
            cells.append(f"{m['normalized_primary']} / {m['normalized_fallback']} / "
                         f"{m['not_normalized']}{size}")
        lines.append(f"| {sector} | " + " | ".join(cells) + " |")
    lines += ["", f"Fallback warnings: {totals['fallback_warnings']}.",
              f"Classified rows: {totals['classified']}/{totals['rows']}, local reviews "
              f"{totals['local_review_classifications']}; "
              f"SIC available for {totals['with_sic']} rows "
              f"({totals['distinct_sic']} codes, candidate industry level only).", ""]
    return "\n".join(lines)


def json_default(value):
    return str(value)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-id", type=UUID, required=True)
    parser.add_argument("--output", type=Path, required=True, help="New output directory")
    args = parser.parse_args()
    dsn = os.environ.get("QUANT_DB_DSN")
    if not dsn:
        sys.exit("Set QUANT_DB_DSN")
    if args.output.exists():
        sys.exit("Output directory already exists; choose a new path")
    import psycopg

    with psycopg.connect(dsn) as connection:
        connection.read_only = True
        with connection.transaction():
            report = audit(*load(connection, args.run_id))
    args.output.mkdir(parents=True)
    (args.output / "sector-audit.json").write_text(
        json.dumps(report, indent=1, default=json_default) + "\n", encoding="utf-8")
    (args.output / "sector-audit.md").write_text(markdown(report), encoding="utf-8")
    print(f"{report['reconciliation']['status']}: {report['reconciliation']['checked_rows']} rows, "
          f"{len(report['reconciliation']['issues'])} differences")


if __name__ == "__main__":
    main()
