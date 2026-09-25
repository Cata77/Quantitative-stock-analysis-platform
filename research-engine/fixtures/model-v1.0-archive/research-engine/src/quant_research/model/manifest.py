"""Closed version-one manifest validation; no expression evaluation."""

from __future__ import annotations

import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).parent


def canonical_bytes(value: object) -> bytes:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), allow_nan=False).encode()


def checksum(value: object) -> str:
    return hashlib.sha256(canonical_bytes(value)).hexdigest()


def load_manifest(path: Path | None = None) -> dict:
    model = json.loads((path or ROOT / "manifest.json").read_text(encoding="utf-8"))
    schema = json.loads((ROOT / "manifest.schema.json").read_text(encoding="utf-8"))
    # This JSON Schema intentionally permits only the reviewed v1 constants.
    # A new hypothesis requires a new version, schema, implementation and golden fixture.
    if set(model) != set(schema["required"]) or any(
        model[key] != rule["const"] for key, rule in schema["properties"].items()
    ):
        raise ValueError("Manifest differs from the allow-listed version-one schema")
    expected = (ROOT / "manifest.sha256").read_text(encoding="utf-8").strip()
    if checksum(model) != expected:
        raise ValueError("Manifest checksum mismatch")
    return model
