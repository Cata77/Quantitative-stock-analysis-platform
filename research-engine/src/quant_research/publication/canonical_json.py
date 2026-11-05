"""Byte-compatible port of ingestion-contract CanonicalJson (Jackson 3, sorted keys).

Rules reproduced from the Java implementation:
- object keys are sorted (TreeMap order: UTF-16 code units; keys here are ASCII);
- output is compact (no spaces), non-ASCII characters are written as UTF-8, not escaped;
- decimals (``Decimal``) lose trailing zeros and are written in plain notation;
- binary floating point values (``float``) are written like Java ``Double.toString``;
- ``hash`` length-prefixes every field (4-byte big-endian length) before SHA-256.
"""

from __future__ import annotations

import hashlib
import json
import math
from decimal import Decimal


def _decimal(value: Decimal) -> str:
    if not value.is_finite():
        raise ValueError("Non-finite decimal")
    if value == 0:
        return "0"
    text = format(value.normalize(), "f")
    return text


def java_double(value: float) -> str:
    """Java Double.toString: shortest round-trip digits, scientific outside [1e-3, 1e7)."""
    if not math.isfinite(value):
        raise ValueError("Non-finite double")
    if value == 0:
        return "-0.0" if math.copysign(1.0, value) < 0 else "0.0"
    sign = "-" if value < 0 else ""
    magnitude = abs(value)
    # repr() is the shortest round-trip form, the same digits as JDK 19+ Double.toString.
    _, digit_tuple, exp = Decimal(repr(magnitude)).normalize().as_tuple()
    digits = "".join(map(str, digit_tuple))
    point = len(digits) + exp  # digits before the decimal point
    if 1e-3 <= magnitude < 1e7:
        if point <= 0:
            return sign + "0." + "0" * (-point) + digits
        if point >= len(digits):
            return sign + digits + "0" * (point - len(digits)) + ".0"
        return sign + digits[:point] + "." + digits[point:]
    return f"{sign}{digits[0]}.{digits[1:] or '0'}E{point - 1}"


def _write(value, out: list[str]) -> None:
    if value is None:
        out.append("null")
    elif value is True:
        out.append("true")
    elif value is False:
        out.append("false")
    elif isinstance(value, Decimal):
        out.append(_decimal(value))
    elif isinstance(value, int):
        out.append(str(value))
    elif isinstance(value, float):
        out.append(java_double(value))
    elif isinstance(value, str):
        out.append(json.dumps(value, ensure_ascii=False))
    elif isinstance(value, dict):
        out.append("{")
        for index, key in enumerate(sorted(value, key=_java_key)):
            if not isinstance(key, str):
                raise TypeError("JSON object keys must be strings")
            if index:
                out.append(",")
            out.append(json.dumps(key, ensure_ascii=False))
            out.append(":")
            _write(value[key], out)
        out.append("}")
    elif isinstance(value, (list, tuple)):
        out.append("[")
        for index, item in enumerate(value):
            if index:
                out.append(",")
            _write(item, out)
        out.append("]")
    else:
        raise TypeError(f"Unsupported JSON value: {type(value).__name__}")


def _java_key(key: str) -> list[int]:
    # TreeMap<String> compares UTF-16 code units.
    encoded = key.encode("utf-16-be")
    return [int.from_bytes(encoded[i:i + 2], "big") for i in range(0, len(encoded), 2)]


def write(value) -> str:
    out: list[str] = []
    _write(value, out)
    return "".join(out)


def read(text: str):
    """Parse like CanonicalJson.MAPPER: floats as Decimal, integers as int."""
    return json.loads(text, parse_float=Decimal)


def sha256(content: str) -> str:
    return hashlib.sha256(content.encode("utf-8")).hexdigest()


def hash_fields(*fields: str) -> str:
    digest = hashlib.sha256()
    for field in fields:
        data = field.encode("utf-8")
        digest.update(len(data).to_bytes(4, "big"))
        digest.update(data)
    return digest.hexdigest()
