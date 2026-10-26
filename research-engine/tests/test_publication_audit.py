import pytest

from quant_research.publication_audit import equivalent


@pytest.mark.parametrize("actual", [True, float("nan"), float("inf"), None, 1.01])
def test_replay_rejects_invalid_numbers(actual):
    with pytest.raises(ValueError):
        equivalent({"metric": 1}, {"metric": actual})


def test_replay_preserves_missing_and_exact_instrument_set():
    equivalent({"a": [1, None, False]}, {"a": [1.0000000001, None, False]})
    with pytest.raises(ValueError):
        equivalent({"a": None}, {"a": 0})
    with pytest.raises(ValueError):
        equivalent({"a": None}, {"b": None})
