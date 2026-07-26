from __future__ import annotations

import os
from dataclasses import dataclass


@dataclass(frozen=True)
class ResearchSettings:
    database_dsn: str
    fees_bps: float = 2.5
    slippage_bps: float = 5.0

    def __post_init__(self) -> None:
        if not self.database_dsn.strip():
            raise ValueError("database_dsn must not be blank")
        total_cost = self.fees_bps + self.slippage_bps
        if not 5.0 <= total_cost <= 10.0:
            raise ValueError("fees_bps + slippage_bps must be between 5 and 10")

    @classmethod
    def from_environment(cls) -> ResearchSettings:
        dsn = os.environ.get("RESEARCH_DB_DSN", "")
        if not dsn:
            raise ValueError("RESEARCH_DB_DSN is required")
        return cls(
            database_dsn=dsn,
            fees_bps=float(os.environ.get("RESEARCH_FEES_BPS", "2.5")),
            slippage_bps=float(os.environ.get("RESEARCH_SLIPPAGE_BPS", "5.0")),
        )
