"""Canonical cross-section of a scoring request, loaded with the shared input query.

Port of scoring-service ScoringInputRequest/ScoringInputRepository. The SQL file is the same
one Java uses (``scoring-inputs.sql``); the payload is projected onto the record components and
serialized exactly as Jackson did (UUID strings, ISO dates, ``Instant.toString`` timestamps,
decimals without trailing zeros, every component present even when null).
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from datetime import UTC, date, datetime, timedelta
from decimal import Decimal
from pathlib import Path
from uuid import UUID
from zoneinfo import ZoneInfo

from . import canonical_json

NEW_YORK = ZoneInfo("America/New_York")
CLOSE = "CLOSE_V1"
PRE_OPEN = "NEXT_OPEN_MINUS_30M_V1"
CURRENT = "CURRENT_SNAPSHOT_V1"
MAPPINGS = {"sec-us-gaap-v1", "sec-us-gaap-v2"}
ROOT = Path(__file__).resolve().parents[4]
INPUT_QUERY = Path(__file__).resolve().parent / "sql/scoring-inputs.sql"
_PARAMETER = re.compile(r"(?<![:\w]):([A-Za-z][A-Za-z0-9_]*)")
_METRIC = re.compile(r"[A-Z][A-Z0-9_]{0,79}")


def instant(value: datetime) -> str:
    """java.time.Instant.toString (ISO_INSTANT): UTC, fraction in groups of three digits."""
    value = value.astimezone(UTC)
    text = value.strftime("%Y-%m-%dT%H:%M:%S")
    micros = value.microsecond
    if micros:
        text += f".{micros // 1000:03d}" if micros % 1000 == 0 else f".{micros:06d}"
    return text + "Z"


def parse_instant(text: str) -> datetime:
    parsed = datetime.fromisoformat(text.replace("Z", "+00:00").replace(" ", "T"))
    if parsed.tzinfo is None:
        raise ValueError("Timestamp without offset")
    return parsed.astimezone(UTC)


def validate_timing(market: datetime, knowledge: datetime, policy: str) -> None:
    if policy == CLOSE:
        valid = knowledge <= market
    else:
        valid = policy in (PRE_OPEN, CURRENT) and market < knowledge <= market + timedelta(days=7)
    if not valid:
        raise ValueError("Invalid knowledge cutoff for timing policy")


@dataclass(frozen=True)
class ScoringRequest:
    score_date: date
    market_cutoff: datetime
    knowledge_cutoff: datetime
    sp500_snapshot: UUID
    nasdaq100_snapshot: UUID
    raw_dataset: UUID
    adjusted_dataset: UUID
    adjustment_basis: date
    classification_version: UUID
    mapping_version: str
    required_metrics: tuple[str, ...]
    timing_policy: str = CLOSE

    def __post_init__(self):
        validate_timing(self.market_cutoff, self.knowledge_cutoff, self.timing_policy)
        vintage = (self.knowledge_cutoff.astimezone(NEW_YORK).date()
                   if self.timing_policy != CLOSE else self.score_date)
        if (self.adjustment_basis > vintage
                or (self.timing_policy != CLOSE and self.adjustment_basis != vintage)
                or self.score_date != self.market_cutoff.astimezone(NEW_YORK).date()
                or self.sp500_snapshot == self.nasdaq100_snapshot
                or self.mapping_version not in MAPPINGS):
            raise ValueError("Invalid scoring cutoff, snapshot pair or mapping version")
        metrics = self.required_metrics
        if not metrics or any(not _METRIC.fullmatch(m) for m in metrics):
            raise ValueError("Declare required canonical metrics explicitly")
        object.__setattr__(self, "required_metrics", tuple(sorted(set(self.required_metrics))))

    @classmethod
    def from_json(cls, data: dict) -> ScoringRequest:
        return cls(
            score_date=date.fromisoformat(data["scoreDate"]),
            market_cutoff=parse_instant(data["marketCutoff"]),
            knowledge_cutoff=parse_instant(data["knowledgeCutoff"]),
            sp500_snapshot=UUID(data["sp500Snapshot"]),
            nasdaq100_snapshot=UUID(data["nasdaq100Snapshot"]),
            raw_dataset=UUID(data["rawDataset"]),
            adjusted_dataset=UUID(data["adjustedDataset"]),
            adjustment_basis=date.fromisoformat(data["adjustmentBasis"]),
            classification_version=UUID(data["classificationVersion"]),
            mapping_version=data["mappingVersion"],
            required_metrics=tuple(data["requiredMetrics"]),
            timing_policy=data.get("timingPolicy", CLOSE),
        )

    def to_json(self) -> dict:
        """Jackson record serialization of ScoringInputRequest (before canonical sorting)."""
        return {
            "scoreDate": self.score_date.isoformat(),
            "marketCutoff": instant(self.market_cutoff),
            "knowledgeCutoff": instant(self.knowledge_cutoff),
            "sp500Snapshot": str(self.sp500_snapshot),
            "nasdaq100Snapshot": str(self.nasdaq100_snapshot),
            "rawDataset": str(self.raw_dataset),
            "adjustedDataset": str(self.adjusted_dataset),
            "adjustmentBasis": self.adjustment_basis.isoformat(),
            "classificationVersion": str(self.classification_version),
            "mappingVersion": self.mapping_version,
            "requiredMetrics": list(self.required_metrics),
            "timingPolicy": self.timing_policy,
        }

    def parameters(self) -> dict:
        return {
            "scoreDate": self.score_date,
            "marketCutoff": self.market_cutoff,
            "knowledgeCutoff": self.knowledge_cutoff,
            "basis": self.adjustment_basis,
            "rawDataset": self.raw_dataset,
            "adjustedDataset": self.adjusted_dataset,
            "sp500": self.sp500_snapshot,
            "nasdaq": self.nasdaq100_snapshot,
            "classification": self.classification_version,
            "mapping": self.mapping_version,
            "timingPolicy": self.timing_policy,
            "requiredMetrics": "{" + ",".join(self.required_metrics) + "}",
        }


def named_to_psycopg(sql: str) -> str:
    """Spring ``:name`` parameters to psycopg ``%(name)s``; ``::type`` casts are kept."""
    return _PARAMETER.sub(lambda m: f"%({m.group(1)})s", sql.replace("%", "%%"))


# Record components of ScoringInput and its nested records, in declaration order.
_DECIMAL = "decimal"
_FIELDS = {
    "instrumentId": "uuid", "issuerId": "uuid", "symbol": "str", "inSp500": "bool",
    "inNasdaq100": "bool", "primaryClass": "bool", "profile": "str", "sector": "str",
    "peerGroup": "str", "rawClose": _DECIMAL, "adjustedClose": _DECIMAL, "momentumRecent": _DECIMAL,
    "momentumOld": _DECIMAL, "historyCount": "int", "liquidityCount": "int",
    "medianDollarVolume": _DECIMAL, "sharesOutstanding": _DECIMAL, "shareFactId": "uuid",
}
_FACT = {
    "factId": "uuid", "filingId": "uuid", "metric": "str", "start": "date", "end": "date",
    "value": _DECIMAL, "unit": "str", "availableAt": "instant", "observedAt": "instant",
    "filedDate": "date", "sourceArtifactId": "uuid", "mappingVersion": "str",
    "parserVersion": "str",
}
_PRICE = {"date": "date", "mode": "str", "observationKey": "str", "sourceArtifactId": "uuid"}
_REASON = {"code": "str", "detail": "str"}


def _convert(value, kind: str):
    if value is None:
        return 0 if kind == "int" else False if kind == "bool" else None
    if kind == "uuid":
        return str(UUID(str(value)))
    if kind == "date":
        return date.fromisoformat(str(value)[:10]).isoformat()
    if kind == "instant":
        return instant(parse_instant(str(value)))
    if kind == _DECIMAL:
        if isinstance(value, bool):
            raise TypeError("Boolean where a decimal is expected")
        return Decimal(str(value))
    if kind == "int":
        if isinstance(value, bool) or int(value) != value:
            raise TypeError("Non-integer count")
        return int(value)
    if kind == "bool":
        if not isinstance(value, bool):
            raise TypeError("Non-boolean flag")
        return value
    return str(value)


def _project(data: dict, fields: dict) -> dict:
    return {name: _convert(data.get(name), kind) for name, kind in fields.items()}


def project_input(data: dict) -> dict:
    row = _project(data, _FIELDS)
    row["facts"] = [_project(f, _FACT) for f in data.get("facts") or []]
    row["priceLineage"] = [_project(p, _PRICE) for p in data.get("priceLineage") or []]
    row["reasons"] = [_project(r, _REASON) for r in data.get("reasons") or []]
    return row


def load(connection, request: ScoringRequest, query: Path = INPUT_QUERY) -> dict:
    """Run the input query and return the CrossSection as Java serialized it (re-read form)."""
    sql = named_to_psycopg(query.read_text(encoding="utf-8"))
    with connection.cursor() as cursor:
        cursor.execute("SET LOCAL statement_timeout = 60000")
        cursor.execute(sql, request.parameters())
        (payload,) = cursor.fetchone()
    result = canonical_json.read(payload)
    if result["errors"]:
        raise ValueError(", ".join(result["errors"]))
    section = {"request": request.to_json(), "inputs": [project_input(i) for i in result["inputs"]]}
    # Java stored CanonicalJson.readObject(MAPPER.writeValueAsString(section)): numbers re-read.
    return canonical_json.read(canonical_json.write(section))
