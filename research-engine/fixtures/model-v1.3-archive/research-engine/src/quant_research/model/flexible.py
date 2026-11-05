"""Version 1.3: metric-local missingness and explicit available-component scores."""

import json
import statistics
from pathlib import Path
from uuid import UUID

from .engine import percentile
from .engine import score as legacy_score
from .manifest import checksum

FAMILIES = ("value", "quality", "momentum")
SOFT = {
    "INSUFFICIENT_VALUE_COVERAGE", "INSUFFICIENT_QUALITY_COVERAGE",
    "INSUFFICIENT_NORMALIZED_VALUE", "INSUFFICIENT_NORMALIZED_QUALITY",
    "INSUFFICIENT_NORMALIZED_MOMENTUM", "MISSING_MOMENTUM_BOUNDARY",
    "INVALID_MARKET_CAP", "MISSING_INSTRUMENT_SHARES", "STALE_SHARES", "STALE",
    "INSUFFICIENT_HISTORY", "INSUFFICIENT_PRICE_HISTORY", "MISSING_PROFILE_ANCHOR",
    "NON_POSITIVE_EBITDARE", "MISSING_REQUIRED_METRIC:TOTAL_ASSETS",
}


def manifest():
    directory = Path(__file__).parent / "flexible"
    result = json.loads((directory / "manifest.json").read_text(encoding="utf-8"))
    if checksum(result) != (directory / "manifest.sha256").read_text().strip():
        raise ValueError("Flexible manifest checksum mismatch")
    return result


def soft(reason):
    return reason in SOFT or reason.startswith((
        "MODEL_NOT_SUPPORTED", "MISSING_SESSION:", "MISSING_RISK_INPUT:",
    ))


def score(rows, *, as_of, candidate="primary"):
    contract = manifest()
    if candidate != "primary":
        raise ValueError("Flexible model supports only primary")
    report = legacy_score(rows, as_of=as_of, candidate=candidate)
    inputs = {str(UUID(r["instrument_id"])): r for r in rows}
    for row in report["rows"]:
        source = inputs[row["instrument_id"]]
        original = row["reasons"]
        row["reasons"] = [r for r in original if not soft(r)]
        row["warnings"] = [w for w in row["warnings"] if not w.startswith("PEER_FALLBACK:")]
        row["warnings"] += ["LIMITATION:" + r for r in original if soft(r)]
        row.update(eligible=False, rank=None, percentile=None, composite=None,
                   families=None, contributions=None, score_status="UNAVAILABLE")
        row["effective_weights"] = dict.fromkeys(FAMILIES, 0.0)
        # Unsupported accounting profiles may expose price momentum only. Never
        # apply industrial-company formulas to a bank or infer a solvency anchor.
        financial_supported = source["profile_supported"] and source["profile"] == "GENERAL"
        if not financial_supported:
            row["metrics"] = {"momentum": {
                "family": "momentum", "raw": row["derived"].get("momentum"),
                "directed": row["derived"].get("momentum"),
                "reason": row["input_reasons"].get("momentum"),
            }}
            row["warnings"].append("LIMITATION:PRICE_ONLY_UNSUPPORTED_ACCOUNTING_PROFILE")
        gaps = any(r.startswith("MISSING_SESSION:") for r in original)
        for metric in row["metrics"].values():
            reason = None
            if metric["family"] == "value" and "STALE_SHARES" in original:
                reason = "STALE_SHARES"
            if metric["family"] != "momentum" and "STALE" in original:
                reason = "STALE_FILING"
            if metric["family"] == "momentum" and (
                gaps or source["history_count"] < 252
            ):
                reason = "INSUFFICIENT_HISTORY"
            if reason:
                metric.update(raw=None, directed=None, reason=reason)
            for key in ("winsorized", "cohort", "lower", "upper", "mean", "sigma",
                        "z", "clipped_z"):
                metric[key] = None
            metric.update(cohort_count=0, effective_weight=0.0, contribution=None)
        row["coverage"] = {"normalized_metrics": 0, "expected_metrics": 7,
                           "available_families": 0}
    base = [r for r in report["rows"] if not r["reasons"]]
    for row in base:
        for name, metric in row["metrics"].items():
            if metric["raw"] is None:
                continue
            compatible = [r for r in base if r["profile"] == row["profile"]
                          and r["metrics"].get(name, {}).get("raw") is not None]
            primary = [r for r in compatible if r["peer_group"] == row["peer_group"]]
            if row["peer_group"] and len(primary) >= 10:
                cohort, label = primary, "PRIMARY:" + row["peer_group"]
            elif len(compatible) >= 20:
                cohort, label = compatible, "PROFILE:" + row["profile"]
                row["warnings"].append("PEER_FALLBACK:" + name)
            else:
                metric["reason"] = "NO_COMPATIBLE_COHORT"
                continue
            values = [r["metrics"][name]["raw"] for r in cohort]
            lower, upper = percentile(values, .025), percentile(values, .975)
            wins = [min(upper, max(lower, v)) for v in values]
            mean, sigma = statistics.mean(wins), statistics.pstdev(wins)
            value = min(upper, max(lower, metric["raw"]))
            z = 0.0 if sigma < 1e-12 else (value - mean) / sigma
            metric.update(winsorized=value, cohort=label, cohort_count=len(cohort),
                          lower=lower, upper=upper, mean=mean, sigma=sigma, z=z,
                          clipped_z=min(3, max(-3, z)),
                          reason="NO_DISPERSION" if sigma < 1e-12 else None)
        parts = {f: [m for m in row["metrics"].values()
                     if m["family"] == f and m["clipped_z"] is not None] for f in FAMILIES}
        families = {f: sum(m["clipped_z"] for m in parts[f]) / len(parts[f])
                    if parts[f] else None for f in FAMILIES}
        weights = dict(zip(FAMILIES, contract["weights"]["primary"], strict=True))
        denominator = sum(weights[f] for f in FAMILIES if parts[f])
        row["families"] = families
        row["coverage"] = {"normalized_metrics": sum(map(len, parts.values())),
                           "expected_metrics": 7,
                           "available_families": sum(bool(p) for p in parts.values())}
        if denominator == 0:
            row["reasons"].append("NO_VALID_NORMALIZED_METRICS")
            continue
        row["effective_weights"] = {
            f: weights[f] / denominator if parts[f] else 0.0 for f in FAMILIES
        }
        row["contributions"] = {
            f: families[f] * row["effective_weights"][f] if parts[f] else None for f in FAMILIES
        }
        for f in FAMILIES:
            for metric in parts[f]:
                metric["effective_weight"] = row["effective_weights"][f] / len(parts[f])
                metric["contribution"] = metric["clipped_z"] * metric["effective_weight"]
        row["composite"] = sum(v for v in row["contributions"].values() if v is not None)
        row["eligible"] = True
        row["score_status"] = (
            "COMPLETE" if row["coverage"]["normalized_metrics"] == 7 else "PARTIAL"
        )
        if row["score_status"] == "PARTIAL":
            row["warnings"].append("PARTIAL_SCORE:AVAILABLE_COMPONENTS_ONLY")
    eligible = sorted((r for r in report["rows"] if r["eligible"]),
                      key=lambda r: (-r["composite"], UUID(r["instrument_id"])))
    if len(eligible) >= 2:
        for rank, row in enumerate(eligible, 1):
            ties = [i for i, r in enumerate(eligible, 1) if r["composite"] == row["composite"]]
            row["rank"] = rank
            row["percentile"] = 100 * (len(eligible) - statistics.mean(ties)) / (len(eligible) - 1)
    for row in report["rows"]:
        row["reasons"] = sorted(set(row["reasons"]))
        row["warnings"] = sorted(set(row["warnings"]))
    report.update(model_version=contract["version"], manifest_sha256=checksum(contract),
                  eligible_count=len(eligible), excluded_count=len(rows) - len(eligible),
                  status="COMPLETE" if len(eligible) >= 2 else "FAILED",
                  reasons=[] if len(eligible) >= 2 else ["INSUFFICIENT_UNIVERSE"])
    return report
