# Market Data Producer Runbook

## Scope

`market-data-producer` is the background ingestion edge of the quantitative
platform. It polls external APIs, converts provider-specific JSON into a stable
versioned event contract, and publishes each event to `market-data-events`.
The future scoring service consumes that topic; the producer does not calculate
factor scores or write TimescaleDB itself.

The service uses Java 25, Spring Boot 4.1, WebClient, Spring for Apache Kafka,
Jackson 3, virtual-thread scheduling, and a small WebFlux/Actuator server on
port `8084`.

Collection is disabled by default. This lets the application start for local
inspection without credentials or a Kafka broker. Enabling collection activates
credential validation and Kafka topic provisioning.

## Provider Strategy

| Data | Production adapter | Development/free-tier behavior |
|---|---|---|
| Latest and historical OHLCV | Alpaca Market Data API | Basic plan uses real-time IEX and historical data since 2016 |
| Fundamental ratios and company metadata | Alpha Vantage `OVERVIEW` | Free service covers most datasets but is limited to 25 requests/day |
| Option chains, open interest, IV, and Greeks | Tradier Brokerage API | Sandbox is delayed and limited to 60 market-data calls/minute |

Alpaca Basic covers only IEX for live equities, not the complete SIP market.
Tradier sandbox market data is delayed. These feeds are appropriate for
development and research plumbing, but production ranking claims must state
their actual coverage and licensing.

Yahoo Finance/yfinance is not used in the production path. It is an unofficial
integration and does not provide a stable provider contract suitable for this
service. The provider boundary leaves room for another licensed adapter later.

Official references:

- [Alpaca latest stock bar](https://docs.alpaca.markets/us/reference/stocklatestbarsingle-1)
- [Alpaca historical stock bars](https://docs.alpaca.markets/us/reference/stockbarsingle-1)
- [Alpaca market-data plans](https://docs.alpaca.markets/us/docs/about-market-data-api)
- [Alpha Vantage fundamentals](https://www.alphavantage.co/documentation/#fundamentals)
- [Tradier option chains](https://docs.tradier.com/reference/brokerage-api-markets-get-options-chains)
- [Tradier option quote fields](https://docs.tradier.com/docs/quotes)

## Collection Flows

### Latest bars

Every configured interval, the job requests Alpaca's latest one-minute bar for
each symbol and maps timestamp, OHLC, volume, VWAP, and trade count. An in-memory
timestamp guard suppresses repeated bars while the market is closed. The guard
resets on restart, so consumers should still treat source timestamp and event
identity as idempotency inputs.

### Historical backfill

When explicitly enabled, an application startup runner requests an ascending,
paginated Alpaca range ending 15 minutes before startup. It publishes every page
in provider order and follows `next_page_token` until exhausted. The default
lookback is 365 days with `1Day` bars.

Backfill is disabled by default because it can produce substantial API and
Kafka volume. It has no durable checkpoint yet; restarting with backfill enabled
replays the configured range.

### Fundamentals

The daily fundamentals job calls Alpha Vantage `OVERVIEW` once per symbol and
normalizes company metadata, reporting quarter, capitalization, revenue,
EBITDA, valuation ratios, margins, returns, growth, target price, and beta.
Provider `Note`, `Information`, and error payloads are surfaced as collection
failures rather than published as data.

The free 25-request daily allowance means the default daily schedule can cover
at most 25 configured symbols if no other Alpha Vantage calls share the key.

### Options

For each underlying, the options job:

1. requests Tradier's available expirations;
2. selects the nearest non-expired date;
3. requests that chain with `greeks=true`;
4. normalizes contract price/quote, volume, open interest, IV, and Greeks; and
5. omits contracts whose IV or required Greeks are absent.

Tradier sometimes returns one JSON object where it otherwise returns an array;
the adapter handles both representations. Missing last price falls back to the
bid/ask midpoint, or zero when neither quote side exists.

## Kafka Contract

The producer creates `market-data-events` with six partitions and one replica
by default. Every send uses:

```java
kafkaTemplate.send(topic, event.symbol(), event)
```

The underlying stock symbol is therefore always the Kafka key, including for
option contracts. Kafka keeps events for one underlying on one partition in
producer order, which is the platform's required ordering invariant.

Producer safety settings are:

- `acks=all`;
- idempotence enabled;
- effectively unbounded Kafka retries within delivery timeout;
- at most five in-flight requests per connection;
- Zstandard compression; and
- blocking send acknowledgement bounded by `MARKET_DATA_KAFKA_SEND_TIMEOUT`.

Scheduled work runs on Java virtual threads. WebClient remains the required HTTP
client, while its bounded `.block(...)` calls occur in scheduled/background
code rather than on Reactor Netty request event loops. Waiting for Kafka
acknowledgement makes provider order and delivery failures explicit.

### Event envelope

All records use schema version `1`:

```json
{
  "eventId": "11d8171d-896c-4809-a3d8-cf9acfe6488f",
  "schemaVersion": 1,
  "eventType": "STOCK_BAR",
  "symbol": "AAPL",
  "provider": "alpaca",
  "observedAt": "2026-07-24T19:59:00Z",
  "stockBar": {
    "time": "2026-07-24T19:59:00Z",
    "open": 213.10,
    "high": 213.40,
    "low": 213.00,
    "close": 213.30,
    "volume": 12450,
    "volumeWeightedAveragePrice": 213.22,
    "tradeCount": 321
  },
  "fundamentals": null,
  "option": null
}
```

`eventType` is one of `STOCK_BAR`, `FUNDAMENTAL_SNAPSHOT`, or
`OPTION_SNAPSHOT`; exactly one matching payload is non-null. Java class type
headers are disabled so consumers depend on this explicit schema rather than
producer implementation class names.

Prices are validated as positive for stock bars and non-negative for option
quotes. Negative volume/open interest and malformed provider dates/numbers are
rejected before publishing.

## Failure Behavior

One symbol or provider failure is logged and does not prevent later symbols in
the same collection run. The next schedule retries naturally. Provider calls
have a hard timeout and do not perform application-level retries, avoiding
retry storms against free-tier rate limits. Kafka handles bounded delivery
retries under its delivery timeout.

Malformed events arriving at the future scoring consumer belong in
`market-data-events-dlq`, as required by the platform plan. The producer does
not own that consumer-side DLQ.

## Configuration

Core variables:

| Variable | Default |
|---|---|
| `MARKET_DATA_PORT` | `8084` |
| `MARKET_DATA_ENABLED` | `false` |
| `MARKET_DATA_SYMBOLS` | `AAPL,MSFT` |
| `MARKET_DATA_TOPIC` | `market-data-events` |
| `MARKET_DATA_TOPIC_PARTITIONS` | `6` |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` |
| `MARKET_DATA_PROVIDER_TIMEOUT` | `10s` |
| `MARKET_DATA_KAFKA_SEND_TIMEOUT` | `15s` |

Feature switches:

| Variable | Default |
|---|---|
| `MARKET_DATA_LATEST_BARS_ENABLED` | `true` |
| `MARKET_DATA_BACKFILL_ENABLED` | `false` |
| `MARKET_DATA_FUNDAMENTALS_ENABLED` | `false` |
| `MARKET_DATA_OPTIONS_ENABLED` | `false` |

Credentials:

| Provider | Required variables |
|---|---|
| Alpaca | `ALPACA_API_KEY_ID`, `ALPACA_API_SECRET_KEY` |
| Alpha Vantage | `ALPHA_VANTAGE_API_KEY` when fundamentals are enabled |
| Tradier | `TRADIER_API_TOKEN` when options are enabled |

Tradier defaults to `https://sandbox.tradier.com/v1`. A production token must be
paired with `TRADIER_BASE_URL=https://api.tradier.com/v1`; sandbox and
production tokens are not interchangeable.

Never commit populated provider credentials. Use deployment secrets or local
process environment variables.

## Local Run

Start Kafka from the existing infrastructure configuration, set credentials,
then enable only the feeds you intend to exercise:

```powershell
$env:ALPACA_API_KEY_ID = "<key-id>"
$env:ALPACA_API_SECRET_KEY = "<secret-key>"
$env:MARKET_DATA_ENABLED = "true"
$env:MARKET_DATA_SYMBOLS = "AAPL,MSFT"
.\gradlew.bat :market-data-producer:bootRun
```

Health is exposed at `http://localhost:8084/actuator/health`. The gateway does
not route this internal service.

To add daily fundamentals:

```powershell
$env:ALPHA_VANTAGE_API_KEY = "<api-key>"
$env:MARKET_DATA_FUNDAMENTALS_ENABLED = "true"
```

To add delayed sandbox options:

```powershell
$env:TRADIER_API_TOKEN = "<sandbox-token>"
$env:MARKET_DATA_OPTIONS_ENABLED = "true"
```

## Build and Tests

Run from the repository root:

```powershell
.\gradlew.bat :market-data-producer:test
.\gradlew.bat :market-data-producer:build
```

The focused suite covers provider authentication and URL construction, Alpaca
pagination and bar normalization, Alpha Vantage error payloads, Tradier
single/array option shapes, incomplete-Greeks filtering, stable Jackson JSON,
Kafka symbol keys and broker failures, duplicate-bar suppression, per-symbol
failure isolation, and application startup with collection disabled.

The tests use mocked HTTP exchanges and KafkaTemplate calls. A real provider
credential smoke test and an embedded/real Kafka integration test remain
deployment gates; they are intentionally not run in the ordinary unit suite.
