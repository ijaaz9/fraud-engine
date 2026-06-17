package com.fraud.detection.rules.impl;

import com.fraud.detection.model.enums.FraudRuleType;
import com.fraud.detection.model.event.TransactionEvent;
import com.fraud.detection.properties.FraudRuleProperties;
import com.fraud.detection.repository.redis.VelocityStore;
import com.fraud.detection.rules.engine.FraudRule;
import com.fraud.detection.rules.engine.RuleEvaluationResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Fraud rule: Velocity Check
 *
 * Flags users who submit an unusually high number of transactions within a
 * short rolling time window. This is a common pattern in account-takeover
 * and card-testing attacks where attackers rapidly probe many small amounts.
 *
 * === Mechanism ===
 * A Redis Sorted Set keyed by userId records the timestamp (epoch millis) of
 * each transaction as both the score and the member. On each evaluation:
 *   1. The current transaction's timestamp is added to the sorted set.
 *   2. Entries older than the configured window are pruned (ZREMRANGEBYSCORE).
 *   3. The remaining count is compared against the configured threshold.
 *
 * Redis sorted set operations are O(log N) and atomic, making this approach
 * correct under concurrent load from multiple Kafka consumer threads.
 *
 * === Configuration (application.yml) ===
 *   fraud.rules.velocity.threshold     (default: 10)
 *   fraud.rules.velocity.window-seconds (default: 300 = 5 minutes)
 *   fraud.rules.velocity.score         (default: 40)
 */

@Slf4j
@Component
@RequiredArgsConstructor
public class VelocityRule implements FraudRule {

    private final VelocityStore velocityStore;
    private final FraudRuleProperties properties;

    @Override
    public FraudRuleType getRuleType() {
        return FraudRuleType.VELOCITY_CHECK;
    }

    @Override
    public RuleEvaluationResult evaluate(TransactionEvent event) {
        long txnCount = velocityStore.recordAndCount(
                event.getUserId(),
                event.getTimestamp().toEpochMilli(),
                properties.getVelocity().getWindowSeconds()
        );

        log.debug("Velocity check for user [{}]: {} transactions in last {}s (threshold: {})",
                event.getUserId(), txnCount, properties.getVelocity().getWindowSeconds(), properties.getVelocity().getThreshold());

        if (txnCount > properties.getVelocity().getThreshold()) {
            String detail = String.format(
                    "%d transactions in the last %d seconds for user [%s] (threshold: %d)",
                    txnCount, properties.getVelocity().getWindowSeconds(), event.getUserId(), properties.getVelocity().getThreshold()
            );
            return RuleEvaluationResult.triggered(getRuleType(), properties.getVelocity().getScore(), detail);
        }

        return RuleEvaluationResult.notTriggered(getRuleType());
    }
}
