# Fraud Detection Service

An event-driven fraud detection pipeline built with **Java 25** and **Spring Boot 4.1**. Consumes transaction events from Kafka, evaluates them against a set of fraud rules, scores each transaction by severity, and persists results to PostgreSQL. Flagged transactions are surfaced via a REST API.

---

## Architecture

```
Kafka Topic (transactions)
         │
         ▼
 Spring Boot Consumer
         │
         ▼
 Fraud Rule Engine  ◄──── Strategy Pattern
  ├── VelocityRule          (Redis sorted sets)
  ├── HighAmountRule        (stateless threshold)
  ├── GeoAnomalyRule        (Redis — location delta)
  ├── ImpossibleTravelRule  (Redis — distance/time)
  └── DuplicateRule         (Redis SET NX)
         │
         ▼
 Severity Score Aggregator
         │
    ┌────┴──────────────┐
    ▼                   ▼
 Redis               PostgreSQL
(velocity windows,  (transactions +
 dup detection,      fraud flags —
 idempotency,        system of record)
 geo state)
                         │
                         ▼
                   REST API
                   GET /api/v1/flags
```

---

## Design Decisions & Trade-offs

### Kafka over RabbitMQ

Kafka was chosen over RabbitMQ for two reasons specific to this use case.

First, **replayability**. Kafka retains messages on disk for a configurable period. If a bug in the rule engine produces incorrect flags, the topic can be replayed from any offset after a fix is deployed — impossible with RabbitMQ's queue-and-discard model.

Second, **partition-based scaling**. Each Kafka partition is consumed by exactly one consumer thread within a group. Adding app replicas increases throughput proportionally up to the partition count, with no coordination overhead. RabbitMQ can distribute messages across consumers but requires careful prefetch tuning to avoid uneven load.

The trade-off: Kafka requires Zookeeper (or KRaft in newer versions), has higher operational complexity, and is overkill if the event volume is low and replayability isn't a concern.

### Redis for velocity windows and duplicate detection over PostgreSQL

Velocity checks and duplicate detection require sub-millisecond state reads on the hot path — once per rule, per transaction. Doing this in PostgreSQL would mean a window query with `WHERE timestamp > now() - interval '5 minutes'` on every incoming message, under write load from the same service.

Redis sorted sets (`ZADD` + `ZCOUNT`) and `SET NX` are atomic, O(log N), and operate entirely in memory. For ephemeral, time-bounded state that doesn't need durability, Redis is the right tool. PostgreSQL remains the system of record for anything that needs to survive a Redis restart.

The trade-off: Redis state is lost on failure without persistence configured. For this use case that's acceptable — a restarted Redis means the velocity window resets, not that data is corrupted.

**Exact match over Bloom filter for duplicate detection** — duplicate detection uses Redis `SET NX` with a per-key TTL rather than a Bloom filter. A Bloom filter would use less memory at extreme scale but has no concept of per-entry expiry — approximating a sliding time window requires rotating filters, adding complexity for no measurable benefit at this scale. `SET NX` is exact, atomic, and the TTL handles window expiry natively.

### Strategy Pattern for fraud rules

Each fraud rule implements a single `FraudRule` interface with one method: `evaluate(TransactionEvent) → RuleEvaluationResult`. The engine holds a `List<FraudRule>` injected by Spring and iterates it without knowing what the rules do.

This means adding a rule is a single file change — create a class, annotate `@Component`, done. No modifications to the engine, service, or any existing rule. Removing or disabling a rule is equally contained.

The alternative — a single class with all rules as methods — is simpler initially but becomes hard to test in isolation and creates merge conflicts when multiple rules are being developed simultaneously.

### Liquibase over Flyway

Both tools solve the same problem. Liquibase was chosen because:

- Changesets are XML (or YAML/JSON/SQL), which allows conditional logic, rollback definitions, and preconditions that Flyway's plain SQL migrations don't support natively.
- Liquibase supports `rollback` blocks per changeset, which matters when a bad migration needs reversing without a full restore.
- The `validate` ddl-auto mode in Hibernate is used alongside Liquibase — Hibernate checks the schema matches the entities at startup but never modifies it. This prevents the common failure mode where Hibernate silently drops or alters columns in non-development environments.

The trade-off: Liquibase XML is more verbose than Flyway SQL files. For a project with simple migrations and no rollback requirements, Flyway is a simpler alternative — but the rollback support and precondition logic were the deciding factors here.

### PostgreSQL over MySQL

Both are viable relational databases for this use case, but PostgreSQL was the better fit for two concrete reasons rooted in this schema specifically.

First, **timezone-aware timestamps**. Every timestamp in the schema — `transactions.timestamp`, `transactions.processed_at`, `fraud_flags.created_at` — uses `TIMESTAMPTZ`, which stores values in UTC and converts correctly on read regardless of the server's local timezone. MySQL's equivalent is `TIMESTAMP`, which does convert to UTC internally, but MySQL users frequently reach for `DATETIME` instead, which stores whatever value it receives with no timezone context at all. For a fraud detection system where the ordering of events across timezones is a correctness concern — particularly for the impossible travel rule, which computes time deltas between consecutive transactions — silent timezone errors are unacceptable.

Second, **the Liquibase migrations use `TIMESTAMPTZ` directly**. That type doesn't exist in MySQL. Using MySQL would require either dialect-specific changeset conditions or falling back to `DATETIME`, which introduces the timezone problem described above. With PostgreSQL, the migration files are unambiguous and portable across environments without workarounds.

Additionally, PostgreSQL's query planner handles the `GROUP BY` across two dimensions in the stats endpoint cleanly, and its `NUMERIC(19,4)` arithmetic for currency amounts is consistent across aggregate functions — areas where MySQL has historically had configuration-dependent edge cases.

### GeoAnomalyRule and ImpossibleTravelRule as complementary signals

These two rules answer different questions from the same underlying data.

**GeoAnomalyRule** asks: *did this user transact somewhere unusual?* It fires when the distance between the previous and current transaction location exceeds 500km. Score: 15 (LOW). A legitimate traveller will trigger this, so the weight is intentionally low.

**ImpossibleTravelRule** asks: *could this user physically be in both places?* It computes the implied speed between consecutive transactions. A Cape Town → Johannesburg transaction 3 hours apart implies ~423 km/h — plausible by air, rule does not fire. Cape Town → London 1 hour apart implies ~9,700 km/h — physically impossible, rule fires. Score: 60 (HIGH alone).

Together, the scores compound:

| Scenario | Rules triggered | Score | Severity |
|----------|----------------|-------|----------|
| Cape Town → Johannesburg, 3hrs | GeoAnomaly only | 15 | LOW |
| Cape Town → London, 1hr | GeoAnomaly + ImpossibleTravel | 75 | HIGH |
| Cape Town → London, 1hr + high amount | All three | 105 | CRITICAL |

ImpossibleTravelRule runs before GeoAnomalyRule (enforced via `@Order`). GeoAnomalyRule immediately overwrites the stored location with the current transaction's coordinates as its first step. ImpossibleTravelRule must therefore read the previous snapshot first — otherwise it would compare the current transaction against itself, producing a distance of zero and a speed of zero. GeoAnomalyRule runs second: it reads the same previous snapshot for its own distance check, then writes the current coordinates so the next transaction has a valid baseline. Both rules read the *previous* snapshot; only GeoAnomalyRule writes the updated one.

---

## Fraud Rules

All rules are evaluated for every transaction. Scores are summed to determine overall severity.

| Rule | Trigger | Score |
|------|---------|-------|
| **Velocity Check** | >10 transactions for the same user in 5 minutes | 40 |
| **High Amount** | Single transaction > $10,000 | 30 |
| **Duplicate Detection** | Same user + merchant + amount within 2 minutes | 25 |
| **Geo Anomaly** | Location >500km from last known location | 15 |
| **Impossible Travel** | Implied speed between consecutive locations exceeds 1,200 km/h | 60 |

**Severity bands:**

| Score | Severity |
|-------|----------|
| 0 | NONE (not flagged) |
| 1–30 | LOW |
| 31–55 | MEDIUM |
| 56–80 | HIGH |
| 81+ | CRITICAL |

---

## Prerequisites

- [Docker](https://docs.docker.com/get-docker/) and [Docker Compose](https://docs.docker.com/compose/install/) (v2+)
- Java 25 and Maven 3.9+ (only needed to run tests locally without Docker)

---

## Quick Start

### 1. Clone and build

```bash
git clone https://github.com/ijaaz9/fraud-engine
cd fraud-engine
```

### 2. Start all services

```bash
docker-compose up --build
```

This starts Zookeeper, Kafka, PostgreSQL, Redis, and the application. Liquibase runs automatically on startup and applies the database schema.

Wait for the app to report healthy:
```
fraud-app  | Started FraudDetectionEngineApplication in X.XXX seconds
```

Or poll the health endpoint:
```bash
curl http://localhost:8080/actuator/health
```

### 3. Send a test transaction

```bash
# Enter the Kafka container
docker exec -it fraud-kafka bash

# Produce a message
kafka-console-producer \
  --bootstrap-server localhost:29092 \
  --topic transactions
```

Paste this JSON (press Enter, then Ctrl+C):

```json
{"transactionId":"txn-test-001","userId":"user-001","amount":15000.00,"merchant":"ACME Corp","category":"ELECTRONICS","latitude":51.5074,"longitude":-0.1278,"timestamp":"2024-03-15T10:30:00Z"}
```

### 4. Query the API

```bash
# List all fraud flags (paginated)
curl "http://localhost:8080/api/v1/flags?page=0&size=20"

# Get flags for a specific transaction
curl "http://localhost:8080/api/v1/flags/transactions/txn-test-001/flags"

# Get fraud statistics for a time range
curl "http://localhost:8080/api/v1/flags/stats?from=2024-01-01T00:00:00Z&to=2024-12-31T23:59:59Z"

# Filter by merchant category
curl "http://localhost:8080/api/v1/flags/stats?from=2024-01-01T00:00:00Z&to=2024-12-31T23:59:59Z&category=ELECTRONICS"
```

### 5. Demo mode

```bash
# Make the demo script executable
chmod +x demo.sh

# Run the demo script
./demo.sh
```

---

## REST API Reference

### `GET /api/v1/flags`

Paginated list of fraud flags, sorted by creation time descending.

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `status` | `OPEN` \| `REVIEWED` | No | (all) | Filter by flag status |
| `page` | integer | No | `0` | Zero-based page number |
| `size` | integer | No | `20` | Results per page (max 100) |

**Example response:**
```json
{
  "content": [
    {
      "flagId": 1,
      "transactionId": "txn-test-001",
      "userId": "user-001",
      "amount": 15000.00,
      "merchant": "ACME Corp",
      "category": "ELECTRONICS",
      "ruleTriggered": "HIGH_AMOUNT",
      "severity": "HIGH",
      "score": 30,
      "detail": "Transaction amount 15000.00 exceeds high-value threshold of 10000.00",
      "status": "OPEN",
      "createdAt": "2024-03-15T10:30:01Z"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1,
  "last": true
}
```

---

### `GET /api/v1/flags/transactions/{transactionId}/flags`

All fraud flags for a specific transaction, with full transaction detail.

**Response:** `200 OK` or `404 Not Found`.

```json
{
  "transactionId": "txn-test-001",
  "userId": "user-001",
  "amount": 15000.00,
  "merchant": "ACME Corp",
  "category": "ELECTRONICS",
  "latitude": 51.5074,
  "longitude": -0.1278,
  "timestamp": "2024-03-15T10:30:00Z",
  "processedAt": "2024-03-15T10:30:01Z",
  "overallSeverity": "HIGH",
  "totalScore": 30,
  "flags": [
    {
      "flagId": 1,
      "ruleTriggered": "HIGH_AMOUNT",
      "severity": "HIGH",
      "score": 30,
      "detail": "Transaction amount 15000.00 exceeds high-value threshold of 10000.00",
      "status": "OPEN",
      "createdAt": "2024-03-15T10:30:01Z"
    }
  ]
}
```

---

### `GET /api/v1/flags/stats`

Aggregated fraud statistics for a time range and optional category.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `from` | ISO-8601 UTC | Yes | Start of reporting period |
| `to` | ISO-8601 UTC | Yes | End of reporting period |
| `category` | string | No | Filter by merchant category |

```json
{
  "from": "2024-03-01T00:00:00Z",
  "to": "2024-03-31T23:59:59Z",
  "category": "ELECTRONICS",
  "totalFlags": 42,
  "byRule": {
    "HIGH_AMOUNT": 20,
    "VELOCITY_CHECK": 15,
    "IMPOSSIBLE_TRAVEL": 7
  },
  "bySeverity": {
    "LOW": 5,
    "MEDIUM": 15,
    "HIGH": 18,
    "CRITICAL": 4
  }
}
```

---

## Configuration

Rule thresholds are externalised in `application.yml` and can be overridden at runtime:

```yaml
fraud:
  idempotency:
    ttl-hours: 24           # Idempotency key TTL — suppresses duplicate Kafka deliveries

  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
    group-id: fraud-detection-group
    concurrency: 3          # Consumer threads — should not exceed topic partition count
    topic:
      transactions: transactions

  rules:
    velocity:
      threshold: 10           # Max transactions per user in the window
      window-seconds: 300     # Rolling window (5 minutes)
      score: 40

    high-amount:
      threshold: 10000
      score: 30

    geo-anomaly:
      distance-threshold-km: 500
      location-ttl-hours: 24
      score: 15

    impossible-travel:
      max-speed-kmh: 1200     # Tune down to ~900 for stricter detection
      min-elapsed-seconds: 60 # Guards against GPS noise on near-simultaneous transactions
      score: 60

    duplicate:
      window-seconds: 120
      score: 25
```

> **Note:** The Kafka consumer/producer are wired manually in `KafkaConfig` and
> bind the application-owned `fraud.kafka.*` keys above — not `spring.kafka.*`.

---

## Metrics

Exposed via `/actuator/prometheus` and `/actuator/metrics`:

| Metric | Type | Description |
|--------|------|-------------|
| `fraud.transactions.processed.total` | Counter | Every transaction processed, regardless of outcome |
| `fraud.flags.generated.total{severity}` | Counter | Fraudulent transactions detected, tagged by severity |
| `fraud.rules.triggered.total{rule}` | Counter | Per-rule trigger count — most useful for tuning thresholds |
| `fraud.critical.flags.total` | Counter | CRITICAL severity flags — useful for simple alerting rules |
| `fraud.processing.latency.ms` | Timer | End-to-end latency from consumer receipt to DB write |

---

## Running Tests

### Unit tests only (no Docker required)

```bash
mvn test -Dtest="!FraudDetectionIntegrationTest"
```

### All tests including integration (requires Docker)

```bash
mvn verify
```

Testcontainers pulls and starts Kafka, PostgreSQL, and Redis automatically.

---

## Project Structure

```
src/
├── main/java/com/fraud/detection/
│   ├── FraudDetectionEngineApplication.java
│   ├── api/
│   │   ├── controller/FraudFlagController.java
│   │   └── dto/FraudFlagDto.java
│   ├── config/
│   │   ├── AppConfig.java
│   │   ├── KafkaConfig.java
│   │   └── RedisConfig.java
│   ├── consumer/
│   │   └── TransactionKafkaConsumer.java
│   ├── exception/
│   │   ├── GlobalExceptionHandler.java
│   │   └── TransactionNotFoundException.java
│   ├── model/
│   │   ├── entity/          # Transaction, FraudFlag
│   │   ├── enums/           # FraudRuleType, Severity, FlagStatus
│   │   └── event/           # TransactionEvent (Kafka DTO)
│   ├── repository/
│   │   ├── postgres/        # JPA repositories
│   │   └── redis/           # Store interfaces + Redis implementations
│   ├── rules/
│   │   ├── engine/          # FraudRule interface, FraudRuleEngine, RuleEvaluationResult
│   │   └── impl/            # VelocityRule, HighAmountRule, GeoAnomalyRule,
│   │                        # ImpossibleTravelRule, DuplicateTransactionRule
│   ├── scoring/             # SeverityScoreAggregator, ScoreAggregation
│   └── service/             # FraudDetectionService, FraudFlagQueryService,
│                            # FraudMetricsService
└── main/resources/
    ├── application.yml
    └── db/changelog/        # Liquibase migrations
```

---

## Observability

| Endpoint | Description |
|----------|-------------|
| `GET /actuator/health` | Health status of app, DB, and Redis |
| `GET /actuator/metrics` | JVM, HTTP, and fraud-specific metrics |
| `GET /actuator/prometheus` | Prometheus scrape endpoint |

---

## Adding a New Fraud Rule

1. Add a value to `FraudRuleType` enum.
2. Create a class implementing `FraudRule`, annotate `@Component`.
3. If the rule depends on order relative to another rule, add `@Order`.
4. Add configuration under `fraud.rules.*` in `application.yml`.
5. Write a unit test.

No changes to `FraudRuleEngine`, `FraudDetectionService`, or any other existing class.

---

## Potential Improvements

| # | Improvement | Area | Impact |
|---|-------------|------|--------|
| 1 | **Schema Registry (Avro/Protobuf)** — replace raw JSON on the Kafka topic with a schema-enforced format registered in Confluent Schema Registry. Producers and consumers negotiate compatibility automatically; breaking schema changes are caught at publish time, not at consumer deserialization. | Reliability | Eliminates the current failure mode where a malformed or schema-changed message silently routes to the DLT with no visibility into what changed. |
| 2 | **API Authentication** — add Spring Security with JWT or API key validation on all `/api/v1/*` endpoints. Currently the API is open. | Security | Prevents unauthenticated access to fraud flag data, which may contain PII (userId, location, merchant). Required before any real deployment. |
| 3 | **Actuator endpoint protection** — restrict `/actuator/*` to internal networks or require credentials. Currently `/actuator/prometheus` and `/actuator/metrics` are publicly reachable. | Security | Metrics can reveal internal behaviour (transaction volumes, flag rates) useful to an attacker probing the system. |
| 4 | **Dead letter topic monitoring** — add a structured handler for `transactions-dlt` that persists failed messages to a `dead_letter_events` table and emits an alert (e.g. via a `fraud.dlt.messages.total` counter). Currently DLT messages are only logged at ERROR. | Operability | DLT messages are currently invisible to operators unless they actively watch logs. A counter makes them alertable in any metrics-based system. |
| 5 | **`PATCH /api/v1/flags/{flagId}/status`** — expose an endpoint to transition a flag from `OPEN` to `REVIEWED`. Currently flags can only be read, not actioned through the API. | Completeness | Without this, analyst workflow requires direct database access to close flags, which is not tenable in practice. |
| 6 | **Redis persistence** — enable Redis AOF (append-only file) persistence in the Docker Compose configuration. Currently a Redis restart resets all velocity windows, dedup markers, and geo-location state. | Reliability | On restart, the velocity window resets to zero for all users, creating a brief window where the velocity rule cannot fire. AOF persistence survives restarts with minimal performance cost. |
| 7 | **Atomic Redis velocity operations via Lua script** — the current `VelocityRule` performs three separate Redis commands (`ZADD`, `ZREMRANGEBYSCORE`, `ZCOUNT`) sequentially. A Lua script executes all three atomically in a single round-trip. | Correctness | Eliminates a theoretical race condition where a concurrent thread reads the count between the add and the prune, producing a count that includes expired entries. Low probability under normal load but non-zero. |
| 8 | **Idempotency key written post-commit** — the Redis idempotency key is currently set before the DB transaction commits. If the DB write fails after Redis is written, the transaction is permanently marked as processed but never actually saved. | Correctness | Use `TransactionSynchronizationManager.registerSynchronization()` to set the Redis key in the `afterCommit()` callback, ensuring the key is only written if the DB transaction succeeds. |
| 9 | **Separate count query on `FraudFlagRepository.findAllWithTransaction`** — Spring Data JPA cannot apply SQL-level `LIMIT`/`OFFSET` when a `JOIN FETCH` is present; it falls back to in-memory pagination and logs `HHH90003004`. A separate `countQuery` attribute resolves this. | Performance | Eliminates in-memory pagination, which loads all matching rows into the JVM before slicing. Under high flag volumes this becomes a memory and latency problem. |
| 10 | **Rule score and threshold configuration via database** — move fraud rule thresholds and scores from `application.yml` into a `rule_config` table managed via a small admin API. Changes take effect without redeployment. | Operability | Threshold tuning currently requires a config change, rebuild, and redeploy. A database-backed config allows analysts to adjust sensitivity in real time in response to emerging fraud patterns. |
| 11 | **Kafka KRaft mode** — replace Zookeeper with Kafka's built-in KRaft consensus (available since Kafka 3.3, production-ready since 3.7). Removes one container from the stack. | Simplicity | Reduces infrastructure footprint and eliminates a known operational pain point — Zookeeper is a separate system to monitor, patch, and back up. |
| 12 | **Structured correlation ID logging** — propagate `transactionId` as an MDC (Mapped Diagnostic Context) field through all log statements from consumer receipt to DB write. | Operability | Currently `transactionId` appears in individual log messages but is not a structured field. With MDC, log aggregation tools (Datadog, Splunk) can filter all log lines for a specific transaction in a single query. |