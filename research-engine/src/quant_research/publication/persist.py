"""Result rows of a publication, as scoring-service ScoringRunService.persist wrote them.

Pure functions: no database access. Each table gets tuples in its column order; numeric columns
use Java's ``BigDecimal(x.toString()).setScale(6, HALF_EVEN)`` and JSON columns the canonical
writer. ``serialize`` is the model's wire serialization (6-decimal rounding of floats).
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime
from decimal import ROUND_HALF_EVEN, Decimal
from uuid import UUID

from . import canonical_json
from .inputs import parse_instant

SCALE = Decimal("0.000001")
DEFAULT_WEIGHTS = {"value": 0.5 / 3, "quality": 0.3 / 3, "momentum": 0.2}


def decimal(value) -> Decimal | None:
    """BigDecimal(x.toString()).setScale(6, HALF_EVEN) for Double, BigDecimal and integers."""
    if value is None:
        return None
    if isinstance(value, bool):
        raise TypeError("Boolean where a number is expected")
    text = canonical_json.java_double(value) if isinstance(value, float) else str(value)
    return Decimal(text).quantize(SCALE, rounding=ROUND_HALF_EVEN)


@dataclass
class ResultRows:
    lineage: list[tuple] = field(default_factory=list)
    artifacts: list[tuple] = field(default_factory=list)
    scores: list[tuple] = field(default_factory=list)
    factors: list[tuple] = field(default_factory=list)
    exclusions: list[tuple] = field(default_factory=list)


def _latest_evidence(prepared: dict) -> datetime | None:
    latest = None
    for evidence in prepared.get("provenance", {}).values():
        for key in ("available_at", "observed_at"):
            if evidence.get(key) is not None:
                moment = parse_instant(str(evidence[key]))
                latest = moment if latest is None or moment > latest else latest
    return latest


def result_rows(run_id: UUID, document: dict, report: dict, serialize) -> ResultRows:
    canonical = {r["instrumentId"]: r for r in document["canonical"]["inputs"]}
    prepared = {r["instrument_id"]: r for r in document["prepared"]["rows"]}
    rows = ResultRows()
    for row in report["rows"]:
        instrument = row["instrument_id"]
        instrument_id = UUID(instrument)
        source, prep = canonical[instrument], prepared[instrument]
        rows.lineage.append((run_id, instrument_id, UUID(source["issuerId"]), source["symbol"],
                             canonical_json.write(source), canonical_json.write(prep),
                             _latest_evidence(prep)))
        artifacts = sorted({str(v["sourceArtifactId"]) for name in ("facts", "priceLineage")
                            for v in source.get(name) or []
                            if v.get("sourceArtifactId") is not None})
        rows.artifacts.extend((run_id, instrument_id, UUID(a)) for a in artifacts)
        families = row.get("families") or {}
        contributions = row.get("contributions") or {}
        metrics = row.get("metrics") or {}
        available = sum(1 for m in metrics.values() if m.get("raw") is not None)
        expected = 7 if "score_status" in row else len(metrics)
        rows.scores.append((
            run_id, instrument_id, row.get("profile"), row.get("peer_group"), row.get("eligible"),
            decimal(families.get("value")), decimal(families.get("quality")),
            decimal(families.get("momentum")), decimal(contributions.get("value")),
            decimal(contributions.get("quality")), decimal(contributions.get("momentum")),
            decimal(row.get("composite")), decimal(row.get("percentile")), row.get("rank"),
            available, expected - available, canonical_json.write(row.get("warnings")),
            canonical_json.write(serialize(row))))
        for name, metric in metrics.items():
            family = metric.get("family")
            flexible = "effective_weight" in metric
            weight = metric["effective_weight"] if flexible else DEFAULT_WEIGHTS[family]
            if flexible:
                contribution = decimal(metric.get("contribution"))
            elif metric.get("clipped_z") is None:
                contribution = Decimal(0) if row.get("eligible") is True else None
            else:
                contribution = decimal(float(metric["clipped_z"]) * weight)
            rows.factors.append((
                run_id, instrument_id, name, family, decimal(metric.get("raw")),
                decimal(metric.get("directed")), decimal(metric.get("winsorized")),
                metric.get("cohort"), metric.get("cohort_count"), decimal(metric.get("lower")),
                decimal(metric.get("upper")), decimal(metric.get("mean")),
                decimal(metric.get("sigma")), decimal(metric.get("z")),
                decimal(metric.get("clipped_z")), decimal(weight), contribution,
                metric.get("reason")))
            if metric.get("raw") is None:
                rows.exclusions.append((run_id, instrument_id, "METRIC",
                                        metric.get("reason", "NOT_REPORTED"), name, None))
        for reason in row.get("reasons") or []:
            rows.exclusions.append((run_id, instrument_id, "ELIGIBILITY", reason, "", reason))
    return rows
