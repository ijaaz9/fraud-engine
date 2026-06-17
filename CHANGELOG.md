# Changelog

All notable changes to this project are documented here.

Format follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

---

## [1.1.0] — 2026-06-17

### Added

- **`ImpossibleTravelRule`** — evaluates implied travel speed between consecutive
  transaction locations. Fires when speed exceeds 1,200 km/h (configurable).
  Score: 60. Complements `GeoAnomalyRule` by adding a time dimension to location
  checks — a Cape Town → London transaction in 1 hour triggers both rules and
  compounds to HIGH severity.

- **`IMPOSSIBLE_TRAVEL`** added to `FraudRuleType` enum.

- **`FraudMetricsService`** — centralised Micrometer instrumentation with five
  metrics pre-registered at startup:
    - `fraud.transactions.processed.total` — pipeline throughput counter
    - `fraud.flags.generated.total{severity}` — fraud detections tagged by severity
    - `fraud.rules.triggered.total{rule}` — per-rule trigger counts
    - `fraud.critical.flags.total` — dedicated CRITICAL counter for alerting
    - `fraud.processing.latency.ms` — end-to-end processing timer

- **`ImpossibleTravelRuleTest`** — seven unit tests covering: no prior location,
  elapsed time below minimum, out-of-order event delivery, realistic air travel
  (Cape Town → Johannesburg, 3hrs), impossible travel (Cape Town → London, 1hr),
  long-haul flight (Cape Town → London, 13hrs), and same-location transactions.

- **Design trade-offs section** added to README covering: Kafka vs RabbitMQ,
  Redis vs PostgreSQL for windowed state, Strategy Pattern rationale, Liquibase
  vs Flyway, and the GeoAnomaly + ImpossibleTravel complementary signal design.

- **Potential improvements section** added to README.

- **`KafkaConsumerProperties`** — typed `@ConfigurationProperties(prefix = "fraud.kafka")`
  binding for `bootstrapServers`, `groupId`, and `concurrency`, replacing three
  `@Value` fields in `KafkaConfig`.

- **`IdempotencyProperties`** — typed `@ConfigurationProperties(prefix = "fraud.idempotency")`
  binding for `ttlHours`, replacing the `@Value` idempotency TTL field in
  `FraudDetectionService`.

- **`CHANGELOG.md`** (this file).

### Changed

- **`GeoAnomalyRule` rescored** from 35 to 15. Rule now represents a location
  anomaly signal rather than a strong fraud indicator on its own. Legitimate
  travellers triggering this rule alone produce LOW severity, which is the
  correct behaviour.

- **`GeoLocationStore`** interface extended — `updateLocation` now accepts a
  `timestampMillis` parameter alongside coordinates. The stored Redis value
  changes from `lat|lon` to `lat|lon|timestampMillis`, enabling `ImpossibleTravelRule`
  to read both location and timestamp in a single round-trip.

- **`RedisGeoLocationStore`** updated to store and parse the three-field value.
  Includes a field-count guard to handle any stale two-field values gracefully.

- **`GeoAnomalyRule`** annotated `@Order(1)` — must run before `ImpossibleTravelRule`
  because it writes the updated location snapshot that `ImpossibleTravelRule` reads.

- **`ImpossibleTravelRule`** annotated `@Order(2)`.

- **`GeoAnomalyRuleTest`** updated for the three-element Redis array, updated
  `updateLocation` signature, and rescored assertions (35 → 15).

- **`SeverityScoreAggregatorTest`** — replaced single four-rule CRITICAL scenario
  with scenario-based tests aligned to the new score table:
    - Impossible travel alone → HIGH (60)
    - Geo anomaly alone → LOW (15)
    - Geo anomaly + impossible travel → HIGH (75)
    - Velocity + impossible travel → CRITICAL (100)

- **`application.yml`** — `geo-anomaly.score` updated to 15; `impossible-travel`
  configuration block added.

- **`application-test.yml`** — same score corrections and `impossible-travel` block.

- **README** — `--broker-list` corrected to `--bootstrap-server` (deprecated in
  Kafka 3.x, removed in later versions). Rules table and metrics section updated.
  "Production-grade / production-ready" language removed.

- **`FraudDetectionService`** — `FraudMetricsService` injected; latency measured
  from start of `process()` to after DB write completion; metrics recorded after
  the DB transaction commits.

- **`KafkaConfig`** extended with `ProducerFactory`, `KafkaTemplate`, and
  `KafkaAdmin` beans. `@RetryableTopic` requires a `KafkaTemplate` to forward
  messages to retry and dead-letter topics; without these beans the consumer
  container could not start because Spring Boot Kafka auto-configuration is not
  on the classpath.

### Fixed

- **`AckMode.MANUAL` → `MANUAL_IMMEDIATE`** in `KafkaConfig`. `@RetryableTopic`
  requires `MANUAL_IMMEDIATE`; using `MANUAL` caused retry topic offset commits to
  conflict with retry routing, leading to messages being sent to the DLT after one
  attempt instead of four.

- **`@EnableRetry` missing** from `KafkaConfig`. Without it `@RetryableTopic` is
  silently ignored — failed messages went straight to the DLT with no retries.

- **`TRUSTED_PACKAGES` hardcoded string** in `KafkaConfig` changed to `"*"`.
  The previous value `"com.fraud.detection.model.event"` would silently break
  deserialisation on any package rename without a compile-time error.

- **Broken SLF4J format string** in `GeoAnomalyRule`. The placeholder `{:.1f}`
  is Python/C syntax and is not valid in SLF4J — it was logged literally rather
  than formatting the distance value. Fixed by pre-formatting with
  `String.format("%.1f", distanceKm)` and using a standard `{}` placeholder.

- **`@Index` annotations removed** from `Transaction` and `FraudFlag` entities.
  Both entities previously declared Hibernate `@Index` annotations alongside
  Liquibase changesets that created the same indexes. This created a split-brain
  between the two schema owners. Liquibase exclusively owns schema and index
  creation; Hibernate is set to `ddl-auto: validate` only.

- **`Thread.sleep(2000)`** in `FraudDetectionIntegrationTest` idempotency test
  replaced with an Awaitility `await().untilAsserted(...)` poll. The raw sleep
  was inconsistent with the rest of the test suite's use of Awaitility and
  introduced an arbitrary 2-second delay regardless of actual consumer speed.

- **Redis state not cleared between integration tests** — `@AfterEach` in
  `FraudDetectionIntegrationTest` previously only deleted PostgreSQL rows.
  Velocity windows, geo-location snapshots, idempotency keys, and dedup markers
  persisted between tests, causing state bleed. All Redis keys are now flushed
  after each test.

- **`TransactionEvent` missing constructors** — `@NoArgsConstructor` and
  `@AllArgsConstructor` added. Jackson requires a no-arg constructor to
  deserialise inbound Kafka messages; without them the deserialiser threw
  `InvalidDefinitionException` on every message consumed from the topic.

- **`FraudFlagRepository` `@Param` import** — `io.lettuce.core.dynamic.annotation.Param`
  replaced with `org.springframework.data.repository.query.Param`. The Lettuce
  annotation is not processed by Spring Data JPA; all custom `@Query` methods
  silently received `null` for bound parameters, returning empty results with
  no error logged.

- **`pom.xml`** — bare `org.liquibase:liquibase-core` replaced with
  `spring-boot-starter-liquibase`. Spring Boot 4.1 moved `LiquibaseAutoConfiguration`
  into this dedicated starter; without it `spring.liquibase.*` was silently
  ignored and migrations never ran, causing a schema validation failure on startup.

- **`application.yml` logging package** — `com.frauddetection` corrected to
  `com.fraud.detection`. The typo prevented the application's `DEBUG`-level
  logging from activating.

- **README** — by-transaction API endpoint corrected
  (`/api/v1/flags/{transactionId}` → `/api/v1/flags/transactions/{transactionId}/flags`);
  `geo-anomaly` config key corrected; base package and main class references
  updated from the retired `za.co.capitec.frauddetection` namespace; `fraud.kafka.*`
  and `fraud.idempotency.*` configuration sections added.

---

## [1.0.0] — 2026-06-10

### Added

- Initial implementation.

- **Kafka consumer** (`TransactionKafkaConsumer`) with `@RetryableTopic` (4
  attempts, exponential backoff) and dead letter topic (`transactions-dlt`).
  Manual offset acknowledgement via `AckMode.MANUAL`.

- **`ErrorHandlingDeserializer`** — wraps the `JacksonJsonDeserializer` in
  `KafkaConfig`; malformed or undeserialisable messages are forwarded to
  `transactions-dlt` rather than blocking the consumer partition.

- **Fraud rule engine** (`FraudRuleEngine`) using the Strategy pattern.
  All `FraudRule` implementations are discovered via Spring component scan —
  no explicit registration required.

- **Four fraud rules**:
    - `VelocityRule` — Redis sorted set, rolling 5-minute window, score 40
    - `HighAmountRule` — stateless threshold check, score 30
    - `GeoAnomalyRule` — Redis last-known-location, Haversine distance, score 35
    - `DuplicateTransactionRule` — Redis SET NX, 2-minute window, score 25

- **`SeverityScoreAggregator`** — sums triggered rule scores, maps to severity
  bands: NONE / LOW (1–30) / MEDIUM (31–55) / HIGH (56–80) / CRITICAL (81+).

- **`FraudDetectionService`** — orchestrates idempotency check, rule evaluation,
  score aggregation, and persistence. Wrapped in a single `@Transactional`
  boundary so transaction and flag rows are committed atomically.

- **Redis idempotency guard** — `SET NX` with 24-hour TTL prevents duplicate
  processing of redelivered Kafka messages.

- **PostgreSQL persistence** — `transactions` and `fraud_flags` tables.
  One `FraudFlag` row per triggered rule per transaction.

- **Liquibase schema management** — two changesets (`001`, `002`) covering table
  and index creation. Hibernate set to `ddl-auto: validate`.

- **REST API** (`FraudFlagController`):
    - `GET /api/v1/flags` — paginated flag list with optional status filter
    - `GET /api/v1/flags/transactions/{transactionId}/flags` — all flags for a transaction
    - `GET /api/v1/flags/stats` — aggregated stats by time range and category

- **`FraudFlagQueryService`** — read-only service separating query logic from
  the write path (`FraudDetectionService`).

- **Global exception handler** — RFC 7807 `ProblemDetail` responses for
  `TransactionNotFoundException` (404), `IllegalArgumentException` (400),
  and unhandled exceptions (500).

- **`AppConfig`** — `ObjectMapper` bean with `JavaTimeModule` registered
  (dates serialised as ISO-8601 strings, not epoch millis) and
  `FAIL_ON_UNKNOWN_PROPERTIES` disabled for forward schema compatibility.
  Also provides an explicit `Validator` bean for `@Valid` constraint checking
  in the Kafka consumer, which runs outside the Spring MVC lifecycle.

- **Spring Actuator** — health, metrics, and Prometheus endpoints exposed.

- **Docker Compose** — single-command startup for Zookeeper, Kafka, PostgreSQL,
  Redis, and the application, with health checks and dependency ordering.

- **Multi-stage Dockerfile** — Maven build stage (JDK 25) and minimal runtime
  stage (JRE 25 Alpine). Runs as a non-root user.

- **Unit tests** for all rule implementations, the rule engine, score aggregator,
  service layer, and REST controller (MockMvc).

- **Integration test** (`FraudDetectionIntegrationTest`) using Testcontainers
  for Kafka, PostgreSQL, and Redis. Covers clean transaction, high-amount flag,
  duplicate detection, idempotency, and REST API responses.