"""Verify the checked-in hypothesis before using it on an untouched final period."""

from __future__ import annotations

import hashlib
import json
from pathlib import Path

from .manifest import checksum, load_manifest


def verify_freeze(root: Path | None = None) -> dict:
    root = root or Path(__file__).resolve().parents[4]
    record = json.loads((root / "research-engine/fixtures/model-v1/freeze.json").read_text())
    if record["manifest_sha256"] != checksum(load_manifest()):
        raise ValueError("Frozen manifest changed")
    for name, expected in record["files"].items():
        path = (root / name).resolve()
        if not path.is_relative_to(root.resolve()):
            raise ValueError("Freeze path leaves repository")
        content = path.read_text(encoding="utf-8").replace("\r\n", "\n").encode()
        if hashlib.sha256(content).hexdigest() != expected:
            raise ValueError("Frozen artifact changed: " + name)
    if record["source_tree_sha256"] != checksum(record["files"]):
        raise ValueError("Frozen source tree checksum changed")
    return record
