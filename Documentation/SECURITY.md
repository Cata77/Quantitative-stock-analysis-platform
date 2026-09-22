# Service security and deployment

Phase 10 keeps the existing service architecture. Auth alone holds the private signing key;
gateway, portfolio, screener, ingestion and scoring validate tokens against pinned public JWKS.
The old shared HMAC secret and header-only portfolio authentication are removed. Existing HMAC
tokens stop working after this upgrade; clients must sign in again.

## Tokens and service boundaries

Only RS256 with RSA keys of at least 2048 bits is accepted. Every verifier requires a known,
unique `kid`, valid signature, matching issuer/audience, UUID subject, `iat`, `nbf` and `exp`.
Clock skew is 30 seconds. Auth tokens expire after 15 minutes by default, at most one hour.
Auth publishes public keys at `GET /auth/jwks`. Verifiers load an operator-controlled local
JWKS file at startup; token headers cannot select a remote URL or key path.

- `/auth/register`, `/auth/login`, `/auth/jwks` and `/actuator/health` are public.
- Portfolio requires `portfolio:write` and derives ownership from the validated JWT subject.
- Screener requires `research:read`, including when called directly.
- Internal operational HTTP reads require `operations:read`. Normal login never issues this
  scope. Provision operator tokens through a controlled signing process; do not grant it to
  ordinary users or distribute the auth private key to operations clients.
- Other routes are denied. Actuator exposes health only, without sensitive details.

Gateway strips caller-supplied `X-Authenticated-*`, `X-Forwarded-*` and `Forwarded` headers.
The compatibility user-ID header it adds is never an authorization source downstream.
Forwarded-address handling is disabled. The limiter keys on the socket peer, so clients behind
one proxy share its quota. Do not enable forwarded headers without an explicit trusted-proxy
address policy. The limiter is per gateway instance, fixed-window, defaults to 100 requests per
minute and 10,000 clients; entries expire lazily and unknown clients receive 429 at capacity.
Multiple gateways require a later shared limiter. CORS requires explicit deployment origins.

## Configuration

All six services require `JWT_ISSUER`, `JWT_AUDIENCE`, and `JWT_PUBLIC_JWKS` (a `file:` URI).
Auth additionally requires `JWT_PRIVATE_KEY` (PKCS8 PEM) and `JWT_KEY_ID`. The public JWKS must
contain the matching RSA public key. Private JWKs, weak keys, duplicate IDs, non-RS256 keys and
the committed development key (even with a renamed ID) are rejected.

| Process | Database role | Password variable |
|---|---|---|
| Flyway | quant_migrator | DATABASE_MIGRATION_PASSWORD |
| Auth | quant_auth | AUTH_DB_PASSWORD |
| Portfolio | quant_portfolio | PORTFOLIO_DB_PASSWORD |
| Producer | quant_ingestion | MARKET_DATA_DB_PASSWORD |
| Scoring | quant_scoring | SCORING_DB_PASSWORD |
| Screener API | quant_screener | SCREENER_DB_PASSWORD |
| Search indexer | quant_search | SEARCH_DB_PASSWORD |
| Python research | quant_research | password in explicit RESEARCH_DB_DSN |

Non-local Java startup rejects absent/development passwords and administrative service role
names. It checks the actual connected role for superuser, role/database creation, RLS bypass
and migration-role membership. Passwords must be unique secrets of at least 20 characters.
The migration runner requires explicit credentials outside local. Python already requires an
explicit DSN and uses read-only transactions; its database role is also read-only.

Use a secret manager or protected deployment environment, never commit `.env`, private keys,
API keys or TLS material. Provider failure logs contain exception types instead of raw messages
that can contain API-key query strings. Do not enable HTTP wire/body/header logging or print
the resolved Compose configuration into logs. Bearer tokens belong in Authorization headers,
not URLs. Keep provider credentials out of ingestion payloads and diagnostic metadata.

## Database provisioning and upgrade

`V014` transfers application object ownership to `quant_migrator`; `R__security_grants.sql`
assigns domain permissions. Application roles cannot create tables, assume the migrator, read
Flyway history or access unrelated user data. Auth modifies users, portfolio modifies holdings,
ingestion owns reference/outbox work, scoring owns inbox/canonical/scoring work. Screener and
research only read; the separate search worker can also write its rebuild checkpoint.
The legacy ticker-based scoring indexer is enabled only by the explicit local profile;
non-local startup rejects attempts to enable it. Deployment indexing uses the separate
search worker and its authenticated Elasticsearch client.
New objects receive no automatic public access. Extend explicit grants with future migrations.

For a fresh database, a privileged bootstrap administrator enables TimescaleDB with `init.sql`,
then runs `bootstrap-roles.sql` before Flyway. Provision role LOGIN credentials separately using
your secret provisioner (or interactive psql `\password role_name`; avoid passwords in shell
history). Bootstrap roles start NOLOGIN and non-administrative. Run Flyway as `quant_migrator`.
The bootstrap administrator is not a running application identity.

For an existing database, back it up, pause writers, and apply V014 plus repeatables once as
the existing owner/administrator. Provision service credentials and switch subsequent Flyway
runs to `quant_migrator`. Do not rerun bootstrap ownership changes blindly on a deployed database.
Flyway history retains its previous owner to avoid a cross-connection ALTER OWNER lock conflict;
the migrator receives the DML permissions Flyway requires. No live migration is part of this
implementation. Do not edit an already-applied V014; use a new migration for later changes.

## Compose environments

`docker-compose.yml` is the deployment base, with no credential defaults and loopback host
bindings. Supply DB credentials, keys under `infrastructure/keys`, TLS material under
`infrastructure/certs`, and explicit CORS origins. Run an HTTPS ingress in front of gateway and
keep the private service network isolated. The base is a configuration template requiring
operator-provisioned certificates and secrets, not a production deployment certification.

Kafka uses TLS client authentication. Put broker JKS stores and credential files in
`certs/kafka` as named in Compose, including `client.properties` for its health check. Each
producer/scoring client has its own `client.keystore.jks` and `client.truststore.jks` under
`certs/market-data-producer` or `certs/scoring-service`; supply corresponding
`INGESTION_KAFKA_*` / `SCORING_KAFKA_*` passwords. Certificates must include the broker's actual
DNS names. Client YAML also supports SASL_SSL via standard Spring Kafka properties.

Elasticsearch requires HTTPS, `certs/elasticsearch/http.key`, `http.crt`, `ca.crt`, and an
explicit bootstrap password. Provision separate `ELASTICSEARCH_API_KEY` (read/search) and
`ELASTICSEARCH_INDEXER_API_KEY` (index/alias management). The Java search clients trust the CA
through `certs/search-client/truststore.jks`; it should contain CA certificates only. Configure
certificate SANs for the service names and never disable hostname verification.

Local development must explicitly opt into the insecure overlay (Compose 2.24.4 or newer):

```powershell
docker compose -f infrastructure/docker-compose.yml -f infrastructure/docker-compose.local.yml --profile application up --build -d
```

The sole Spring profile is `local`; combining it with a deployment profile fails. This uses
committed development keys/passwords, plaintext Kafka and unauthenticated Elasticsearch.
`GenerateLocalKeys.java` regenerates test/local fixtures only; never use those fixtures for
 deployment. Existing local volumes do not rerun init scripts: provision their local roles and
apply the ownership upgrade before switching service credentials. Do not delete volumes to
perform an upgrade. Search rebuild runs in the separate `search-indexer` process.

## Key rotation

Generate a fresh RSA key pair through the deployment key manager, with a new unique ID. Keep
the private key auth-only. Add the new public key alongside the old one in each verifier's
pinned JWKS and roll all verifier processes. Then switch auth's private key and `JWT_KEY_ID`
and restart auth. Keep the old public key until the last old token expires plus 30 seconds
(use the previous configured lifetime, up to one hour). Remove the old public key and roll
verifiers again. JWKS files are snapshots loaded at startup, not hot-reloaded. For emergency
revocation remove the compromised public key immediately and require affected clients to login.

## Verification

```powershell
.\gradlew.bat test bootJar
.\.venv\Scripts\python.exe -m pytest research-engine/tests
.\.venv\Scripts\python.exe -m ruff check research-engine
```

Tests cover the JWT rejection matrix, key overlap/retirement, non-local startup failure,
forged-header rejection, scopes, CORS, limiter capacity/expiry, real database role grants and
denials, fresh restricted-role bootstrap and upgrade. Existing scoring tests retain Kafka DLQ,
point-in-time parity and concurrent virtual-thread JFR checks. CI runs the full Java/Python set.

Implementation references: [Spring servlet JWT validation](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html),
[reactive JWT validation](https://docs.spring.io/spring-security/reference/reactive/oauth2/resource-server/jwt.html),
and [PostgreSQL privileges](https://www.postgresql.org/docs/18/ddl-priv.html).

Verified on 2026-09-22: 162 distinct Java tests passed across the full run and final
affected-suite rerun, including 9 shared-security and 11 migration tests. All seven bootJars,
85 Python tests, Ruff, both Compose configurations and diff checks passed. Final reruns covered
the new legacy-indexer deployment guard and migration credential rejection. Tests used
isolated dependencies; deployment migration, secrets, certificates and live rollout remain
operator steps described above.
