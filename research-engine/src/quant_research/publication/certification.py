"""Fail-closed certification of the Python model before a publication.

Python counterpart of scoring-service FlexibleModel.certify: the packaged fixture is scored and
compared with its expected output (absolute tolerance 1e-9); the identity hashes the manifest,
the fixture files, the input queries and the Python implementation sources.
"""

from __future__ import annotations

import json
import math
from dataclasses import dataclass
from pathlib import Path

from ..model import flexible
from ..model.manifest import checksum
from . import canonical_json
from .document import OUTCOMES_QUERY
from .inputs import INPUT_QUERY, ROOT

TOLERANCE = 1e-9
FIXTURES = {"1.5.0": ROOT / "research-engine/fixtures/model-v1.5"}
PACKAGE = Path(__file__).resolve().parent
SOURCES = sorted([
    *PACKAGE.parent.joinpath("model").glob("*.py"),
    *PACKAGE.glob("*.py"),
])


@dataclass(frozen=True)
class Certification:
    certification_id: str
    model_version: str
    manifest_sha256: str
    implementation_version: str


def compare(expected, actual, path="$") -> None:
    if isinstance(expected, bool) or not isinstance(expected, (int, float)):
        if isinstance(expected, dict):
            if not isinstance(actual, dict) or expected.keys() != actual.keys():
                raise ValueError(f"{path}: keys differ")
            for key in expected:
                compare(expected[key], actual[key], f"{path}.{key}")
        elif isinstance(expected, list):
            if not isinstance(actual, list) or len(expected) != len(actual):
                raise ValueError(f"{path}: lengths differ")
            for index, (left, right) in enumerate(zip(expected, actual, strict=True)):
                compare(left, right, f"{path}[{index}]")
        elif expected != actual:
            raise ValueError(f"{path}: values differ")
    elif (isinstance(actual, bool) or not isinstance(actual, (int, float))
          or not math.isfinite(actual) or abs(expected - actual) > TOLERANCE):
        raise ValueError(f"{path}: numbers differ")


def _text(path: Path) -> str:
    return path.read_text(encoding="utf-8").replace("\r\n", "\n")


def certify(version: str) -> Certification:
    if version not in FIXTURES:
        raise ValueError("No certification fixture for this model version")
    contract = flexible.manifest(version)
    manifest_sha = checksum(contract)
    folder = FIXTURES[version]
    prepared_text, expected_text = _text(folder / "prepared.json"), _text(folder / "expected.json")
    prepared = json.loads(prepared_text)
    actual = flexible.score(prepared["rows"], as_of=prepared["as_of"], candidate="primary",
                            version=version)
    compare(json.loads(expected_text), json.loads(json.dumps(actual)))
    implementation = canonical_json.write({
        str(path.relative_to(ROOT)).replace("\\", "/"): canonical_json.sha256(_text(path))
        for path in SOURCES})
    identity = canonical_json.hash_fields(
        manifest_sha, canonical_json.sha256(implementation),
        canonical_json.sha256(prepared_text), canonical_json.sha256(expected_text),
        canonical_json.sha256(_text(INPUT_QUERY)), canonical_json.sha256(_text(OUTCOMES_QUERY)))
    return Certification(identity, version, manifest_sha, f"python-flexible-{version}")
