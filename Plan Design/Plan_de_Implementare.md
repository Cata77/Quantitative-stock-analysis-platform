# Implementation Plan (Master-Plan) for the AI Programming Agent
NOTE FOR AI AGENT: This document represents the complete architectural specification and execution plan for building the hybrid financial ranking platform. Follow the phases and steps strictly in sequence. Do not deviate from the technologies or versions specified below.

## 1. Agent Status File (STATUS.md)
Instruction for Agent: Create this file in the root of the project before starting the implementation to track your status between runs.

## 2. Technological Decisions and Safe Versions (2026)

| Component | Technology / Version | Technical Justification |
| :--- | :--- | :--- |
| Backend Base Language | Java 25 (LTS) | Enables AOT compilation via Project Leyden and resolves virtual thread pinning (JEP 491) in DB and Kafka calls. |
| Build System | Gradle 9.1+ | Full native support for Java 25 and multi-module dependency management. |
| Java Framework | Spring Boot 3.4+ / Spring Cloud | Stable support for Java 25, out-of-the-box integration with virtual threads, and Spring WebFlux/Gateway. |
| Temporal Database | TimescaleDB 2.26+ (PG 18.3) | Enables temporal optimizations via Hypertables, Point-in-Time indexing, and UUIDv7 support. |
| Relational Database | PostgreSQL 18.3 (standard) | Manages user data and portfolios in classic transactional mode. |
| Message Broker | Apache Kafka (Confluent Platform) | Enables total decoupling of data ingestion from asynchronous score calculation. |
| Search Engine | Elasticsearch 8.11.0 | Indexes company metadata for instant searches in Frontend. |
| Research Language | Python 3.11+ | Full compatibility with the scientific computing suite (Polars, Vectorbt, SciPy). |

## 3. Phase 1: Infrastructure Configuration (docker-compose.yml)
Create the `docker-compose.yml` file in the root directory to start and coordinate all auxiliary services:
(YAML code provided in Plan_de_Implementare.md)

## 4. Phase 2: SQL Schema & TimescaleDB Hypertables
Run the following database initialization script. It defines transactional tables for users and automatically partitioned temporal tables (Hypertables) for massive write and Point-in-Time read performance:
(SQL code provided in Plan_de_Implementare.md)

## 5. Phase 3: Microservice Specifications (Java 25 & Python)
### 1. Gateway Service (Java 25)
Role: Unique entry point for Frontend. Routes external requests to microservices, validates JWT tokens, and implements rate-limiting mechanisms.
Technologies: Spring Cloud Gateway, WebFlux, Spring Security, Netty.
Key files to generate: GatewayApplication.java, application.yml (routes to /auth/, /screener/, /portfolio/).

### 2. User Auth Service (Java 25)
Role: User registration, validation of credentials, and generation of secure JWT tokens.
Technologies: Spring Boot Security, JSON Web Token (jjwt), Spring Data JPA.
DB Interaction: Writes/reads from users table in PostgreSQL.

### 3. Portfolio Service (Java 25)
Role: Allows authenticated users (by extracting the ID from the JWT delivered by the Gateway) to define, modify, and delete financial assets in their personal portfolio.
Technologies: Spring Boot Web, Spring Data JPA.
DB Interaction: Reads and writes to portfolios table in PostgreSQL.

### 4. Market Data Producer (Java 25)
Role: Asynchronous service running in the background. Periodically downloads fundamental data and current prices and publishes them to Kafka.
Technologies: Spring Kafka, Spring WebClient, Jackson.
Execution Flow: Calls External Financial API, parses data in JSON format and publishes them to the `market-data-events` topic.
Golden Rule: Messages sent to Kafka must use the stock symbol (e.g., AAPL) as the partition key. This guarantees that all events for the same company reach the same partition in strict chronological order.

### 5. Scoring Service (Java 25)
Role: Asynchronous processing engine. Consumes events arrived from Kafka, applies mathematical formulas (established in Phase 4), and persists the results.
Technologies: Spring Kafka Consumer, Spring Cloud OpenFeign (optional).
Execution Flow: Annotated with @KafkaListener to fetch messages in real-time from `market-data-events`, executes ranking and Z-score calculation (see formulas below), DB Writing: Saves results directly into `factor_scores` temporal table in TimescaleDB, Elasticsearch Indexing: Sends a simple call to Elasticsearch to update company metadata and make the new company searchable in Screener instantly.

### 6. Screener Service (Java 25)
Role: Extremely fast, Read-Only REST service. Quickly returns user rankings.
Technologies: Spring Boot Web, Spring Data, Elasticsearch API Client.
Execution Flow: REST Endpoint `/screener/rankings`: Executes a fast SQL query on `factor_scores` table in TimescaleDB, fetching the pre-calculated ranking to avoid CPU consumption in real-time. REST Endpoint `/screener/search`: Queries Elasticsearch to search for companies by name or industry in real-time.

### 7. Python Research Engine (Python 3.11+)
Role: Financial research sandbox where you statistically validate if a formula has a real edge before being programmed into Scoring.
Technologies: Polars (fast in-memory data manipulation), Vectorbt (20-year backtesting engine), SciPy (linear algebra), hmmlearn (Markov models).
Research Flow: Downloads historical data from TimescaleDB via strict point-in-time queries (`as_of_date`) to prevent look-ahead bias. Runs backtest in Vectorbt penalizing each transaction with 5-10 bps (commissions and slippage). Applies SVD (`numpy.linalg.svd`) on the correlation matrix to ensure real factor diversification. Runs linear regressions with Newey-West corrections on excess returns series.
Manual Transposition: Once you have the perfect formula in Python, you manually write the same equation into the Scoring Service (Java) production code.

## 6. Phase 4: Mathematical Formula Specifications
The AI Agent must use exactly the following mathematical equations in the calculation code:
### 1. Cross-Sectional Z-Score (Calculated daily for each factor)
Each raw metric of a company must be standardized against the mean and standard deviation of the entire universe of companies on that day to be correctly added.

### 2. Final Composite Score (for Stocks)
The final score by which companies in the Screener are ordered is the arithmetic mean of the cross-sectional z-scores calculated for the 3 base factors.

### 3. Implied Volatility Rank (IVR - for Call Options)
If you process data from the options topic, the consumer in the Scoring Service will classify the options according to their current level of implied volatility relative to the extremes of the last 52 weeks.

## 7. Phase 5: Testing Guide, Quality Assurance and Defensive Gates
Instruction for Agent: Before considering the implementation complete, validate the following points in your pipeline:
- Thread Pinning Verification (Java 25 JEP 491): Run load testing on Scoring Service using a profiling utility or JDK Flight Recorder (JFR). Ensure that no I/O write operation on TimescaleDB or read from Kafka causes virtual thread pinning.
- Look-ahead Bias Prevention (Python): Verify historical data reading code in Python. It must use exclusively the SQL clause `as_of_date` based on the actual publication date of financial reports, completely excluding the use of calendar quarter-end data before they are officially public.
- Dead Letter Queue (DLQ) Setup: Configure a robust error handling strategy in Scoring Service. If a JSON message arrived from Kafka is malformed or has a negative price (invalid), it must be automatically directed to the `market-data-events-dlq` error topic for manual audit, avoiding blocking or entering the production consumer into an eternal crash-loop.
