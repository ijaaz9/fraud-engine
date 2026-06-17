package com.fraud.detection.service;

import com.fraud.detection.model.entity.FraudFlag;
import com.fraud.detection.model.entity.Transaction;
import com.fraud.detection.model.enums.FlagStatus;
import com.fraud.detection.model.event.TransactionEvent;
import com.fraud.detection.repository.postgres.FraudFlagRepository;
import com.fraud.detection.repository.postgres.TransactionRepository;
import com.fraud.detection.repository.redis.IdempotencyStore;
import com.fraud.detection.rules.engine.FraudRuleEngine;
import com.fraud.detection.rules.engine.RuleEvaluationResult;
import com.fraud.detection.scoring.ScoreAggregation;
import com.fraud.detection.scoring.SeverityScoreAggregator;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;


/**
 * Orchestrates the full fraud detection pipeline for a single transaction event.
 *
 * === Responsibilities (Single Responsibility per collaborator) ===
 *   - Idempotency guard: delegates to {@link IdempotencyStore}
 *   - Rule evaluation:   delegates to {@link FraudRuleEngine}
 *   - Score aggregation: delegates to {@link SeverityScoreAggregator}
 *   - Persistence:       delegates to JPA repositories
 *   - Metrics:           delegates to {@link FraudMetricsService}
 *
 * This service is intentionally thin — it coordinates, not computes.
 *
 * === Transactionality ===
 * The entire persist step (transaction + fraud flags) is wrapped in a single
 * DB transaction. If the flag inserts fail, the transaction record is also
 * rolled back, preventing orphaned transaction rows with no flag data.
 *
 * === Metrics boundary ===
 * Latency is measured from the start of process() to after the DB commit.
 * Redis operations (idempotency, rule state) are inside the measured window
 * because they are part of the observable processing cost.
 */

@Slf4j
@Service
@RequiredArgsConstructor
public class FraudDetectionService {


    private final FraudRuleEngine ruleEngine;
    private final SeverityScoreAggregator scoreAggregator;
    private final IdempotencyStore idempotencyStore;
    private final TransactionRepository transactionRepository;
    private final FraudFlagRepository fraudFlagRepository;
    private final FraudMetricsService metricsService;

    /** How long to retain the idempotency marker in Redis (should cover max Kafka redelivery window). */
    @Value("${fraud.idempotency.ttl-hours:24}")
    private long idempotencyTtlHours;

    /**
     * Processes a single transaction event through the full fraud detection pipeline.
     *
     * Steps:
     *   1. Idempotency check — skip if already processed (Kafka at-least-once delivery).
     *   2. Evaluate all fraud rules via the engine.
     *   3. Aggregate scores to determine overall severity.
     *   4. Persist the transaction record to PostgreSQL (always — for audit trail).
     *   5. If any rules fired, persist one FraudFlag per triggered rule.
     *   6. Record metrics.
     *
     * @param event the inbound transaction event from Kafka
     */
    @Transactional
    public void process(TransactionEvent event) {
        long startMillis = System.currentTimeMillis();

        log.info("Processing transaction [{}] for user [{}]",
                event.getTransactionId(), event.getUserId());

        // === Step 1: Idempotency guard ===
        // Redis SET NX ensures we skip duplicate Kafka deliveries without
        // needing a DB round-trip on the hot path.
        if (!idempotencyStore.isNewAndMark(event.getTransactionId(), idempotencyTtlHours)) {
            log.warn("Transaction [{}] already processed — skipping", event.getTransactionId());
            return;
        }

        // === Step 2: Evaluate all fraud rules ===
        List<RuleEvaluationResult> ruleResults = ruleEngine.evaluate(event);

        // === Step 3: Aggregate scores → severity ===
        ScoreAggregation aggregation = scoreAggregator.aggregate(ruleResults);

        log.info("Transaction [{}] scored {} (severity={}), {} rule(s) triggered",
                event.getTransactionId(),
                aggregation.getTotalScore(),
                aggregation.getSeverity(),
                aggregation.getTriggeredResults().size());

        // === Step 4: Persist transaction (always) ===
        Transaction transaction = mapToEntity(event);
        transaction = transactionRepository.save(transaction);

        // === Step 5: Persist fraud flags (only if rules fired) ===
        if (aggregation.isFraudulent()) {
            persistFraudFlags(transaction, aggregation);
        }

        // === Step 6: Record metrics ===
        // Metrics are recorded after all DB writes so latency reflects the
        // full cost of processing, not just the rule evaluation phase.
        long latencyMillis = System.currentTimeMillis() - startMillis;
        metricsService.recordTransactionProcessed(latencyMillis);
        if (aggregation.isFraudulent()) {
            metricsService.recordFraudDetected(aggregation);
        }
    }

    /**
     * Creates and saves one {@link FraudFlag} row for each rule that fired.
     * All flags share the same aggregate score and severity so that any single
     * flag gives a complete picture of the transaction risk without further joins.
     */
    private void persistFraudFlags(Transaction transaction, ScoreAggregation aggregation) {
        List<FraudFlag> flags = aggregation.getTriggeredResults().stream()
                .map(result -> FraudFlag.builder()
                        .transaction(transaction)
                        .ruleTriggered(result.getRuleType())
                        .severity(aggregation.getSeverity())
                        .score(aggregation.getTotalScore())
                        .detail(result.getDetail())
                        .status(FlagStatus.OPEN)
                        .build())
                .toList();

        fraudFlagRepository.saveAll(flags);

        log.info("Persisted {} fraud flag(s) for transaction [{}] with severity [{}]",
                flags.size(), transaction.getTransactionId(), aggregation.getSeverity());
    }

    /**
     * Maps an inbound {@link TransactionEvent} to a {@link Transaction} JPA entity.
     * Kept here (rather than on the entity or event) to respect the separation
     * between the messaging layer and the persistence layer.
     */
    private Transaction mapToEntity(TransactionEvent event) {
        return Transaction.builder()
                .transactionId(event.getTransactionId())
                .userId(event.getUserId())
                .amount(event.getAmount())
                .merchant(event.getMerchant())
                .category(event.getCategory())
                .latitude(event.getLatitude())
                .longitude(event.getLongitude())
                .timestamp(event.getTimestamp())
                .build();
    }

}
