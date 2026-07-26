# STATUS.md (AI Agent Tracker)

## Done

- [x] Read implementation plan and translate documentation to English.
- [x] Update Master Implementation Plan (`Plan Design/Implementation_plan.md`) with configurations, schemas, and formulas.
- [x] Initialize repository and define directory structure.
- [x] Phase 1: Configure Docker Compose environment (`infrastructure/docker-compose.yml`).
- [x] Phase 2: TimescaleDB initialization script and hypertables (`infrastructure/init.sql`).
- [x] Gateway service: direct routing, reactive JWT verification, trusted identity forwarding, CORS, and single-instance rate limiting.
- [x] Gateway service production runbook (`Documentation/GATEWAY-SERVICE.md`).
- [x] Auth service: registration, normalized usernames, BCrypt credentials, HS256 JWT issuance, validation, and integration tests.
- [x] Auth service production runbook (`Documentation/AUTH-SERVICE.md`).
- [x] Portfolio service: authenticated user-scoped CRUD, validation, ownership isolation, and integration tests.
- [x] Portfolio service production runbook (`Documentation/PORTFOLIO-SERVICE.md`).
- [x] Market data producer: Alpaca OHLCV, Alpha Vantage fundamentals, Tradier options, normalized Kafka events, and tests.
- [x] Market data producer production runbook (`Documentation/MARKET-DATA-PRODUCER.md`).

## In Progress

- [ ] Phase 3: Develop remaining Spring Boot microservices (Java 25).
- [ ] Phase 4: Develop Python Research Sandbox.

## Never Touch

- Do not modify Docker network configuration or database ports without user approval.
- Do not introduce real-time streaming frameworks (for example, Apache Spark) into production code.
