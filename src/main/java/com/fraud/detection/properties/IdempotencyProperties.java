package com.fraud.detection.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Strongly-typed configuration for idempotent transaction processing.
 *
 * Binds {@code fraud.idempotency.*}, e.g.:
 * <pre>
 * fraud:
 *   idempotency:
 *     ttl-hours: 24
 * </pre>
 *
 * Kept separate from {@link FraudRuleProperties} because idempotency is a
 * processing-pipeline concern, not a fraud rule.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "fraud.idempotency")
public class IdempotencyProperties {

    /**
     * How long to retain the idempotency marker in Redis. Should comfortably
     * cover the maximum Kafka redelivery window. Defaults to 24 hours.
     */
    private long ttlHours = 24;
}

