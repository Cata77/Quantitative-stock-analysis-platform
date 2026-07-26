# Portfolio Service Runbook

## Scope

The `portfolio-service` owns CRUD operations for financial holdings stored in
the transactional `portfolios` table. It is a Java 25 Spring MVC service using
Spring Data JPA, Hibernate, Jakarta Bean Validation, virtual threads, and
PostgreSQL. The default HTTP port is `8083`.

The service is intended to be reached through the gateway at `/portfolio/**`.
It does not decode JWTs. The gateway validates the bearer token, removes any
client-supplied identity header, and forwards the verified JWT `sub` claim as:

```http
X-Authenticated-User-Id: <user UUID>
```

Deployment must prevent untrusted clients from reaching the portfolio service
directly. The header is trusted only when the network boundary ensures that the
gateway is its sole caller.

## API

Every endpoint requires a valid UUID in `X-Authenticated-User-Id`.

| Method | Path | Success | Purpose |
|---|---|---:|---|
| `GET` | `/portfolio` | `200` | List the authenticated user's holdings |
| `POST` | `/portfolio` | `201` | Create a holding |
| `GET` | `/portfolio/{holdingId}` | `200` | Read an owned holding |
| `PUT` | `/portfolio/{holdingId}` | `200` | Replace an owned holding |
| `DELETE` | `/portfolio/{holdingId}` | `204` | Delete an owned holding |

Lists are ordered by `purchasedAt` descending. A holding owned by another user
is treated as absent and returns `404`; the API does not reveal whether another
account owns the supplied identifier.

Create and update accept the same complete request:

```json
{
  "ticker": "AAPL",
  "quantity": 2.5000,
  "entryPrice": 195.1250,
  "purchasedAt": "2025-05-10T12:30:00Z"
}
```

Successful responses contain:

```json
{
  "id": "d1fb7fb7-c31b-45d8-af18-2cc418756e78",
  "ticker": "AAPL",
  "quantity": 2.5000,
  "entryPrice": 195.1250,
  "purchasedAt": "2025-05-10T12:30:00Z"
}
```

The service converts tickers to uppercase. Tickers must contain 1-10 letters,
digits, dots, or hyphens and must begin with a letter or digit. Quantity and
entry price must be positive and fit PostgreSQL `NUMERIC(15,4)` (at most 11
integer and 4 fractional digits). Purchase time is required and cannot be in
the future.

## Ownership and Persistence

The JPA entity maps to the existing `portfolios` table:

| Java field | Database column |
|---|---|
| `id` | `id` |
| `userId` | `user_id` |
| `ticker` | `ticker` |
| `quantity` | `quantity` |
| `entryPrice` | `entry_price` |
| `purchasedAt` | `purchased_at` |

All single-holding repository lookups use both `id` and authenticated
`user_id`. Collection queries filter by authenticated `user_id`. The public
response omits `userId`, so clients cannot assign or change ownership through
the request body.

Read operations use read-only transactions. Create, update, and delete use
short read-write transactions. `spring.jpa.open-in-view` is disabled.

## Errors

Errors use Spring `ProblemDetail`:

| Status | Meaning |
|---:|---|
| `400` | Invalid JSON, path UUID, or request field |
| `401` | Missing, blank, or malformed trusted user header |
| `404` | Holding does not exist for the authenticated user |

Validation responses include a `violations` object keyed by JSON field name.

## Configuration

| Environment variable | Default |
|---|---|
| `PORTFOLIO_PORT` | `8083` |
| `PORTFOLIO_DB_URL` | `jdbc:postgresql://localhost:6543/quant_platform` |
| `PORTFOLIO_DB_USER` | `postgres` |
| `PORTFOLIO_DB_PASSWORD` | `postgres_secure_pass` |

Production starts with Hibernate `ddl-auto: validate`; schema creation remains
the responsibility of `infrastructure/init.sql`. JDBC timestamps use UTC.

## Build and Test

Run from the repository root:

```powershell
.\gradlew.bat :portfolio-service:build
.\gradlew.bat :portfolio-service:bootRun
```

The integration suite uses H2 in PostgreSQL mode and covers:

- create, list, read, update, and delete;
- ticker normalization;
- cross-user collection and item isolation;
- missing and malformed trusted identity; and
- request validation against financial and timestamp constraints.

The H2 suite validates application behavior but does not replace a PostgreSQL
integration test of the production foreign key from `portfolios.user_id` to
`users.id`.
