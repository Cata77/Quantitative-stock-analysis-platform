"""Start the new Screener jar locally (port 18182) with the container's configuration.
Credentials are passed only through the child environment; nothing is written to disk."""
import json, os, subprocess, sys
from pathlib import Path
root = Path.cwd(); out = root / "build/p2b-20261102/live"
env = dict(os.environ)
raw = subprocess.run(["docker", "inspect", "--format", "{{json .Config.Env}}", "quant-platform-screener-service-1"],
                     capture_output=True, text=True, check=True).stdout
container = dict(e.split("=", 1) for e in json.loads(raw) if "=" in e)
for key in ("SCREENER_DB_PASSWORD", "SCREENER_DB_USER", "JWT_PUBLIC_JWKS", "JWT_AUDIENCE", "JWT_ISSUER", "SPRING_PROFILES_ACTIVE"):
    if key in container: env[key] = container[key]
url = container["SCREENER_DB_URL"].replace("timescaledb:5432", "localhost:6543")
env.update(SCREENER_DB_URL=url, SEARCH_REBUILD_ENABLED="false", ELASTICSEARCH_URL="http://localhost:1")
jar = root / "screener-service/build/libs/screener-service-0.0.1-SNAPSHOT.jar"
log = (out / "screener.log").open("w")
p = subprocess.Popen(["java", "-jar", str(jar), "--server.port=18182"], env=env, stdout=log, stderr=subprocess.STDOUT)
(out / "screener.pid").write_text(str(p.pid)); print(p.pid)
