import json
import runpy
from datetime import UTC, datetime
from pathlib import Path

import pytest

SCRIPT = Path(__file__).resolve().parents[4] / (
    "Documentation/evidence/p2/closeout-final/finish-20261030.py"
)
ready = runpy.run_path(str(SCRIPT))["ready"]


def run(session="2026-10-29", **changes):
    return dict(dict(session=session, expected_count=3, actual_count=3,
                     complete=2, absent=1), **changes)


def test_terminal_missing_session_is_not_a_fabricated_score():
    assert ready([run()], ["2026-10-29"])


@pytest.mark.parametrize("runs,sessions", [
    ([], []),
    ([], ["2026-10-29"]),
    ([run()], ["2026-10-28", "2026-10-29"]),
    ([run(), run()], ["2026-10-28", "2026-10-29"]),
    ([run(actual_count=2)], ["2026-10-29"]),
    ([run(complete=1)], ["2026-10-29"]),
    ([run(terminal_errors=1)], ["2026-10-29"]),
    ([run(expected_count=0, actual_count=0, complete=0, absent=0)], ["2026-10-29"]),
])
def test_missing_boundaries_duplicates_and_pending_items_block_publication(runs, sessions):
    assert not ready(runs, sessions)


def test_recovery_requires_the_exact_audited_request_for_each_failed_session():
    recovery = runpy.run_path(str(SCRIPT.with_name("recover-20261030.py")))
    rows = [dict(run(session="2026-02-25"), request_key=key) for key in (
        "scheduled", "force:unrelated", recovery["REQUEST_KEY"]
    )]
    normal = dict(run(), request_key="scheduled")
    selected = recovery["select_runs"]([*rows, normal])
    assert selected == [rows[2], normal]
    assert ready(selected, ["2026-02-25", "2026-10-29"])
    assert not ready(recovery["select_runs"]([rows[0], normal]),
                     ["2026-02-25", "2026-10-29"])
    assert not ready(recovery["select_runs"]([*rows, rows[2], normal]),
                     ["2026-02-25", "2026-10-29"])
    failed = dict(run(session="2026-02-26"), request_key=recovery["REQUEST_KEY"])
    replacement = dict(failed, request_key=recovery["SECOND_REQUEST_KEY"])
    assert recovery["select_runs"]([failed, replacement]) == [replacement]


def test_terminal_transport_error_stops_before_publication_request(tmp_path, monkeypatch):
    script = runpy.run_path(str(SCRIPT))
    jar = tmp_path / "scoring-service/build/libs/scoring-service-0.0.1-SNAPSHOT.jar"
    jar.parent.mkdir(parents=True)
    jar.write_bytes(b"test artifact never executed")
    output = tmp_path / "evidence"
    script["main"].__globals__.update(
        ROOT=tmp_path, OUT=output,
        DEADLINE=datetime(2099, 1, 1, tzinfo=UTC),
        query=lambda _: [{"session": "2026-10-29"}],
        collection=lambda: [run(complete=1, terminal_errors=1)],
    )
    monkeypatch.setattr("sys.argv", [str(SCRIPT)])
    with pytest.raises(SystemExit) as stopped:
        script["main"]()
    assert stopped.value.code == 1
    status = json.loads((output / "status.json").read_text())
    assert status["state"] == "STOPPED_REQUIRES_INSPECTION"
    assert json.loads((output / "collection-failures.json").read_text())[0]["terminal_errors"] == 1
    assert not (output / "request.json").exists()
    assert not (output / "publish.log").exists()
