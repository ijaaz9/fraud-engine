
package com.fraud.detection.service;

import com.fraud.detection.model.enums.FraudRuleType;
import com.fraud.detection.model.enums.Severity;
import com.fraud.detection.scoring.ScoreAggregation;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Centralised Micrometer metrics for the fraud detection pipeline.
 *
 * All metrics are registered here rather than scattered across rules and services.
 * This keeps instrumentation concerns in one place (Single Responsibility) and
 * makes it easy to see the full metric surface at a glance.
 *
 * === Metrics exposed ===
 *
 * fraud_transactions_processed_total
 *   Counter — incremented for every transaction that completes processing,
 *   regardless of whether fraud was detected. Gives overall pipeline throughput.
 *
 * fraud_flags_generated_total{severity}
 *   Counter — incremented once per fraudulent transaction (not per flag row).
 *   Tagged by severity so dashboards can track LOW/MEDIUM/HIGH/CRITICAL trends.
 *
 * fraud_rules_triggered_total{rule}
 *   Counter — incremented once per rule that fires on a transaction.
 *   Tagged by rule name — the most useful metric for understanding which signals
 *   are most active. Replaces the need for a dedicated duplicate_transactions_total
 *   counter since rules_triggered_total{rule="DUPLICATE_TRANSACTION"} covers it.
 *
 * fraud_critical_flags_total
 *   Counter — dedicated counter for CRITICAL severity flags. Exists separately
 *   from the tagged flags counter so alerting rules can reference a single metric
 *   without label filtering (some alerting systems handle this more reliably).
 *
 * fraud_processing_latency_ms
 *   Timer — measures end-to-end processing time per transaction from consumer
 *   receipt to DB write completion. Recorded in the service layer.
 *
 * === Viewing metrics ===
 *   GET /actuator/metrics/fraud.transactions.processed.total
 *   GET /actuator/prometheus  (Prometheus scrape endpoint)
 */
@Component
@RequiredArgsConstructor

public class FraudMetricsService {

    private final MeterRegistry meterRegistry;

    // Counters — initialised in @PostConstruct so they appear in /actuator/metrics
    // immediately at startup, even before any transactions are processed.
    // Without pre-registration, counters only appear after their first increment.
    private Counter transactionsProcessedCounter;
    private Counter criticalFlagsCounter;
    private Timer processingLatencyTimer;

    @PostConstruct
    void initMetrics() {
        transactionsProcessedCounter = Counter.builder("fraud.transactions.processed.total")
                .description("Total number of transactions processed by the fraud engine")
                .register(meterRegistry);

        criticalFlagsCounter = Counter.builder("fraud.critical.flags.total")
                .description("Total number of transactions flagged at CRITICAL severity")
                .register(meterRegistry);

        processingLatencyTimer = Timer.builder("fraud.processing.latency.ms")
                .description("End-to-end processing latency per transaction in milliseconds")
                .register(meterRegistry);

        // Pre-register rule counters for all known rule types so they appear
        // in metrics output from startup, not only after first trigger.
        for (FraudRuleType rule : FraudRuleType.values()) {
            Counter.builder("fraud.rules.triggered.total")
                    .description("Number of times each fraud rule has triggered")
                    .tag("rule", rule.name())
                    .register(meterRegistry);
        }

        // Pre-register flag counters for all severity levels
        for (Severity severity : Severity.values()) {
            if (severity == Severity.NONE) continue; // NONE means no flag was raised
            Counter.builder("fraud.flags.generated.total")
                    .description("Number of fraudulent transactions detected, by severity")
                    .tag("severity", severity.name())
                    .register(meterRegistry);
        }
    }

    /**
     * Records a completed transaction processing cycle.
     * Called once per transaction regardless of fraud outcome.
     *
     * @param latencyMillis end-to-end processing time in milliseconds
     */
    public void recordTransactionProcessed(long latencyMillis) {
        transactionsProcessedCounter.increment();
        processingLatencyTimer.record(latencyMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * Records metrics for a fraudulent transaction — one flag counter increment
     * per transaction (not per flag row), and one increment per triggered rule.
     *
     * @param aggregation the scoring result containing triggered rules and severity
     */
    public void recordFraudDetected(ScoreAggregation aggregation) {
        // Increment the severity-tagged flag counter once for this transaction
        meterRegistry.counter("fraud.flags.generated.total",
                        "severity", aggregation.getSeverity().name())
                .increment();

        // Dedicated CRITICAL counter for simpler alerting rules
        if (aggregation.getSeverity() == Severity.CRITICAL) {
            criticalFlagsCounter.increment();
        }

        // Increment per-rule counter for each rule that fired
        aggregation.getTriggeredResults().forEach(result ->
                meterRegistry.counter("fraud.rules.triggered.total",
                                "rule", result.getRuleType().name())
                        .increment()
        );
    }
}
