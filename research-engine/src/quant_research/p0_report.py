"""P0 root-cause inventory and source-backed, offline repair experiments."""

from __future__ import annotations

import argparse
import copy
import csv
import hashlib
import json
from collections import Counter
from pathlib import Path

from .exclusion_audit import category, index, overlap
from .model.engine import score, serialize
from .model.periods import ADDITIVE, BALANCES

ALTERNATIVES = {
    "INTEREST_EXPENSE": ["InterestExpenseNonoperating", "InterestExpenseOperating"],
    "CAPEX": ["PaymentsToAcquireProductiveAssets"],
    "PRETAX_INCOME": [
        "IncomeLossFromContinuingOperationsBeforeIncomeTaxesMinorityInterestAndIncomeLossFromEquityMethodInvestments"
    ],
    "COST_OF_REVENUE": ["CostOfGoodsAndServiceExcludingDepreciationDepletionAndAmortization"],
}


def source_facts(
    source: dict, concepts: list[tuple[str, str]], end: str | None = None
) -> list[dict]:
    result = []
    for taxonomy, concept in concepts:
        definition = ((source.get("facts") or {}).get(taxonomy) or {}).get(concept, {})
        if not definition:
            continue
        for unit, facts in definition.get("units", {}).items():
            for fact in facts:
                if (end is None or fact["end"] == end) and fact.get("form") in {
                    "10-K",
                    "10-Q",
                    "10-K/A",
                    "10-Q/A",
                }:
                    result.append(dict(fact, unit=unit, concept=concept, taxonomy=taxonomy))
    return result


def metric_name(key: str) -> str:
    if key.startswith("ANNUAL_TAX_"):
        return "TAX_EXPENSE"
    if key.startswith("ANNUAL_PRETAX_"):
        return "PRETAX_INCOME"
    return key.removesuffix("_PRIOR_TTM").removesuffix("_PRIOR").removesuffix("_TTM")


def diagnose(key: str, c: dict, p: dict, source: dict, mappings: list[dict]) -> dict:
    metric = metric_name(key)
    facts = [f for f in c["facts"] if f["metric"] == metric]
    target = p["period_end"]
    if key.endswith("_PRIOR"):
        target = p["provenance"].get("TOTAL_ASSETS_PRIOR", {}).get("period_end")
    concepts = [
        (m["taxonomy"], m["source_concept"]) for m in mappings if m["metric_code"] == metric
    ]
    candidates = source_facts(source, concepts, target) if target else []
    matching = [f for f in facts if f["end"] == target and f["unit"] == "USD"]
    if key == "SHARES_OUTSTANDING":
        share_source = source_facts(source, concepts)
        cause = (
            "INSTRUMENT_SHARE_LINK_OR_IDENTITY"
            if facts or share_source
            else "SOURCE_SHARES_UNAVAILABLE"
        )
    elif metric not in ADDITIVE | BALANCES and not concepts:
        cause = (
            "PROFILE_SUPPLEMENT_NOT_IMPLEMENTED"
            if c["profile"] != "GENERAL"
            else "PRICE_HISTORY_GAP"
        )
    elif not source:
        cause = "NO_RETAINED_SOURCE"
    elif not target:
        cause = "NO_COMPARABLE_BALANCE_PERIOD"
    elif (
        metric == "COMMON_EQUITY"
        and not matching
        and source_facts(source, [("us-gaap", "StockholdersEquity")], target)
    ):
        cause = "COMMON_EQUITY_RECONCILIATION_EVIDENCE"
    elif matching and key.endswith("_TTM"):
        cause = "TTM_NO_COMPATIBLE_COMBINATION"
    elif key.startswith("ANNUAL_"):
        cause = "ANNUAL_TAX_HISTORY_OR_ALIGNMENT"
    elif candidates:
        cause = "CANONICAL_SELECTION_OR_QUALITY_REJECTION"
    elif facts:
        cause = "MAPPED_FACTS_OTHER_PERIOD"
    else:
        present = any(
            ((source.get("facts") or {}).get(taxonomy) or {}).get(concept)
            for taxonomy, concept in concepts
        )
        cause = "SOURCE_CONTEXT_NOT_USABLE" if present else "MAPPED_CONCEPT_ABSENT_FROM_SOURCE"
    alternatives = (
        source_facts(source, [("us-gaap", name) for name in ALTERNATIVES.get(metric, [])], target)
        if target and not key.startswith("ANNUAL_")
        else []
    )
    baseline_cause = cause
    if alternatives and not matching:
        cause = "UNMAPPED_CONCEPT_SCOPE_REVIEW"
    return {
        "cause": cause,
        "baseline_cause": baseline_cause,
        "metric": metric,
        "target_end": target,
        "canonical_fact_ids": [f["factId"] for f in facts],
        "canonical_latest_end": max((f["end"] for f in facts), default=None),
        "source_candidates_at_target": candidates,
        "alternative_source_candidates": alternatives,
        "mapped_concepts": concepts,
        "original_artifact_id": source.get("original_id"),
    }


def gross_profit_patch(c: dict, p: dict) -> dict | None:
    """Accounting identity, using identical period legs and identical filing per leg."""
    if c["profile"] != "GENERAL" or p["values"].get("COST_OF_REVENUE_TTM") is not None:
        return None
    if any(p["values"].get(k) is None for k in ("REVENUE_TTM", "GROSS_PROFIT_TTM")):
        return None
    by_id = {f["factId"]: f for f in c["facts"]}
    proofs = [p["provenance"][k] for k in ("REVENUE_TTM", "GROSS_PROFIT_TTM")]
    legs = [
        {
            (by_id[i]["start"], by_id[i]["end"], by_id[i]["filingId"], by_id[i]["unit"])
            for i in proof["source_ids"]
        }
        for proof in proofs
    ]
    if legs[0] != legs[1]:
        return None
    value = p["values"]["REVENUE_TTM"] - p["values"]["GROSS_PROFIT_TTM"]
    if value < 0:
        return None
    evidence = dict(
        proofs[0],
        source_ids=sorted(set(proofs[0]["source_ids"] + proofs[1]["source_ids"])),
        available_at=max(x["available_at"] for x in proofs),
        observed_at=max(x["observed_at"] for x in proofs),
    )
    return {
        "key": "COST_OF_REVENUE_TTM",
        "value": value,
        "provenance": evidence,
        "policy": "REVENUE_MINUS_REPORTED_GROSS_PROFIT_SAME_FILING_PERIOD_LEGS",
    }


def simulate(
    prepared: list[dict], patches: dict[str, list[dict]], as_of: str, baseline: dict, symbols: dict
) -> dict:
    rows = copy.deepcopy(prepared)
    for row in rows:
        for patch in patches.get(row["instrument_id"], []):
            if patch.get("remove_blocker"):
                row["blocking_reasons"].remove(patch["remove_blocker"])
                continue
            row["values"][patch["key"]] = patch["value"]
            row["provenance"][patch["key"]] = patch["provenance"]
    result = serialize(score(rows, as_of=as_of, candidate="primary"))
    outputs = index(result["rows"], "instrument_id")
    gained = [i for i, r in outputs.items() if r["eligible"] and not baseline[i]["eligible"]]
    lost = [i for i, r in outputs.items() if not r["eligible"] and baseline[i]["eligible"]]
    return {
        "scope": "OFFLINE_DIAGNOSTIC_NOT_PUBLISHED",
        "eligible": result["eligible_count"],
        "recovered": sorted(symbols[i] for i in gained),
        "lost": sorted(symbols[i] for i in lost),
        "patched": sorted(symbols[i] for i in patches),
        "remaining_blockers": {symbols[i]: outputs[i]["reasons"] for i in patches},
        "patches": patches,
        "outputs": result,
    }


def build(publication: Path, sources: Path, output: Path) -> dict:
    def read(path):
        return json.loads(path.read_text(encoding="utf-8-sig"))

    doc = read(publication / "published-input-document.json")
    manifest = read(sources / "manifest.json")
    digest = hashlib.sha256(
        (publication / "published-input-document.json").read_bytes()
    ).hexdigest()
    if digest != manifest["input_sha256"]:
        raise ValueError("Source inventory belongs to different inputs")
    evidence = read(publication / "published-model-evidence.json")
    if (
        serialize(
            score(doc["prepared"]["rows"], as_of=doc["prepared"]["as_of"], candidate="primary")
        )
        != evidence
    ):
        raise ValueError("Model evidence does not reproduce")
    canonical = index(doc["canonical"]["inputs"], "instrumentId")
    prepared = index(doc["prepared"]["rows"], "instrument_id")
    outputs = index(evidence["rows"], "instrument_id")
    identities = index(manifest["identities"], "instrument_id")
    if (
        canonical.keys() != prepared.keys()
        or canonical.keys() != outputs.keys()
        or canonical.keys() != identities.keys()
    ):
        raise ValueError("Instrument sets differ")
    source_data = {}
    for source_id, expected in manifest["sources"].items():
        raw = (sources / f"{source_id}.json").read_bytes()
        if hashlib.sha256(raw).hexdigest() != expected:
            raise ValueError("Source inventory file changed")
        source_data[source_id] = json.loads(raw)
    profile_details = index(manifest["profile_details"], "source_artifact_id")
    reports, patches = [], {}
    profile_patches, equity_patches, unit_patches = {}, {}, {}
    for identity, c in canonical.items():
        p, r = prepared[identity], outputs[identity]
        link = identities[identity]
        source = source_data.get(link["anchor_artifact_id"], {})
        observation = link["profile_observation"] or {}
        profile_detail = profile_details.get(observation.get("source_artifact_id"), {})
        missing = {
            key: diagnose(key, c, p, source, manifest["mappings"])
            for key, value in r["derived"].items()
            if key.isupper() and value is None
        }
        explanations = []
        for reason in r["reasons"]:
            family = (
                "value"
                if reason.startswith("INSUFFICIENT_VALUE")
                else ("quality" if reason.startswith("INSUFFICIENT_QUALITY") else None)
            )
            explanations.append(
                {
                    "reason": reason,
                    "category": category(reason),
                    "metrics": {
                        k: v for k, v in r["metrics"].items() if family and v["family"] == family
                    },
                    "canonical_reasons": c["reasons"],
                }
            )
        patch = gross_profit_patch(c, p)
        if patch:
            patches[identity] = [patch]
        blocker = "MODEL_NOT_SUPPORTED:NO_MAPPED_FACTS"
        if (
            c["profile"] == "GENERAL"
            and blocker in p["blocking_reasons"]
            and p["values"].get("TOTAL_ASSETS") is not None
            and profile_detail.get("parsed_fact_count") == 0
        ):
            profile_patches[identity] = [
                {
                    "remove_blocker": blocker,
                    "policy": "ASSESS_CANONICAL_COVERAGE_NOT_LAST_EMPTY_FILING",
                    "evidence": profile_detail,
                }
            ]
        if c["symbol"] == "AAPL":
            # Reviewed balance-sheet and equity roll-forward: solely common stock/APIC,
            # retained earnings and OCI; numerical values already retained before cutoff.
            # No preferred/minority zero is inferred or added to any model input.
            for key, end, expected in [
                ("COMMON_EQUITY", "2026-06-27", 107520000000),
                ("COMMON_EQUITY_PRIOR", "2025-06-28", 65830000000),
            ]:
                fs = [
                    f
                    for f in c["facts"]
                    if f["metric"] == "STOCKHOLDERS_EQUITY"
                    and f["end"] == end
                    and not f.get("start")
                    and f["unit"] == "USD"
                ]
                if len(fs) != 1 or fs[0]["value"] != expected:
                    raise ValueError("AAPL reviewed equity no longer matches archived facts")
                f = fs[0]
                equity_patches.setdefault(identity, []).append(
                    {
                        "key": key,
                        "value": f["value"],
                        "policy": "AAPL_REVIEWED_COMMON_ONLY_EQUITY_STATEMENT",
                        "reviewed_at": "2026-10-22",
                        "review_is_new_numeric_source_is_retained": True,
                        "review_source": "https://www.sec.gov/Archives/edgar/data/320193/000032019326000020/aapl-20260627.htm",
                        "provenance": {
                            "source_ids": [f["factId"]],
                            "unit": f["unit"],
                            "period_end": f["end"],
                            "filed_date": f["filedDate"],
                            "available_at": f["availableAt"],
                            "observed_at": f["observedAt"],
                        },
                    }
                )
        if c["symbol"] == "PEP":
            valid = [
                f
                for f in manifest["unit_case_facts"]
                if f["quality_state"] == "VALID"
                and f["unit"] == "USD"
                and f["period_end"] == p["period_end"]
            ]
            invalid = [
                f for f in manifest["unit_case_facts"] if f["quality_state"] == "INVALID_UNIT"
            ]
            if len(valid) == 1 and invalid:
                f = valid[0]
                if "DEBT_CURRENT" in missing:
                    missing["DEBT_CURRENT"]["cause"] = "MIXED_UNIT_PERIOD_REJECTION"
                    missing["DEBT_CURRENT"]["db_evidence"] = manifest["unit_case_facts"]
                unit_patches[identity] = [
                    {
                        "key": "DEBT_CURRENT",
                        "value": f["numeric_value"],
                        "policy": "ISOLATE_INVALID_UNIT_BEFORE_SAME_METRIC_PERIOD_CONFLICT_CHECK",
                        "provenance": {
                            "source_ids": [f["fact_id"]],
                            "unit": "USD",
                            "period_end": f["period_end"],
                            "available_at": f["available_at"],
                            "observed_at": f["observed_at"],
                        },
                    }
                ]
        reports.append(
            {
                "run_id": manifest["run_id"],
                "instrument_id": identity,
                "symbol": c["symbol"],
                "issuer_id": c["issuerId"],
                "profile": c["profile"],
                "sector": c["sector"],
                "eligible": r["eligible"],
                "reasons": r["reasons"],
                "explanations": explanations,
                "missing_inputs": missing,
                "source_link": link,
                "profile_observation": link["profile_observation"],
                "profile_source": profile_detail,
                "available_values": p["values"],
                "provenance": p["provenance"],
                "metrics": r["metrics"],
                "derived": r["derived"],
                "period_end": p["period_end"],
                "filing_date": p["filing_date"],
            }
        )
    output.mkdir(parents=True, exist_ok=True)
    counts = Counter(
        v["cause"] for r in reports if not r["eligible"] for v in r["missing_inputs"].values()
    )
    affected = Counter(
        cause
        for r in reports
        if not r["eligible"]
        for cause in {v["cause"] for v in r["missing_inputs"].values()}
    )
    combined = {}
    scenarios = {}
    for name, changes in [
        ("gross_profit", patches),
        ("profile_status", profile_patches),
        ("aapl_equity_review", equity_patches),
        ("unit_selection", unit_patches),
    ]:
        for identity, changes_for_row in changes.items():
            combined.setdefault(identity, []).extend(changes_for_row)
        scenarios[name] = simulate(
            doc["prepared"]["rows"],
            changes,
            doc["prepared"]["as_of"],
            outputs,
            {k: c["symbol"] for k, c in canonical.items()},
        )
    scenarios["combined"] = simulate(
        doc["prepared"]["rows"],
        combined,
        doc["prepared"]["as_of"],
        outputs,
        {k: c["symbol"] for k, c in canonical.items()},
    )
    for name, scenario in scenarios.items():
        (output / f"{name}-scenario.json").write_text(
            json.dumps(scenario, indent=2), encoding="utf-8"
        )
    with (output / "impact.csv").open("w", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(
            stream,
            fieldnames=[
                "scenario",
                "patched",
                "eligible",
                "recovered",
                "lost",
                "recovered_symbols",
            ],
        )
        writer.writeheader()
        for name, scenario in scenarios.items():
            writer.writerow(
                {
                    "scenario": name,
                    "patched": len(scenario["patched"]),
                    "eligible": scenario["eligible"],
                    "recovered": len(scenario["recovered"]),
                    "lost": len(scenario["lost"]),
                    "recovered_symbols": "|".join(scenario["recovered"]),
                }
            )
    with (output / "instruments.jsonl").open("w", encoding="utf-8") as stream:
        for report in sorted(reports, key=lambda r: r["symbol"]):
            stream.write(json.dumps(report, sort_keys=True) + "\n")
    with (output / "instruments.csv").open("w", encoding="utf-8", newline="") as stream:
        fields = ["instrument_id", "symbol", "profile", "sector", "eligible", "reasons", "causes"]
        writer = csv.DictWriter(stream, fieldnames=fields)
        writer.writeheader()
        for r in sorted(reports, key=lambda r: r["symbol"]):
            writer.writerow(
                {
                    **{k: r[k] for k in fields if k in r},
                    "reasons": "|".join(r["reasons"]),
                    "causes": "|".join(sorted({v["cause"] for v in r["missing_inputs"].values()})),
                }
            )
    if any(category(reason) == "UNRESOLVED" for r in reports for reason in r["reasons"]):
        raise ValueError("Unclassified eligibility reason")
    if any(not r["eligible"] and not r["explanations"] for r in reports):
        raise ValueError("Excluded member without explanation")
    if len(reports) != evidence["eligible_count"] + evidence["excluded_count"]:
        raise ValueError("Publication counts do not reconcile")
    summary = {
        "run_id": manifest["run_id"],
        "input_sha256": digest,
        "source_manifest_sha256": hashlib.sha256(
            (sources / "manifest.json").read_bytes()
        ).hexdigest(),
        "expected": len(reports),
        "eligible": evidence["eligible_count"],
        "excluded": evidence["excluded_count"],
        "source_anchors": len(source_data),
        "excluded_with_explanations": sum(
            not r["eligible"] and bool(r["explanations"]) for r in reports
        ),
        "missing_input_causes": dict(counts.most_common()),
        "instruments_by_cause": dict(affected.most_common()),
        "overlap": overlap(evidence["rows"]),
        "scenarios": {
            name: {k: v for k, v in scenario.items() if k not in {"outputs", "patches"}}
            for name, scenario in scenarios.items()
        },
    }
    (output / "summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    return summary


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--publication", type=Path, required=True)
    parser.add_argument("--sources", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    summary = build(args.publication, args.sources, args.output)
    print(json.dumps({k: v for k, v in summary.items() if k != "overlap"}, indent=2))


if __name__ == "__main__":
    main()
