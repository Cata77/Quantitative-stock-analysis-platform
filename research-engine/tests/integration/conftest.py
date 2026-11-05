"""Database integration tests for the Python publication (P2s).

Opt-in: ``QUANT_DB_TESTS=1``. A disposable ``timescale/timescaledb:2.29.1-pg18`` container is
started for the session; the versioned and repeatable migrations are applied once, in Flyway
order and with Flyway's search path, into a template database; every test gets a fresh copy.
The container is removed at the end. No other database is touched.
"""

from __future__ import annotations

import os
import re
import shutil
import subprocess
import time
import uuid
from pathlib import Path

import psycopg
import pytest

IMAGE = "timescale/timescaledb:2.29.1-pg18"
MIGRATIONS = Path(__file__).resolve().parents[3] / "database-migrations/src/main/resources/db/migration"
TEMPLATE = "quant_template"
PASSWORD = "postgres"


def pytest_collection_modifyitems(config, items):
    if os.environ.get("QUANT_DB_TESTS") == "1" and shutil.which("docker"):
        return
    skip = pytest.mark.skip(reason="set QUANT_DB_TESTS=1 (requires Docker) to run DB tests")
    for item in items:
        if "integration" in item.keywords:
            item.add_marker(skip)


def _versioned(path: Path) -> tuple:
    match = re.match(r"V(\d+)__", path.name)
    return (int(match.group(1)),) if match else (float("inf"), path.name)


def apply_migrations(dsn: str) -> None:
    versioned = sorted(MIGRATIONS.glob("V*__*.sql"), key=_versioned)
    repeatable = sorted(MIGRATIONS.glob("R__*.sql"), key=lambda p: p.name)
    with psycopg.connect(dsn, autocommit=True) as connection:
        connection.execute("CREATE EXTENSION IF NOT EXISTS timescaledb CASCADE")
        connection.execute("CREATE SCHEMA IF NOT EXISTS operations")
        connection.execute("""
            CREATE TABLE operations.flyway_schema_history (installed_rank INT PRIMARY KEY,
                version VARCHAR(50), description VARCHAR(200) NOT NULL, type VARCHAR(20) NOT NULL,
                script VARCHAR(1000) NOT NULL, checksum INT, installed_by VARCHAR(100) NOT NULL,
                installed_on TIMESTAMP NOT NULL DEFAULT now(), execution_time INT NOT NULL,
                success BOOLEAN NOT NULL)""")
        for rank, path in enumerate(versioned + repeatable, 1):
            with connection.transaction():
                connection.execute("SET LOCAL search_path TO operations, public")
                connection.execute(path.read_text(encoding="utf-8"))
                version = re.match(r"V(\d+)__", path.name)
                connection.execute(
                    "INSERT INTO operations.flyway_schema_history VALUES "
                    "(%s,%s,%s,%s,%s,NULL,'pytest',now(),0,true)",
                    (rank, version.group(1) if version else None, path.stem.split("__", 1)[1],
                     "SQL", path.name))


@pytest.fixture(scope="session")
def postgres_server():
    name = f"quant-pytest-{uuid.uuid4().hex[:8]}"
    subprocess.run(["docker", "run", "-d", "--rm", "--name", name, "-e", f"POSTGRES_PASSWORD={PASSWORD}",
                    "-p", "127.0.0.1::5432", IMAGE], check=True, capture_output=True)
    try:
        port = subprocess.run(["docker", "port", name, "5432/tcp"], check=True, capture_output=True,
                              text=True).stdout.strip().splitlines()[0].rsplit(":", 1)[1]
        base = f"host=127.0.0.1 port={port} user=postgres password={PASSWORD}"
        deadline = time.monotonic() + 90
        while True:
            try:
                with psycopg.connect(base + " dbname=postgres", connect_timeout=3) as c:
                    c.execute("SELECT 1")
                break
            except psycopg.OperationalError:
                if time.monotonic() > deadline:
                    raise
                time.sleep(1)
        with psycopg.connect(base + " dbname=postgres", autocommit=True) as c:
            c.execute(f"CREATE DATABASE {TEMPLATE}")
        apply_migrations(base + f" dbname={TEMPLATE}")
        with psycopg.connect(base + " dbname=postgres", autocommit=True) as c:
            # TimescaleDB background workers keep a session open; a template must be idle.
            c.execute(f"ALTER DATABASE {TEMPLATE} WITH IS_TEMPLATE true ALLOW_CONNECTIONS false")
            c.execute("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname=%s",
                      (TEMPLATE,))
        yield base
    finally:
        subprocess.run(["docker", "rm", "-f", name], capture_output=True)


@pytest.fixture
def database(postgres_server):
    """A fresh migrated database for one test; yields its DSN."""
    name = "test_" + uuid.uuid4().hex
    with psycopg.connect(postgres_server + " dbname=postgres", autocommit=True) as c:
        c.execute(f"CREATE DATABASE {name} TEMPLATE {TEMPLATE}")
    try:
        yield postgres_server + f" dbname={name}"
    finally:
        with psycopg.connect(postgres_server + " dbname=postgres", autocommit=True) as c:
            c.execute(f"DROP DATABASE IF EXISTS {name} WITH (FORCE)")


@pytest.fixture
def db(database):
    with psycopg.connect(database, autocommit=True) as connection:
        yield connection
