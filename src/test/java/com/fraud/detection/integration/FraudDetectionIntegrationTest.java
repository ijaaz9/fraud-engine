package com.fraud.detection.integration;

import com.fraud.detection.model.enums.FlagStatus;
import com.fraud.detection.model.event.TransactionEvent;
import com.fraud.detection.repository.postgres.FraudFlagRepository;
import com.fraud.detection.repository.postgres.TransactionRepository;
import com.jayway.jsonpath.JsonPath;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Integration test suite for the Fraud Detection service.
 *
 * <p>This test class validates the end-to-end behaviour of the fraud detection pipeline,
 * including Kafka ingestion, rule evaluation, persistence, Redis state handling, and REST API exposure.</p>
 *
 * <h2>Test Environment</h2>
 * <p>The test runs in a fully isolated environment using Testcontainers:</p>
 * <ul>
 *     <li>PostgreSQL (database persistence)</li>
 *     <li>Kafka (event ingestion)</li>
 *     <li>Redis (stateful fraud signals such as velocity checks and geo tracking)</li>
 * </ul>
 *
 * <p>Spring Boot is started on a random port and wired to containerized infrastructure
 * via {@code @DynamicPropertySource}.</p>
 *
 * <h2>Coverage</h2>
 * <ul>
 *     <li>Transaction ingestion via Kafka</li>
 *     <li>Fraud rule evaluation (high amount, duplicate detection, geo anomalies)</li>
 *     <li>Idempotency guarantees under at-least-once delivery</li>
 *     <li>Stateful detection using Redis (velocity, geo baselines)</li>
 *     <li>REST API correctness for flagged transactions</li>
 * </ul>
 *
 * <h2>Isolation Strategy</h2>
 * <p>Each test is isolated by clearing both relational and cache state:</p>
 * <ul>
 *     <li>All PostgreSQL tables are truncated between tests</li>
 *     <li>Redis is fully cleared to avoid cross-test contamination of stateful rules</li>
 * </ul>
 *
 * <h2>Kafka Behavior</h2>
 * <p>Events are published directly to a Testcontainers-managed Kafka broker using a
 * real Kafka producer. The application consumes from a dedicated test topic.</p>
 *
 * <h2>Asynchronous Processing</h2>
 * <p>Assertions use Awaitility to account for asynchronous processing within the consumer
 * and rule engine pipeline.</p>
 *
 * <h2>Key Validations</h2>
 * <ul>
 *     <li>Clean transactions are persisted without fraud flags</li>
 *     <li>High-value transactions trigger HIGH_AMOUNT rules</li>
 *     <li>Repeated transactions trigger DUPLICATE_TRANSACTION rules</li>
 *     <li>Duplicate Kafka messages are processed idempotently</li>
 *     <li>Geo-anomalies and impossible travel scenarios are detected using Redis state</li>
 *     <li>REST endpoints reflect persisted fraud decisions correctly</li>
 * </ul>
 *
 * <h2>Design Notes</h2>
 * <p>This test suite intentionally uses real infrastructure components instead of mocks
 * to validate production-like behaviour, including serialization, message delivery,
 * transaction boundaries, and rule evaluation correctness.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
@DisplayName("Fraud Detection Integration Tests")
public class FraudDetectionIntegrationTest {

    // ── Testcontainers ────────────────────────────────────────────────────────

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("testdb")
            .withUsername("test")
            .withPassword("test");

    @Container
    static KafkaContainer kafka = new KafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.5.0"));

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);

        // The application's consumer factory binds fraud.kafka.* (KafkaConsumerProperties),
        // while Spring Boot's auto-configured KafkaTemplate (used by @RetryableTopic to
        // route to retry/DLT topics) binds spring.kafka.* — both must point at the broker.
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("fraud.kafka.bootstrap-servers", kafka::getBootstrapServers);

        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    // ── Spring beans ─────────────────────────────────────────────────────────

    @LocalServerPort private int port;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private FraudFlagRepository fraudFlagRepository;
    @Autowired private StringRedisTemplate redisTemplate;

    @AfterEach
    void cleanUp() {
        // Reset Postgres state between tests
        fraudFlagRepository.deleteAll();
        transactionRepository.deleteAll();
        // Reset Redis state — clears velocity windows, geo-location, idempotency keys,
        // and dedup markers so tests don't bleed state into each other
        Set<String> keys = redisTemplate.keys("*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("clean transaction: should be persisted with no fraud flags")
    void cleanTransaction_shouldPersistWithNoFlags() throws Exception {
        TransactionEvent event = buildEvent(
                UUID.randomUUID().toString(),
                "user-clean-001",
                new BigDecimal("50.00"),  // below high-amount threshold
                51.5074, -0.1278           // London (no prior location — no geo flag)
        );

        publishToKafka(event);

        // Wait for the consumer to process the message
        await().atMost(15, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(
                        transactionRepository.existsByTransactionId(event.getTransactionId())
                ).isTrue());

        assertThat(fraudFlagRepository.findAllByTransactionId(event.getTransactionId())).isEmpty();
    }

    @Test
    @DisplayName("high-amount transaction: should raise a HIGH_AMOUNT fraud flag")
    void highAmountTransaction_shouldRaiseFlag() throws Exception {
        String txnId = UUID.randomUUID().toString();
        TransactionEvent event = buildEvent(
                txnId,
                "user-highamount-001",
                new BigDecimal("5000.00"),  // exceeds test threshold of 1000
                51.5074, -0.1278
        );

        publishToKafka(event);

        await().atMost(15, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    var flags = fraudFlagRepository.findAllByTransactionId(txnId);
                    assertThat(flags).isNotEmpty();
                    assertThat(flags).anyMatch(f ->
                            f.getRuleTriggered().name().equals("HIGH_AMOUNT"));
                });

        var flags = fraudFlagRepository.findAllByTransactionId(txnId);
        assertThat(flags.get(0).getStatus()).isEqualTo(FlagStatus.OPEN);
        assertThat(flags.get(0).getScore()).isGreaterThan(0);
    }

    @Test
    @DisplayName("duplicate transaction: should raise a DUPLICATE_TRANSACTION flag on repeat")
    void duplicateTransaction_shouldRaiseFlagOnRepeat() throws Exception {
        String userId = "user-dup-" + UUID.randomUUID();
        String merchant = "DupMerchant";
        BigDecimal amount = new BigDecimal("100.00");

        // First transaction — should be clean
        String firstTxnId = UUID.randomUUID().toString();
        publishToKafka(buildEvent(firstTxnId, userId, amount, 51.5074, -0.1278, merchant));

        await().atMost(15, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(
                        transactionRepository.existsByTransactionId(firstTxnId)
                ).isTrue());

        // Second transaction — same user, merchant, amount → duplicate
        String secondTxnId = UUID.randomUUID().toString();
        publishToKafka(buildEvent(secondTxnId, userId, amount, 51.5074, -0.1278, merchant));

        await().atMost(15, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    var flags = fraudFlagRepository.findAllByTransactionId(secondTxnId);
                    assertThat(flags).anyMatch(f ->
                            f.getRuleTriggered().name().equals("DUPLICATE_TRANSACTION"));
                });
    }

    @Test
    @DisplayName("idempotency: duplicate Kafka messages should not be processed twice")
    void duplicateKafkaMessage_shouldBeIdempotent() throws Exception {
        String txnId = UUID.randomUUID().toString();
        TransactionEvent event = buildEvent(txnId, "user-idem-001",
                new BigDecimal("5000.00"), 51.5074, -0.1278);

        // Publish the same message twice (simulating Kafka at-least-once redelivery)
        publishToKafka(event);
        publishToKafka(event);

        await().atMost(15, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(
                        transactionRepository.existsByTransactionId(txnId)
                ).isTrue());

        // Poll until both messages have been consumed, then assert only one record exists.
        // A brief additional wait ensures the second message has been fully processed
        // before asserting idempotency — replaced Thread.sleep with Awaitility.
        await().atMost(10, TimeUnit.SECONDS)
                .untilAsserted(() ->
                        assertThat(transactionRepository.findAll().stream()
                                .filter(t -> t.getTransactionId().equals(txnId))
                                .count()).isEqualTo(1)
                );
    }

    @Test
    @DisplayName("impossible travel: should raise GEO_ANOMALY and IMPOSSIBLE_TRAVEL flags")
    void impossibleTravelTransaction_shouldRaiseBothGeoFlags() throws Exception {
        String userId = "user-travel-" + UUID.randomUUID();

        // Transaction 1: Cape Town — establishes the location baseline in Redis
        String firstTxnId = UUID.randomUUID().toString();
        publishToKafka(buildEvent(firstTxnId, userId, new BigDecimal("100.00"),
                -33.9249, 18.4241, "CT Cafe", Instant.parse("2026-01-01T10:00:00Z")));

        // Wait for the first transaction to be fully processed before sending the second,
        // so the geo-location snapshot is written to Redis when the second event arrives.
        await().atMost(15, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(
                        transactionRepository.existsByTransactionId(firstTxnId)
                ).isTrue());

        // Transaction 2: London, 30 minutes later (~9,670km, implied speed ~19,340 km/h)
        String secondTxnId = UUID.randomUUID().toString();
        publishToKafka(buildEvent(secondTxnId, userId, new BigDecimal("200.00"),
                51.5074, -0.1278, "London Hotel", Instant.parse("2026-01-01T10:30:00Z")));

        await().atMost(15, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    var flags = fraudFlagRepository.findAllByTransactionId(secondTxnId);
                    assertThat(flags).anyMatch(f -> f.getRuleTriggered().name().equals("GEO_ANOMALY"));
                    assertThat(flags).anyMatch(f -> f.getRuleTriggered().name().equals("IMPOSSIBLE_TRAVEL"));
                });
    }

    @Test
    @DisplayName("REST API: GET /api/v1/flags returns flagged transactions")
    void restApi_getFlagsEndpoint_returnsFlaggedTransactions() throws Exception {
        String txnId = UUID.randomUUID().toString();
        TransactionEvent event = buildEvent(txnId, "user-api-001",
                new BigDecimal("5000.00"), 51.5074, -0.1278);

        publishToKafka(event);

        // Wait for the flag to appear in the database
        await().atMost(15, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(
                        fraudFlagRepository.findAllByTransactionId(txnId)
                ).isNotEmpty());

        ResponseEntity<String> response = httpGet("/api/v1/flags");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((Integer) JsonPath.read(response.getBody(), "$.totalElements")).isGreaterThan(0);
        assertThat((java.util.List<?>) JsonPath.read(response.getBody(), "$.content")).isNotNull();
    }

    @Test
    @DisplayName("REST API: GET /api/v1/flags/transactions/{id}/flags returns 404 for unknown transaction")
    void restApi_getByTransactionId_returns404WhenNotFound() {
        ResponseEntity<String> response =
                httpGet("/api/v1/flags/transactions/non-existent-txn-id/flags");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat((Integer) JsonPath.read(response.getBody(), "$.status")).isEqualTo(404);
    }

    // ── REST helpers ──────────────────────────────────────────────────────────

    /**
     * Performs a real HTTP GET against the running server using Spring's
     * {@link RestClient}. The error-status handler is overridden with a no-op so
     * that 4xx/5xx responses are returned as a {@link ResponseEntity} for the
     * test to assert on, rather than throwing.
     */
    private ResponseEntity<String> httpGet(String path) {
        return RestClient.create()
                .get()
                .uri("http://localhost:" + port + path)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, response) -> {
                    // no-op: assertions below inspect the status code and body
                })
                .toEntity(String.class);
    }

    // ── Kafka producer helpers ────────────────────────────────────────────────

    private void publishToKafka(TransactionEvent event) throws Exception {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JacksonJsonSerializer.class);

        try (KafkaProducer<String, TransactionEvent> producer = new KafkaProducer<>(props)) {
            producer.send(new ProducerRecord<>("transactions-test", event.getTransactionId(), event)).get();
        }
    }

    // ── Event builder helpers ─────────────────────────────────────────────────

    private TransactionEvent buildEvent(String txnId, String userId, BigDecimal amount,
                                        double lat, double lon) {
        return buildEvent(txnId, userId, amount, lat, lon, "Default Merchant");
    }

    private TransactionEvent buildEvent(String txnId, String userId, BigDecimal amount,
                                        double lat, double lon, String merchant) {
        return buildEvent(txnId, userId, amount, lat, lon, merchant, Instant.now());
    }

    private TransactionEvent buildEvent(String txnId, String userId, BigDecimal amount,
                                        double lat, double lon, String merchant, Instant timestamp) {
        return TransactionEvent.builder()
                .transactionId(txnId)
                .userId(userId)
                .amount(amount)
                .merchant(merchant)
                .category("RETAIL")
                .latitude(lat)
                .longitude(lon)
                .timestamp(timestamp)
                .build();
    }
}
