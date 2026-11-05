"""Expand the language-neutral compact golden fixture deterministically."""

import copy
import json
from datetime import date, timedelta
from pathlib import Path
from uuid import UUID

FIXTURE = Path(__file__).parents[1] / "fixtures" / "model-v1" / "inputs.json"


def fixture_rows():
    fixture = json.loads(FIXTURE.read_text(encoding="utf-8"))
    rows = []
    for group, (profile, extra) in enumerate(fixture["profiles"].items()):
        for number in range(10):
            values = fixture["common"] | extra
            provenance = {}
            for key in values:
                end = "2026-06-30"
                if key in ("RAW_CLOSE", "ADJUSTED_CLOSE"):
                    end = "2026-09-30"
                elif key.endswith("_PRIOR") or key == "SAME_STORE_NOI_PRIOR_TTM":
                    end = "2025-06-30"
                elif key in ("AFFO_PRIOR_TTM", "DIVIDENDS_DECLARED_PRIOR_TTM"):
                    end = "2026-03-31"
                unit = (
                    "shares"
                    if key == "SHARES_OUTSTANDING"
                    else "pure"
                    if key.startswith("CET1_REQUIREMENT_")
                    else "square_feet"
                    if key in ("OCCUPIED_AREA", "AVAILABLE_AREA")
                    else "USD"
                )
                provenance[key] = {
                    "unit": unit,
                    "effective_from": "2026-01-01",
                    "effective_to": "2027-01-01",
                    "period_end": end,
                    "available_at": "2026-08-01T00:00:00Z",
                    "observed_at": "2026-08-01T00:00:00Z",
                    "source_ids": ["fixture:" + key],
                }
            if profile in ("PC_INSURER", "LIFE_INSURER"):
                end = date(2026, 6, 30)
                for q in range(1, 13):
                    start = date(end.year, end.month - 2, 1)
                    key = f"OPERATING_ROA_Q{q}"
                    values[key] = 0.02
                    provenance[key] = {
                        "unit": "pure",
                        "period_start": start.isoformat(),
                        "period_end": end.isoformat(),
                        "available_at": "2026-08-01T00:00:00Z",
                        "observed_at": "2026-08-01T00:00:00Z",
                        "source_ids": ["quarter:" + str(q)],
                    }
                    end = start - timedelta(days=1)
            rows.append(
                {
                    "instrument_id": str(UUID(int=group * 100 + number + 1)),
                    "issuer_id": str(UUID(int=group * 100 + number + 1)),
                    "profile": profile,
                    "profile_supported": True,
                    "regulatory_scope": "PUBLIC_PARENT",
                    "capital_approach": "STANDARDIZED",
                    "peer_group": profile + "_PEER",
                    "in_universe": True,
                    "active": True,
                    "primary_class": True,
                    "security_type": "COMMON_STOCK",
                    "jurisdiction": "US",
                    "history_count": 252,
                    "liquidity_count": 20,
                    "filing_date": "2026-08-01",
                    "period_end": "2026-06-30",
                    "values": values,
                    "provenance": provenance,
                    "capital_requirement_components": [
                        "CET1_REQUIREMENT_BASE",
                        "CET1_REQUIREMENT_BUFFER",
                    ],
                    "reit_reconciled": True,
                    "same_store_pool_consistent": True,
                    "blocking_reasons": [],
                }
            )
    return fixture, copy.deepcopy(rows)
