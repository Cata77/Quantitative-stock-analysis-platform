"""Capture a compact, cutoff-bound SEC source inventory for every run member."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
from uuid import UUID

from .model.engine import timestamp
from .source_audit import query


def capture(publication: Path, output: Path, container: str) -> None:
    raw = (publication / "published-input-document.json").read_bytes()
    doc = json.loads(raw)
    verification = json.loads((publication / "publication-verification.json").read_text())
    run_id = str(UUID(verification["run"]["score_run_id"]))
    digest = hashlib.sha256(raw).hexdigest()
    run = query(
        container, f"SELECT input_sha256 FROM research.scoring_runs WHERE score_run_id='{run_id}'"
    )
    if run != [{"input_sha256": digest}]:
        raise ValueError("Archive differs from published database input")
    output.mkdir(parents=True, exist_ok=True)
    context_path = output / "capture-context.json"
    existing_manifest = output / "manifest.json"
    if context_path.exists():
        context = json.loads(context_path.read_text())
        if context["input_sha256"] != digest:
            raise ValueError("Source cache belongs to another publication")
    elif existing_manifest.exists():
        if json.loads(existing_manifest.read_text())["input_sha256"] != digest:
            raise ValueError("Source cache belongs to another publication")
    elif any(output.glob("*.json")):
        raise ValueError("Unbound source cache; use a fresh output directory")
    context_path.write_text(
        json.dumps({"run_id": run_id, "input_sha256": digest}), encoding="utf-8"
    )
    mappings = query(
        container,
        "SELECT * FROM fundamentals.source_metric_mappings WHERE "
        "mapping_version=(SELECT request->'inputs'->>'mappingVersion' "
        f"FROM research.scoring_runs WHERE score_run_id='{run_id}')",
    )
    profiles = query(
        container,
        "SELECT DISTINCT ON (issuer_id) * "
        "FROM fundamentals.profile_observations WHERE available_at<="
        f"(SELECT knowledge_cutoff FROM research.scoring_runs WHERE score_run_id='{run_id}') "
        "ORDER BY issuer_id,available_at DESC,observation_key",
    )
    by_issuer = {r["issuer_id"]: r for r in profiles}
    identities = []
    for c in doc["canonical"]["inputs"]:
        # The profile event retains the original SEC bundle even when no 10-K/Q was parsed.
        profile = by_issuer.get(c["issuerId"])
        anchor = max(
            c["facts"], key=lambda f: (f["filedDate"], f["end"], f["factId"]), default=None
        )
        source_id = (
            anchor["sourceArtifactId"]
            if anchor
            else (profile["source_artifact_id"] if profile else None)
        )
        identities.append(
            {
                "instrument_id": c["instrumentId"],
                "symbol": c["symbol"],
                "issuer_id": c["issuerId"],
                "profile_observation": profile,
                "anchor_artifact_id": source_id,
            }
        )
    source_ids = sorted(
        {str(UUID(r["anchor_artifact_id"])) for r in identities if r["anchor_artifact_id"]}
    )
    manifest = {
        "run_id": run_id,
        "input_sha256": digest,
        "mappings": mappings,
        "identities": identities,
        "sources": {},
    }
    for n, source_id in enumerate(source_ids):
        path = output / f"{source_id}.json"
        if not path.exists():
            rows = query(
                container,
                f"""
                WITH a AS (SELECT * FROM operations.source_artifacts
                           WHERE source_artifact_id='{source_id}'),
                original AS (SELECT b.* FROM operations.source_artifacts b,a
                    WHERE b.source_artifact_id=coalesce(
                        (a.inline_content->>'originalArtifactId')::uuid,a.source_artifact_id)),
                run AS (SELECT * FROM research.scoring_runs WHERE score_run_id='{run_id}')
                SELECT a.source_artifact_id anchor_id,a.content_hash anchor_hash,
                       a.inline_content->>'originalContentHash' linked_original_hash,
                       o.source_artifact_id original_id,o.content_hash original_hash,
                       o.retrieved_at,o.source_uri,
                       o.inline_content->'companyFacts'->>'cik' cik,
                       o.inline_content->'catalogs' catalogs,
                       (SELECT jsonb_object_agg(t.key,concepts.value)
                        FROM jsonb_each(o.inline_content->'companyFacts'->'facts') t,
                        LATERAL (SELECT jsonb_object_agg(c.key,jsonb_build_object(
                            'latest_end',(SELECT max(f->>'end') FROM jsonb_each(c.value->'units') u,
                                          jsonb_array_elements(u.value) f),
                            'units',(SELECT jsonb_object_agg(u.key,ff.value)
                                     FROM jsonb_each(c.value->'units') u,
                                     LATERAL (SELECT coalesce(jsonb_agg(f),'[]'::jsonb) value
                                         FROM jsonb_array_elements(u.value) f
                                         WHERE (f->>'end')::date >=
                                             run.as_of_date-interval '1100 days'
                                           AND (f->>'filed')::date<=run.knowledge_cutoff::date
                                     ) ff))) value
                            FROM jsonb_each(t.value) c
                            WHERE c.key IN (SELECT source_concept
                                FROM fundamentals.source_metric_mappings
                                WHERE mapping_version=run.request->'inputs'->>'mappingVersion')
                            OR c.key ~ ('^(PreferredStock|InterestExpense|CostOf|'
                                || 'PaymentsToAcquire|CommonStock|StockholdersEquity|'
                                || 'IncomeLossFromContinuingOperationsBeforeIncomeTax)')
                        ) concepts) facts
                FROM a,original o,run
            """,
            )
            if len(rows) != 1:
                raise ValueError(f"Missing source {source_id}")
            path.write_text(json.dumps(rows[0], ensure_ascii=False), encoding="utf-8")
        entry = json.loads(path.read_text(encoding="utf-8"))
        if entry["anchor_id"] != source_id:
            raise ValueError("Source cache identity mismatch")
        if entry["linked_original_hash"] not in (None, entry["original_hash"]):
            raise ValueError("Source hash chain mismatch")
        if timestamp(entry["retrieved_at"]) > timestamp(
            doc["canonical"]["request"]["knowledgeCutoff"]
        ):
            raise ValueError("Source after cutoff")
        manifest["sources"][source_id] = hashlib.sha256(path.read_bytes()).hexdigest()
        if (n + 1) % 25 == 0:
            print(f"Captured {n + 1}/{len(source_ids)} sources", flush=True)
    profile_ids = ",".join(
        "'" + str(UUID(r["profile_observation"]["source_artifact_id"])) + "'"
        for r in identities
        if r["profile_observation"]
    )
    profile_details = query(
        container,
        "SELECT source_artifact_id,content_hash,"
        "inline_content->'filing'->>'accession' accession,"
        "inline_content->'filing'->>'filedDate' filed_date,"
        "inline_content->'filing'->>'fiscalPeriodEnd' period_end,"
        "jsonb_array_length(inline_content->'filing'->'facts') parsed_fact_count "
        f"FROM operations.source_artifacts WHERE source_artifact_id IN ({profile_ids})",
    )
    issuer_ids = ",".join("'" + str(UUID(r["issuer_id"])) + "'" for r in identities)
    invalid_periods = query(
        container,
        "SELECT DISTINCT issuer_id,metric_code,period_start,period_end "
        "FROM fundamentals.fundamental_facts WHERE quality_state<>'VALID' "
        f"AND issuer_id IN ({issuer_ids}) AND mapping_version="
        "(SELECT request->'inputs'->>'mappingVersion' FROM research.scoring_runs "
        f"WHERE score_run_id='{run_id}') "
        "AND available_at<="
        f"(SELECT knowledge_cutoff FROM research.scoring_runs WHERE score_run_id='{run_id}')",
    )
    manifest["profile_details"] = profile_details
    manifest["invalid_fact_periods"] = invalid_periods
    # Preserve rejected and valid same-period PEP facts for the unit-mixing case study.
    pep = next(r for r in identities if r["symbol"] == "PEP")
    manifest["unit_case_facts"] = query(
        container,
        "SELECT * FROM fundamentals.fundamental_facts "
        f"WHERE issuer_id='{UUID(pep['issuer_id'])}' AND metric_code='DEBT_CURRENT' "
        "AND period_end='2026-06-13' AND mapping_version='sec-us-gaap-v2'",
    )
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    print(f"Complete: {len(identities)} members, {len(source_ids)} source anchors", flush=True)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--publication", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--container", default="quant-platform-timescaledb-1")
    args = parser.parse_args()
    capture(args.publication, args.output, args.container)


if __name__ == "__main__":
    main()
