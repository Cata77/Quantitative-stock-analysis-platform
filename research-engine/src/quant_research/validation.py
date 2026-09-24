"""Monthly next-open research diagnostics. Never promotes a model to production."""
from __future__ import annotations

import argparse
import hashlib
import json
import math
from pathlib import Path

import numpy as np
import pandas as pd
import statsmodels.api as sm
from scipy.stats import spearmanr

COSTS = (5, 10, 25, 50)
TIME_COLUMNS = ("signal_at", "knowledge_at", "execution_at", "next_session_open", "exit_at",
                "forward_end")
VALUE_COLUMNS = ("score", "total_return", "forward_6m", "market_cap")


def aware_timestamps(values: pd.Series) -> pd.Series:
    """Reject implicit local/UTC assumptions before normalizing explicit offsets."""
    for value in values:
        stamp = pd.Timestamp(value)
        if pd.isna(stamp) or stamp.tzinfo is None:
            raise ValueError("research timestamps must be explicitly timezone-aware")
    return pd.to_datetime(values, utc=True, errors="raise")


def validate_panel(frame: pd.DataFrame) -> pd.DataFrame:
    required = {*TIME_COLUMNS, *VALUE_COLUMNS, "instrument_id", "sector", "profile",
                "expected_count"}
    if missing := required - set(frame.columns):
        raise ValueError(f"missing panel columns: {sorted(missing)}")
    frame = frame.copy()
    for name in TIME_COLUMNS:
        frame[name] = aware_timestamps(frame[name])
    if frame.empty or frame[list(required)].isna().any().any():
        raise ValueError("missing observations cannot be silently dropped")
    if frame.duplicated(["signal_at", "instrument_id"]).any():
        raise ValueError("duplicate signal/instrument identity")
    if not np.isfinite(frame[list(VALUE_COLUMNS)].to_numpy(dtype=float)).all():
        raise ValueError("non-finite research input")
    if ((frame.knowledge_at > frame.signal_at)
            | (frame.execution_at <= frame.signal_at)
            | (frame.execution_at != frame.next_session_open)
            | (frame.exit_at <= frame.execution_at)
            | (frame.forward_end < frame.exit_at)).any():
        raise ValueError("look-ahead or same-bar execution, or invalid return horizon")
    if ((frame.market_cap <= 0).any() or (frame.total_return < -1).any()
            or (frame.forward_6m < -1).any()):
        raise ValueError("invalid market value or total return")
    for _, group in frame.groupby("signal_at"):
        for column in ("execution_at", "exit_at", "next_session_open", "expected_count"):
            if group[column].nunique() != 1:
                raise ValueError("cross-section dates/counts disagree")
        expected = float(group.expected_count.iloc[0])
        if not math.isfinite(expected) or expected < len(group) or expected != int(expected):
            raise ValueError("expected universe cannot be smaller than the scored cross-section")
    dates = sorted(frame.signal_at.unique())
    months = [pd.Timestamp(date).year * 12 + pd.Timestamp(date).month for date in dates]
    if any(b - a != 1 for a, b in zip(months, months[1:], strict=False)):
        raise ValueError("signals must be one contiguous monthly cross-section")
    groups = [g for _, g in frame.groupby("signal_at", sort=True)]
    for before, after in zip(groups, groups[1:], strict=False):
        if before.exit_at.iloc[0] != after.execution_at.iloc[0]:
            raise ValueError("return intervals must join at the next rebalance open")
    return frame.sort_values(["signal_at", "instrument_id"])


def chronological_partitions(dates: list) -> dict[str, list]:
    dates = sorted(set(dates))
    if len(dates) < 30:
        raise ValueError("at least 30 monthly signals are required for chronological splits")
    first, second = int(len(dates) * .6), int(len(dates) * .8)
    return {"development": dates[:first], "validation": dates[first:second],
            "final": dates[second:]}


def walk_forward_windows(dates: list) -> list[dict]:
    """Annual validation windows; training labels need a six-month embargo."""
    dates = sorted(set(dates))
    result = []
    for year in sorted({pd.Timestamp(d).year for d in dates})[2:]:
        test = [d for d in dates if pd.Timestamp(d).year == year]
        boundary = pd.Timestamp(test[0]) - pd.DateOffset(months=6)
        train = [d for d in dates if pd.Timestamp(d) < boundary]
        if len(train) >= 12:
            result.append({"train_start": str(train[0]), "train_end": str(train[-1]),
                           "test_start": str(test[0]), "test_end": str(test[-1])})
    return result


def hac_mean(values: list[float], lags: int = 6) -> dict:
    values = np.asarray(values, dtype=float)
    if len(values) <= lags + 1 or not np.isfinite(values).all():
        return {"mean": None, "t": None, "observations": len(values)}
    result = sm.OLS(values, np.ones((len(values), 1))).fit(
        cov_type="HAC", cov_kwds={"maxlags": lags})
    t = float(result.tvalues[0])
    return {"mean": float(result.params[0]), "t": t if math.isfinite(t) else None,
            "observations": len(values)}


def targets(group: pd.DataFrame, view: str) -> dict[str, float]:
    ranked = group.sort_values(["score", "instrument_id"], ascending=[False, True])
    if view == "sector_neutral":
        result = {}
        for _, sector in ranked.groupby("sector"):
            selected = sector.head(max(1, math.ceil(len(sector) / 5)))
            result.update(dict.fromkeys(selected.instrument_id, len(sector) / len(group)
                                        / len(selected)))
        return result
    if view == "top20":
        ranked = ranked.head(20)
    elif view.startswith("q"):
        quintile = int(view[1]) - 1
        ranked = ranked.iloc[np.array_split(np.arange(len(ranked)), 5)[quintile]]
    if ranked.empty:
        raise ValueError("each quintile needs at least one instrument")
    weights = ranked.market_cap / ranked.market_cap.sum() if view == "cap_weight" else (
        np.ones(len(ranked)) / len(ranked))
    return dict(zip(ranked.instrument_id, weights, strict=True))


def portfolio_series(frame: pd.DataFrame, view: str, bps: int) -> list[float]:
    previous: dict[str, float] = {}
    series = []
    for _, group in frame.groupby("signal_at", sort=True):
        weights = targets(group, view)
        # Cost on both buys and sells. Solve post-cost invested wealth before weighting.
        fraction = 1.0
        for _ in range(50):
            turnover = sum(abs(fraction * weights.get(k, 0) - previous.get(k, 0))
                           for k in weights.keys() | previous.keys())
            updated = 1 - bps / 10_000 * turnover
            if abs(updated - fraction) < 1e-14:
                break
            fraction = updated
        returns = dict(zip(group.instrument_id, group.total_return, strict=True))
        gross = sum(w * (1 + returns[k]) for k, w in weights.items())
        wealth = fraction * gross
        series.append(wealth - 1)
        if gross <= 0:
            raise ValueError("portfolio lost all capital")
        previous = {k: w * (1 + returns[k]) / gross for k, w in weights.items()}
    return series


def evaluate(frame: pd.DataFrame, evidence: dict, partition: str = "validation",
             open_final: bool = False) -> dict:
    frame = frame.copy()
    frame["signal_at"] = aware_timestamps(frame["signal_at"])
    splits = chronological_partitions(frame.signal_at.tolist())
    if partition == "final" and not open_final:
        raise ValueError("final test is sealed; explicit --open-final-test is required")
    frozen_hash = Path(__file__).with_name("model").joinpath("manifest.sha256").read_text().strip()
    if evidence.get("model_manifest_sha256") != frozen_hash:
        raise ValueError("dataset must identify the frozen model manifest")
    if evidence.get("candidate") != "primary":
        raise ValueError("predeclare a different candidate before opening a final test")
    selected = validate_panel(frame[frame.signal_at.isin(splits[partition])])
    boundary = selected.exit_at.max()
    ic = []
    for _, group in selected.groupby("signal_at", sort=True):
        if (group.forward_end > boundary).any():
            continue  # Purge outcomes crossing into the next chronological partition.
        if group.score.nunique() > 1 and group.forward_6m.nunique() > 1:
            ic.append(float(spearmanr(group.score, group.forward_6m).statistic))
    views = ("top20", "q1", "q2", "q3", "q4", "q5", "equal_weight", "cap_weight",
             "sector_neutral")
    performance = {}
    for cost in COSTS:
        series = {view: portfolio_series(selected, view, cost) for view in views}
        active = np.array(series["top20"]) - np.array(series["equal_weight"])
        spread = np.array(series["q1"]) - np.array(series["q5"])
        deviation = float(np.std(active, ddof=1))
        performance[str(cost)] = {
            "mean_monthly_return": {view: float(np.mean(values))
                                    for view, values in series.items()},
            "top20_active_mean": float(np.mean(active)),
            "top20_information_ratio": float(np.mean(active) / deviation * np.sqrt(12))
            if deviation > 0 else None,
            "top_minus_bottom": hac_mean(spread.tolist()),
        }
    base = performance["10"]
    ic_report = hac_mean(ic)
    means = base["mean_monthly_return"]
    checks = {
        "rank_ic": ic_report["mean"] is not None and ic_report["mean"] >= .02,
        "rank_ic_t": ic_report["t"] is not None and ic_report["t"] >= 2,
        "spread_t": base["top_minus_bottom"]["t"] is not None
        and base["top_minus_bottom"]["t"] >= 2,
        "quintile_monotonicity": all(means[f"q{i}"] >= means[f"q{i+1}"]
                                     for i in range(1, 5)),
        "top20_active": base["top20_active_mean"] > 0,
        "information_ratio": base["top20_information_ratio"] is not None
        and base["top20_information_ratio"] > 0,
        "cost_25bps": performance["25"]["top20_active_mean"] > 0
        and performance["25"]["top_minus_bottom"]["mean"] is not None
        and performance["25"]["top_minus_bottom"]["mean"] > 0,
        "union_coverage": all(len(g) / g.expected_count.iloc[0] >= .9
                              for _, g in selected.groupby("signal_at")),
    }
    missing = ["reviewed point-in-time corporate-action and delisting lineage",
               "profile-specific expected membership and coverage evidence",
               "sector/peer/regime concentration and predefined partition robustness",
               "liquidity/winsorization/peer/REIT/weight sensitivity experiments",
               "out-of-sample parity evidence and independent bias audit"]
    if len(splits["development"] + splits["validation"] + splits["final"]) < 180:
        missing.append("at least 15 years of complete history")
    if evidence.get("universe_mode") != "POINT_IN_TIME_LICENSED":
        missing.append("licensed point-in-time membership; current constituents are biased")
    if evidence.get("synthetic", True):
        missing.append("real observations; synthetic fixtures cannot establish performance")
    if partition != "final":
        missing.append("sealed final out-of-sample evaluation")
    return {"status": "DIAGNOSTIC_ONLY", "production_ready": False,
            "partition": partition, "model_manifest_sha256": frozen_hash,
            "final_test_opened": partition == "final", "rank_ic_6m": ic_report,
            "performance_by_cost_bps_per_side": performance, "computed_checks": checks,
            "unmet_evidence_gates": missing,
            "walk_forward_windows": walk_forward_windows(splits["development"]
                                                         + splits["validation"])}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--panel", required=True)
    parser.add_argument("--evidence", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--partition", choices=["development", "validation", "final"],
                        default="validation")
    parser.add_argument("--open-final-test", action="store_true")
    args = parser.parse_args(argv)
    panel = Path(args.panel)
    evidence_path = Path(args.evidence)
    result = evaluate(pd.read_csv(panel), json.loads(evidence_path.read_text()),
                      args.partition, args.open_final_test)
    result["panel_sha256"] = hashlib.sha256(panel.read_bytes()).hexdigest()
    result["evidence_sha256"] = hashlib.sha256(evidence_path.read_bytes()).hexdigest()
    Path(args.output).write_text(json.dumps(result, indent=2, allow_nan=False) + "\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
